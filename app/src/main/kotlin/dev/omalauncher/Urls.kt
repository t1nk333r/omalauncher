package dev.omalauncher

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/** Opens [url] in whatever app handles it. False when none does (no browser, an unknown scheme). */
fun Context.openUrl(url: String): Boolean =
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

/** The web searches the Style menu offers, by name; style's home.web-search can be any other. The first is the default. */
val WEB_SEARCHES =
    listOf(
        "google" to "https://www.google.com/search?q=%s",
        "duckduckgo" to "https://duckduckgo.com/?q=%s",
        "brave" to "https://search.brave.com/search?q=%s",
        "startpage" to "https://www.startpage.com/do/search?q=%s",
    )

/** Whether [template] is a web search address: http(s), with %s where the words go. */
fun isWebSearch(template: String) = (template.startsWith("https://") || template.startsWith("http://")) && "%s" in template

/** [query] searched with [template] (null for the default), its lines as one, encoded for the address. */
fun webSearchUrl(
    template: String?,
    query: String,
): String = (template ?: WEB_SEARCHES.first().second).replace("%s", URLEncoder.encode(query.trim().replace(Regex("\\s+"), " "), "UTF-8"))

/** What [template] is called: one of [WEB_SEARCHES]' names, else its site ("searx.example.org"). */
fun webSearchName(template: String?): String {
    val t = template ?: WEB_SEARCHES.first().second
    return WEB_SEARCHES.firstOrNull { it.second == t }?.first ?: t.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.")
}

/** Puts [text] on the clipboard, [label] saying what it is. */
fun Context.copyText(
    label: String,
    text: String,
) = getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))

/** Android's share sheet for [text], to send it to any app. */
fun Context.shareText(
    text: String,
    subject: String? = null,
    chooserTitle: String? = null,
) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    subject?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
    startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
