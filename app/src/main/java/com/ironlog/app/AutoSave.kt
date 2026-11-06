package com.ironlog.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

    /** This phone: the folder you picked (Loadout keeps its own Loadout folder inside it, and remakes files that go missing). */
    @Volatile private var phoneTree: String? = null

    fun load(c: Context) {
        app = c.applicationContext
        val o = runCatching { JSONObject(file(c).readText()) }.getOrDefault(JSONObject())
        phoneTree = o.optString("tree").ifEmpty { null }
        for (s in Slot.entries) {
            val j = o.optJSONObject(s.key)
            states[s] = if (j == null) EMPTY else State(
                listOf(j.optString("a").ifEmpty { null }, j.optString("b").ifEmpty { null }),
                listOf(j.optLong("la").takeIf { it > 0 }, j.optLong("lb").takeIf { it > 0 }),
                j.optBoolean("on", true), j.optString("err").ifEmpty { null })
        }
    }

    private fun save(c: Context) {
        val o = JSONObject().put("tree", phoneTree ?: "")
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
     * This phone: you picked a folder. Loadout makes its own "Loadout" folder inside it (or uses it, if that's what you
     * picked), names its two files there for you, and keeps permission so it can remake them if they're ever deleted.
     * Returns a problem to show, or null.
     */
    fun chosenFolder(c: Context, tree: Uri): String? {
        if (isDrive(tree)) return "For Google Drive, use the Google Drive auto-save below."
        return runCatching {
            c.contentResolver.takePersistableUriPermission(tree, RW)
            phoneTree?.takeIf { it != tree.toString() }?.let { old -> runCatching { c.contentResolver.releasePersistableUriPermission(Uri.parse(old), RW) } }
            phoneTree = tree.toString()
            val uris = phoneFiles(c, tree, Slot.PHONE.names.indices.toList())
            states[Slot.PHONE]?.uris?.filterNotNull()?.filter { it !in uris }?.forEach { u -> runCatching { c.contentResolver.releasePersistableUriPermission(Uri.parse(u), RW) } }
            states[Slot.PHONE] = State(uris, listOf(null, null), true, null)
            save(c)
            null
        }.getOrElse { "Couldn't use that folder: ${it.message}" }
    }

    /** Children of a folder in the picked tree: name -> (document id, is a folder). */
    private fun children(c: Context, tree: Uri, docId: String): Map<String, Pair<String, Boolean>> {
        val out = HashMap<String, Pair<String, Boolean>>()
        val kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        c.contentResolver.query(kids, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cur ->
            while (cur.moveToNext()) out[cur.getString(1) ?: ""] = cur.getString(0) to (cur.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
        }
        return out
    }

    /** The Loadout folder in the picked tree (made if it's missing), and the two files in it (made if missing). */
    private fun phoneFiles(c: Context, tree: Uri, which: List<Int>): List<String> {
        val r = c.contentResolver
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        // Picked a folder called Loadout? Use it; otherwise use (or make) Loadout inside it.
        val pickedName = r.query(root, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        val dirId = if (pickedName == "Loadout") rootId else children(c, tree, rootId)["Loadout"]?.takeIf { it.second }?.first
            ?: android.provider.DocumentsContract.getDocumentId(
                android.provider.DocumentsContract.createDocument(r, root, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, "Loadout") ?: error("couldn't make the Loadout folder"))
        val dir = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        val have = children(c, tree, dirId)
        val current = states[Slot.PHONE]?.uris.orEmpty()
        return Slot.PHONE.names.mapIndexed { i, n ->
            if (i !in which && current.getOrNull(i) != null) current[i]!!
            else have[n]?.let { android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, it.first).toString() }
                ?: android.provider.DocumentsContract.createDocument(r, dir, "application/zip", n)?.toString() ?: error("couldn't make $n")
        }
    }

    /** Is this file still there? */
    private fun exists(c: Context, uri: String?): Boolean = uri != null && runCatching {
        c.contentResolver.query(Uri.parse(uri), arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    /** A place that lost access (shown as a pop-up as soon as the app is open): which one, and what happened. */
    val problem = androidx.compose.runtime.mutableStateOf<Pair<Slot, String>?>(null)

    /** That place can't be reached: switch it off (it needs setting up again) and say so straight away. */
    private fun lost(c: Context, s: Slot, why: String) {
        forget(c, s)
        problem.value = s to why
    }

    /** On opening the app: can every auto-save place still be reached? (Missing phone files are just remade.) */
    fun checkAccess(c: Context) {
        val held = c.contentResolver.persistedUriPermissions.filter { it.isWritePermission }.map { it.uri.toString() }.toSet()
        for (s in active()) {
            if (s == Slot.PHONE) {
                val t = phoneTree
                val ok = t != null && t in held && runCatching {
                    val tree = Uri.parse(t)
                    c.contentResolver.query(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                        android.provider.DocumentsContract.getTreeDocumentId(tree)), null, null, null, null)?.use { it.moveToFirst() } == true
                }.getOrDefault(false)
                if (!ok) lost(c, s, "Loadout can't reach the folder it auto-saves to on this phone any more (the folder was deleted, or Android took back access).")
            } else if (missing(c, s).isNotEmpty() || states[s]?.uris?.any { it !in held } == true) {
                lost(c, s, "Loadout can't reach its auto-save files in Google Drive any more (they were deleted or moved, or Android took back access).")
            }
        }
    }

    /** Which of a place's files are missing (deleted, moved to the trash…). */
    fun missing(c: Context, s: Slot): List<Int> = states[s]?.takeIf { it.ready }?.uris?.withIndex()?.filter { !exists(c, it.value) }?.map { it.index }.orEmpty()

    /** The phone's Documents folder, where the pickers open (a suggestion: you can still go anywhere). */
    val DOCUMENTS: Uri = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")

    /** Save-as picker that opens where [initial] is (e.g. the folder file A went to, for file B). */
    class CreateIn : androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip") {
        var initial: Uri? = null
        override fun createIntent(context: Context, input: String): Intent =
            super.createIntent(context, input).apply { initial?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it) } }
    }

    /** Pick files to restore, opening in Documents. */
    class OpenBackups : androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, DOCUMENTS)
    }

    /** This phone's own auto-save files, if they're set up (no picker needed to restore them). */
    fun ownFiles(): List<Uri> = states[Slot.PHONE]?.takeIf { it.ready && it.on }?.uris?.filterNotNull()?.map(Uri::parse).orEmpty()

    /** Backups in a folder you opened (and its subfolders, one level down): auto-save files and saved backups. */
    fun findInTree(c: Context, tree: Uri): List<Uri> {
        val r = c.contentResolver
        val out = mutableListOf<Uri>()
        val names = Slot.entries.flatMap { it.names }.toSet()
        fun scan(docId: String, depth: Int) {
            val kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            runCatching {
                r.query(kids, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null)?.use { cur ->
                    while (cur.moveToNext()) {
                        val id = cur.getString(0); val name = cur.getString(1) ?: ""; val mime = cur.getString(2)
                        if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) { if (depth < 1) scan(id, depth + 1) }
                        else if (name in names || (name.startsWith("loadout-backup") && name.endsWith(".zip")))
                            out += android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }
        scan(android.provider.DocumentsContract.getTreeDocumentId(tree), 0)
        return out
    }

    /** The reminder that pops up wherever you pick where to save, or pick restore files yourself. */
    const val TWO_FILES = "Loadout saves two backup files that take turns (A and B), so one is always a good copy if a save is cut short. " +
        "To restore, select both files – Loadout uses the newer one."

    /**
     * Switching a place off deletes its backup files. This phone keeps the folder, so switching back on makes the files
     * again and saves straight away; Google Drive forgets the files, so switching back on asks for them again.
     */
    fun turnOff(c: Context, s: Slot) {
        val st = states[s] ?: return
        st.uris.filterNotNull().forEach { u -> runCatching { android.provider.DocumentsContract.deleteDocument(c.contentResolver, Uri.parse(u)) } }
        if (s == Slot.PHONE && phoneTree != null) { states[s] = State(st.uris, listOf(null, null), false, null); save(c) }
        else forget(c, s)
    }

    /** Switching this phone's auto-save back on: remake the files in the same folder and save. False if it needs a folder again. */
    fun turnOn(c: Context, s: Slot): Boolean {
        val st = states[s]?.takeIf { it.ready } ?: return false
        if (s == Slot.PHONE && phoneTree == null) return false
        states[s] = State(st.uris, st.lasts, true, null); save(c)
        soon(c)
        return true
    }

    fun forget(c: Context, s: Slot) {
        states[s]?.uris?.filterNotNull()?.forEach { u -> runCatching { c.contentResolver.releasePersistableUriPermission(Uri.parse(u), RW) } }
        if (s == Slot.PHONE) { phoneTree?.let { t -> runCatching { c.contentResolver.releasePersistableUriPermission(Uri.parse(t), RW) } }; phoneTree = null }
        states[s] = EMPTY; save(c)
    }

    /** Places that are set up and switched on. */
    fun active(): List<Slot> = Slot.entries.filter { states[it]?.let { st -> st.on && st.ready } == true }

    /** Writes one place now: whichever of A and B is older. True when it worked. */
    @Synchronized
    fun write(c: Context, s: Slot): Boolean {
        var st = states[s]?.takeIf { it.ready } ?: return false
        val gone = missing(c, s)
        if (gone.isNotEmpty()) {
            val tree = phoneTree
            if (s == Slot.PHONE && tree != null) {
                // Deleted? Make them again (and the Loadout folder, if that went too), then save into them.
                val remade = runCatching { phoneFiles(c, Uri.parse(tree), gone) }.getOrNull()
                if (remade == null) { lost(c, s, "Loadout can't reach the folder it auto-saves to on this phone any more (the folder was deleted, or Android took back access)."); return false }
                st = State(remade, st.lasts.mapIndexed { i, l -> if (i in gone) null else l }, st.on, null)
                states[s] = st; save(c)
            } else {
                lost(c, s, "An auto-save file in Google Drive was deleted or moved, and Loadout can't make files in Drive by itself."); return false
            }
        }
        // A remade (empty) file is filled first; otherwise the older of A and B.
        val i = gone.firstOrNull() ?: if ((st.lasts[0] ?: 0) <= (st.lasts[1] ?: 0)) 0 else 1
        val err = runCatching {
            // "wt": replace what's in that file (it's overwritten, never added to). The other file is left alone.
            c.contentResolver.openOutputStream(Uri.parse(st.uris[i]), "wt")?.use { Store.writeBackup(it) } ?: error("couldn't open the file")
        }.exceptionOrNull()
        if (err is SecurityException) {
            lost(c, s, if (s == Slot.PHONE) "Android took back Loadout's access to its auto-save folder on this phone."
                else "Android took back Loadout's access to its auto-save files in Google Drive.")
            return false
        }
        val msg = err?.let { it.message ?: "couldn't write the file" }
        val lasts = st.lasts.toMutableList().also { if (err == null) it[i] = System.currentTimeMillis() }
        states[s] = State(st.uris, lasts, st.on, msg)
        save(c)
        // Both were deleted: fill the other one too, so there are two good copies again.
        if (err == null && gone.size > 1) gone.drop(1).forEach { j ->
            runCatching { c.contentResolver.openOutputStream(Uri.parse(st.uris[j]), "wt")?.use { Store.writeBackup(it) } }.onSuccess {
                states[s] = State(st.uris, states[s]!!.lasts.toMutableList().also { it[j] = System.currentTimeMillis() }, st.on, null); save(c)
            }
        }
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
            if (onlyIfDue) checkAccess(ctx)
            for (s in active()) {
                // On opening the app: save if it's been a day, or straight away if a file has gone missing.
                if (!onlyIfDue || System.currentTimeMillis() - (states[s]?.last ?: 0) > DAY || missing(ctx, s).isNotEmpty()) write(ctx, s)
            }
        }.start()
    }
}

