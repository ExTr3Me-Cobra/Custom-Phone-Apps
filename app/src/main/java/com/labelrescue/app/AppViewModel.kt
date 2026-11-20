package com.labelrescue.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.labelrescue.app.pipeline.Images
import com.labelrescue.app.pipeline.MlKitText
import com.labelrescue.core.candidates.CompositePrior
import com.labelrescue.core.candidates.HistoryPrior
import com.labelrescue.core.candidates.HistoryStats
import com.labelrescue.core.candidates.ManualEntry
import com.labelrescue.core.candidates.RankingPrior
import com.labelrescue.core.candidates.StructurePrior
import com.labelrescue.core.fusion.NormRect
import com.labelrescue.core.image.GrayImage
import com.labelrescue.app.pipeline.MlKitBarcode
import com.labelrescue.app.pipeline.OnDeviceModels
import com.labelrescue.core.barcode.ZxingDecoder
import com.labelrescue.core.fusion.CancelledException
import com.labelrescue.core.fusion.DiagnosticLog
import com.labelrescue.app.pipeline.ZxingCppDecoder
import com.labelrescue.core.fusion.Progress
import com.labelrescue.core.fusion.RecoveryOutput
import com.labelrescue.core.fusion.RecoveryPipeline
import com.labelrescue.app.data.ConfirmedNumber
import com.labelrescue.app.data.HistoryDb
import com.labelrescue.app.data.HistoryFile
import com.labelrescue.app.data.Settings
import com.labelrescue.app.data.TrainingCrops
import com.labelrescue.app.data.TrainingReportStore
import com.labelrescue.app.verify.VerificationController
import com.labelrescue.core.verify.LabelContext
import com.labelrescue.core.verify.VerificationPrior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {

    // ---- Learned history ----
    private val db = HistoryDb.get(app)
    val history: StateFlow<List<ConfirmedNumber>> =
        db.confirmed().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val historyStats = MutableStateFlow(HistoryStats.EMPTY)
    val learned: StateFlow<HistoryStats> = historyStats
    private val _message = MutableStateFlow<String?>(null)
    /** One-off messages (toasts). */
    val message: StateFlow<String?> = _message
    fun messageShown() { _message.value = null }

    private val crops by lazy { TrainingCrops(app) }

    // ---- Training report (one compact entry per scan, cleared after export) ----
    private val report by lazy { TrainingReportStore(app) }
    private val _reportCount = MutableStateFlow(0)
    val reportCount: StateFlow<Int> = _reportCount
    private val _reportPhoto = MutableStateFlow<android.graphics.Bitmap?>(null)
    /** The latest report photo (names and addresses whited out), shown before export. */
    val reportPhoto: StateFlow<android.graphics.Bitmap?> = _reportPhoto
    private val _reportPhotos = MutableStateFlow(0)
    val reportPhotos: StateFlow<Int> = _reportPhotos
    @Volatile private var scanId: String? = null
    @Volatile private var scanTime: String = ""
    @Volatile private var scanConfirmed: String? = null
    @Volatile private var lastRanked: com.labelrescue.core.candidates.RankResult? = null

    private fun writeReport() {
        val id = scanId ?: return
        val out = photoOutput ?: return
        val header = "app ${com.labelrescue.app.BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · models: ${OnDeviceModels.status}"
        val verification = photoVerify.state.value.results.mapValues { (_, v) -> v.verdict.status.label + (v.verdict.summary.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") }
        val entry = com.labelrescue.core.fusion.TrainingReport.entry(id, scanTime, header, out, lastRanked, scanConfirmed, verification)
        val frame = _frames.value.getOrNull(out.privacyFrame)
        viewModelScope.launch(Dispatchers.IO) {
            report.put(id, entry)
            // One photo per scan, with every text line that isn't label boilerplate whited out.
            val blank = out.privacyBlank
            if (frame != null && blank != null && !report.hasPhoto(id)) {
                runCatching {
                    val bmp = com.labelrescue.app.pipeline.Images.toBitmap(com.labelrescue.core.privacy.Redactor.apply(frame, blank))
                    val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
                    report.putPhoto(id, bytes)
                    _reportPhoto.value = android.graphics.Bitmap.createScaledBitmap(bmp, 480, 480 * bmp.height / bmp.width, true)
                }
            }
            _reportCount.value = report.count(); _reportPhotos.value = report.photos().size
        }
    }

    fun refreshReportCount() { viewModelScope.launch(Dispatchers.IO) { _reportCount.value = report.count(); _reportPhotos.value = report.photos().size } }

    /** The whole report; clears it (call after handing it to the clipboard or share sheet). */
    fun takeTrainingReport(): TrainingReportStore.Export? {
        val export = report.take() ?: return null
        _reportCount.value = 0; _reportPhotos.value = 0; _reportPhoto.value = null
        return export
    }

    fun confirm(tracking: String, source: String) {
        viewModelScope.launch {
            db.confirmed().insert(ConfirmedNumber(tracking = tracking, confirmedAt = System.currentTimeMillis(), source = source))
            var res: TrainingCrops.SaveResult? = null
            val out = photoOutput
            if (source == "photo" && out != null && settings().saveTrainingCrops) {
                val trusted = photoVerify.statuses()[tracking] == com.labelrescue.core.verify.VerifyStatus.CONFIRMED
                res = withContext(Dispatchers.IO) { runCatching { crops.save(tracking, out, _frames.value, trusted) }.getOrNull() }
            }
            if (source == "photo") { scanConfirmed = tracking; writeReport() }
            _message.value = "Saved $tracking. Its Mailer ID and service code now help rank future labels." + (res?.let { r ->
                " ${r.saved} digit crops kept for retraining" +
                    (if (r.skipped > 0) ", ${r.skipped} skipped (no reader agreed with the digit)" else "") + "."
            } ?: "")
        }
    }

    // ---- Training crop review ----
    private val _cropItems = MutableStateFlow<List<TrainingCrops.Item>>(emptyList())
    val cropItems: StateFlow<List<TrainingCrops.Item>> = _cropItems

    fun loadCrops() { viewModelScope.launch { _cropItems.value = withContext(Dispatchers.IO) { crops.items() } } }

    fun cropThumbnail(item: TrainingCrops.Item): android.graphics.Bitmap? = crops.thumbnail(item)

    fun deleteCrop(item: TrainingCrops.Item) {
        viewModelScope.launch { withContext(Dispatchers.IO) { crops.delete(item) }; loadCrops() }
    }

    fun deleteUntrustedCrops() {
        viewModelScope.launch { withContext(Dispatchers.IO) { crops.deleteUntrusted() }; loadCrops(); _message.value = "Untrusted crops deleted." }
    }

    /** USPS confirmed a number after its crops were saved: mark them trusted. */
    private fun markCropsTrusted() {
        val confirmed = photoVerify.statuses().filterValues { it == com.labelrescue.core.verify.VerifyStatus.CONFIRMED }.keys
        if (confirmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { confirmed.forEach { runCatching { crops.markTrusted(it) } } }
    }

    fun exportTrainingCrops(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { crops.exportZip(getApplication(), uri) }.isSuccess }
            _message.value = if (ok) "Training crops exported." else "Couldn't write the file."
        }
    }

    fun clearTrainingCrops() {
        viewModelScope.launch { withContext(Dispatchers.IO) { crops.clear() }; loadSettings(); _message.value = "Training crops deleted." }
    }

    fun deleteConfirmed(id: Long) { viewModelScope.launch { db.confirmed().delete(id) } }
    fun clearHistory() { viewModelScope.launch { db.confirmed().clear() } }

    fun exportHistory(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = HistoryFile.encode(db.confirmed().allOnce())
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            _message.value = if (ok) "History exported." else "Couldn't write the file."
        }
    }

    fun importHistory(uri: Uri) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    val items = HistoryFile.decode(text)
                    db.confirmed().insertAll(items)
                    items.size
                }
            }
            _message.value = r.fold({ "Imported $it numbers." }, { "Couldn't import: ${it.message}" })
        }
    }

    // ---- Settings and optional USPS verification ----
    private var settingsObj: Settings? = null
    private suspend fun settings(): Settings = settingsObj ?: withContext(Dispatchers.IO) { Settings(getApplication()) }.also { settingsObj = it }

    data class SettingsUi(
        val verifyEnabled: Boolean = false, val clientId: String = "", val hasSecret: Boolean = false,
        val autoVerify: Boolean = true, val requestsPerHour: Int = 60, val cached: Int = 0, val loaded: Boolean = false,
        val saveCrops: Boolean = false, val cropCount: Int = 0, val models: String = "",
        val quickScan: Boolean = true,
    )
    private val _settingsUi = MutableStateFlow(SettingsUi())
    val settingsUi: StateFlow<SettingsUi> = _settingsUi

    fun loadSettings() {
        viewModelScope.launch {
            _settingsUi.value = settingsUiFrom(settings())
        }
    }

    private suspend fun settingsUiFrom(st: Settings) = withContext(Dispatchers.IO) {
        SettingsUi(
            st.verifyEnabled, st.clientId, st.clientSecret.isNotBlank(), st.autoVerify, st.requestsPerHour, db.verification().count(), true,
            st.saveTrainingCrops, crops.count(), OnDeviceModels.load(getApplication()).let { OnDeviceModels.status },
            st.quickScan,
        )
    }

    fun setQuickScan(on: Boolean) {
        viewModelScope.launch { val st = settings(); withContext(Dispatchers.IO) { st.quickScan = on }; _settingsUi.value = settingsUiFrom(st) }
    }

    fun setSaveCrops(on: Boolean) {
        viewModelScope.launch { val st = settings(); withContext(Dispatchers.IO) { st.saveTrainingCrops = on }; _settingsUi.value = settingsUiFrom(st) }
    }

    fun saveSettings(enabled: Boolean, clientId: String, secret: String?, autoVerify: Boolean, perHour: Int) {
        viewModelScope.launch {
            val st = settings()
            withContext(Dispatchers.IO) {
                st.verifyEnabled = enabled
                st.clientId = clientId
                if (secret != null) st.clientSecret = secret
                st.autoVerify = autoVerify
                st.requestsPerHour = perHour
            }
            _settingsUi.value = settingsUiFrom(st)
            _message.value = "Settings saved."
        }
    }

    fun clearVerificationCache() {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { db.verification().count().also { db.verification().clear() } }
            _message.value = "Cleared $n cached USPS results."
        }
    }

    // ---- Online verification (optional) ----
    val manualVerify: VerificationController = VerificationController(viewModelScope, db, ::settings) { manual.rerank() }
    val photoVerify: VerificationController = VerificationController(viewModelScope, db, ::settings) { photo.rerank(); markCropsTrusted() }

    private fun verificationPrior(c: VerificationController) = VerificationPrior(c.statuses())

    private fun prior(structure: StructurePrior = StructurePrior()): RankingPrior =
        CompositePrior(listOf(structure, HistoryPrior(historyStats.value)))

    // ---- Manual entry ----
    private val _manualText = MutableStateFlow("")
    val manualText: StateFlow<String> = _manualText
    private val _manualError = MutableStateFlow<String?>(null)
    val manualError: StateFlow<String?> = _manualError
    val manual: ResultController = ResultController(
        viewModelScope,
        prior = { CompositePrior(listOf(prior(), verificationPrior(manualVerify))) },
        onRanked = { r -> manualVerify.onRanked(r.candidates.map { it.tracking }) },
    )

    fun onManualText(t: String) {
        _manualText.value = t
        if (t.isBlank()) { _manualError.value = null; manual.clear(); return }
        when (val r = ManualEntry.parse(t)) {
            is ManualEntry.Result.Error -> { _manualError.value = r.message; manual.clear() }
            is ManualEntry.Result.Ok -> {
                _manualError.value = null
                val cur = manual.state.value
                if (cur != null && cur.format == r.parsed.format && cur.baseSlots.map { it.toString() } == r.parsed.slots.map { it.toString() }) return
                manual.set(r.parsed.format, r.parsed.slots)
                manualVerify.reset(LabelContext())
            }
        }
    }

    // ---- Photos ----
    private val _frames = MutableStateFlow<List<GrayImage>>(emptyList())
    val frames: StateFlow<List<GrayImage>> = _frames
    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview
    private val _barcodeCrop = MutableStateFlow<NormRect?>(null)
    val barcodeCrop: StateFlow<NormRect?> = _barcodeCrop
    private val _numberCrop = MutableStateFlow<NormRect?>(null)
    val numberCrop: StateFlow<NormRect?> = _numberCrop
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    fun clearFrames() {
        _frames.value = emptyList(); _preview.value = null
        _barcodeCrop.value = null; _numberCrop.value = null
    }

    fun addFrame(g: GrayImage) {
        _frames.value = _frames.value + g
        if (_preview.value == null) _preview.value = Images.toBitmap(g.limited(1600))
    }

    fun importUris(uris: List<Uri>, onDone: () -> Unit) {
        viewModelScope.launch {
            _status.value = "Loading ${uris.size} photo(s)…"
            val imgs = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { Images.fromUri(getApplication(), it) }.getOrNull() } }
            imgs.forEach { addFrame(it) }
            _status.value = if (imgs.isEmpty()) "Couldn't open those photos." else null
            if (imgs.isNotEmpty()) onDone()
        }
    }

    fun setCrops(barcode: NormRect?, number: NormRect?) { _barcodeCrop.value = barcode; _numberCrop.value = number }

    private var photoOutput: RecoveryOutput? = null

    /** Detailed log of the last photo recovery (for development; copy/share from the results). */
    @Volatile private var currentLog: DiagnosticLog? = null

    /** The last recovery's diagnostic log, plus verification results; [redact] hides names and addresses. */
    fun diagnosticLog(redact: Boolean): String? {
        val log = currentLog ?: return null
        val v = photoVerify.state.value
        val extra = StringBuilder()
        if (v.results.isNotEmpty() || v.status != null) {
            extra.append("\n===== Online verification =====\n")
            extra.append("context: ${v.context}\nstatus: ${v.status}\n")
            for ((n, r) in v.results) extra.append("$n: ${r.verdict.status} ${r.verdict.summary}${if (r.fromCache) " (cached)" else ""}\n")
        }
        val text = log.text(redact) + extra
        return if (redact) text.lines().joinToString("\n") { DiagnosticLog.redactLine(it) } else text
    }
    val photo: ResultController = ResultController(
        viewModelScope,
        prior = { CompositePrior(listOf(photoOutput?.prior(historyStats.value) ?: prior(), verificationPrior(photoVerify))) },
        exactEvidence = { photoOutput?.exactEvidence() },
        onRanked = { r ->
            currentLog?.candidates(r)
            lastRanked = r
            writeReport()
            photoVerify.onRanked(r.candidates.map { it.tracking })
        },
    )
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    private val _noResult = MutableStateFlow<RecoveryOutput?>(null)
    /** Set when the pipeline found no number at all. */
    val noResult: StateFlow<RecoveryOutput?> = _noResult
    private var job: Job? = null

    /**
     * Runs the whole pipeline on all frames: full barcode decodes (ML Kit + ZXing on every
     * variant), the partial Code 128 decoder, DataMatrix retries, OCR, consensus and fusion.
     */
    fun startRecovery(onResult: () -> Unit) {
        job?.cancel()
        photo.clear(); _noResult.value = null
        val frames = _frames.value
        if (frames.isEmpty()) return
        val log = DiagnosticLog()
        currentLog = log
        val now = java.util.Date()
        scanId = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(now)
        scanTime = java.text.DateFormat.getDateTimeInstance().format(now)
        scanConfirmed = null
        lastRanked = null
        job = viewModelScope.launch {
            _progress.value = Progress("Starting", 0, frames.size)
            val out = try {
                withContext(Dispatchers.Default) {
                    MlKitBarcode().use { mb ->
                        MlKitText().use { mt ->
                            val app = getApplication<Application>()
                            val models = OnDeviceModels.load(app)
                            val paddle = OnDeviceModels.paddle(app)
                            val quick = settings().quickScan
                            log.section("Label Rescue ${com.labelrescue.app.BuildConfig.VERSION_NAME} diagnostic log")
                            log.line("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${Runtime.getRuntime().availableProcessors()} cores")
                            log.line("models: ${OnDeviceModels.status}")
                            RecoveryPipeline(
                                listOf(mb, ZxingCppDecoder(), ZxingDecoder()), mt,
                                models = models, text2 = paddle, log = log,
                            ).run(
                                frames,
                                RecoveryPipeline.Options(barcodeCrop = _barcodeCrop.value, numberCrop = _numberCrop.value, earlyStop = quick),
                                progress = { _progress.value = it },
                                cancelled = { !isActive },
                            )
                        }
                    }
                }
            } catch (e: CancelledException) {
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = "Something went wrong: ${e.message}"
                null
            } finally {
                _progress.value = null
            }
            if (out == null) return@launch
            val format = out.format
            if (format == null) {
                _noResult.value = out
                onResult()
                return@launch
            }
            val crops = withContext(Dispatchers.Default) {
                out.locations.mapNotNull { (pos, loc) ->
                    frames.getOrNull(loc.frame)?.let { f -> Images.zoomCrop(f, loc.box)?.let { pos to it } }
                }.toMap()
            }
            photoOutput = out
            photoVerify.reset(LabelContext.fromLabelText(out.labelText, out.zip ?: out.fullDecode?.zip))
            photo.set(format, out.slots) { copy(photo = PhotoEvidence(out, crops)) }
            onResult()
        }
    }

    fun cancelRecovery() { job?.cancel(); _progress.value = null; _status.value = null }

    // Declared last: uses the result controllers above.
    init {
        viewModelScope.launch {
            history.collect { list ->
                val stats = HistoryStats.from(list.map { it.tracking })
                if (stats != historyStats.value) {
                    historyStats.value = stats
                    // New learned priors: re-rank what's on screen.
                    manual.rerank(); photo.rerank()
                }
            }
        }
    }

}
