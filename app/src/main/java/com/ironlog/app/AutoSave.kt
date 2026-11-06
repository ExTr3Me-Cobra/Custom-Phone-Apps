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
 * Auto-save: a full backup (the same exact copy as Save backup) kept in two files per place, A and B, written in turn.
 * Each save overwrites the older one, so if a save is ever cut short (phone dies, app closed) the other file is still a
 * good copy. Two places: this phone's storage (e.g. Documents) and Google Drive. It saves after every finished workout,
 * 2 minutes after other changes (a burst of edits becomes one save), and at least once a day when you open the app.
 * The picked files belong to this phone (Android gives the permission to this install only), so they're kept apart and
 * never go into a backup themselves.
 */
object AutoSave {
    enum class Slot(val key: String, val title: String, val names: List<String>) {
        PHONE("phone", "On this phone", listOf("loadout-autosave-A.zip", "loadout-autosave-B.zip")),
        DRIVE("drive", "Google Drive", listOf("loadout-drive-autosave-A.zip", "loadout-drive-autosave-B.zip")),
    }

    /** One place: its two files (A, B), when each was last written, on/off, and the last problem. */
    class State(val uris: List<String?>, val lasts: List<Long?>, val on: Boolean, val error: String?) {
        val ready get() = uris.all { it != null }
        val last get() = lasts.filterNotNull().maxOrNull()
    }
    private val EMPTY = State(listOf(null, null), listOf(null, null), false, null)

    /** Each slot's state, for the Settings screen. */
    val states = mutableStateMapOf<Slot, State>()

    private const val DAY = 24 * 60 * 60 * 1000L
    private const val DELAY_MS = 2 * 60 * 1000L
    private fun file(c: Context) = File(c.noBackupFilesDir, "autosave.json")
    private var app: Context? = null
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val later = Runnable { app?.let { soon(it) } }

    fun load(c: Context) {
        app = c.applicationContext
        val o = runCatching { JSONObject(file(c).readText()) }.getOrDefault(JSONObject())
        for (s in Slot.entries) {
            val j = o.optJSONObject(s.key)
            states[s] = if (j == null) EMPTY else State(
                listOf(j.optString("a").ifEmpty { null }, j.optString("b").ifEmpty { null }),
                listOf(j.optLong("la").takeIf { it > 0 }, j.optLong("lb").takeIf { it > 0 }),
                j.optBoolean("on", true), j.optString("err").ifEmpty { null })
        }
    }

    private fun save(c: Context) {
        val o = JSONObject()
        for ((s, st) in states) o.put(s.key, JSONObject().put("a", st.uris[0] ?: "").put("b", st.uris[1] ?: "")
            .put("la", st.lasts[0] ?: 0).put("lb", st.lasts[1] ?: 0).put("on", st.on).put("err", st.error ?: ""))
        runCatching { file(c).writeText(o.toString()) }
    }

    fun isDrive(uri: Uri) = uri.authority?.contains("com.google.android.apps.docs") == true
    private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /**
     * You picked file A ([i] = 0) or B (1) for [s]. Returns a problem to show, or null.
     */
    fun chosen(c: Context, s: Slot, i: Int, uri: Uri): String? {
        if (s == Slot.DRIVE && !isDrive(uri)) return "That file isn't in Google Drive. In the picker, open the menu (☰) and choose Drive. " +
            "(If Drive isn't listed, install the Google Drive app and sign in first.)"
        val st = states[s] ?: EMPTY
        if (i == 1 && st.uris[0] == uri.toString()) return "Pick a second, different file (the B copy)."
        runCatching { c.contentResolver.takePersistableUriPermission(uri, RW) }
        st.uris[i]?.takeIf { it != uri.toString() }?.let { old -> runCatching { c.contentResolver.releasePersistableUriPermission(Uri.parse(old), RW) } }
        val uris = st.uris.toMutableList().also { it[i] = uri.toString() }
        states[s] = State(uris, if (i == 0) listOf(null, null) else st.lasts, true, null)
        save(c)
        return null
    }

