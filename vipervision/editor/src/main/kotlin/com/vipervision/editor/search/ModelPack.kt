package com.vipervision.editor.search

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.graphics.Bitmap
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.t8rin.imagetoolbox.core.domain.remote.DownloadManager
import com.t8rin.imagetoolbox.feature.ai_tools.domain.AiToolsRepository
import com.t8rin.imagetoolbox.feature.ai_tools.domain.model.NeuralModel
import com.t8rin.neural_tools.bgremover.BgRemover
import com.t8rin.neural_tools.bgremover.Sam2
import com.t8rin.neural_tools.depth.DepthAnything
import com.t8rin.neural_tools.inpaint.LaMaProcessor
import com.t8rin.neural_tools.ocr.PaddleOCR
import com.vipervision.editor.AiTool
import com.vipervision.editor.ViperLog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Every AI model Viper Vision uses, fetched once (about 2.1 GB) and kept through updates: the
 * editor's AI bubbles (Auto background remover's BiRefNet also does Manual background remover's
 * Refine edges), Blemish remover, Smart select and the AI search. Each comes from the app's own permanent copy first (the
 * "vipervision-ai-models" release; see AndroidDownloadManager.mirrorUrl), else its original site.
 * Each is fetched through its own tool, so it lands exactly where that tool looks for it.
 */
object ModelPack {

