package com.ironlog.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import com.ironlog.app.Json.objs

/** Everything the app remembers, saved as small JSON files. */
object Store {
    private lateinit var dir: File
    private lateinit var safeDir: File
    /** Files Android never backs up (API key, backup status). */
    fun noBackupDir(): File = safeDir
    var loaded = false
        private set
    private var assets: android.content.res.AssetManager? = null
    /** Pictures built into the app (assets/pictures/<name>.jpg). */
    private var bundled: Set<String> = emptySet()

    private val _exercises = MutableStateFlow<Map<String, Exercise>>(emptyMap())
    private val _routines = MutableStateFlow<List<Routine>>(emptyList())
    private val _workouts = MutableStateFlow<List<Workout>>(emptyList())
    private val _active = MutableStateFlow<Workout?>(null)
    private val _profile = MutableStateFlow(Profile())
    private val _images = MutableStateFlow(0)

    val exercises: StateFlow<Map<String, Exercise>> get() = _exercises
    val routines: StateFlow<List<Routine>> get() = _routines
    /** Finished workouts, newest first. */
    val workouts: StateFlow<List<Workout>> get() = _workouts
    val active: StateFlow<Workout?> get() = _active
    val profile: StateFlow<Profile> get() = _profile
    /** Changes whenever an exercise picture is added or removed. */
    val imageVersion: StateFlow<Int> get() = _images