    /**
     * This phone: you picked a folder. Loadout makes (or reuses) its two files there, named for you, and keeps
     * permission to the folder. Returns a problem to show, or null.
     */
    fun chosenFolder(c: Context, tree: Uri): String? {
        if (isDrive(tree)) return "For Google Drive, use the Google Drive auto-save below."
        return runCatching {
            c.contentResolver.takePersistableUriPermission(tree, RW)
            val r = c.contentResolver
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            val kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, android.provider.DocumentsContract.getDocumentId(parent))
            val have = HashMap<String, Uri>()
            r.query(kids, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cur ->
                while (cur.moveToNext()) have[cur.getString(1)] = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0))
            }
            val uris = Slot.PHONE.names.map { n ->
                (have[n] ?: android.provider.DocumentsContract.createDocument(r, parent, "application/zip", n) ?: error("couldn't make $n")).toString()
            }
            states[Slot.PHONE]?.uris?.filterNotNull()?.filter { it !in uris }?.forEach { u -> runCatching { r.releasePersistableUriPermission(Uri.parse(u), RW) } }
            states[Slot.PHONE] = State(uris, listOf(null, null), true, null)
            save(c)
            null
        }.getOrElse { "Couldn't use that folder: ${it.message}" }
    }

    /** The reminder shown wherever you pick where to save, or restore. */
    const val TWO_FILES = "Loadout saves two backup files that take turns (A and B), so one is always a good copy if a save is cut short. " +
        "To restore, select both files – Loadout uses the newer one."

    fun setOn(c: Context, s: Slot, on: Boolean) {
        val st = states[s] ?: return
        states[s] = State(st.uris, st.lasts, on, st.error); save(c)
    }

    fun forget(c: Context, s: Slot) {
        states[s]?.uris?.filterNotNull()?.forEach { u -> runCatching { c.contentResolver.releasePersistableUriPermission(Uri.parse(u), RW) } }
        states[s] = EMPTY; save(c)
    }

    /** Places that are set up and switched on. */
    fun active(): List<Slot> = Slot.entries.filter { states[it]?.let { st -> st.on && st.ready } == true }

    /** Writes one place now: whichever of A and B is older. True when it worked. */
    @Synchronized
    fun write(c: Context, s: Slot): Boolean {
        val st = states[s]?.takeIf { it.ready } ?: return false
        val i = if ((st.lasts[0] ?: 0) <= (st.lasts[1] ?: 0)) 0 else 1
        val err = runCatching {
            // "wt": replace what's in that file (it's overwritten, never added to). The other file is left alone.
            c.contentResolver.openOutputStream(Uri.parse(st.uris[i]), "wt")?.use { Store.writeBackup(it) } ?: error("couldn't open the file")
        }.exceptionOrNull()
        val msg = err?.let {
            if (it is SecurityException) "Loadout lost permission to a file – choose the files again." else (it.message ?: "couldn't write the file")
        }
        val lasts = st.lasts.toMutableList().also { if (err == null) it[i] = System.currentTimeMillis() }
        states[s] = State(st.uris, lasts, st.on, msg)
        save(c)
        return err == null
    }

    /** Saves every place now (Save backup now). One line per place, or null if none is set up. */
    fun saveNow(c: Context): String? {
        val slots = active()
        if (slots.isEmpty()) return null
        handler.removeCallbacks(later)
        val results = slots.map { s -> s to write(c, s) }
        return if (results.all { it.second }) "Auto-save complete ✓  " + results.joinToString(" · ") { "${it.first.title} ✓" }
            else results.joinToString("\n") { (s, ok) -> if (ok) "${s.title} ✓" else "${s.title}: couldn't save – ${states[s]?.error}" }
    }

    /** Something changed (a routine, a setting, an exercise…): save 2 minutes from now, unless more changes follow. */
    fun changed() {
        if (app == null || active().isEmpty()) return
        handler.removeCallbacks(later)
        handler.postDelayed(later, DELAY_MS)
    }

    /** In the background: after a workout, after changes, or when it's been a day. */
    fun soon(c: Context, onlyIfDue: Boolean = false) {
        val ctx = c.applicationContext
        if (!onlyIfDue) handler.removeCallbacks(later)
        Thread {
            for (s in active()) {
                if (!onlyIfDue || System.currentTimeMillis() - (states[s]?.last ?: 0) > DAY) write(ctx, s)
            }
        }.start()
    }
}

