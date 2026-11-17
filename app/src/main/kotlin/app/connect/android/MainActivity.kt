package app.connect.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.connect.android.debug.DebugControls
import app.connect.android.push.Notifier
import app.connect.android.push.VisibleConversation
import app.connect.core.ui.ConnectTheme
import app.connect.domain.diagnostics.Analytics
import app.connect.domain.diagnostics.AnalyticsEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var startup: AppStartup

    @Inject
    lateinit var visibleConversation: VisibleConversation

    @Inject
    lateinit var analytics: Analytics

    @Inject
    lateinit var debugControls: DebugControls

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        startup.start()
        // Not on recreation: a rotation is not a second tap on the notification.
        if (savedInstanceState == null) trackPushOpen(intent)

        setContent {
            ConnectTheme {
                ConnectApp(
                    showDiagnostics = BuildConfig.DEBUG || debugControls.available,
                    onVisibleConversationChange = { visibleConversation.id = it },
                )
            }
        }
    }

    // The navigation graph is told about the new intent by a listener registered in ConnectApp.
    // getIntent() deliberately keeps returning the launch intent: nothing reads the newer one from
    // there, and replacing it would make a later recreation look like a fresh notification tap.
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        trackPushOpen(intent)
    }

    private fun trackPushOpen(intent: Intent?) {
        intent?.getStringExtra(Notifier.EXTRA_PUSH_KIND)?.let { kind ->
            analytics.track(AnalyticsEvent.PushOpened(kind))
        }
    }
}