/** Settings → Backup: the two auto-save places, each with its two files, status and an on/off switch. */
@androidx.compose.runtime.Composable
fun AutoSaveSettings(only: AutoSave.Slot? = null, show: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The file being picked: which place, and A (0) or B (1).
    var picking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<AutoSave.Slot, Int>?>(null) }
    var next by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<AutoSave.Slot, Int>?>(null) }
    val createIn = androidx.compose.runtime.remember { AutoSave.CreateIn() }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(createIn) { uri ->
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
                show(if (ok) "On this phone: auto-save set up and saved ✓ (Loadout folder: loadout-autosave-A.zip and -B.zip)" else "On this phone: couldn't save – ${AutoSave.states[AutoSave.Slot.PHONE]?.error}")
            }
        }
    }
    // Switching off deletes the backup files: asked first.
    var confirmOff by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<AutoSave.Slot?>(null) }
    confirmOff?.let { s ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmOff = null },
            text = { Text("Turn off ${s.title.lowercase().replaceFirstChar { it.uppercase() }} auto-save? Its two backup files are deleted until you turn it back on.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                confirmOff = null
                scope.launch(kotlinx.coroutines.Dispatchers.IO) { AutoSave.turnOff(context, s) }
            }) { Text("Turn off and delete", color = Red) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmOff = null }) { Text("Keep it on") } },
        )
    }
    // The A/B explanation pops up before each picker; OK opens it.
    var explain by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<AutoSave.Slot?>(null) }
    explain?.let { s ->
        TwoFilesDialog(if (s == AutoSave.Slot.PHONE) "Pick a folder such as Documents: Loadout makes a Loadout folder in it, names both files for you, and remakes them if they're ever deleted."
            else "Needs the Google Drive app, signed in. In the picker, open the menu (☰) and choose Drive, then tap Save twice – the names are filled in.",
            onDismiss = { explain = null }) {
            explain = null
            if (s == AutoSave.Slot.PHONE) folder.launch(AutoSave.DOCUMENTS) else next = s to 0
        }
    }
    androidx.compose.runtime.LaunchedEffect(next) {
        next?.let { (s, i) ->
            next = null; picking = s to i
            // File B opens in the folder file A went to.
            createIn.initial = if (i == 1) AutoSave.states[s]?.uris?.getOrNull(0)?.let(Uri::parse) else null
            pick.launch(s.names[i])
        }
    }
    for (s in AutoSave.Slot.entries.filter { only == null || it == only }) {
        val st = AutoSave.states[s]
        val on = st?.ready == true && st.on
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Card).padding(14.dp),
        ) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text((if (s == AutoSave.Slot.PHONE) "📱 Auto-save on this phone" else "☁️ Auto-save to Google Drive"), fontWeight = FontWeight.Bold,
                    modifier = androidx.compose.ui.Modifier.weight(1f))
                // On: this phone remakes its files in the same folder; otherwise the A/B note, then the picker.
                // Off: deletes the backup files (after asking).
                androidx.compose.material3.Switch(on, { want ->
                    when {
                        !want -> confirmOff = s
                        st?.ready == true && AutoSave.turnOn(context, s) -> show("${s.title}: auto-save on – saving…")
                        else -> explain = s
                    }
                })
            }
            // When it last saved there (updates as soon as a save finishes).
            val last = st?.last
            if (on && last != null) Text("Last saved " + java.text.SimpleDateFormat("EEE, MMM d · h:mm a", java.util.Locale.getDefault()).format(java.util.Date(last)),
                color = Sub, fontSize = 13.sp, modifier = androidx.compose.ui.Modifier.padding(top = 2.dp))
            // Only problems are shown (e.g. a Drive file was deleted).
            st?.error?.takeIf { st.ready }?.let { Text("⚠️ $it", color = Red, fontSize = 13.sp, modifier = androidx.compose.ui.Modifier.padding(top = 4.dp)) }
        }
    }
}

