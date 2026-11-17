package app.connect.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import app.connect.android.debug.DiagnosticsRoute
import app.connect.core.ui.TestTags
import app.connect.domain.model.AuthState
import app.connect.domain.model.ConversationId
import app.connect.domain.model.SignOutReason
import app.connect.domain.model.UserId
import app.connect.domain.repository.AuthRepository
import app.connect.domain.repository.ChatRepository
import app.connect.feature.auth.AuthRoute
import app.connect.feature.chat.ChatRoute
import app.connect.feature.chat.ChatViewModel
import app.connect.feature.chat.ConversationsRoute
import app.connect.feature.discovery.DiscoveryRoute
import app.connect.feature.profile.MyProfileRoute
import app.connect.feature.profile.ProfileDetailRoute
import app.connect.feature.profile.ProfileDetailViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object Routes {
    const val DISCOVER = "discover"
    const val CHATS = "chats"
    const val ME = "me"
    const val DIAGNOSTICS = "diagnostics"
    const val PROFILE = "profile/{${ProfileDetailViewModel.ARG_USER_ID}}"
    const val CHAT = "chat/{${ChatViewModel.ARG_CONVERSATION_ID}}"

    /** What a notification carries. Resolved by the navigation graph, so a tap lands in the right conversation. */
    const val CHAT_DEEP_LINK = "connect://chat/{${ChatViewModel.ARG_CONVERSATION_ID}}"

    val TOP_LEVEL = setOf(DISCOVER, CHATS, ME)

    fun profile(id: UserId) = "profile/${id.value}"

    fun chat(id: ConversationId) = "chat/${id.value}"

    fun chatDeepLink(id: ConversationId) = "connect://chat/${id.value}"
}

@HiltViewModel
class AppViewModel @Inject constructor(
    auth: AuthRepository,
    chat: ChatRepository,
) : ViewModel() {
    val authState: StateFlow<AuthState> = auth.authState

    val unreadCount: StateFlow<Int> =
        chat.observeUnreadCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

/**
 * The whole app is a function of the auth state. There is no "navigate to
 * login" call anywhere: when the session ends, for whatever reason, the state
 * changes and the signed-in UI leaves the composition.
 */
@Composable
fun ConnectApp(
    showDiagnostics: Boolean,
    onVisibleConversationChange: (ConversationId?) -> Unit,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    when (val state = authState) {
        AuthState.Restoring -> Splash()
        is AuthState.SignedOut -> AuthRoute(sessionExpired = state.reason == SignOutReason.SESSION_EXPIRED)
        // Keyed by user: a different account gets a new back stack and new ViewModels, never the last user's.
        is AuthState.SignedIn -> key(state.userId) {
            val unread by viewModel.unreadCount.collectAsStateWithLifecycle()
            SignedInApp(unread, showDiagnostics, onVisibleConversationChange)
        }
    }
}

@Composable
private fun Splash() {
    Surface(Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Text("Connect", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SignedInApp(
    unreadCount: Int,
    showDiagnostics: Boolean,
    onVisibleConversationChange: (ConversationId?) -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NotificationPermissionRequest()
    DeepLinksFromNewIntents(navController)

    Scaffold(
        bottomBar = {
            if (currentRoute in Routes.TOP_LEVEL) {
                BottomBar(currentRoute, unreadCount) { navController.navigateToTab(it) }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DISCOVER,
            // Each screen has its own Scaffold. Marking these insets as consumed stops it applying them again.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.DISCOVER) {
                DiscoveryRoute(
                    onOpenProfile = { navController.navigate(Routes.profile(it)) },
                    onOpenChat = { navController.navigate(Routes.chat(it)) },
                )
            }
            composable(Routes.CHATS) {
                ConversationsRoute(onOpenChat = { navController.navigate(Routes.chat(it)) })
            }
            composable(Routes.ME) {
                MyProfileRoute(
                    onOpenDiagnostics = if (showDiagnostics) ({ navController.navigate(Routes.DIAGNOSTICS) }) else null,
                )
            }
            composable(
                route = Routes.PROFILE,
                arguments = listOf(navArgument(ProfileDetailViewModel.ARG_USER_ID) { type = NavType.StringType }),
            ) {
                ProfileDetailRoute(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.CHAT,
                arguments = listOf(navArgument(ChatViewModel.ARG_CONVERSATION_ID) { type = NavType.StringType }),
                deepLinks = listOf(navDeepLink { uriPattern = Routes.CHAT_DEEP_LINK }),
            ) {
                ChatRoute(
                    onBack = { navController.popBackStack() },
                    onVisibleConversationChange = onVisibleConversationChange,
                )
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsRoute(onBack = { navController.popBackStack() })
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val tag: String)

private val tabs = listOf(
    Tab(Routes.DISCOVER, "Discover", Icons.Filled.Search, TestTags.NAV_DISCOVER),
    Tab(Routes.CHATS, "Chats", Icons.Filled.Email, TestTags.NAV_CHATS),
    Tab(Routes.ME, "You", Icons.Filled.Person, TestTags.NAV_ME),
)

@Composable
private fun BottomBar(currentRoute: String?, unreadCount: Int, onSelect: (String) -> Unit) {
    NavigationBar {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onSelect(tab.route) },
                label = { Text(tab.label) },
                modifier = Modifier.testTag(tab.tag),
                icon = {
                    if (tab.route == Routes.CHATS && unreadCount > 0) {
                        BadgedBox(badge = { Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) } }) {
                            Icon(tab.icon, contentDescription = "${tab.label}, $unreadCount unread")
                        }
                    } else {
                        Icon(tab.icon, contentDescription = tab.label)
                    }
                },
            )
        }
    }
}

/** Switching tabs keeps each tab's own back stack and scroll position, and never stacks a tab on itself. */
private fun NavController.navigateToTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/**
 * A notification tapped while the app is already open arrives as a new intent
 * on the existing activity. The graph handles the first intent itself; this
 * forwards the later ones.
 */
@Composable
private fun DeepLinksFromNewIntents(navController: NavHostController) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    DisposableEffect(activity, navController) {
        val listener = Consumer<Intent> { intent -> navController.handleDeepLink(intent) }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }
    // The graph has already consumed the launch intent by the time this runs. Dropping its URI means a
    // later sign-out and sign-in does not replay an old notification.
    LaunchedEffect(activity) {
        if (activity.intent?.data != null) activity.intent = Intent(activity.intent).setData(null)
    }
}

@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