    class Item(val name: String, val size: Long, val isHere: (Context) -> Boolean, val fetch: suspend (Context, Deps, suspend (Float) -> Unit) -> Unit)

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun downloadManager(): DownloadManager
        fun aiTools(): AiToolsRepository<Bitmap>
    }

    private fun deps(context: Context) = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)

    private suspend fun Flow<com.t8rin.imagetoolbox.core.domain.remote.DownloadProgress>.follow(onProgress: suspend (Float) -> Unit) =
        collect { onProgress(it.currentPercent) }

    private suspend fun Flow<com.t8rin.neural_tools.DownloadProgress>.followTool(onProgress: suspend (Float) -> Unit) =
        collect { onProgress(it.currentPercent) }

    private fun neural(model: NeuralModel) = Item(
        name = model.title,
        size = model.downloadSize,
        isHere = { File(File(it.filesDir, "ai_models"), model.name).let { f -> f.isFile && f.length() == model.downloadSize } },
        fetch = { _, deps, progress -> deps.aiTools().downloadModel(model).follow(progress) }
    )

    /**
     * Manual background remover's other models (its Auto button, since removed). Nothing in the
     * app uses them any more; earlier builds may have downloaded them (about 1.65 GB), so they're
     * deleted when the app starts.
     */
    private val unusedRemovers = listOf(
        BgRemover.Type.BiRefNet, BgRemover.Type.U2Net, BgRemover.Type.RMBG1_4, BgRemover.Type.InSPyReNet,
        BgRemover.Type.ISNet, BgRemover.Type.MODNet, BgRemover.Type.YOLO
    )

    private fun deleteUnused(context: Context) {
        for (type in unusedRemovers) {
            val remover = BgRemover.getRemover(type)
            if (remover.modelFile.exists()) {
                remover.close()
                ViperLog.i("MODELS", "Deleting unused ${remover.modelFile.name} (${remover.modelFile.length() / 1_000_000} MB)")
                remover.modelFile.delete()
                remover.checkModel()
            }
        }
        if (com.t8rin.neural_tools.bgremover.MobileSAM.isDownloaded()) {
            ViperLog.i("MODELS", "Deleting unused MobileSAM")
            com.t8rin.neural_tools.bgremover.MobileSAM.deleteModels()
        }
        // EdgeSAM, replaced by SAM 2.1.
        java.io.File(context.filesDir, "ai_models/edge_sam").takeIf { it.exists() }?.let {
            ViperLog.i("MODELS", "Deleting unused EdgeSAM")
            it.deleteRecursively()
        }
    }

    /** Everything, in the order it's fetched (the editor's bubbles first, search last). */
    val items: List<Item> by lazy {
        val editorModels = AiTool.entries.flatMap { it.choices }
            .filterNot { it.isBundled }
            .map { it.model }
            .distinctBy { it.name }
        editorModels.map(::neural) + listOf(
            Item("LaMa (Blemish remover)", 205_294_245, { LaMaProcessor.isDownloaded.value }) { _, _, progress ->
                LaMaProcessor.startDownload().followTool(progress)
            },
            Item("Depth Anything V2 (Portrait blur)", DepthAnything.SIZE, { DepthAnything.isDownloaded() }) { _, _, progress ->
                DepthAnything.download(progress)
            },
            Item("SAM 2.1 (Smart select)", Sam2.SIZE, { Sam2.isDownloaded() }) { _, _, progress ->
                Sam2.download(progress)
            },
            Item("PaddleOCR (reads text, AI search)", 12_265_739, { PaddleOCR.isModelDownloaded(PaddleOCR.Model.English) }) { _, _, progress ->
                PaddleOCR.startDownload(PaddleOCR.Model.English).followTool(progress)
            },
            Item("SigLIP 2 (AI search)", Siglip.files.sumOf { it.size }, { Siglip.isDownloaded(it) }) { context, deps, progress ->
                Siglip.download(context, deps.downloadManager(), progress)
            }
        )
    }

    val totalSize: Long get() = items.sumOf { it.size }

    /** Bytes already here, and whether everything is. */
    fun state(context: Context): Pair<Long, Boolean> {
        val here = items.filter { runCatching { it.isHere(context) }.getOrDefault(false) }
        return here.sumOf { it.size } to (here.size == items.size)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("Prefs", Context.MODE_PRIVATE)

    /**
     * The all-in-one APK carries every model (assets/vv_models, see AndroidDownloadManager's
     * copyBundled) plus the normal APK (assets/vv_slim.apk): the models are unpacked from it with
     * no internet, then the app replaces itself with the normal one (same app and signature, so
     * nothing is lost) and Android deletes the big one.
     */
    fun isAllInOne(context: Context): Boolean =
        runCatching { context.assets.open("vv_models/index.txt").close(); true }.getOrDefault(false)

    /**
     * Installs the normal APK over this all-in-one one. Android shows its usual "Update" prompt
     * (sent back to [AiModelsActivity] as [ACTION_SLIM_INSTALLED] when it needs the user's OK).
     */
    fun switchToSlim(activity: Activity) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(activity.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            activity.assets.open(SLIM_APK).use { input ->
                session.openWrite("base.apk", 0, -1).use { output ->
                    input.copyTo(output, 1 shl 20)
                    session.fsync(output)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getActivity(
                activity, id,
                Intent(activity, AiModelsActivity::class.java).setAction(ACTION_SLIM_INSTALLED),
                flags
            )
            session.commit(pending.intentSender)
        }
        ViperLog.i("MODELS", "Switching to the normal APK")
    }

    /**
     * Checks which models are already on the phone (e.g. downloaded by an earlier build, one tool
     * at a time) and notes it for the gallery: whether to offer the download at all, and how much
     * is left. Run when the app starts, off the main thread.
     */
    fun refreshState(context: Context) {
        runCatching { deleteUnused(context) }.onFailure { ViperLog.error("MODELS", "couldn't delete unused models", it) }
        runCatching { Siglip.replaceTokenizer(context) }.onFailure { ViperLog.error("MODELS", "couldn't shrink the search vocabulary", it) }
        val (here, complete) = state(context)
        prefs(context).edit()
            .putBoolean(KEY_COMPLETE, complete)
            .putLong(KEY_HERE_BYTES, here)
            .putLong(KEY_TOTAL_BYTES, totalSize)
            .putBoolean(KEY_CHECKED, true)
            .apply()
        // The all-in-one APK unpacks its models straight away (no internet needed).
        if (!complete && isAllInOne(context) && !isRunning(context)) start(context, allowMobileData = true)
        ViperLog.i("MODELS", "AI models on the phone: ${here / 1_000_000} of ${totalSize / 1_000_000} MB" + if (complete) " (all)" else "")
    }

    fun isRunning(context: Context) = prefs(context).getBoolean(KEY_RUNNING, false) && workRunning(context)

    /**
     * What WorkManager itself says (the saved flag stays set if the app was killed mid-download,
     * which used to keep an old "wait for Wi-Fi" download in place of a new "use mobile data").
     * Blocks briefly.
     */
    private fun workRunning(context: Context): Boolean = runCatching {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK).get()
            .any { it.state == androidx.work.WorkInfo.State.RUNNING }
    }.getOrDefault(false)

    /** Android's Data Saver is on and Viper Vision isn't allowed mobile data in the background. */
    fun dataSaverBlocks(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.isActiveNetworkMetered && cm.restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }.getOrDefault(false)

    /** A download has been asked for (it may be running, or waiting for Wi-Fi). */
    fun isRequested(context: Context) = prefs(context).getBoolean(KEY_REQUESTED, false)

    /** Queued but not started (waiting for Wi-Fi). Blocks briefly; call off the main thread. */
    fun isWaiting(context: Context): Boolean = !isRunning(context) && runCatching {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK).get()
            .any { it.state == androidx.work.WorkInfo.State.ENQUEUED }
    }.getOrDefault(false)
    fun progress(context: Context) = prefs(context).getFloat(KEY_PROGRESS, 0f)
    fun current(context: Context) = prefs(context).getString(KEY_CURRENT, null)
    fun lastError(context: Context) = prefs(context).getString(KEY_ERROR, null)

    /** Starts (or continues) fetching everything missing, in the background. */
    fun start(context: Context, allowMobileData: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROMPTED, true).putBoolean(KEY_REQUESTED, true).remove(KEY_ERROR).apply()
        val running = workRunning(context)
        if (!running) prefs(context).edit().putBoolean(KEY_RUNNING, false).apply()
        ViperLog.i("MODELS", "Download asked for: ${if (isAllInOne(context)) "unpack" else if (allowMobileData) "any connection" else "Wi-Fi only"}" +
                (if (running) " (one is already running)" else "") + (if (dataSaverBlocks(context)) "; Data Saver is restricting background data" else ""))
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK,
            // The newest choice wins over a download still waiting (e.g. for Wi-Fi); one already
            // running is left alone.
            if (isAllInOne(context) || !running) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<Worker>()
                // Starts straight away, not when Android next gets round to background work.
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            when {
                                isAllInOne(context) -> NetworkType.NOT_REQUIRED
                                allowMobileData -> NetworkType.CONNECTED
                                else -> NetworkType.UNMETERED
                            }
                        )
                        .build()
                )
                .build()
        )
    }

    class Worker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
        // Needed for a download that starts straight away (expedited) on Android 11 and older.
        override suspend fun getForegroundInfo() = AiSearch.foreground(applicationContext, "Downloading AI models", 0)

        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val context = applicationContext
            val deps = deps(context)
            val prefs = prefs(context)
            prefs.edit().putBoolean(KEY_RUNNING, true).apply()
            val total = totalSize.toFloat()
            val verb = if (isAllInOne(context)) "Unpacking" else "Downloading"
            var done = 0L
            try {
                for (item in items) {
                    if (isStopped) return@withContext Result.retry()
                    if (runCatching { item.isHere(context) }.getOrDefault(false)) {
                        done += item.size
                        continue
                    }
                    ViperLog.i("MODELS", "$verb ${item.name}")
                    prefs.edit().putString(KEY_CURRENT, item.name).apply()
                    var lastShown = -1
                    item.fetch(context, deps) { fraction ->
                        val overall = (done + fraction.coerceIn(0f, 1f) * item.size) / total
                        val percent = (overall * 100).toInt()
                        if (percent != lastShown) {
                            lastShown = percent
                            prefs.edit().putFloat(KEY_PROGRESS, overall).apply()
                            setForegroundAsync(AiSearch.foreground(context, "$verb AI models: $percent% (${item.name})", percent))
                        }
                    }
                    done += item.size
                }
                prefs.edit().putFloat(KEY_PROGRESS, 1f).putBoolean(KEY_COMPLETE, true).putLong(KEY_HERE_BYTES, totalSize)
                    .remove(KEY_CURRENT).apply()
                ViperLog.i("MODELS", "All AI models are here")
                // The search can start indexing now.
                AiSearch.onAppStart(context)
                Result.success()
            } catch (t: Throwable) {
                ViperLog.error("MODELS", "model download stopped", t)
                prefs.edit().putString(KEY_ERROR, t.message ?: t.javaClass.simpleName).apply()
                Result.retry()
            } finally {
                prefs.edit().putBoolean(KEY_RUNNING, false).apply()
            }
        }
    }

    private const val WORK = "vv_model_pack"
    private const val SLIM_APK = "vv_slim.apk"
    const val ACTION_SLIM_INSTALLED = "com.vipervision.SLIM_INSTALLED"
    // Shared with the gallery (it offers the download once and shows the status in Settings).
    const val KEY_PROMPTED = "vv_models_prompted"
    const val KEY_COMPLETE = "vv_models_complete"
    // Read by the gallery: once asked for, the AI models screen isn't forced open any more.
    const val KEY_REQUESTED = "vv_models_requested"
    const val KEY_CHECKED = "vv_models_checked"
    const val KEY_HERE_BYTES = "vv_models_here_bytes"
    const val KEY_TOTAL_BYTES = "vv_models_total_bytes"
    private const val KEY_RUNNING = "vv_models_running"
    private const val KEY_PROGRESS = "vv_models_progress"
    private const val KEY_CURRENT = "vv_models_current"
    private const val KEY_ERROR = "vv_models_error"
}
