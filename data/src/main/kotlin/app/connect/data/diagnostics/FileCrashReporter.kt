package app.connect.data.diagnostics

import app.connect.domain.diagnostics.Breadcrumbs
import app.connect.domain.diagnostics.CrashReporter
import app.connect.domain.util.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CrashReport(
    val atMillis: Long,
    val fatal: Boolean,
    val thread: String,
    val exception: String,
    val message: String?,
    val stack: List<String>,
    val breadcrumbs: List<String>,
    val context: Map<String, String> = emptyMap(),
)

/**
 * Writes a report to disk when the app crashes, with the breadcrumb trail
 * that led up to it. The file is written synchronously inside the uncaught
 * exception handler, because nothing asynchronous survives a dying process;
 * the next launch finds it through [pendingReports] and can upload it.
 */
class FileCrashReporter(
    private val directory: File,
    private val breadcrumbs: Breadcrumbs,
    private val clock: Clock,
    private val maxReports: Int = 20,
) : CrashReporter {
    private val json = Json { prettyPrint = false }

    override fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(throwable, fatal = true, thread = thread.name, context = emptyMap()) }
            // Hand over to the platform handler so the process still dies and the system still reports it.
            previous?.uncaughtException(thread, throwable)
        }
    }

    override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {
        runCatching { write(throwable, fatal = false, thread = Thread.currentThread().name, context = context) }
    }

    fun pendingReports(): List<CrashReport> =
        reportFiles().mapNotNull { file ->
            runCatching { json.decodeFromString(CrashReport.serializer(), file.readText()) }.getOrNull()
        }

    fun clearReports() {
        reportFiles().forEach { it.delete() }
    }

    private fun write(throwable: Throwable, fatal: Boolean, thread: String, context: Map<String, String>) {
        directory.mkdirs()
        val report = CrashReport(
            atMillis = clock.nowMillis(),
            fatal = fatal,
            thread = thread,
            exception = throwable::class.java.name,
            // Messages can contain user input; keep only a bounded prefix.
            message = throwable.message?.take(MAX_MESSAGE_LENGTH),
            stack = throwable.stackTrace.take(MAX_FRAMES).map { it.toString() },
            breadcrumbs = breadcrumbs.snapshot().map { "${it.atMillis} [${it.category}] ${it.message}" },
            context = context,
        )
        val name = "${report.atMillis}-${if (fatal) "fatal" else "nonfatal"}-${System.nanoTime()}.json"
        File(directory, name).writeText(json.encodeToString(CrashReport.serializer(), report))
        reportFiles().dropLast(maxReports).forEach { it.delete() }
    }

    private fun reportFiles(): List<File> =
        directory.listFiles { file -> file.extension == "json" }?.sortedBy { it.name }.orEmpty()

    private companion object {
        const val MAX_FRAMES = 30
        const val MAX_MESSAGE_LENGTH = 200
    }
}
