package dev.omalauncher.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import dev.omalauncher.MainActivity
import java.io.File

/**
 * Phone build: restarts the launcher's process, for a restore. Runs in a process of its own (`:restart`): it
 * ends the launcher's, then opens home, which starts a new one, and ends itself. Started by a visible activity,
 * so Android lets it open home whether or not this launcher is the default one.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Too late: the launcher gave up waiting and dropped the restore. Leave it running.
        if (File(filesDir, "${Backups.RESTORE_DIR}/${Backups.READY}").isFile) {
            Process.killProcess(intent.getIntExtra(EXTRA_PID, -1))
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
        Process.killProcess(Process.myPid())
    }

    companion object {
        private const val EXTRA_PID = "pid"

        fun restart(context: Context) {
            context.startActivity(
                Intent(context, RestartActivity::class.java)
                    .putExtra(EXTRA_PID, Process.myPid())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}
