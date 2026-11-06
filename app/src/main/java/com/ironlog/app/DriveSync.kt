package com.ironlog.app

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Auto-save straight to Google Drive after you sign in with Google (no file picker). Loadout keeps a "Loadout"
 * folder in your Drive with the two auto-save files (A and B, taking turns) and uploads into it.
 *
 * Permission is Google's narrowest Drive one (drive.file): Loadout can only see the files it made, nothing else in
 * your Drive. The sign-in is tied to this app's package name and signing key, set up once in Google Cloud Console
 * (an Android OAuth client), so no client ID or secret is kept in the app.
 */
object DriveSync {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val FOLDER = "Loadout"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    private fun prefs(c: Context) = File(c.noBackupFilesDir, "drive_account.json")

    /** The Google account you signed in with (null = not signed in). Kept on this phone only. */
    @Volatile var account: String? = null
        private set
    val signedIn get() = account != null

    fun load(c: Context) {
        account = runCatching { JSONObject(prefs(c).readText()).optString("email").ifEmpty { null } }.getOrNull()
    }

    private fun saveAccount(c: Context, email: String?) {
        account = email
        runCatching { if (email == null) prefs(c).delete() else prefs(c).writeText(JSONObject().put("email", email).toString()) }
    }

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Signing in (or a token that needs you to tap Allow): a screen to show, or a token straight away. */
    sealed class Auth {
        class Token(val token: String) : Auth()
        class NeedsYou(val intent: PendingIntent) : Auth()
        class Failed(val why: String) : Auth()
    }

    /** Asks Google for access. Off the main thread only. Shows nothing by itself: [Auth.NeedsYou] carries the screen. */
    fun authorize(c: Context): Auth = runCatching {
        val r: AuthorizationResult = Tasks.await(Identity.getAuthorizationClient(c).authorize(request()))
        when {
            r.hasResolution() -> Auth.NeedsYou(r.pendingIntent!!)
            r.accessToken != null -> Auth.Token(r.accessToken!!)
            else -> Auth.Failed("Google didn't give access")
        }
    }.getOrElse { Auth.Failed(explain(it)) }

    /** After the Google screen: the token, or why not. */
    fun fromScreen(a: Activity, data: android.content.Intent?): Auth = runCatching {
        val r = Identity.getAuthorizationClient(a).getAuthorizationResultFromIntent(data)
        r.accessToken?.let { Auth.Token(it) } ?: Auth.Failed("Google didn't give access")
    }.getOrElse { Auth.Failed(explain(it)) }

    private fun explain(e: Throwable): String {
        val m = (e.cause ?: e).message ?: e.javaClass.simpleName
        return when {
            // Status 10 = DEVELOPER_ERROR: the Android OAuth client in Google Cloud isn't set up (or doesn't match).
            m.contains("10:") || m.contains("DEVELOPER_ERROR") ->
                "Google sign-in isn't set up for Loadout yet (the Google Cloud step). Until then, use the file picker for Drive."
            m.contains("12501") || m.contains("16:") || m.contains("cancel", true) -> "Sign-in cancelled."
            m.contains("7:") || m.contains("NETWORK", true) -> "No internet connection."
            else -> m
        }
    }

    /** Signed in: remember which account (from Drive itself). */
    fun finishSignIn(c: Context, token: String): String? = runCatching {
        val about = JSONObject(call("GET", "$API/about?fields=user(emailAddress)", token))
        saveAccount(c, about.getJSONObject("user").optString("emailAddress").ifEmpty { "your Google account" })
        null
    }.getOrElse { it.message ?: "couldn't reach Google Drive" }

    /** Signs out on this phone (and lets go of Loadout's access to your Drive). */
    fun signOut(c: Context) {
        val token = (authorize(c) as? Auth.Token)?.token
        if (token != null) runCatching {
            (URL("https://oauth2.googleapis.com/revoke?token=" + URLEncoder.encode(token, "UTF-8")).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15000; readTimeout = 15000; responseCode; disconnect()
            }
        }
        saveAccount(c, null)
    }

    // ---- Drive calls ----

