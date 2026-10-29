package dev.varun.studio.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.varun.studio.AppContainer
import dev.varun.studio.R
import dev.varun.studio.ui.screens.AuditScreen
import dev.varun.studio.ui.screens.ChatScreen
import dev.varun.studio.ui.screens.HomeScreen
import dev.varun.studio.ui.screens.ModelsScreen
import dev.varun.studio.ui.screens.ReposScreen
import dev.varun.studio.ui.screens.SettingsScreen
import dev.varun.studio.ui.screens.TasksScreen
import dev.varun.studio.ui.screens.WorkspaceScreen

/** Bottom-bar destinations. */
enum class TopLevel(val route: String) {
    HOME("home"),
    MODELS("models"),
    CHAT("chat"),
    REPOS("repos"),
    SETTINGS("settings"),
}

/** Secondary destinations reachable from Home / Repos. */
object Routes {
    const val TASKS = "tasks"
    const val AUDIT = "audit"
    const val WORKSPACE = "workspace"
}

@Composable
fun NavGraph(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val topLevel = listOf(
        Triple(TopLevel.HOME, Icons.Filled.Home, R.string.nav_home),
        Triple(TopLevel.MODELS, Icons.Filled.Memory, R.string.nav_models),
        Triple(TopLevel.CHAT, Icons.Filled.Chat, R.string.nav_chat),
        Triple(TopLevel.REPOS, Icons.Filled.Folder, R.string.nav_repos),
        Triple(TopLevel.SETTINGS, Icons.Filled.Settings, R.string.nav_settings),
    )

    Scaffold(
        bottomBar = {
            if (topLevel.any { it.first.route == currentRoute }) {
                NavigationBar {
                    for ((dest, icon, label) in topLevel) {
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                nav.navigate(dest.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(stringResource(label)) },
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = TopLevel.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopLevel.HOME.route) {
                HomeScreen(
                    vm = viewModel(factory = container.viewModelFactory),
                    onOpenModels = { nav.navigate(TopLevel.MODELS.route) },
                    onOpenChat = { nav.navigate(TopLevel.CHAT.route) },
                    onOpenRepos = { nav.navigate(TopLevel.REPOS.route) },
                    onOpenTasks = { nav.navigate(Routes.TASKS) },
                    onOpenAudit = { nav.navigate(Routes.AUDIT) },
                    onOpenSettings = { nav.navigate(TopLevel.SETTINGS.route) },
                )
            }
            composable(TopLevel.MODELS.route) {
                ModelsScreen(vm = viewModel(factory = container.viewModelFactory))
            }
            composable(TopLevel.CHAT.route) {
                ChatScreen(
                    vm = viewModel(factory = container.viewModelFactory),
                    workspaceVm = viewModel(factory = container.viewModelFactory),
                    onOpenModels = { nav.navigate(TopLevel.MODELS.route) },
                    onOpenWorkspace = { nav.navigate(Routes.WORKSPACE) },
                )
            }
            composable(TopLevel.REPOS.route) {
                ReposScreen(
                    vm = viewModel(factory = container.viewModelFactory),
                    onOpenWorkspace = { nav.navigate(Routes.WORKSPACE) },
                )
            }
            composable(TopLevel.SETTINGS.route) {
                SettingsScreen(vm = viewModel(factory = container.viewModelFactory))
            }
            composable(Routes.TASKS) {
                TasksScreen(
                    vm = viewModel(factory = container.viewModelFactory),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.AUDIT) {
                AuditScreen(
                    vm = viewModel(factory = container.viewModelFactory),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.WORKSPACE) {
                WorkspaceScreen(
                    vm = viewModel(factory = container.viewModelFactory),
                    chatVm = viewModel(factory = container.viewModelFactory),
                    onBack = { nav.popBackStack() },
                    onOpenChat = { nav.navigate(TopLevel.CHAT.route) },
                )
            }
        }
    }
}
