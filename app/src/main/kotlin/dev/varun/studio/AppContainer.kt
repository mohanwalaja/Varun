package dev.varun.studio

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import dev.varun.studio.agent.CodingAgent
import dev.varun.studio.ai.LlamaEngine
import dev.varun.studio.core.AppLog
import dev.varun.studio.core.DeviceMemory
import dev.varun.studio.core.security.SecureVault
import dev.varun.studio.data.db.VarunDatabase
import dev.varun.studio.data.repo.ChatRepository
import dev.varun.studio.data.repo.GitHubRepository
import dev.varun.studio.data.repo.ModelRepository
import dev.varun.studio.data.repo.SettingsRepository
import dev.varun.studio.data.repo.TaskRepository
import dev.varun.studio.data.repo.WorkspaceRepository
import dev.varun.studio.ui.vm.ChatViewModel
import dev.varun.studio.ui.vm.GitHubViewModel
import dev.varun.studio.ui.vm.HomeViewModel
import dev.varun.studio.ui.vm.ModelsViewModel
import dev.varun.studio.ui.vm.SettingsViewModel
import dev.varun.studio.ui.vm.TasksViewModel
import dev.varun.studio.ui.vm.WorkspaceViewModel
import java.io.File

/**
 * Hand-rolled service locator.
 *
 * Hilt/Dagger would add an annotation processor and a codegen step to a project
 * that has to build in CI from a phone-only workflow. A single explicit graph
 * is easier to audit, has zero build magic, and keeps the dependency list short.
 *
 * Everything is created lazily and lives for the Application lifetime, except
 * ViewModels which are scoped normally.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val db: VarunDatabase by lazy { VarunDatabase.get(appContext) }
    val vault: SecureVault by lazy { SecureVault(appContext) }
    val device: DeviceMemory by lazy { DeviceMemory(appContext) }

    val engine: LlamaEngine by lazy { LlamaEngine() }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext, db) }
    val models: ModelRepository by lazy { ModelRepository(appContext, db, engine, settings) }
    val github: GitHubRepository by lazy { GitHubRepository(vault) }

    val githubClient: dev.varun.studio.github.GitHubClient by lazy {
        dev.varun.studio.github.GitHubClient(
            tokenProvider = { vault.getString(SecureVault.KEY_GITHUB_TOKEN) },
        )
    }

    val workspace: WorkspaceRepository by lazy {
        WorkspaceRepository(appContext, db, settings, githubClient)
    }
    val chat: ChatRepository by lazy { ChatRepository(db) }
    val tasks: TaskRepository by lazy { TaskRepository(db) }

    /** Agent bound to the currently open workspace, recreated when it changes. */
    @Volatile
    private var agentCache: Pair<File, CodingAgent>? = null

    fun agentFor(root: File): CodingAgent {
        agentCache?.let { if (it.first == root) return it.second }
        val a = CodingAgent(engine, root, dev.varun.studio.workspace.EditJournal(root))
        agentCache = root to a
        return a
    }

    fun modelsDir(): File = models.modelsDir()
    fun workspacesDir(): File = workspace.workspacesRoot()

    /** Wipe every trace: DB rows, secrets, models, workspaces, caches. */
    suspend fun eraseEverything() {
        runCatching { engine.unload() }
        runCatching { db.wipe() }
        runCatching { vault.clearAll() }
        runCatching { models.modelsDir().deleteRecursively() }
        runCatching { workspace.workspacesRoot().deleteRecursively() }
        runCatching { appContext.cacheDir.deleteRecursively() }
        runCatching { models.modelsDir().mkdirs() }
        runCatching { workspace.workspacesRoot().mkdirs() }
        agentCache = null
        AppLog.i("Erase-all completed")
    }

    val viewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            return when {
                modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                    HomeViewModel(device, engine, models, settings, tasks, workspace) as T
                modelClass.isAssignableFrom(ModelsViewModel::class.java) ->
                    ModelsViewModel(models, engine, settings, device) as T
                modelClass.isAssignableFrom(ChatViewModel::class.java) ->
                    ChatViewModel(chat, engine, models, settings, device, tasks, workspace, github) as T
                modelClass.isAssignableFrom(GitHubViewModel::class.java) ->
                    GitHubViewModel(github, workspace, settings, tasks) as T
                modelClass.isAssignableFrom(WorkspaceViewModel::class.java) ->
                    WorkspaceViewModel(workspace, settings, engine, tasks, chat, device) as T
                modelClass.isAssignableFrom(TasksViewModel::class.java) ->
                    TasksViewModel(tasks, chat) as T
                modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                    SettingsViewModel(settings, device, engine, vault, models, workspace, db, appContext) as T
                else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
            }
        }
    }
}