    fun newId(): String = UUID.randomUUID().toString().take(12)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        dir = c.filesDir
        safeDir = c.noBackupFilesDir
        assets = c.assets
        bundled = runCatching { c.assets.list("pictures")?.toSet() }.getOrNull() ?: emptySet()
        val builtIn = runCatching {
            JSONArray(c.assets.open("exercises.json").bufferedReader().readText()).objs().map { Exercise.fromJson(it, false) }
        }.getOrDefault(emptyList())
        val custom = read("custom.json")?.let { JSONArray(it).objs().map { o -> Exercise.fromJson(o, true) } } ?: emptyList()
        original = builtIn.associateBy { it.id }
        overrides = read("overrides.json")?.let { JSONArray(it).objs().map { o -> Exercise.fromJson(o, false).copy(edited = true) } }
            ?.associateBy { it.id } ?: emptyMap()
        _exercises.value = (builtIn.map { overrides[it.id] ?: it } + custom).associateBy { it.id }
        _routines.value = read("routines.json")?.let { JSONArray(it).objs().map(Json::routine) } ?: emptyList()
        _workouts.value = read("workouts.json")?.let { JSONArray(it).objs().map(Json::workout) }?.sortedByDescending { it.start } ?: emptyList()
        _active.value = read("active.json")?.let { runCatching { Json.workout(JSONObject(it)) }.getOrNull() }
        val p = read("profile.json")?.let { runCatching { Json.profile(JSONObject(it)) }.getOrNull() } ?: Profile()
        val keyFile = File(safeDir, "api_key.txt")
        val savedKey = runCatching { keyFile.takeIf { it.exists() }?.readText() }.getOrNull()
        // Google backup was replaced by auto-save (Settings → Backup): nothing goes to Google any more.
        _profile.value = p.copy(apiKey = savedKey ?: p.apiKey, cloud = if (p.cloud == "google") "local" else p.cloud)
        Accent.color = androidx.compose.ui.graphics.Color(p.accent)
        // Older versions kept the key inside profile.json; move it to the never-backed-up folder.
        if (savedKey == null && p.apiKey.isNotEmpty()) setProfile(_profile.value)
        RecycleBin.init()
        AppText.load()
        OneRmFx.load()
        ChartRange.load()
    }

    /** The app's data folder. */
    fun dataDir(): File = dir

    /** Small app files (e.g. remembered import choices). */
    fun readFile(name: String) = read(name)
    fun writeFile(name: String, text: String) = write(name, text)

    private fun read(name: String): String? = runCatching { File(dir, name).takeIf { it.exists() }?.readText() }.getOrNull()
    /** Set while a full restore swaps files in: nothing still in memory may write over the restored copy. */
    @Volatile private var frozen = false

    private fun write(name: String, text: String) {
        if (frozen) return
        runCatching {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, name))
        }
        // Auto-save picks up changes a couple of minutes later (the workout in progress is saved when it finishes).
        if (name != "active.json") AutoSave.changed()
    }

    fun exercise(id: String): Exercise? = _exercises.value[id]

    /** Built-in exercises as they came, and your changes to them. */
    private var original: Map<String, Exercise> = emptyMap()
    private var overrides: Map<String, Exercise> = emptyMap()

    /** Saves a changed exercise (your own, or your version of a built-in one). */
    fun saveExercise(e: Exercise) {
        if (e.custom) return saveCustomExercise(e)
        val changed = e.copy(edited = true)
        overrides = overrides + (e.id to changed)
        write("overrides.json", JSONArray(overrides.values.map { it.toJson() }).toString())
        _exercises.value = _exercises.value + (e.id to changed)
    }

    /** Puts a built-in exercise back the way it came. */
    fun resetExercise(id: String) {
        val o = original[id] ?: return
        overrides[id]?.let { RecycleBin.edits(it) }
        overrides = overrides - id
        write("overrides.json", JSONArray(overrides.values.map { it.toJson() }).toString())
        _exercises.value = _exercises.value + (id to o)
    }

    // ----- exercises -----
    fun saveCustomExercise(e: Exercise) {
        _exercises.value = _exercises.value + (e.id to e.copy(custom = true))
        write("custom.json", JSONArray(_exercises.value.values.filter { it.custom }.map { it.toJson() }).toString())
    }

    /** Deletes your own exercise; it (and its picture) goes to the Recycle Bin unless [toBin] is false. */
    fun deleteCustomExercise(id: String, toBin: Boolean = true) {
        if (toBin) _exercises.value[id]?.let { RecycleBin.exercise(it) }
        _exercises.value = _exercises.value - id
        write("custom.json", JSONArray(_exercises.value.values.filter { it.custom }.map { it.toJson() }).toString())
        imageFile(id).delete()
        _images.value++
    }

    /** Moves every logged set of exercise [from] in your finished workouts onto [to]; [from] itself stays. Returns sets moved. */
    fun moveHistory(from: String, to: String): Int {
        if (from == to) return 0
        var sets = 0
        setWorkouts(_workouts.value.map { w ->
            if (w.items.none { it.exerciseId == from }) w
            else w.copy(items = w.items.map { if (it.exerciseId == from) { sets += it.sets.count { s -> s.done }; it.copy(exerciseId = to) } else it })
        })
        return sets
    }

    /**
     * Moves everything logged or planned with custom exercise [from] onto exercise [to] (workouts, the workout in
     * progress, routines, Hevy matches, 1 Rep Max list), then deletes [from]. Returns how many sets moved.
     */
    fun mergeExercise(from: String, to: String): Int {
        if (from == to) return 0
        val sets = moveHistory(from, to)
        _active.value?.let { a -> if (a.items.any { it.exerciseId == from }) setActive(a.copy(items = a.items.map { if (it.exerciseId == from) it.copy(exerciseId = to) else it })) }
        setRoutines(_routines.value.map { r -> r.copy(items = r.items.map { if (it.exerciseId == from) it.copy(exerciseId = to) else it }) })
        runCatching {
            val o = JSONObject(read("hevy_map.json") ?: "{}")
            o.keys().asSequence().toList().forEach { k -> if (o.optString(k) == from) o.put(k, to) }
            write("hevy_map.json", o.toString())
        }
        runCatching {
            val a = org.json.JSONArray(read("onerm_list.json") ?: "[]")
            val ids = List(a.length()) { a.getString(it) }.map { if (it == from) to else it }.distinct()
            write("onerm_list.json", org.json.JSONArray(ids).toString())
        }
        deleteCustomExercise(from)
        return sets
    }

    // ----- pictures -----
    fun imageName(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg"
    fun imagesDir() = File(dir, "images").apply { mkdirs() }
    fun imageFile(id: String) = File(imagesDir(), imageName(id))
    /** A picture built into the app for this exercise, if any. */
    fun bundledImage(id: String): java.io.InputStream? =
        if (imageName(id) in bundled) runCatching { assets?.open("pictures/" + imageName(id)) }.getOrNull() else null
    fun hasImage(id: String) = imageFile(id).exists() || imageName(id) in bundled
    fun imageChanged() { _images.value++ }

    // ----- routines -----
    fun saveRoutine(r: Routine) {
        val list = _routines.value.toMutableList()
        val i = list.indexOfFirst { it.id == r.id }
        if (i >= 0) list[i] = r else list += r
        setRoutines(list)
    }

    fun deleteRoutine(id: String) {
        _routines.value.firstOrNull { it.id == id }?.let { RecycleBin.routine(it) }
        setRoutines(_routines.value.filter { it.id != id })
    }

    fun setRoutines(list: List<Routine>) {
        _routines.value = list
        write("routines.json", JSONArray(list.map(Json::routine)).toString())
    }

    // ----- workouts -----
    fun setActive(w: Workout?) {
        _active.value = w
        if (frozen) return
        if (w == null) File(dir, "active.json").delete() else write("active.json", Json.workout(w).toString())
    }

    fun saveWorkout(w: Workout) {
        val list = (_workouts.value.filter { it.id != w.id } + w).sortedByDescending { it.start }
        setWorkouts(list)
    }

    fun deleteWorkout(id: String) {
        workout(id)?.let { RecycleBin.workout(it) }
        setWorkouts(_workouts.value.filter { it.id != id })
    }

    private fun setWorkouts(list: List<Workout>) {
        _workouts.value = list
        write("workouts.json", JSONArray(list.map(Json::workout)).toString())
    }

    fun workout(id: String): Workout? = _workouts.value.firstOrNull { it.id == id }

    // ----- profile -----
    fun setProfile(p: Profile) {
        val old = _profile.value
        _profile.value = p
        Accent.color = androidx.compose.ui.graphics.Color(p.accent)
        write("profile.json", Json.profile(p).toString())
        // The OpenAI key lives in a folder Android never backs up or transfers, so it stays on this phone.
        val keyFile = File(safeDir, "api_key.txt")
        if (p.apiKey != old.apiKey || !keyFile.exists()) runCatching { if (p.apiKey.isEmpty()) keyFile.delete() else keyFile.writeText(p.apiKey) }
    }

    /** Exercises used most recently first. */
    fun recentExerciseIds(limit: Int = 12): List<String> =
        _workouts.value.flatMap { w -> w.items.map { it.exerciseId } }.distinct().take(limit)

    // ----- backup -----
    fun backup(): String = JSONObject()
        .put("app", "Loadout").put("version", 1)
        .put("routines", JSONArray(_routines.value.map(Json::routine)))
        .put("workouts", JSONArray(_workouts.value.map(Json::workout)))
        .put("custom", JSONArray(_exercises.value.values.filter { it.custom }.map { it.toJson() }))
        .put("profile", Json.profile(_profile.value.copy(apiKey = "")))
        // Your versions of built-in exercises, and small settings kept in their own files.
        .put("edited", JSONArray(overrides.values.map { it.toJson() }))
        .put("files", JSONObject().apply { BACKUP_FILES.forEach { n -> read(n)?.let { put(n, it) } } })
        .toString()

    /** Small settings files included in backups: Hevy match choices, the 1 Rep Max list. */
    private val BACKUP_FILES = listOf("hevy_map.json", "onerm_list.json", "own_photos.json")

    /** App settings kept by Android (the heading history etc.); the picture-making queue is left out on purpose. */
    private fun prefsDir() = File(dir.parentFile, "shared_prefs")
    private val SKIP_PREFS = setOf("gen_queue.xml")

    /**
     * Full backup as a zip, an exact copy of everything the app keeps: data/ holds every file in the app's data
     * folder (workouts, the workout in progress, routines, profile and every setting, your exercise changes, text
     * edits, reference weights and photos, the 1 Rep Max list, chart and animation choices, the Recycle Bin, pictures)
     * and prefs/ Android's small settings files. Only the OpenAI key (kept apart on this phone) and this phone's
     * backup status are left out. backup.json and pictures/ are kept too, so older versions can still read it.
     * Returns how many pictures went in.
     */
    fun writeBackup(out: java.io.OutputStream): Int {
        // Skip copies of built-in pictures (e.g. from an imported picture pack): the app already has those.
        fun sameAsBuiltIn(f: File) = f.name in bundled && runCatching {
            assets?.open("pictures/" + f.name)?.use { it.readBytes() }?.contentEquals(f.readBytes()) == true
        }.getOrDefault(false)
        val pics = imagesDir().listFiles { f -> f.name.endsWith(".jpg") }?.filterNot(::sameAsBuiltIn)?.sortedBy { it.name } ?: emptyList()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("backup.json"))
            zip.write(backup().toByteArray())
            zip.closeEntry()
            for (f in pics) {
                zip.putNextEntry(java.util.zip.ZipEntry("pictures/" + f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            // The exact copy. Pictures already went in above, so images/ isn't stored twice.
            val root = dir.canonicalFile
            dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                val rel = f.canonicalFile.relativeTo(root).invariantSeparatorsPath
                if (rel.startsWith("images/")) return@forEach
                zip.putNextEntry(java.util.zip.ZipEntry("data/$rel"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            prefsDir().listFiles { f -> f.isFile && f.name.endsWith(".xml") && f.name !in SKIP_PREFS }?.forEach { f ->
                zip.putNextEntry(java.util.zip.ZipEntry("prefs/" + f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(java.util.zip.ZipEntry("full.txt"))
            zip.write("Loadout full backup 2\nsaved=${System.currentTimeMillis()}".toByteArray())
            zip.closeEntry()
        }
        return pics.size
    }

    /** Restores from one or more picked files (e.g. auto-save A and B): the newest one is used. Same result as [readBackup]. */
    fun restoreFiles(c: Context, uris: List<android.net.Uri>): Int {
        val files = uris.map { u -> c.contentResolver.openInputStream(u)!!.use { it.readBytes() } }
        return readBackup(files.maxByOrNull { savedAt(it) ?: 0L }!!.inputStream())
    }

    /** When a backup zip was saved (from its full.txt), or null for older backups. */
    fun savedAt(bytes: ByteArray): Long? = runCatching {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == "full.txt" }?.let {
                zip.readBytes().toString(Charsets.UTF_8).lines().firstOrNull { l -> l.startsWith("saved=") }?.removePrefix("saved=")?.trim()?.toLong()
            }
        }
    }.getOrNull()

    /** The last restore was a full (exact copy) backup: the app restarts to load it. */
    var lastFull = false

    /** Restores a backup zip (or an older .json backup). Returns how many pictures were restored. */
    fun readBackup(input: java.io.InputStream): Int {
        lastHevy = false
        val bytes = input.readBytes()
        if (bytes.size < 2 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            val text = String(bytes)
            if (HevyImport.looksLikeHevy(text)) {
                lastHevy = true
                val plan = HevyImport.prepare(text)
                // The Hevy import screen asks about unmatched exercises and which routines to make.
                HevyImport.pending = plan; lastImported = null; return -1
            }
            restore(text); return 0
        }
        lastFull = false
        var json: String? = null
        var pics = 0
        // A full backup replaces everything with the exact copy it holds.
        val names = java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
        if ("full.txt" in names) return restoreFull(bytes)
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = File(e.name).name
                when {
                    e.isDirectory -> {}
                    e.name == "backup.json" -> json = zip.readBytes().toString(Charsets.UTF_8)
                    // Only plain picture names: never writes outside the pictures folder.
                    e.name.startsWith("pictures/") && name.matches(Regex("[A-Za-z0-9_-]+\\.jpg")) -> {
                        File(imagesDir(), name).outputStream().use { zip.copyTo(it) }
                        pics++
                    }
                }
                zip.closeEntry()
            }
        }
        restore(json ?: error("No backup.json in the zip"))
        imageChanged()
        return pics
    }

    /**
     * Restores a full backup: clears the app's data folder and settings, writes back every file exactly as it was, and
     * keeps the OpenAI key that's on this phone. The app then restarts so every part of it loads the restored copy.
     */
    private fun restoreFull(bytes: ByteArray): Int {
        val root = dir.canonicalFile
        val staging = File(dir.parentFile, "restore_tmp").apply { deleteRecursively(); mkdirs() }
        val stagingPrefs = File(staging, "__prefs").apply { mkdirs() }
        var pics = 0
        // Unpack into a side folder first, so a broken zip changes nothing.
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) {
                    val target = when {
                        e.name.startsWith("data/") -> File(staging, e.name.removePrefix("data/"))
                        e.name.startsWith("pictures/") -> File(staging, "images/" + File(e.name).name).also { pics++ }
                        e.name.startsWith("prefs/") -> File(stagingPrefs, File(e.name).name)
                        else -> null
                    }
                    // Only inside the side folder: a name with ../ can't write anywhere else.
                    if (target != null && target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
            }
        }
        if (!File(staging, "profile.json").exists() && !File(staging, "workouts.json").exists()) {
            staging.deleteRecursively(); error("Not a Loadout backup")
        }
        // Swap it in: everything old goes, the copy takes its place. Until the restart nothing else is saved.
        frozen = true
        root.listFiles()?.forEach { it.deleteRecursively() }
        staging.listFiles()?.filter { it.name != "__prefs" }?.forEach { f -> f.copyRecursively(File(root, f.name), overwrite = true) }
        prefsDir().apply { mkdirs() }.listFiles { f -> f.name !in SKIP_PREFS }?.forEach { it.delete() }
        stagingPrefs.listFiles()?.forEach { it.copyTo(File(prefsDir(), it.name), overwrite = true) }
        staging.deleteRecursively()
        lastFull = true
        return pics
    }

    /** Starts the app again from scratch (after a full restore), so nothing old stays in memory. */
    fun restartApp(c: Context) {
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return
        c.startActivity(android.content.Intent.makeRestartActivityTask(i.component))
        Runtime.getRuntime().exit(0)
    }

    /** The last restore was a Hevy CSV. */
    var lastHevy = false

    /** Workouts added by the last import (null after a normal restore). */
    var lastImported: Int? = null
        private set

    /** Routines added by the last import. */
    var lastRoutines = 0

    fun restore(text: String) {
        val o = JSONObject(text)
        lastImported = null; lastRoutines = 0
        // An import (e.g. history from another app) adds workouts and exercises without replacing anything.
        if (o.optBoolean("merge")) {
            o.optJSONArray("custom")?.objs()?.forEach { c ->
                if (_exercises.value[c.getString("i")] == null) saveCustomExercise(Exercise.fromJson(c, true))
            }
            val have = _workouts.value.map { it.id }.toSet()
            val add = o.optJSONArray("workouts")?.objs()?.map(Json::workout)?.filter { it.id !in have } ?: emptyList()
            setWorkouts((_workouts.value + add).sortedByDescending { w -> w.start })
            lastImported = add.size
            val haveR = _routines.value.map { it.id }.toSet()
            val addR = o.optJSONArray("routines")?.objs()?.map(Json::routine)?.filter { it.id !in haveR } ?: emptyList()
            if (addR.isNotEmpty()) setRoutines(_routines.value + addR)
            lastRoutines = addR.size
            return
        }
        val key = _profile.value.apiKey
        o.optJSONArray("custom")?.objs()?.forEach { saveCustomExercise(Exercise.fromJson(it, true)) }
        o.optJSONArray("routines")?.let { setRoutines(it.objs().map(Json::routine)) }
        o.optJSONArray("workouts")?.let { setWorkouts(it.objs().map(Json::workout).sortedByDescending { w -> w.start }) }
        o.optJSONObject("profile")?.let { setProfile(Json.profile(it).copy(apiKey = key, onboarded = true, cloud = _profile.value.cloud)) }
        o.optJSONArray("edited")?.objs()?.forEach { saveExercise(Exercise.fromJson(it, false)) }
        o.optJSONObject("files")?.let { f -> BACKUP_FILES.forEach { n -> if (f.has(n)) write(n, f.getString(n)) } }
    }
}