    private fun call(method: String, url: String, token: String, json: String? = null): String {
        val h = URL(url).openConnection() as HttpURLConnection
        h.requestMethod = method; h.connectTimeout = 20000; h.readTimeout = 60000
        h.setRequestProperty("Authorization", "Bearer $token")
        if (json != null) {
            h.doOutput = true
            h.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            h.outputStream.use { it.write(json.toByteArray()) }
        }
        val code = h.responseCode
        val body = (if (code in 200..299) h.inputStream else h.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        h.disconnect()
        if (code == 401) throw SecurityException("Google sign-in expired")
        if (code !in 200..299) error("Drive said $code ${body.take(200)}")
        return body
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The Loadout folder in your Drive (made if it isn't there). */
    private fun folder(token: String): String {
        val found = JSONObject(call("GET", "$API/files?spaces=drive&fields=files(id)&q=" +
            q("name = '$FOLDER' and mimeType = '$FOLDER_MIME' and trashed = false"), token)).getJSONArray("files")
        if (found.length() > 0) return found.getJSONObject(0).getString("id")
        return JSONObject(call("POST", "$API/files?fields=id", token, JSONObject().put("name", FOLDER).put("mimeType", FOLDER_MIME).toString())).getString("id")
    }

    /** The auto-save files in the Loadout folder: name -> (id, modified time). */
    private fun files(token: String, folder: String): Map<String, Pair<String, Long>> {
        val a = JSONObject(call("GET", "$API/files?spaces=drive&fields=files(id,name,modifiedTime)&q=" +
            q("'$folder' in parents and trashed = false"), token)).getJSONArray("files")
        return (0 until a.length()).associate { i ->
            val o = a.getJSONObject(i)
            o.getString("name") to (o.getString("id") to runCatching { java.time.Instant.parse(o.getString("modifiedTime")).toEpochMilli() }.getOrDefault(0L))
        }
    }

    /**
     * Uploads a full backup as [name] in the Loadout folder (replacing it if it's there). Drive only swaps in the new
     * copy once the upload has finished, so a cut-off upload leaves the old one as it was.
     */
    fun upload(c: Context, name: String) {
        val token = (authorize(c) as? Auth.Token)?.token ?: throw SecurityException("Google needs you to sign in again")
        val tmp = File(c.cacheDir, "drive_upload.zip")
        try {
            tmp.outputStream().use { Store.writeBackup(it) }
            val dir = folder(token)
            val existing = files(token, dir)[name]?.first
            // Resumable upload: start it, then send the file in one go.
            val start = URL(if (existing != null) "$UPLOAD/files/$existing?uploadType=resumable" else "$UPLOAD/files?uploadType=resumable")
                .openConnection() as HttpURLConnection
            // Replacing a file is a PATCH, which Android's HTTP doesn't send: POST with an override header instead.
            start.requestMethod = "POST"
            if (existing != null) start.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            start.doOutput = true; start.connectTimeout = 20000; start.readTimeout = 60000
            start.setRequestProperty("Authorization", "Bearer $token")
            start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            start.setRequestProperty("X-Upload-Content-Type", "application/zip")
            start.setRequestProperty("X-Upload-Content-Length", tmp.length().toString())
            val meta = if (existing != null) JSONObject() else JSONObject().put("name", name).put("parents", org.json.JSONArray().put(dir))
            start.outputStream.use { it.write(meta.toString().toByteArray()) }
            val code = start.responseCode
            val where = start.getHeaderField("Location")
            start.disconnect()
            if (code == 401) throw SecurityException("Google sign-in expired")
            if (code !in 200..299 || where == null) error("Drive said $code")
            val put = URL(where).openConnection() as HttpURLConnection
            put.requestMethod = "PUT"; put.doOutput = true; put.connectTimeout = 20000; put.readTimeout = 120000
            put.setFixedLengthStreamingMode(tmp.length())
            put.setRequestProperty("Content-Type", "application/zip")
            put.outputStream.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            val done = put.responseCode
            put.disconnect()
            if (done !in 200..299) error("Drive upload failed ($done)")
        } finally { tmp.delete() }
    }

    /** Deletes the auto-save files from your Drive's Loadout folder (switching Drive auto-save off). */
    fun deleteBackups(c: Context) {
        val token = (authorize(c) as? Auth.Token)?.token ?: return
        runCatching {
            val dir = folder(token)
            val names = AutoSave.Slot.DRIVE.names.toSet()
            files(token, dir).filterKeys { it in names }.values.forEach { (id, _) -> runCatching { call("DELETE", "$API/files/$id", token) } }
        }
    }

    /** When the newest auto-save in your Drive was saved, or null. */
    fun newestTime(c: Context): Long? = runCatching {
        val token = (authorize(c) as? Auth.Token)?.token ?: return null
        val names = AutoSave.Slot.DRIVE.names.toSet()
        files(token, folder(token)).filterKeys { it in names }.values.maxOfOrNull { it.second }
    }.getOrNull()

    /** The newest auto-save in your Drive's Loadout folder, downloaded (null if there's none). */
    fun newestBackup(c: Context): ByteArray? {
        val token = (authorize(c) as? Auth.Token)?.token ?: throw SecurityException("Google needs you to sign in again")
        val dir = folder(token)
        val names = AutoSave.Slot.DRIVE.names.toSet()
        val newest = files(token, dir).filterKeys { it in names }.values.maxByOrNull { it.second } ?: return null
        val h = URL("$API/files/${newest.first}?alt=media").openConnection() as HttpURLConnection
        h.setRequestProperty("Authorization", "Bearer $token"); h.connectTimeout = 20000; h.readTimeout = 120000
        return try { if (h.responseCode in 200..299) h.inputStream.use { it.readBytes() } else null } finally { h.disconnect() }
    }
}
