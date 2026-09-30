package com.ultrax26.recorder.calls

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.ultrax26.recorder.util.UxLog

/**
 * Hands a call off to the Google Meet app. Google offers no API for a third-party app to join or place
 * Meet calls with its own media, so this only *launches Meet*: start a new meeting, open a meeting
 * link / code, or video-call a phone number the Meet way (formerly Duo). The call then runs inside
 * Meet — UltraX effects do not apply there. Without the Meet app, links open in the browser.
 */
object MeetLauncher {
    /** Google Meet (the merged Duo app) and the older stand-alone "Google Meet (original)". */
    const val MEET_PACKAGE = "com.google.android.apps.tachyon"
    const val MEET_LEGACY_PACKAGE = "com.google.android.apps.meetings"
    const val NEW_MEETING_URL = "https://meet.google.com/new"
    const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=$MEET_PACKAGE"
    /** Contacts-provider mime types Meet/Duo register for one-tap calls from dialers and contacts apps. */
    const val VIDEO_CALL_MIME = "vnd.android.cursor.item/com.google.android.apps.tachyon.phone"
    const val AUDIO_CALL_MIME = "vnd.android.cursor.item/com.google.android.apps.tachyon.phone.audio"

    private val codeRegex = Regex("^([a-z]{3})-?([a-z]{4})-?([a-z]{3})$", RegexOption.IGNORE_CASE)
    private val nicknameRegex = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{1,63}$")

    fun installedPackage(ctx: Context): String? = listOf(MEET_PACKAGE, MEET_LEGACY_PACKAGE).firstOrNull { pkg ->
        try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false } catch (_: Throwable) { false }
    }

    fun isInstalled(ctx: Context): Boolean = installedPackage(ctx) != null

    /**
     * Turns whatever the user pasted into a Meet URL: a full link (query/fragment dropped), a
     * `abc-defg-hij` code with or without dashes, a `g.co/meet/<name>` short link, or a Workspace
     * meeting nickname (opened through `/lookup/`). Returns null when it cannot be a meeting.
     */
    fun normalizeMeetingLink(input: String): String? {
        val t = input.trim().removePrefix("<").removeSuffix(">").trim()
        if (t.isEmpty()) return null
        val lower = t.lowercase()
        val meetIdx = lower.indexOf("meet.google.com/")
        if (meetIdx >= 0) {
            val path = t.substring(meetIdx + "meet.google.com/".length).substringBefore('?').substringBefore('#').trim('/')
            if (path.isEmpty() || path == "landing") return null
            return "https://meet.google.com/$path"
        }
        val gco = lower.indexOf("g.co/meet/")
        if (gco >= 0) {
            val nick = t.substring(gco + "g.co/meet/".length).substringBefore('?').substringBefore('#').trim('/')
            return if (nick.isEmpty()) null else "https://g.co/meet/$nick"
        }
        if (lower.contains("://") || lower.contains('/') || lower.contains('.')) return null
        codeRegex.matchEntire(t)?.let { m -> return "https://meet.google.com/${m.groupValues[1].lowercase()}-${m.groupValues[2].lowercase()}-${m.groupValues[3].lowercase()}" }
        if (nicknameRegex.matches(t)) return "https://meet.google.com/lookup/${t.lowercase()}"
        return null
    }

    /** Keeps a leading '+' and digits; null unless at least 3 digits remain. */
    fun normalizeNumber(raw: String): String? {
        val t = raw.trim()
        val plus = t.startsWith("+")
        val digits = t.filter { it.isDigit() }
        if (digits.length < 3) return null
        return (if (plus) "+" else "") + digits
    }

    fun startNewMeeting(ctx: Context): Boolean = open(ctx, Uri.parse(NEW_MEETING_URL))

    fun joinMeeting(ctx: Context, input: String): Boolean {
        val url = normalizeMeetingLink(input) ?: return false
        return open(ctx, Uri.parse(url))
    }

    /** Meet/Duo-style call to a phone number. Needs the Meet app; the callee must be reachable on Meet. */
    fun callNumber(ctx: Context, raw: String, video: Boolean = true): Boolean {
        val n = normalizeNumber(raw) ?: return false
        val pkg = installedPackage(ctx) ?: return false
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("tel:$n"), if (video) VIDEO_CALL_MIME else AUDIO_CALL_MIME).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(ctx, i)
    }

    fun openPlayStore(ctx: Context): Boolean =
        launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$MEET_PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) ||
            launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Prefer the Meet app for meet.google.com links; otherwise let the system (browser) handle it. */
    private fun open(ctx: Context, uri: Uri): Boolean {
        installedPackage(ctx)?.let { pkg -> if (launch(ctx, Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return true }
        return launch(ctx, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun launch(ctx: Context, i: Intent): Boolean = try { ctx.startActivity(i); true } catch (e: ActivityNotFoundException) { UxLog.w("Meet", "no activity for ${i.data}: ${e.message}"); false } catch (t: Throwable) { UxLog.w("Meet", "launch failed: ${t.message}"); false }
}
