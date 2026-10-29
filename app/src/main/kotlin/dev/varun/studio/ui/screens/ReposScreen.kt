package dev.varun.studio.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.varun.studio.R
import dev.varun.studio.github.GitHubRepo
import dev.varun.studio.ui.components.BusyRow
import dev.varun.studio.ui.components.CodeBlock
import dev.varun.studio.ui.components.ConfirmDialog
import dev.varun.studio.ui.components.EmptyState
import dev.varun.studio.ui.components.ErrorBanner
import dev.varun.studio.ui.components.InfoBanner
import dev.varun.studio.ui.components.KeyValueRow
import dev.varun.studio.ui.components.SectionCard
import dev.varun.studio.ui.components.WarningBanner
import dev.varun.studio.ui.theme.CodeFamily
import dev.varun.studio.ui.vm.GitHubViewModel

/**
 * GitHub: connect, list repositories, browse files/branches/commits, search
 * code, and run the safe branch -> commit -> push workflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen(vm: GitHubViewModel, onOpenWorkspace: () -> Unit) {
    val ui by vm.ui.collectAsState()

    if (!ui.connected) {
        ConnectScreen(vm, ui)
        return
    }
    if (ui.openRepo != null) {
        RepoDetailScreen(vm, ui, onOpenWorkspace)
        return
    }
    RepoListScreen(vm, ui, onOpenWorkspace)
}

// ------------------------------------------------------------------ connect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectScreen(vm: GitHubViewModel, ui: GitHubViewModel.UiState) {
    var showScopes by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.github_title)) })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(stringResource(R.string.github_connect_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.github_connect_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = ui.tokenDraft,
                onValueChange = { vm.setTokenDraft(it) },
                label = { Text(stringResource(R.string.github_token_label)) },
                placeholder = { Text(stringResource(R.string.github_token_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                // Never echo the token back on screen or into logs.
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.connect() },
                enabled = !ui.connecting && ui.tokenDraft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (ui.connecting) stringResource(R.string.state_working) else stringResource(R.string.action_connect)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showScopes = !showScopes }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.github_scopes_title))
            }
            if (showScopes) {
                SectionCard(title = stringResource(R.string.github_scopes_title)) {
                    Text(stringResource(R.string.github_scopes_body), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "How to create one on your phone:\n" +
                            "1. Open github.com → Settings → Developer settings → Fine-grained personal access tokens\n" +
                            "2. Generate new token, choose only the repositories you need\n" +
                            "3. Permissions: Contents = Read and write, Metadata = Read-only\n" +
                            "4. Copy the token (it is shown once) and paste it above",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFamily),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This app stores no client id, no client secret, no GitHub App private key and no " +
                            "service token. Your token is encrypted with the Android Keystore and stays on this " +
                            "phone. Disconnecting erases it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ErrorBanner(ui.connectError)
            Spacer(Modifier.height(12.dp))
            WarningBanner(stringResource(R.string.github_needs_internet))
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Why not an OAuth login screen?") {
                Text(
                    "The standard GitHub OAuth web flow needs a client secret exchanged on a server. This " +
                        "project forbids a backend, and embedding a secret in an APK would expose it to " +
                        "anyone who unpacks the file. A user-chosen fine-grained PAT with minimum " +
                        "permissions is the supported, secret-free alternative. GitHub's device flow is " +
                        "also secret-free and can be added later if you register your own OAuth App.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------- repo list

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoListScreen(vm: GitHubViewModel, ui: GitHubViewModel.UiState, onOpenWorkspace: () -> Unit) {
    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var confirmDisconnect by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.github_repos), style = MaterialTheme.typography.titleMedium)
                    Text(
                        ui.user?.login?.let { stringResource(R.string.github_connected_as, it) } ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                IconButton(onClick = { searchMode = !searchMode }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                }
                TextButton(onClick = { confirmDisconnect = true }) {
                    Text(stringResource(R.string.action_disconnect))
                }
            },
        )

        Column(Modifier.padding(horizontal = 12.dp)) {
            ErrorBanner(ui.reposError ?: ui.error) { vm.dismissMessages() }
            InfoBanner(ui.info) { vm.dismissMessages() }
            ui.rateLimit?.let { rl ->
                if (rl.nearlyExhausted) {
                    WarningBanner("GitHub rate limit is almost exhausted (${rl.remaining}/${rl.limit} left).")
                }
            }
            if (searchMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Search all of GitHub…") },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { vm.searchRepos(query) }) { Text(stringResource(R.string.action_search)) }
                }
                Spacer(Modifier.height(6.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ui.repoFilter,
                        onValueChange = { vm.setRepoFilter(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Filter your repositories…") },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { vm.loadRepos() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_refresh))
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.loadRepos() }) { Text(stringResource(R.string.action_refresh)) }
                OutlinedButton(onClick = onOpenWorkspace) { Text(stringResource(R.string.workspace_title)) }
            }
        }

        if (ui.reposLoading) {
            BusyRow(stringResource(R.string.state_loading))
        }

        val repos = vm.visibleRepos
        if (repos.isEmpty() && !ui.reposLoading) {
            EmptyState(
                if (ui.reposError != null) "Could not load repositories."
                else "No repositories match. Pull to refresh, or search GitHub above.",
            )
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(repos, key = { it.id }) { repo -> RepoRow(repo) { vm.openRepo(repo) } }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (confirmDisconnect) {
        ConfirmDialog(
            title = stringResource(R.string.action_disconnect),
            body = stringResource(R.string.github_disconnect_confirm),
            confirmLabel = stringResource(R.string.action_disconnect),
            dismissLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = {
                vm.disconnect()
                confirmDisconnect = false
            },
            onDismiss = { confirmDisconnect = false },
        )
    }
}

@Composable
private fun RepoRow(repo: GitHubRepo, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (repo.isPrivate) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.github_private_repo),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(repo.fullName, style = MaterialTheme.typography.titleSmall)
                repo.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Text(
                    buildString {
                        append(repo.language ?: "—")
                        append(" · ")
                        append(repo.sizeKb / 1024).append(" MB")
                        append(" · ")
                        append(stringResourceSafe(repo.defaultBranch))
                        if (repo.permissions.canPush) append(" · push")
                        if (repo.isFork) append(" · fork")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun stringResourceSafe(s: String) = s

// ---------------------------------------------------------- repo detail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoDetailScreen(vm: GitHubViewModel, ui: GitHubViewModel.UiState, onOpenWorkspace: () -> Unit) {
    val repo = ui.openRepo ?: return
    var confirmImportAll by remember { mutableStateOf(false) }
    var confirmPush by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(repo.fullName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.github_default_branch, repo.defaultBranch),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = { vm.closeRepo() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )

        Column(Modifier.padding(horizontal = 12.dp)) {
            ErrorBanner(ui.error) { vm.dismissMessages() }
            InfoBanner(ui.info) { vm.dismissMessages() }
            WarningBanner(ui.pushBlockedReason) { vm.dismissMessages() }
            ui.busyMessage?.let { BusyRow(it) }
        }

        val tabs = listOf(
            GitHubViewModel.RepoTab.FILES to stringResource(R.string.workspace_files),
            GitHubViewModel.RepoTab.BRANCHES to stringResource(R.string.github_branches),
            GitHubViewModel.RepoTab.COMMITS to stringResource(R.string.github_commits),
            GitHubViewModel.RepoTab.SEARCH to stringResource(R.string.action_search),
        )
        ScrollableTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == ui.tab }, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, (tab, label) ->
                Tab(selected = ui.tab == tab, onClick = { vm.setTab(tab) }, text = { Text(label) })
            }
        }

        when (ui.tab) {
            GitHubViewModel.RepoTab.FILES -> FilesTab(vm, ui, repo)
            GitHubViewModel.RepoTab.BRANCHES -> BranchesTab(vm, ui)
            GitHubViewModel.RepoTab.COMMITS -> CommitsTab(vm, ui)
            GitHubViewModel.RepoTab.SEARCH -> SearchTab(vm, ui, onOpenWorkspace)
        }

        if (confirmImportAll) {
            ConfirmDialog(
                title = stringResource(R.string.github_import_workspace),
                body = "Download the whole ${repo.fullName} archive (${repo.sizeKb / 1024} MB reported by GitHub) " +
                    "into private app storage? Large repositories will be refused by the import limit.",
                confirmLabel = stringResource(R.string.action_import),
                dismissLabel = stringResource(R.string.action_cancel),
                onConfirm = {
                    vm.importWholeRepo()
                    confirmImportAll = false
                },
                onDismiss = { confirmImportAll = false },
            )
        }
        if (confirmPush) {
            ConfirmDialog(
                title = stringResource(R.string.git_needs_approval),
                body = stringResource(
                    R.string.git_push_confirm,
                    ui.pendingChanges.size,
                    "${repo.owner}/${repo.name}",
                    ui.selectedRef ?: repo.defaultBranch,
                ) + "\n\n" + ui.pendingChanges.joinToString("\n") { "• ${it.path}" } +
                    "\n\nCommit message: ${ui.commitMessageDraft}",
                confirmLabel = stringResource(R.string.git_push),
                dismissLabel = stringResource(R.string.action_cancel),
                destructive = true,
                onConfirm = {
                    vm.commitAndPush(userConfirmed = true)
                    confirmPush = false
                },
                onDismiss = { confirmPush = false },
            )
        }
    }
}

@Composable
private fun FilesTab(vm: GitHubViewModel, ui: GitHubViewModel.UiState, repo: GitHubRepo) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (ui.pathStack.isEmpty()) "/" else "/" + ui.pathStack.joinToString("/"),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFamily),
                modifier = Modifier.weight(1f),
            )
            if (ui.pathStack.isNotEmpty()) {
                TextButton(onClick = { vm.upOneLevel() }) { Text("Up") }
            }
        }
        if (ui.contentsLoading) BusyRow(stringResource(R.string.state_loading))

        if (ui.openFilePath != null) {
            SectionCard(
                title = ui.openFilePath!!,
                subtitle = if (ui.openFileTruncated) "Truncated by GitHub (>1 MB)" else null,
                trailing = { TextButton(onClick = { vm.closeFile() }) { Text(stringResource(R.string.action_close)) } },
            ) {
                if (ui.openFileLoading) BusyRow(stringResource(R.string.state_loading))
                else CodeBlock(ui.openFileText ?: "", fontSize = 11, maxLines = 60)
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(ui.contents, key = { it.path }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (c.isDirectory) vm.enterDir(c.name) else vm.openFile(c)
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (c.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (!c.isDirectory) {
                        Text(
                            "${c.sizeBytes / 1024} KB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun BranchesTab(vm: GitHubViewModel, ui: GitHubViewModel.UiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (ui.branches.isEmpty()) EmptyState(stringResource(R.string.state_loading))
        LazyColumn {
            items(ui.branches, key = { it.name }) { b ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.selectRef(b.name) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(b.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            b.sha.take(8) + if (b.isProtected) " · protected" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (b.isProtected) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (ui.selectedRef == b.name) {
                        Text("selected", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        SectionCard(title = stringResource(R.string.git_create_branch)) {
            OutlinedTextField(
                value = ui.branchDraft,
                onValueChange = { vm.setBranchDraft(it) },
                label = { Text(stringResource(R.string.git_branch_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.createBranch() },
                enabled = ui.branchDraft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.git_create_branch)) }
            Text(
                stringResource(R.string.git_protected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun CommitsTab(vm: GitHubViewModel, ui: GitHubViewModel.UiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (ui.commits.isEmpty()) EmptyState(stringResource(R.string.state_loading))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(ui.commits, key = { it.sha }) { c ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(c.message, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                        Text(
                            "${c.sha.take(7)} · ${c.authorLogin ?: c.authorName ?: "unknown"} · ${c.date ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTab(vm: GitHubViewModel, ui: GitHubViewModel.UiState, onOpenWorkspace: () -> Unit) {
    var confirmImportSelected by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ui.searchQuery,
                onValueChange = { vm.setSearchQuery(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.github_search_placeholder)) },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.runCodeSearch() }) { Text(stringResource(R.string.action_search)) }
        }
        if (ui.searching || ui.treeLoading) BusyRow(stringResource(R.string.state_working))

        if (ui.searchHits.isNotEmpty()) {
            SectionCard(title = "GitHub code search", subtitle = "${ui.searchHits.size} hit(s)") {
                ui.searchHits.take(30).forEach { hit ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(hit.path, style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFamily))
                        hit.textMatches.firstOrNull()?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                        TextButton(onClick = { vm.togglePathSelection(hit.path) }) {
                            Text(if (ui.selectedPaths.contains(hit.path)) "Deselect" else "Select for import")
                        }
                    }
                }
            }
        }

        SectionCard(
            title = "Browse every path (targeted retrieval)",
            subtitle = "${ui.tree.size} file(s) in this ref · select only what you need",
            trailing = {
                if (ui.selectedPaths.isNotEmpty()) {
                    TextButton(onClick = { vm.clearSelection() }) { Text("Clear") }
                }
            },
        ) {
            Text(
                "For a large repository, do NOT import everything. Pick the files you actually want to " +
                    "analyse; Varun fetches only those. This keeps the model's context useful and your " +
                    "storage free.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(ui.tree.take(2000), key = { it.path }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.togglePathSelection(entry.path) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = ui.selectedPaths.contains(entry.path),
                            onCheckedChange = { vm.togglePathSelection(entry.path) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.path,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFamily),
                                maxLines = 2,
                            )
                            entry.sizeBytes?.let {
                                Text("${it / 1024} KB", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(
                onClick = { confirmImportSelected = true },
                enabled = ui.selectedPaths.isNotEmpty(),
            ) {
                Text("${stringResource(R.string.github_import_workspace)} (${ui.selectedPaths.size})")
            }
            OutlinedButton(onClick = onOpenWorkspace) { Text(stringResource(R.string.workspace_title)) }
        }

        if (confirmImportSelected) {
            ConfirmDialog(
                title = stringResource(R.string.github_import_workspace),
                body = "Fetch ${ui.selectedPaths.size} file(s) into private app storage?\n\n" +
                    ui.selectedPaths.take(12).joinToString("\n") { "• $it" } +
                    if (ui.selectedPaths.size > 12) "\n…and ${ui.selectedPaths.size - 12} more" else "",
                confirmLabel = stringResource(R.string.action_import),
                dismissLabel = stringResource(R.string.action_cancel),
                onConfirm = {
                    vm.importSelectedToWorkspace()
                    confirmImportSelected = false
                },
                onDismiss = { confirmImportSelected = false },
            )
        }
    }
}


