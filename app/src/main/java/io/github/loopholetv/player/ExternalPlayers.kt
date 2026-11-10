package io.github.loopholetv.player

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.net.URLEncoder

/** An external video player app that can be chosen to play a channel. */
data class ExternalPlayerApp(val packageName: String, val label: String)

/** The stored choice meaning "show the system app chooser every time". */
const val ASK_EACH_TIME = "ask"

/** A resolved stream plus whether it is HLS, ready to pass to an external player. Never logged. */
data class Handoff(val stream: ResolvedStream, val hls: Boolean) {
    override fun toString() = "Handoff(***)"
}

/**
 * Catalogue of supported external players and the VIEW intents that open a stream in them.
 * Intended to be called only when a channel is opened full screen; previews are not handed off.
 */
object ExternalPlayers {
    private const val MX_FREE = "com.mxtech.videoplayer.ad"
    private const val MX_PRO = "com.mxtech.videoplayer.pro"
    private const val KODI = "org.xbmc.kodi"

    val KNOWN = listOf(
        ExternalPlayerApp(MX_FREE, "MX Player"),
        ExternalPlayerApp(MX_PRO, "MX Player Pro"),
        ExternalPlayerApp("org.videolan.vlc", "VLC"),
        ExternalPlayerApp(KODI, "Kodi"),
        ExternalPlayerApp("com.brouken.player", "Just Player"),
    )

    /** The [KNOWN] players installed on this device, in [KNOWN] order. */
    fun installed(pm: PackageManager): List<ExternalPlayerApp> = KNOWN.filter { app ->
        try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun choiceLabel(choice: String?): String = when (choice) {
        null -> "Off"
        ASK_EACH_TIME -> "Ask each time"
        else -> KNOWN.firstOrNull { it.packageName == choice }?.label ?: choice
    }

    fun intentFor(choice: String, handoff: Handoff, title: String): Intent {
        val stream = handoff.stream
        val isKodi = choice == KODI
        val url = if (isKodi) stream.url + "|" + kodiOptions(stream) else stream.url
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), if (handoff.hls) "application/x-mpegURL" else "video/*")
            .putExtra("title", title)
        when (choice) {
            ASK_EACH_TIME -> return Intent.createChooser(view, null)
            MX_FREE, MX_PRO -> {
                val activity = if (choice == MX_PRO) "com.mxtech.videoplayer.ActivityScreen" else "com.mxtech.videoplayer.ad.ActivityScreen"
                val headers = buildList {
                    add("User-Agent"); add(stream.userAgent)
                    stream.headers.forEach { (k, v) -> add(k); add(v) }
                }
                view.setClassName(choice, activity)
                    .putExtra("headers", headers.toTypedArray())
                    .putExtra("return_result", true)
            }
            else -> view.setPackage(choice)
        }
        return view
    }

    private fun kodiOptions(stream: ResolvedStream): String {
        val parts = mutableListOf("User-Agent=" + enc(stream.userAgent))
        stream.headers["Referer"]?.let { parts += "Referer=" + enc(it) }
        return parts.joinToString("&")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
