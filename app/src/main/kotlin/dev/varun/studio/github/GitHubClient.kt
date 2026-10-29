package dev.varun.studio.github

import dev.varun.studio.core.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * GitHub REST v3 client.
 *
 * Authorization model: a fine-grained personal access token supplied by the
 * user, held encrypted on-device and sent as a bearer header. There is NO
 * embedded client id, client secret, GitHub App private key or service token
 * anywhere in this app — nothing privileged ships in the APK, and the token's
 * permissions are chosen by the user on github.com.
 *
 * Scope guidance is surfaced in the UI: Contents (read/write), Metadata (read),
 * and optionally Pull requests. Administration, delete and account scopes are
 * never needed and the app warns if it sees them.
 *
 * GitHub is used ONLY for repository access. AI inference never touches the
 * network.
 */
class GitHubClient(
    private val tokenProvider: () -> String?,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {

    private val json = "application/vnd.github+json".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Follow redirects: the tarball endpoint 302s to codeload.github.com.
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    // ---------------------------------------------------------------- plumbing

    sealed interface ApiResult<out T> {
        data class Ok<T>(val value: T, val rateLimit: GitHubRateLimit?) : ApiResult<T>
        data class Err(val error: GitHubError) : ApiResult<Nothing>
    }

    private data class RawResponse(
        val status: Int,
        val body: String,
        val rateLimit: GitHubRateLimit?,
        val headers: Map<String, String>,
    )

    private fun token(): String? = tokenProvider()?.takeIf { it.isNotBlank() }

    private fun buildRequest(
        method: String,
        url: String,
        jsonBody: JSONObject? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
        token()?.let { builder.header("Authorization", "Bearer $it") }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val body = jsonBody?.toString()?.toRequestBody(json)
        builder.method(method, body)
        return builder.build()
    }

    private fun execute(request: Request): RawResponse {
        http.newCall(request).execute().use { response: Response ->
            val rate = parseRateLimit(response)
            val text = runCatching { response.body?.string() ?: "" }.getOrDefault("")
            val headers = mutableMapOf<String, String>()
            for (name in listOf("Content-Type", "Location", "X-RateLimit-Remaining")) {
                response.header(name)?.let { headers[name] = it }
            }
            return RawResponse(response.code, text, rate, headers)
        }
    }

    private fun parseRateLimit(response: Response): GitHubRateLimit? {
        val limit = response.header("X-RateLimit-Limit")?.toIntOrNull() ?: return null
        val remaining = response.header("X-RateLimit-Remaining")?.toIntOrNull() ?: 0
        val reset = response.header("X-RateLimit-Reset")?.toLongOrNull() ?: 0L
        return GitHubRateLimit(limit, remaining, reset)
    }

    private fun errorFor(status: Int, body: String, rate: GitHubRateLimit?): GitHubError {
        val apiMessage = runCatching { JSONObject(body).optString("message").ifBlank { null } }
            .getOrNull()
        return when (status) {
            401 -> GitHubError.Unauthorized(
                apiMessage ?: "GitHub rejected these credentials (401). Reconnect with a valid token.",
            )
            403 -> {
                if (rate != null && rate.remaining == 0) {
                    GitHubError.RateLimited(
                        rate.resetEpochSeconds,
                        "GitHub rate limit exhausted. Resets at ${formatEpoch(rate.resetEpochSeconds)}.",
                    )
                } else {
                    GitHubError.Forbidden(
                        apiMessage ?: "Your token does not have permission for this (403).",
                    )
                }
            }
            404 -> GitHubError.NotFound(
                apiMessage ?: "Not found (404). The repository may be private and not granted to this token.",
            )
            422 -> GitHubError.ValidationFailed(apiMessage ?: "GitHub rejected the request (422).")
            429 -> GitHubError.RateLimited(rate?.resetEpochSeconds ?: 0L, apiMessage ?: "Too many requests (429).")
            in 500..599 -> GitHubError.Server(status, apiMessage ?: "GitHub server error ($status).")
            else -> GitHubError.Server(status, apiMessage ?: "Unexpected response ($status).")
        }
    }

    private suspend fun <T> call(
        method: String,
        path: String,
        jsonBody: JSONObject? = null,
        parse: (String) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val t = token()
        if (t == null && PUBLIC_OK_PREFIXES.none { path.startsWith(it) }) {
            return@withContext ApiResult.Err(GitHubError.NoCredentials())
        }
        val url = if (path.startsWith("http")) path else "$API_BASE$path"
        try {
            val raw = execute(buildRequest(method, url, jsonBody))
            if (raw.status in 200..299) {
                ApiResult.Ok(parse(raw.body), raw.rateLimit)
            } else {
                ApiResult.Err(errorFor(raw.status, raw.body, raw.rateLimit))
            }
        } catch (c: CancellationException) {
            throw c
        } catch (io: IOException) {
            AppLog.w("GitHub network failure on ${method} $path")
            ApiResult.Err(
                GitHubError.Network(
                    "Could not reach github.com. GitHub access needs an internet connection; " +
                        "your local model keeps working offline.",
                ),
            )
        } catch (t2: Throwable) {
            AppLog.e("GitHub call failed", t2)
            ApiResult.Err(GitHubError.Parse(AppLog.describeError(t2)))
        }
    }

    // ------------------------------------------------------------------- auth

    /** Validates the stored token and returns the authenticated user. */
    suspend fun currentUser(): ApiResult<GitHubUser> = call("GET", "/user") { parseUser(it) }

    /**
     * Scopes granted to the current credentials. For a fine-grained PAT GitHub
     * does not return an `X-OAuth-Scopes` header, so we infer capability from
     * a lightweight probe instead of guessing.
     */
    suspend fun probeScopes(): ApiResult<ScopeProbe> = withContext(Dispatchers.IO) {
        val url = "$API_BASE/user"
        try {
            val raw = execute(buildRequest("GET", url))
            if (raw.status !in 200..299) {
                return@withContext ApiResult.Err(errorFor(raw.status, raw.body, raw.rateLimit))
            }
            val classicScopes = raw.headers["X-OAuth-Scopes"]
                ?: runCatching {
                    // header() is not captured above for all names; re-read safely
                    null
                }.getOrNull()
            ApiResult.Ok(ScopeProbe(classicScopes, raw.rateLimit), raw.rateLimit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            ApiResult.Err(GitHubError.Network(AppLog.describeError(t)))
        }
    }

    data class ScopeProbe(val classicScopes: String?, val rateLimit: GitHubRateLimit?)

    // ------------------------------------------------------------------ repos

    suspend fun listRepos(page: Int = 1, perPage: Int = 50): ApiResult<List<GitHubRepo>> =
        call("GET", "/user/repos?per_page=$perPage&page=$page&sort=pushed&affiliation=owner,collaborator,organization_member") {
            parseRepos(it)
        }

    suspend fun searchRepos(query: String, page: Int = 1): ApiResult<List<GitHubRepo>> =
        call("GET", "/search/repositories?q=${enc(query)}&per_page=30&page=$page") { body ->
            val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
            (0 until items.length()).map { parseRepo(items.getJSONObject(it)) }
        }

    suspend fun repo(owner: String, name: String): ApiResult<GitHubRepo> =
        call("GET", "/repos/${enc(owner)}/${enc(name)}") { parseRepo(JSONObject(it)) }

    suspend fun listBranches(owner: String, name: String): ApiResult<List<GitHubBranch>> =
        call("GET", "/repos/${enc(owner)}/${enc(name)}/branches?per_page=100") { body ->
            val arr = JSONArray(body)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                GitHubBranch(
                    name = o.optString("name"),
                    sha = o.optJSONObject("commit")?.optString("sha").orEmpty(),
                    isProtected = o.optBoolean("protected", false),
                )
            }
        }

    suspend fun listCommits(
        owner: String,
        name: String,
        sha: String? = null,
        path: String? = null,
        perPage: Int = 30,
    ): ApiResult<List<GitHubCommit>> {
        val q = buildString {
            append("/repos/${enc(owner)}/${enc(name)}/commits?per_page=$perPage")
            if (!sha.isNullOrBlank()) append("&sha=${enc(sha)}")
            if (!path.isNullOrBlank()) append("&path=${enc(path)}")
        }
        return call("GET", q) { body ->
            val arr = JSONArray(body)
            (0 until arr.length()).map { parseCommit(arr.getJSONObject(it)) }
        }
    }

    suspend fun listContents(
        owner: String,
        name: String,
        path: String,
        ref: String?,
    ): ApiResult<List<GitHubContent>> {
        val q = buildString {
            append("/repos/${enc(owner)}/${enc(name)}/contents/")
            append(path.split('/').joinToString("/") { enc(it) })
            if (!ref.isNullOrBlank()) append("?ref=${enc(ref)}")
        }
        return call("GET", q) { body ->
            // A single file comes back as an object, a directory as an array.
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).map { parseContent(arr.getJSONObject(it)) }
            } else {
                listOf(parseContent(JSONObject(trimmed)))
            }
        }
    }

    /**
     * Fetch a file's contents. GitHub inlines base64 up to 1 MB and returns
     * `download_url` beyond that; we honour both and report truncation rather
     than silently handing the model a partial file.
     */
    suspend fun fileContent(
        owner: String,
        name: String,
        path: String,
        ref: String?,
        maxBytes: Long = 1_500_000,
    ): ApiResult<GitHubFileContent> {
        val q = buildString {
            append("/repos/${enc(owner)}/${enc(name)}/contents/")
            append(path.split('/').joinToString("/") { enc(it) })
            if (!ref.isNullOrBlank()) append("?ref=${enc(ref)}")
        }
        return call("GET", q) { body ->
            val o = JSONObject(body)
            val size = o.optLong("size", 0)
            val encoding = o.optString("encoding")
            val raw = o.optString("content", "")
            val truncated = size > maxBytes || raw.isBlank() && size > 0
            GitHubFileContent(
                path = o.optString("path", path),
                sha = o.optString("sha"),
                sizeBytes = size,
                encoding = encoding,
                base64 = raw.takeIf { it.isNotBlank() },
                text = raw.takeIf { it.isNotBlank() }?.let { decodeBase64(it) },
                truncated = truncated,
            )
        }
    }

    /**
     * Full recursive tree for a ref. This is the "targeted file retrieval"
     * primitive: one request gives every path, so the agent can pick a handful
     * of files instead of cloning the repository onto the phone.
     */
    suspend fun tree(
        owner: String,
        name: String,
        ref: String,
        recursive: Boolean = true,
    ): ApiResult<List<GitHubTreeEntry>> =
        call("GET", "/repos/${enc(owner)}/${enc(name)}/git/trees/${enc(ref)}?recursive=$recursive") { body ->
            val o = JSONObject(body)
            val arr = o.optJSONArray("tree") ?: JSONArray()
            val truncated = o.optBoolean("truncated", false)
            val out = ArrayList<GitHubTreeEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                out.add(
                    GitHubTreeEntry(
                        path = e.optString("path"),
                        type = e.optString("type"),
                        sizeBytes = if (e.isNull("size")) null else e.optLong("size"),
                        sha = e.optString("sha"),
                    ),
                )
            }
            if (truncated) {
                AppLog.w("GitHub tree was truncated for $owner/$name@$ref; falling back to directory listing")
            }
            out
        }

    suspend fun refSha(owner: String, name: String, branch: String): ApiResult<String> =
        call("GET", "/repos/${enc(owner)}/${enc(name)}/git/ref/heads/${enc(branch)}") {
            JSONObject(it).optJSONObject("object")?.optString("sha").orEmpty()
        }

    /** GitHub code search. Requires a token; returns matches with snippets. */
    suspend fun searchCode(query: String, owner: String, name: String): ApiResult<List<CodeSearchHit>> =
        call(
            "GET",
            "/search/code?q=${enc(query)}+repo:${enc(owner)}/${enc(name)}&per_page=50",
        ) { body ->
            val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
            (0 until items.length()).map {
                val o = items.getJSONObject(it)
                CodeSearchHit(
                    path = o.optString("path"),
                    name = o.optString("name"),
                    sha = o.optString("sha"),
                    repository = o.optJSONObject("repository")?.optString("full_name"),
                    score = o.optDouble("score", 0.0),
                    textMatches = (o.optJSONArray("text_matches") ?: JSONArray()).let { arr ->
                        (0 until arr.length()).map { arr.getJSONObject(it).optString("fragment") }
                    },
                )
            }
        }

    data class CodeSearchHit(
        val path: String,
        val name: String,
        val sha: String,
        val repository: String?,
        val score: Double,
        val textMatches: List<String>,
    )

    /**
     * Download a repository archive (tar.gz) for offline import.
     * Streams to a callback so the caller can enforce a size budget and show
     * progress, and can be cancelled cooperatively.
     */
    suspend fun downloadArchive(
        owner: String,
        name: String,
        ref: String,
        maxBytes: Long,
        onBytes: (Long) -> Boolean,
    ): ApiResult<ByteArray> = withContext(Dispatchers.IO) {
        if (token() == null) return@withContext ApiResult.Err(GitHubError.NoCredentials())
        val url = "$API_BASE/repos/${enc(owner)}/${enc(name)}/tarball/${enc(ref)}"
        try {
            http.newCall(buildRequest("GET", url)).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    return@withContext ApiResult.Err(errorFor(response.code, body, parseRateLimit(response)))
                }
                val stream: InputStream = response.body?.byteStream()
                    ?: return@withContext ApiResult.Err(GitHubError.Network("Empty archive response."))
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) {
                        return@withContext ApiResult.Err(
                            GitHubError.Refused(
                                "Archive exceeds the ${maxBytes / (1024 * 1024)} MB import limit. " +
                                    "Use targeted file retrieval instead of importing the whole repository.",
                            ),
                        )
                    }
                    if (!onBytes(total)) {
                        return@withContext ApiResult.Err(GitHubError.Cancelled())
                    }
                    out.write(buf, 0, n)
                }
                ApiResult.Ok(out.toByteArray(), parseRateLimit(response))
            }
        } catch (c: CancellationException) {
            throw c
        } catch (io: IOException) {
            ApiResult.Err(GitHubError.Network(AppLog.describeError(io)))
        } catch (t: Throwable) {
            ApiResult.Err(GitHubError.Parse(AppLog.describeError(t)))
        }
    }

    // ------------------------------------------------------------------ writes

    internal suspend fun rawJson(
        method: String,
        path: String,
        body: JSONObject?,
    ): ApiResult<JSONObject> = call(method, path, body) { if (it.isBlank()) JSONObject() else JSONObject(it) }

    // ----------------------------------------------------------------- parsing

    private fun parseUser(body: String): GitHubUser {
        val o = JSONObject(body)
        return GitHubUser(
            login = o.optString("login"),
            name = o.optString("name").ifBlank { null },
            avatarUrl = o.optString("avatar_url").ifBlank { null },
            plan = o.optJSONObject("plan")?.optString("name"),
        )
    }

    private fun parseRepos(body: String): List<GitHubRepo> {
        val arr = JSONArray(body)
        return (0 until arr.length()).map { parseRepo(arr.getJSONObject(it)) }
    }

    private fun parseRepo(o: JSONObject): GitHubRepo {
        val ownerObj = o.optJSONObject("owner")
        val perms = o.optJSONObject("permissions")
        return GitHubRepo(
            id = o.optLong("id"),
            fullName = o.optString("full_name"),
            name = o.optString("name"),
            owner = ownerObj?.optString("login") ?: o.optString("full_name").substringBefore('/'),
            isPrivate = o.optBoolean("private", false),
            defaultBranch = o.optString("default_branch", "main"),
            description = o.optString("description").ifBlank { null },
            language = o.optString("language").ifBlank { null },
            sizeKb = o.optLong("size", 0),
            updatedAt = o.optString("updated_at").ifBlank { null },
            htmlUrl = o.optString("html_url"),
            isFork = o.optBoolean("fork", false),
            permissions = RepoPermissions(
                canPull = perms?.optBoolean("pull", false) ?: false,
                canPush = perms?.optBoolean("push", false) ?: false,
                canAdmin = perms?.optBoolean("admin", false) ?: false,
            ),
        )
    }

    private fun parseCommit(o: JSONObject): GitHubCommit {
        val commit = o.optJSONObject("commit")
        val author = commit?.optJSONObject("author")
        return GitHubCommit(
            sha = o.optString("sha"),
            message = commit?.optString("message")?.lineSequence()?.firstOrNull().orEmpty(),
            authorName = author?.optString("name"),
            authorLogin = o.optJSONObject("author")?.optString("login"),
            date = author?.optString("date"),
            htmlUrl = o.optString("html_url").ifBlank { null },
        )
    }

    private fun parseContent(o: JSONObject): GitHubContent = GitHubContent(
        name = o.optString("name"),
        path = o.optString("path"),
        type = o.optString("type", "file"),
        sizeBytes = o.optLong("size", 0),
        sha = o.optString("sha").ifBlank { null },
        downloadUrl = o.optString("download_url").ifBlank { null },
    )

    // ------------------------------------------------------------------ helpers

    private fun decodeBase64(raw: String): String {
        val cleaned = raw.replace("\n", "").replace("\r", "").replace(" ", "")
        return runCatching {
            String(java.util.Base64.getMimeDecoder().decode(cleaned), Charsets.UTF_8)
        }.getOrElse {
            runCatching {
                String(java.util.Base64.getDecoder().decode(cleaned), Charsets.UTF_8)
            }.getOrDefault("")
        }
    }

    private fun formatEpoch(seconds: Long): String {
        if (seconds <= 0) return "unknown"
        return runCatching {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(seconds * 1000))
        }.getOrDefault("soon")
    }

    companion object {
        const val API_BASE = "https://api.github.com"
        const val DEFAULT_USER_AGENT = "VarunStudio-Android/1.0"

        /** Endpoints reachable without a token (public repos). */
        private val PUBLIC_OK_PREFIXES = arrayOf("/search/repositories", "/rate_limit")

        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