/** Settings → Backup: the two auto-save places, each with its two files, status and an on/off switch. */
@androidx.compose.runtime.Composable
fun AutoSaveSettings(show: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The file being picked: which place, and A (0) or B (1).
    var picking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<AutoSave.Slot, Int>?>(null) }
    var next by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<AutoSave.Slot, Int>?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val (s, i) = picking ?: return@rememberLauncherForActivityResult
        picking = null
        if (uri == null) { if (i == 1) show("${s.title}: both files are needed – tap Choose files to finish."); return@rememberLauncherForActivityResult }
        val problem = AutoSave.chosen(context, s, i, uri)
        when {
            problem != null -> { show(problem); if (i == 1) next = s to 1 }
            i == 0 -> { show("Now save the second file (B) – usually just tap Save."); next = s to 1 }
            else -> {
                show("Saving…")
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val ok = AutoSave.write(context, s)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        show(if (ok) "${s.title}: auto-save set up and saved ✓" else "${s.title}: couldn't save – ${AutoSave.states[s]?.error}")
                    }
                }
            }
        }
    }
    val folder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        val problem = AutoSave.chosenFolder(context, tree)
        if (problem != null) { show(problem); return@rememberLauncherForActivityResult }
        show("Saving…")
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = AutoSave.write(context, AutoSave.Slot.PHONE)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                show(if (ok) "On this phone: auto-save set up and saved ✓ (loadout-autosave-A.zip and -B.zip)" else "On this phone: couldn't save – ${AutoSave.states[AutoSave.Slot.PHONE]?.error}")
            }
        }
    }
    Text("⚠️ " + AutoSave.TWO_FILES, color = Sub, fontSize = 13.sp)
    androidx.compose.runtime.LaunchedEffect(next) {
        next?.let { (s, i) -> next = null; picking = s to i; pick.launch(s.names[i]) }
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
                    val last = st?.last
                    Text(
                        when {
                            st == null || !st.ready -> if (s == AutoSave.Slot.PHONE) "Not set up. Pick a folder such as Documents; Loadout names the two files for you. They stay even if Loadout is uninstalled."
                                else "Not set up. Needs the Google Drive app, signed in. In the picker, open the menu (☰) and choose Drive, then tap Save twice (the names are filled in). Keeps a copy if you lose the phone."
                            st.error != null -> "⚠️ ${st.error}"
                            !st.on -> "Off"
                            last != null -> "✓ Last saved ${fmt.format(java.util.Date(last))}"
                            else -> "Not saved yet"
                        },
                        color = if (st?.error != null) Red else Sub, fontSize = 13.sp,
                    )
                }
                if (st?.ready == true) androidx.compose.material3.Switch(st.on, { AutoSave.setOn(context, s, it) })
            }
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.padding(top = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                GreyButton(when { s == AutoSave.Slot.PHONE -> if (st?.ready == true) "Change folder" else "Choose folder"
                    st?.ready == true -> "Change files"; else -> "Choose files" }, androidx.compose.ui.Modifier.weight(1f)) {
                    if (s == AutoSave.Slot.PHONE) folder.launch(null) else next = s to 0
                }
                if (st?.uris?.any { it != null } == true) GreyButton("Stop using", androidx.compose.ui.Modifier.weight(1f), Red) { AutoSave.forget(context, s) }
            }
        }
    }
}
