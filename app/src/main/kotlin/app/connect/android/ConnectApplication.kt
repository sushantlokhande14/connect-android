package app.connect.android

import android.app.Application
import app.connect.android.push.Notifier
import app.connect.android.push.PushSource
import app.connect.data.di.ApplicationScope
import app.connect.domain.diagnostics.Breadcrumbs
import app.connect.domain.diagnostics.CrashReporter
import app.connect.domain.repository.AuthRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class ConnectApplication : Application() {
    @Inject
    lateinit var startup: AppStartup

    override fun onCreate() {
        super.onCreate()
        startup.start()
    }
}

/**
 * Work the process needs once, whichever component wakes it up. The
 * application calls it, and so do the activity and the push service: a push
 * can start the process without any activity, and under test the application
 * class is replaced, so no single entry point can be relied on. Calling it
 * again is free.
 */
@Singleton
class AppStartup @Inject constructor(
    private val crashReporter: CrashReporter,
    private val notifier: Notifier,
    private val auth: AuthRepository,
    private val pushSource: PushSource,
    private val breadcrumbs: Breadcrumbs,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        crashReporter.install()
        notifier.createChannels()
        scope.launch { auth.restoreSession() }
        pushSource.start(scope)
        breadcrumbs.leave("app", "process started")
    }
}