/**
 * Restore: uses this phone's auto-save files if they're set up; otherwise opens Documents (you allow access once),
 * finds the auto-save files or a saved backup there and restores the newest; if there's none, [pickManually] opens
 * the file picker. [restore] does the restoring (it picks the newest of the files it's given).
 */
@androidx.compose.runtime.Composable
fun RestoreButton(label: String, modifier: androidx.compose.ui.Modifier, big: Boolean, show: (String) -> Unit,
                  pickManually: () -> Unit, restore: (List<Uri>) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<Uri>?>(null) }
    // Finding the files yourself: the A/B explanation first.
    var manual by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (manual) TwoFilesDialog("Find loadout-autosave-A.zip and -B.zip (or loadout-drive-autosave-A/B.zip in Google Drive) and select both.",
        onDismiss = { manual = false }) { manual = false; pickManually() }
    val folder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        show("Looking for your backup…")
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val found = AutoSave.findInTree(context, tree)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (found.isEmpty()) { show("No Loadout backup in that folder."); manual = true }
                else restore(found)
            }
        }
    }
    val onClick = {
        val own = AutoSave.ownFiles()
        if (own.isNotEmpty()) confirm = own else folder.launch(AutoSave.DOCUMENTS)
    }
    if (big) BigButton(label, modifier = modifier) { onClick() } else GreyButton(label, modifier) { onClick() }
    confirm?.let { files ->
        val last = AutoSave.states[AutoSave.Slot.PHONE]?.last
        val fmt = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text("Restore from this phone's auto-save" + (last?.let { " (saved ${fmt.format(java.util.Date(it))})" } ?: "") +
                "? Everything in Loadout is replaced with that copy.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirm = null; restore(files) }) { Text("Restore", color = Blue, fontWeight = FontWeight.Bold) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = null; manual = true }) { Text("Pick another file", color = Sub) } },
        )
    }
}

