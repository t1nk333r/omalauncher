package dev.omalauncher.linux

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.omalauncher.registerNotExported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "guest-sleep"

/**
 * Phone build: Linux sleeps while the screen is off, so its daemons stop keeping the phone and its mobile radio
 * awake. A minute after the screen goes off every guest process is stopped (SIGSTOP), unless Linux is busy: a job
 * of the launcher's runs ([Proot.jobs]), [keepAwake] says so (sound playing), or the guest used more than
 * [BUSY_TICKS] of CPU in that minute, as an agent at work or a build does. Busy, it looks again each minute.
 * The screen coming on wakes it (SIGCONT), and so does the launcher asking Linux for something ([needed]), which
 * sleeps again a minute later if the screen is still off.
 */
class GuestSleep(
    private val context: Context,
    private val proot: Proot,
    private val scope: CoroutineScope,
    private val keepAwake: () -> Boolean,
) {
    private val lock = Any()
    private var screenOn = true
    private var running = false
    private var check: Job? = null

    @Volatile
    private var asleep = false

    private val packageName = context.packageName
    private val self = Os.getpid()

    private var registered = false

    /** Linux started or stopped: it may only sleep while it runs. */
    fun linux(on: Boolean) {
        synchronized(lock) {
            if (!registered) {
                registered = true
                screenOn = context.getSystemService(PowerManager::class.java).isInteractive
                val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
                context.registerNotExported(filter) { screen(it.action == Intent.ACTION_SCREEN_ON) }
            }
            running = on
            if (on && !screenOn) {
                schedule()
            } else if (!on) {
                cancel()
                wake()
            }
        }
    }

    private fun screen(on: Boolean) =
        synchronized(lock) {
            screenOn = on
            if (on) {
                cancel()
                wake()
            } else if (running) {
                schedule()
            }
        }

    /** The launcher is about to use Linux: awake for it, and asleep again a minute later while the screen is off. */
    fun needed() {
        if (!asleep) return
        synchronized(lock) {
            if (!asleep) return
            wake()
            if (running && !screenOn) schedule()
        }
    }

    private fun cancel() {
        check?.cancel()
        check = null
    }

    private fun schedule() {
        cancel()
        check =
            scope.launch(Dispatchers.IO) {
                var before = cpu()
                while (true) {
                    delay(CHECK_MS)
                    val now = cpu()
                    val used = now.entries.sumOf { (pid, ticks) -> ticks - (before[pid] ?: 0L) }
                    before = now
                    val slept =
                        synchronized(lock) {
                            if (!running || screenOn || asleep) return@launch
                            val busy = proot.jobs.get() > 0 || keepAwake() || used > BUSY_TICKS
                            if (!busy) sleep()
                            !busy
                        }
                    if (slept) return@launch
                    Log.i(TAG, "busy (${used * 10} ms of CPU in the last minute); looking again in a minute")
                }
            }
    }

    /** proot first: a guest process stopped while its proot still runs would be let go by it. */
    private fun sleep() {
        val guest = guest()
        guest.filter { it.second }.forEach { (pid, _) -> signal(pid, OsConstants.SIGSTOP) }
        guest.filterNot { it.second }.forEach { (pid, _) -> signal(pid, OsConstants.SIGSTOP) }
        asleep = true
        Log.i(TAG, "asleep: ${guest.size} processes stopped")
    }

    /**
     * Guest processes first, which drops a SIGSTOP one hasn't taken yet, then proot. One that did take it, proot
     * passes on once it runs again and the process stops anew, so they're all continued twice more after it.
     */
    private fun wake() {
        if (!asleep) return
        asleep = false
        resume()
        scope.launch(Dispatchers.IO) {
            for (ms in longArrayOf(200, 1500)) {
                delay(ms)
                if (asleep) return@launch
                resume()
            }
        }
        Log.i(TAG, "awake")
    }

    private fun resume() {
        val guest = guest()
        guest.filterNot { it.second }.forEach { (pid, _) -> signal(pid, OsConstants.SIGCONT) }
        guest.filter { it.second }.forEach { (pid, _) -> signal(pid, OsConstants.SIGCONT) }
    }

    private fun signal(
        pid: Int,
        sig: Int,
    ) = runCatching { Os.kill(pid, sig) }

    /**
     * Every guest process, with whether it's a proot: all of ours but the app's own processes (named after the
     * package), since proot starts everything else the launcher runs. Our own are the only ones /proc shows us.
     */
    private fun guest(): List<Pair<Int, Boolean>> {
        val prootPath = proot.prootBinary
        return File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull()?.takeIf { it != self } ?: return@mapNotNull null
            val argv0 =
                runCatching { File(dir, "cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().decodeToString() }
                    .getOrNull() ?: return@mapNotNull null
            if (argv0.isEmpty() || argv0 == packageName || argv0.startsWith("$packageName:")) return@mapNotNull null
            pid to (argv0 == prootPath)
        }
    }

    /** CPU ticks (1/100 s) each guest process has used. */
    private fun cpu(): Map<Int, Long> =
        guest().associate { (pid, _) ->
            val fields = statFields(File("/proc/$pid"))
            pid to ((fields?.getOrNull(11)?.toLongOrNull() ?: 0L) + (fields?.getOrNull(12)?.toLongOrNull() ?: 0L))
        }

    private companion object {
        const val CHECK_MS = 60_000L

        /** 2 s of CPU in a minute. Idle, the guest used about 0.6 s; an agent at work uses far more. */
        const val BUSY_TICKS = 200L
    }
}
