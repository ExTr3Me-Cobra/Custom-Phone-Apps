package com.ironlog.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

/**
 * Auto-save: a full backup (the same exact copy as Save backup) written to a file you pick once, overwritten each time.
 * Two places: one on this phone's storage (e.g. Documents) and one in Google Drive. It saves after every finished
 * workout and at least once a day when you open the app. The picked files are this phone's own (Android gives the
 * permission to this phone only), so they're kept apart and never go into a backup themselves.
 */
object AutoSave {
    enum class Slot(val key: String, val title: String, val file: String) {
        PHONE("phone", "On this phone", "loadout-autosave.zip"),
        DRIVE("drive", "Google Drive", "loadout-autosave-drive.zip"),
    }

    class State(val uri: String?, val on: Boolean, val last: Long?, val error: String?)

    /** Each slot's state, for the Settings screen. */
    val states = mutableStateMapOf<Slot, State>()
    @Volatile var saving = false
        private set

    private const val DAY = 24 * 60 * 60 * 1000L
    private fun file(c: Context) = File(c.noBackupFilesDir, "autosave.json")

    fun load(c: Context) {
        val o = runCatching { JSONObject(file(c).readText()) }.getOrDefault(JSONObject())
        for (s in Slot.entries) {
            val j = o.optJSONObject(s.key)
            states[s] = State(j?.optString("uri")?.ifEmpty { null }, j?.optBoolean("on", true) ?: false,
                j?.optLong("last")?.takeIf { it > 0 }, j?.optString("err")?.ifEmpty { null })
        }
    }

    private fun save(c: Context) {
        val o = JSONObject()
        for ((s, st) in states) o.put(s.key, JSONObject().put("uri", st.uri ?: "").put("on", st.on).put("last", st.last ?: 0).put("err", st.error ?: ""))
        runCatching { file(c).writeText(o.toString()) }
    }

    fun isDrive(uri: Uri) = uri.authority?.contains("com.google.android.apps.docs") == true

    /** You picked (or created) the file for [s]: keep permission to write it, then save straight away. */
    fun chosen(c: Context, s: Slot, uri: Uri): String {
        if (s == Slot.DRIVE && !isDrive(uri)) return "That file isn't in Google Drive. In the picker, open the menu (☰) and choose Drive. " +
            "(If Drive isn't listed, install the Google Drive app and sign in first.)"
        runCatching {
            c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        // Let go of the old file's permission.
        states[s]?.uri?.takeIf { it != uri.toString() }?.let { old ->
            runCatching { c.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        states[s] = State(uri.toString(), true, null, null)
        save(c)
        return if (write(c, s)) "${s.title}: auto-save set up and saved ✓" else "${s.title}: couldn't save – ${states[s]?.error}"
    }

    fun setOn(c: Context, s: Slot, on: Boolean) {
        val st = states[s] ?: return
        states[s] = State(st.uri, on, st.last, st.error); save(c)
    }

    fun forget(c: Context, s: Slot) {
        states[s]?.uri?.let { u -> runCatching { c.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        states[s] = State(null, false, null, null); save(c)
    }

    /** Places that are set up and switched on. */
    fun active(): List<Slot> = Slot.entries.filter { states[it]?.let { st -> st.on && st.uri != null } == true }

    /** Writes one place now. True when it worked. */
    @Synchronized
    private fun write(c: Context, s: Slot): Boolean {
        val st = states[s] ?: return false
        val uri = Uri.parse(st.uri ?: return false)
        saving = true
        val err = runCatching {
            // "wt": replace what's there (the file is overwritten each time, never added to).
            c.contentResolver.openOutputStream(uri, "wt")?.use { Store.writeBackup(it) } ?: error("couldn't open the file")
        }.exceptionOrNull()
        saving = false
        val msg = err?.let {
            if (it is SecurityException) "Loadout lost permission to the file – choose it again." else (it.message ?: "couldn't write the file")
        }
        states[s] = State(st.uri, st.on, if (err == null) System.currentTimeMillis() else st.last, msg)
        save(c)
        return err == null
    }

    /** Saves every place now (the Save backup button). Returns one line per place, or null if none is set up. */
    fun saveNow(c: Context): String? {
        val slots = active()
        if (slots.isEmpty()) return null
        val results = slots.map { s -> s to write(c, s) }
        return if (results.all { it.second }) "Auto-save complete ✓ " + results.joinToString(" · ") { "${it.first.title} ✓" }
            else results.joinToString("\n") { (s, ok) -> if (ok) "${s.title} ✓" else "${s.title}: couldn't save – ${states[s]?.error}" }
    }

    /** In the background: after a workout, or when it's been a day. */
    fun soon(c: Context, onlyIfDue: Boolean = false) {
        val app = c.applicationContext
        Thread {
            for (s in active()) {
                val last = states[s]?.last ?: 0
                if (!onlyIfDue || System.currentTimeMillis() - last > DAY) write(app, s)
            }
        }.start()
    }
}

/** Settings → Backup: the two auto-save places, each with its file, status and an on/off switch. */
@androidx.compose.runtime.Composable
fun AutoSaveSettings(show: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var picking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<AutoSave.Slot?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val s = picking ?: return@rememberLauncherForActivityResult
        picking = null
        if (uri == null) return@rememberLauncherForActivityResult
        show("Saving…")
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val msg = AutoSave.chosen(context, s, uri)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { show(msg) }
        }
    }
    val fmt = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
    for (s in AutoSave.Slot.entries) {
        val st = AutoSave.states[s]
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Card).padding(14.dp),
        ) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
                    Text((if (s == AutoSave.Slot.PHONE) "📱 Auto-save on this phone" else "☁️ Auto-save to Google Drive"), fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            st?.uri == null -> if (s == AutoSave.Slot.PHONE) "Not set up. Pick a folder such as Documents – the file stays even if Loadout is uninstalled."
                                else "Not set up. Needs the Google Drive app, signed in. In the picker, open the menu (☰) and choose Drive. Keeps a copy if you lose the phone."
                            st.error != null -> "⚠️ ${st.error}"
                            !st.on -> "Off"
                            st.last != null -> "✓ Last saved ${fmt.format(java.util.Date(st.last))}"
                            else -> "Not saved yet"
                        },
                        color = if (st?.error != null) Red else Sub, fontSize = 13.sp,
                    )
                }
                if (st?.uri != null) androidx.compose.material3.Switch(st.on, { AutoSave.setOn(context, s, it) })
            }
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.padding(top = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                GreyButton(if (st?.uri == null) "Choose file" else "Change file", androidx.compose.ui.Modifier.weight(1f)) {
                    picking = s; pick.launch(s.file)
                }
                if (st?.uri != null) GreyButton("Stop using", androidx.compose.ui.Modifier.weight(1f), Red) { AutoSave.forget(context, s) }
            }
        }
    }
}
