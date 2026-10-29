package dev.varun.studio.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.varun.studio.core.AppLog
import dev.varun.studio.data.repo.GitHubRepository
import dev.varun.studio.data.repo.SettingsRepository
import dev.varun.studio.data.repo.TaskRepository
import dev.varun.studio.data.repo.WorkspaceRepository
import dev.varun.studio.github.GitHubBranch
import dev.varun.studio.github.GitHubClient
import dev.varun.studio.github.GitHubCommit
import dev.varun.studio.github.GitHubContent
import dev.varun.studio.github.GitHubGitOps
import dev.varun.studio.github.GitHubRateLimit
import dev.varun.studio.github.GitHubRepo
import dev.varun.studio.github.GitHubTreeEntry
import dev.varun.studio.github.GitHubUser
import dev.varun.studio.github.GitWriteOutcome
import dev.varun.studio.github.StagedChange
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * GitHub browsing and the safe write workflow.
 *
 * Every write goes through an explicit confirmation flag; nothing is committed
 * or pushed as a side effect of browsing.
 */
class GitHubViewModel(
    private val github: GitHubRepository,
    private val workspace: WorkspaceRepository,
    private val settings: SettingsRepository,
    private val tasks: TaskRepository,
) : ViewModel() {

    enum class RepoTab { FILES, BRANCHES, COMMITS, SEARCH }

    data class UiState(
        val connected: Boolean = false,
        val user: GitHubUser? = null,
        val tokenDraft: String = "",
        val connecting: Boolean = false,
        val connectError: String? = null,
        val rateLimit: GitHubRateLimit? = null,
        val repos: List<GitHubRepo> = emptyList(),
        val reposLoading: Boolean = false,
        val reposError: String? = null,
        val repoFilter: String = "",
        // open repo
        val openRepo: GitHubRepo? = null,
        val tab: RepoTab = RepoTab.FILES,
        val pathStack: List<String> = emptyList(),
        val contents: List<GitHubContent> = emptyList(),
        val contentsLoading: Boolean = false,
        val branches: List<GitHubBranch> = emptyList(),
        val commits: List<GitHubCommit> = emptyList(),
        val selectedRef: String? = null,
        val openFilePath: String? = null,
        val openFileText: String? = null,
        val openFileTruncated: Boolean = false,
        val openFileLoading: Boolean = false,
        val searchQuery: String = "",
        val searchHits: List<GitHubClient.CodeSearchHit> = emptyList(),
        val searching: Boolean = false,
        val tree: List<GitHubTreeEntry> = emptyList(),
        val treeLoading: Boolean = false,
        val selectedPaths: Set<String> = emptySet(),
        val busyMessage: String? = null,
        val error: String? = null,
        val info: String? = null,
        // write workflow
        val safety: GitHubGitOps.SafetyContext? = null,
        val branchDraft: String = "",
        val commitMessageDraft: String = "",
        val pendingChanges: List<StagedChange> = emptyList(),
        val pushConfirmed: Boolean = false,
        val pushResult: String? = null,
        val pushBlockedReason: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var reposJob: Job? = null

    init {
        github.restoreFromVault()
        viewModelScope.launch {
            github.connected.collect { c -> _ui.value = _ui.value.copy(connected = c) }
        }
        viewModelScope.launch { github.user.collect { u -> _ui.value = _ui.value.copy(user = u) } }
        viewModelScope.launch { github.rateLimit.collect { r -> _ui.value = _ui.value.copy(rateLimit = r) } }
        viewModelScope.launch { github.lastError.collect { e -> _ui.value = _ui.value.copy(error = e) } }
        if (github.isConnectedLocally) loadRepos()
    }

    // -------------------------------------------------------------- connect

    fun setTokenDraft(v: String) { _ui.value = _ui.value.copy(tokenDraft = v) }

    fun connect() {
        val token = _ui.value.tokenDraft.trim()
        if (token.isEmpty()) {
            _ui.value = _ui.value.copy(connectError = "Paste a fine-grained personal access token.")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(connecting = true, connectError = null)
            val r = github.connect(token)
            _ui.value = _ui.value.copy(
                connecting = false,
                tokenDraft = "",   // never keep the raw token in UI state
                connectError = r.exceptionOrNull()?.let { describe(it) },
            )
            if (r.isSuccess) {
                _ui.value = _ui.value.copy(info = "Connected as ${github.user.value?.login}")
                loadRepos()
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            github.disconnect()
            _ui.value = _ui.value.copy(
                connected = false, user = null, repos = emptyList(), openRepo = null,
                contents = emptyList(), branches = emptyList(), commits = emptyList(),
                tree = emptyList(), selectedPaths = emptySet(), safety = null,
                info = "Disconnected. The stored token was erased from this phone.",
            )
        }
    }

    private fun describe(t: Throwable): String = when (t) {
        is GitHubRepository.BlockedOperationException -> t.message ?: "Refused by a safety rule."
        is GitHubRepository.GitHubException -> t.error.message
        else -> t.message ?: AppLog.describeError(t)
    }

    // ---------------------------------------------------------------- repos

    fun setRepoFilter(v: String) {
        _ui.value = _ui.value.copy(repoFilter = v)
    }

    fun loadRepos() {
        reposJob?.cancel()
        reposJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(reposLoading = true, reposError = null)
            val r = github.listRepos()
            r.fold(
                { list ->
                    _ui.value = _ui.value.copy(reposLoading = false, repos = list)
                },
                { err ->
                    _ui.value = _ui.value.copy(reposLoading = false, reposError = describe(err))
                },
            )
        }
    }

    fun searchRepos(query: String) {
        viewModelScope.launch {
            if (query.isBlank()) { loadRepos(); return@launch }
            _ui.value = _ui.value.copy(reposLoading = true, reposError = null)
            github.searchRepos(query).fold(
                { _ui.value = _ui.value.copy(reposLoading = false, repos = it) },
                { _ui.value = _ui.value.copy(reposLoading = false, reposError = describe(it)) },
            )
        }
    }

    val visibleRepos: List<GitHubRepo>
        get() {
            val f = _ui.value.repoFilter.trim().lowercase()
            if (f.isEmpty()) return _ui.value.repos
            return _ui.value.repos.filter { it.fullName.lowercase().contains(f) }
        }

    // ------------------------------------------------------------ open repo

    fun openRepo(repo: GitHubRepo) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                openRepo = repo,
                selectedRef = repo.defaultBranch,
                pathStack = emptyList(),
                tab = RepoTab.FILES,
                contents = emptyList(),
                branches = emptyList(),
                commits = emptyList(),
                tree = emptyList(),
                selectedPaths = emptySet(),
                openFilePath = null,
                openFileText = null,
                safety = null,
                pushResult = null,
                pushBlockedReason = null,
                pendingChanges = emptyList(),
                info = null,
                error = null,
            )
            settings.update { it.copy(lastGithubOwner = repo.owner, lastGithubRepo = repo.name) }
            loadContents()
            loadSafety(repo)
        }
    }

    fun closeRepo() {
        _ui.value = _ui.value.copy(openRepo = null, contents = emptyList(), openFilePath = null, openFileText = null)
    }

    fun setTab(tab: RepoTab) {
        _ui.value = _ui.value.copy(tab = tab)
        val repo = _ui.value.openRepo ?: return
        viewModelScope.launch {
            when (tab) {
                RepoTab.BRANCHES -> if (_ui.value.branches.isEmpty()) loadBranches(repo)
                RepoTab.COMMITS -> if (_ui.value.commits.isEmpty()) loadCommits(repo)
                RepoTab.SEARCH -> if (_ui.value.tree.isEmpty()) loadTree(repo)
                RepoTab.FILES -> Unit
            }
        }
    }

    fun selectRef(ref: String) {
        _ui.value = _ui.value.copy(selectedRef = ref, pathStack = emptyList())
        loadContents()
    }

    fun loadContents() {
        val repo = _ui.value.openRepo ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(contentsLoading = true)
            val path = _ui.value.pathStack.joinToString("/")
            github.contents(repo.owner, repo.name, path, _ui.value.selectedRef).fold(
                { list ->
                    _ui.value = _ui.value.copy(
                        contentsLoading = false,
                        contents = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })),
                    )
                },
                { err ->
                    _ui.value = _ui.value.copy(contentsLoading = false, error = describe(err))
                },
            )
        }
    }

    fun enterDir(name: String) {
        _ui.value = _ui.value.copy(pathStack = _ui.value.pathStack + name, openFilePath = null)
        loadContents()
    }

    fun upOneLevel() {
        val stack = _ui.value.pathStack
        if (stack.isEmpty()) return
        _ui.value = _ui.value.copy(pathStack = stack.dropLast(1))
        loadContents()
    }

    fun openFile(content: GitHubContent) {
        val repo = _ui.value.openRepo ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(openFilePath = content.path, openFileLoading = true, openFileText = null)
            github.file(repo.owner, repo.name, content.path, _ui.value.selectedRef).fold(
                { fc ->
                    _ui.value = _ui.value.copy(
                        openFileLoading = false,
                        openFileText = fc.text ?: "(binary or too large to display)",
                        openFileTruncated = fc.truncated,
                    )
                },
                { err -> _ui.value = _ui.value.copy(openFileLoading = false, error = describe(err)) },
            )
        }
    }

    fun closeFile() {
        _ui.value = _ui.value.copy(openFilePath = null, openFileText = null, openFileTruncated = false)
    }

    private suspend fun loadBranches(repo: GitHubRepo) {
        github.branches(repo.owner, repo.name).fold(
            { _ui.value = _ui.value.copy(branches = it) },
            { _ui.value = _ui.value.copy(error = describe(it)) },
        )
    }

    private suspend fun loadCommits(repo: GitHubRepo) {
        github.commits(repo.owner, repo.name, _ui.value.selectedRef, null).fold(
            { _ui.value = _ui.value.copy(commits = it) },
            { _ui.value = _ui.value.copy(error = describe(it)) },
        )
    }

    /** One request gives every path; the user then selects what to fetch. */
    private suspend fun loadTree(repo: GitHubRepo) {
        val ref = _ui.value.selectedRef ?: repo.defaultBranch
        _ui.value = _ui.value.copy(treeLoading = true)
        github.tree(repo.owner, repo.name, ref).fold(
            { entries ->
                _ui.value = _ui.value.copy(
                    treeLoading = false,
                    tree = entries.filter { it.type == "blob" }.sortedBy { it.path },
                )
            },
            { err -> _ui.value = _ui.value.copy(treeLoading = false, error = describe(err)) },
        )
    }

    fun setSearchQuery(q: String) { _ui.value = _ui.value.copy(searchQuery = q) }

    fun runCodeSearch() {
        val repo = _ui.value.openRepo ?: return
        val q = _ui.value.searchQuery.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(searching = true)
            github.searchCode(q, repo.owner, repo.name).fold(
                { _ui.value = _ui.value.copy(searching = false, searchHits = it) },
                { err ->
                    // Code search can be unavailable for private repos depending
                    // on the token; fall back to the already-loaded tree.
                    val local = _ui.value.tree.filter { it.path.contains(q, ignoreCase = true) }
                    _ui.value = _ui.value.copy(
                        searching = false,
                        searchHits = emptyList(),
                        error = describe(err),
                        info = if (local.isNotEmpty()) {
                            "GitHub code search was unavailable; matched ${local.size} path(s) locally from the tree listing."
                        } else null,
                    )
                },
            )
        }
    }

    fun togglePathSelection(path: String) {
        val cur = _ui.value.selectedPaths
        _ui.value = _ui.value.copy(
            selectedPaths = if (cur.contains(path)) cur - path else cur + path,
        )
    }

    fun clearSelection() { _ui.value = _ui.value.copy(selectedPaths = emptySet()) }

    /** Fetch ONLY the selected paths into a local workspace. */
    fun importSelectedToWorkspace() {
        val repo = _ui.value.openRepo ?: return
        val paths = _ui.value.selectedPaths.toList()
        if (paths.isEmpty()) {
            _ui.value = _ui.value.copy(error = "Select at least one file first.")
            return
        }
        viewModelScope.launch {
            val taskId = tasks.enqueue("import", "${repo.fullName}: ${paths.size} file(s)", null)
            tasks.markRunning(taskId)
            _ui.value = _ui.value.copy(busyMessage = "Importing ${paths.size} file(s)…")
            val r = workspace.importPaths(repo.owner, repo.name, _ui.value.selectedRef ?: repo.defaultBranch, paths)
            _ui.value = _ui.value.copy(busyMessage = null)
            when (r) {
                is WorkspaceRepository.ImportResult.Success -> {
                    tasks.markDone(taskId, "${r.filesWritten} file(s) imported")
                    _ui.value = _ui.value.copy(
                        info = "Imported ${r.filesWritten} file(s)" +
                            if (r.skipped > 0) " (${r.skipped} skipped)" else "",
                        selectedPaths = emptySet(),
                    )
                }
                is WorkspaceRepository.ImportResult.Failure -> {
                    tasks.markFailed(taskId, r.message)
                    _ui.value = _ui.value.copy(error = r.message)
                }
            }
        }
    }

    /** Import the whole repository archive. Refused if it exceeds the budget. */
    fun importWholeRepo() {
        val repo = _ui.value.openRepo ?: return
        viewModelScope.launch {
            val taskId = tasks.enqueue("clone", repo.fullName, null)
            tasks.markRunning(taskId)
            _ui.value = _ui.value.copy(busyMessage = "Downloading ${repo.fullName}…")
            val r = workspace.importFromGitHub(repo.owner, repo.name, _ui.value.selectedRef ?: repo.defaultBranch)
            _ui.value = _ui.value.copy(busyMessage = null)
            when (r) {
                is WorkspaceRepository.ImportResult.Success -> {
                    tasks.markDone(taskId, "${r.filesWritten} file(s)")
                    _ui.value = _ui.value.copy(info = "Imported ${r.filesWritten} file(s) locally")
                }
                is WorkspaceRepository.ImportResult.Failure -> {
                    tasks.markFailed(taskId, r.message)
                    _ui.value = _ui.value.copy(error = r.message)
                }
            }
        }
    }

    // --------------------------------------------------------- write workflow

    private suspend fun loadSafety(repo: GitHubRepo) {
        val ctx = github.safetyContext(repo.owner, repo.name, repo.defaultBranch)
        _ui.value = _ui.value.copy(safety = ctx)
    }

    fun setBranchDraft(v: String) { _ui.value = _ui.value.copy(branchDraft = v) }
    fun setCommitMessage(v: String) { _ui.value = _ui.value.copy(commitMessageDraft = v) }

    /** Prepare a commit from the local workspace's changed files. */
    fun prepareCommitFromWorkspace() {
        viewModelScope.launch {
            val changed = workspace.changedFiles()
            if (changed.isEmpty()) {
                _ui.value = _ui.value.copy(
                    error = "No local changes to commit. Edit a file in the workspace first.",
                    pendingChanges = emptyList(),
                )
                return@launch
            }
            _ui.value = _ui.value.copy(
                pendingChanges = changed.map { (path, text) ->
                    StagedChange(path = path, newText = text, oldSha = null, isNewFile = false, isDelete = false)
                },
                pushResult = null,
                pushBlockedReason = null,
                pushConfirmed = false,
                info = "${changed.size} changed file(s) staged for review.",
            )
        }
    }

    fun createBranch() {
        val ctx = _ui.value.safety ?: return
        val name = _ui.value.branchDraft.trim()
        if (name.isEmpty()) {
            _ui.value = _ui.value.copy(error = "Enter a branch name.")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busyMessage = "Creating branch…", error = null)
            val baseSha = resolveBaseSha(ctx)
            if (baseSha == null) {
                _ui.value = _ui.value.copy(
                    busyMessage = null,
                    error = "Could not resolve the base commit for '${_ui.value.selectedRef ?: ctx.defaultBranch}'.",
                )
                return@launch
            }
            github.createBranch(ctx, name, baseSha).fold(
                { outcome ->
                    _ui.value = _ui.value.copy(
                        busyMessage = null,
                        selectedRef = name,
                        branchDraft = "",
                        info = "Created branch '$name'.",
                        pushResult = outcome.htmlUrl,
                    )
                    val repo = _ui.value.openRepo
                    if (repo != null) loadBranches(repo)
                },
                { err -> _ui.value = _ui.value.copy(busyMessage = null, error = describe(err)) },
            )
        }
    }

    /**
     * Resolve the commit a new branch should start from: the currently selected
     * ref, falling back to the repository default branch.
     */
    private suspend fun resolveBaseSha(ctx: GitHubGitOps.SafetyContext): String? {
        val ref = _ui.value.selectedRef ?: ctx.defaultBranch
        val direct = github.headSha(ctx.owner, ctx.repo, ref).getOrNull()
        if (!direct.isNullOrBlank()) return direct
        if (ref == ctx.defaultBranch) return null
        // The selected ref may be a tag or a SHA rather than a branch head.
        return github.headSha(ctx.owner, ctx.repo, ctx.defaultBranch).getOrNull()
    }

    /**
     * Commit and push. [userConfirmed] must be true and comes only from the
     * confirmation dialog that lists the exact files and the target branch.
     */
    fun commitAndPush(userConfirmed: Boolean) {
        val ctx = _ui.value.safety
        val changes = _ui.value.pendingChanges
        val message = _ui.value.commitMessageDraft.trim()
        val branch = _ui.value.selectedRef ?: ctx?.defaultBranch
        if (ctx == null || branch == null) {
            _ui.value = _ui.value.copy(error = "Open a repository first.")
            return
        }
        if (changes.isEmpty()) {
            _ui.value = _ui.value.copy(error = "Nothing staged. Prepare a commit from the workspace first.")
            return
        }
        if (message.isEmpty()) {
            _ui.value = _ui.value.copy(error = "Write a commit message.")
            return
        }
        if (!userConfirmed) {
            _ui.value = _ui.value.copy(pushBlockedReason = "Push requires your explicit confirmation.")
            return
        }
        val s = settings.settings.value
        if (branch == ctx.defaultBranch && s.refuseDefaultBranchCommits) {
            _ui.value = _ui.value.copy(
                pushBlockedReason = "'$branch' is the default branch. Create a dedicated branch first; " +
                    "committing straight to the default branch is disabled in Settings.",
            )
            return
        }
        viewModelScope.launch {
            val taskId = tasks.enqueue("push", "${ctx.owner}/${ctx.repo} -> $branch (${changes.size} files)", null)
            tasks.markRunning(taskId)
            _ui.value = _ui.value.copy(busyMessage = "Committing ${changes.size} file(s)…", pushResult = null, pushBlockedReason = null)
            val r = github.commitAndPush(
                ctx = ctx,
                branch = branch,
                message = message,
                changes = changes,
                requireExplicitConfirmation = true,
                allowDefaultBranch = !s.refuseDefaultBranchCommits,
            )
            _ui.value = _ui.value.copy(busyMessage = null, pushConfirmed = false)
            r.fold(
                { outcome: GitWriteOutcome ->
                    tasks.markDone(taskId, outcome.commitSha?.take(8))
                    _ui.value = _ui.value.copy(
                        pushResult = "Pushed ${outcome.filesChanged} file(s) to '$branch'. " +
                            (outcome.htmlUrl ?: ""),
                        pendingChanges = emptyList(),
                        commitMessageDraft = "",
                    )
                },
                { err ->
                    tasks.markFailed(taskId, describe(err))
                    if (err is GitHubRepository.BlockedOperationException) {
                        _ui.value = _ui.value.copy(pushBlockedReason = err.message)
                    } else {
                        _ui.value = _ui.value.copy(error = describe(err))
                    }
                },
            )
        }
    }

    fun dismissMessages() {
        _ui.value = _ui.value.copy(error = null, info = null, pushBlockedReason = null)
    }
}
