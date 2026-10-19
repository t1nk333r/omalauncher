package dev.omalauncher.linux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * The phone's shared storage, bound into the guest as /sdcard (as Termux's storage) only while the user
 * allows Android's all-files access from settings › linux. Opt-in: with it, everything Linux runs, coding
 * agents too, can read and change every file on the phone.
 */
object SharedStorage {
    const val GUEST = "/sdcard"
    val root = File("/storage/emulated/0")

    /** All-files access exists from Android 11; before that the row isn't offered. */
    val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Android allows it now. Android checks every access itself, so turning it off there stops the guest's at once. */
    fun allowed() = supported && Environment.isExternalStorageManager()

    /** The bind [Proot] adds while it's [allowed]. */
    fun binds() = if (allowed()) listOf(GUEST to root) else emptyList()

    /** Android's all-files access page for this app, where the user turns it on or off. */
    fun settings(context: Context) =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
}
