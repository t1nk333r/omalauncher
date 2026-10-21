package dev.omalauncher.backup

import android.app.job.JobParameters
import android.app.job.JobService
import dev.omalauncher.App
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/** Phone build: the daily backup to Feather ([Backups.ensureScheduled]). */
class BackupJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        // During Android's own backup the process has a plain Application: nothing to do then.
        val backups = (application as? App)?.backups ?: return false
        thread(name = "backup-job") {
            val status = runBlocking { backups.backUp(auto = true) }
            // Feather out of reach (away from home): try again later today rather than tomorrow.
            jobFinished(params, !status.ok && "can't reach" in status.message)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