/** The A/B explanation, shown before a picker opens; [ok] opens it. [extra] adds a line for that picker. */
@androidx.compose.runtime.Composable
fun TwoFilesDialog(extra: String? = null, onDismiss: () -> Unit, ok: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Two backup files") },
        text = { Text(AutoSave.TWO_FILES + (extra?.let { "\n\n$it" } ?: "")) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = ok) { Text("OK") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pops up as soon as the app is open if an auto-save place lost access: what happened, and its setup to fix it. */
@androidx.compose.runtime.Composable
fun AutoSaveProblem() {
    val (s, why) = AutoSave.problem.value ?: return
    // Not over the lock screen or first setup: it shows once you're in.
    if ((Store.profile.value.appLock && AppLock.locked) || !Store.profile.value.onboarded) return
    var msg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Set up again: done.
    if (AutoSave.states[s]?.let { it.ready && it.on } == true && msg?.contains("✓") == true) {
        androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(1200); AutoSave.problem.value = null }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text("Auto-save stopped") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Text("$why Your data is safe in Loadout, but it isn't being backed up there. Switch it back on to set it up again.")
                AutoSaveSettings(only = s) { msg = it }
                msg?.let { Text(it, color = Blue, fontSize = 13.sp) }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { AutoSave.problem.value = null }) { Text(if (msg?.contains("✓") == true) "Done" else "Later") } },
    )
}
