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
    fun showMessage(m: String) { _message.value = m }

    private val crops by lazy { TrainingCrops(app) }
    private val barcodeExamples by lazy { com.labelrescue.app.data.BarcodeExamples(app) }
    private val barcodeLayouts by lazy { com.labelrescue.app.data.BarcodeLayouts(app) }

    /** Keep a damaged-barcode example when collecting training data is on (either setting). */
    private fun keepBarcodeExample(tracking: String, out: RecoveryOutput?, truthFrom: String) {
        out ?: return
        val frames = _frames.value
        viewModelScope.launch(Dispatchers.IO) {
            val st = settings()
            if (st.saveTrainingCrops || st.learnFromIntact) runCatching { barcodeExamples.save(tracking, out, frames, truthFrom) }
        }
    }

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
    @Volatile private var scanPasted: String? = null
    @Volatile private var lastRanked: com.labelrescue.core.candidates.RankResult? = null

    private fun writeReport() {
        val id = scanId ?: return
        val out = photoOutput ?: return
        val header = "app ${com.labelrescue.app.BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · models: ${OnDeviceModels.status}"
        val entry = com.labelrescue.core.fusion.TrainingReport.entry(id, scanTime, header, out, lastRanked, scanConfirmed, scanPasted)
        val frame = _frames.value.getOrNull(out.privacyFrame)
        viewModelScope.launch(Dispatchers.IO) {
            report.put(id, entry)
            // One photo per scan, as taken (training data is not redacted, by the user's choice).
            if (frame != null && !report.hasPhoto(id)) {
                runCatching {
                    val bmp = com.labelrescue.app.pipeline.Images.toBitmap(frame)
                    val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
                    report.putPhoto(id, bytes)
                    _reportPhoto.value = android.graphics.Bitmap.createScaledBitmap(bmp, 480, 480 * bmp.height / bmp.width, true)
                }
            }
            _reportCount.value = report.count(); _reportPhotos.value = report.photos().size
        }
    }

    fun refreshReportCount() { viewModelScope.launch(Dispatchers.IO) { _reportCount.value = report.count(); _reportPhotos.value = report.photos().size } }

    /**
     * Everything for training in one ZIP in Downloads (report + photos, digit crops, barcode
     * examples, the last diagnostic log, confirmed numbers). The training report clears after.
     */
    fun exportTrainingData() {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    com.labelrescue.app.data.TrainingExport.write(
                        getApplication(), report, crops, barcodeExamples, diagnosticLog(redact = false),
                        db.confirmed().allOnce(), com.labelrescue.app.BuildConfig.VERSION_NAME, layout = barcodeLayouts.file,
                    )
                }
            }
            res.onSuccess { r ->
                _reportCount.value = 0; _reportPhotos.value = 0; _reportPhoto.value = null
                _message.value = "Saved ${r.name} to ${r.where} (${r.entries} files). Send that one file."
            }.onFailure { _message.value = "Couldn't save the export: ${it.message}" }
            loadSettings()
        }
    }

    fun confirm(tracking: String, source: String) {
        viewModelScope.launch {
            db.confirmed().insert(ConfirmedNumber(tracking = tracking, confirmedAt = System.currentTimeMillis(), source = source))
            var res: TrainingCrops.SaveResult? = null
            val out = photoOutput
            if (source == "photo" && out != null && settings().saveTrainingCrops) {
                // Trusted only when the barcode itself proved the number (learn mode saves those).
                res = withContext(Dispatchers.IO) { runCatching { crops.save(tracking, out, _frames.value, trusted = false) }.getOrNull() }
            }
            if (source == "photo") { scanConfirmed = tracking; writeReport(); keepBarcodeExample(tracking, out, "confirmed") }
            _message.value = "Saved $tracking. Its Mailer ID and service code now help rank future labels." + (res?.let { r ->
                " ${r.saved} digit crops kept for retraining" +
                    (if (r.skipped > 0) ", ${r.skipped} skipped (no reader agreed with the digit)" else "") + "."
            } ?: "")
        }
    }

    /**
     * A USPS number from elsewhere (copied from ups.com) for the photographed label: set as every
     * digit of the result when the formats match. False if they don't (the caller opens it as a
     * typed number instead).
     */
    fun useUspsNumber(n: com.labelrescue.core.usps.ParsedNumber): Boolean {
        // Whatever the format, it's the photographed label's real number: record it for the report.
        currentLog?.line("USPS number pasted from the website: ${n.tracking}")
        scanPasted = n.tracking
        writeReport()
        keepBarcodeExample(n.tracking, photoOutput, "pasted")
        val st = photo.state.value ?: return false
        if (st.format.trackingFormat != n.format.trackingFormat) return false
        val off = st.format.routingLength
        photo.pickAll(n.tracking.withIndex().associate { (i, c) -> off + i to c })
        return true
    }

    // ---- Training crop review ----

    fun clearTrainingCrops() {
        viewModelScope.launch { withContext(Dispatchers.IO) { crops.clear(); barcodeExamples.clear() }; loadSettings(); _message.value = "Training crops and barcode examples deleted." }
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

    // ---- Settings ----
    private var settingsObj: Settings? = null
    private suspend fun settings(): Settings = settingsObj ?: withContext(Dispatchers.IO) { Settings(getApplication()) }.also { settingsObj = it }

    data class SettingsUi(
        val loaded: Boolean = false,
        val saveCrops: Boolean = false, val cropCount: Int = 0, val models: String = "",
        val quickScan: Boolean = true,
        val learnFromIntact: Boolean = false,
        val barcodeCount: Int = 0,
        val reportCount: Int = 0,
        val hasDiagnosticLog: Boolean = false,
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
            true, st.saveTrainingCrops, crops.count(), OnDeviceModels.load(getApplication()).let { OnDeviceModels.status },
            st.quickScan, st.learnFromIntact, barcodeExamples.count(), report.count(), currentLog != null,
        )
    }

    fun setLearnFromIntact(on: Boolean) {
        viewModelScope.launch { val st = settings(); withContext(Dispatchers.IO) { st.learnFromIntact = on }; _settingsUi.value = settingsUiFrom(st) }
    }

    fun setQuickScan(on: Boolean) {
        viewModelScope.launch { val st = settings(); withContext(Dispatchers.IO) { st.quickScan = on }; _settingsUi.value = settingsUiFrom(st) }
    }

    fun setSaveCrops(on: Boolean) {
        viewModelScope.launch { val st = settings(); withContext(Dispatchers.IO) { st.saveTrainingCrops = on }; _settingsUi.value = settingsUiFrom(st) }
    }

    private fun prior(structure: StructurePrior = StructurePrior()): RankingPrior =
        CompositePrior(listOf(structure, HistoryPrior(historyStats.value)))

    // ---- Manual entry ----
    private val _manualText = MutableStateFlow("")
    val manualText: StateFlow<String> = _manualText
    private val _manualError = MutableStateFlow<String?>(null)
    val manualError: StateFlow<String?> = _manualError
    val manual: ResultController = ResultController(
        viewModelScope,
        prior = { prior() },
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

    /** The last recovery's diagnostic log; [redact] hides names and addresses. */
    fun diagnosticLog(redact: Boolean): String? {
        val log = currentLog ?: return null
        val text = log.text(redact)
        return if (redact) text.lines().joinToString("\n") { DiagnosticLog.redactLine(it) } else text
    }
    val photo: ResultController = ResultController(
        viewModelScope,
        prior = { photoOutput?.prior(historyStats.value) ?: prior() },
        exactEvidence = { photoOutput?.exactEvidence() },
        onRanked = { r ->
            currentLog?.candidates(r)
            // The report keeps the app's own ranking, not one after digits were picked or pasted.
            if (photo.state.value?.overrides.isNullOrEmpty()) lastRanked = r
            writeReport()
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
        _stickerUps.value = null; _stickerAsked.value = false
        val frames = _frames.value
        if (frames.isEmpty()) return
        val log = DiagnosticLog()
        currentLog = log
        // Live view of the log on the progress screen: names and addresses hidden, recent lines
        // only, refreshed a few times a second (lines arrive from several threads).
        val live = ArrayDeque<String>()
        val liveDirty = java.util.concurrent.atomic.AtomicBoolean(false)
        _liveLog.value = emptyList()
        log.onLine = { l ->
            val shown = DiagnosticLog.redactLine(l).let { if (it.length > 140) it.take(139) + "…" else it }
            synchronized(live) { live.addLast(shown); while (live.size > LIVE_LINES) live.removeFirst() }
            liveDirty.set(true)
        }
        val ticker = viewModelScope.launch {
            while (true) {
                if (liveDirty.getAndSet(false)) { _liveLog.value = synchronized(live) { live.toList() } }
                kotlinx.coroutines.delay(80)
            }
        }
        val now = java.util.Date()
        scanId = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(now)
        scanTime = java.text.DateFormat.getDateTimeInstance().format(now)
        scanConfirmed = null; scanPasted = null
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
                            val learn = settings().learnFromIntact
                            val layout = runCatching { barcodeLayouts.load() }.getOrNull()?.takeIf { it.size > 0 }
                            log.line("learned barcode positions: ${layout?.size ?: 0}")
                            log.section("Label Rescue ${com.labelrescue.app.BuildConfig.VERSION_NAME} diagnostic log")
                            log.line("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${Runtime.getRuntime().availableProcessors()} cores")
                            log.line("models: ${OnDeviceModels.status}")
                            RecoveryPipeline(
                                listOf(mb, ZxingCppDecoder(), ZxingDecoder()), mt,
                                models = models, text2 = paddle, log = log,
                            ).run(
                                frames,
                                RecoveryPipeline.Options(barcodeCrop = _barcodeCrop.value, numberCrop = _numberCrop.value, earlyStop = quick, learnFromIntact = learn, layout = layout),
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
                log.onLine = null
                ticker.cancel()
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
            photo.set(format, out.slots) { copy(photo = PhotoEvidence(out, crops)) }
            onResult()
            // Learn mode: an intact label's barcode proved the number, so its located printed
            // digits are saved right away as trusted training crops (no confirmation needed).
            val fd = out.fullDecode
            // A 2D code (or another barcode) proved the number while the main 1D barcode didn't
            // read: that barcode is a labeled example of real damage.
            if (fd != null && !out.barcodeRead) keepBarcodeExample(fd.tracking, out, "2d-code")
            // Learn mode: where this intact label's barcodes sat, to look there first on damaged ones.
            var layoutNote = ""
            if (fd != null && out.layoutSamples.isNotEmpty()) {
                val n = withContext(Dispatchers.IO) { runCatching { barcodeLayouts.add(out.layoutSamples) }.getOrNull() }
                if (n != null) layoutNote = " Barcode position learned ($n known)."
            }
            if (fd != null && out.locations.isEmpty() && layoutNote.isNotEmpty()) _message.value = "Learn mode:$layoutNote"
            if (fd != null && out.locations.isNotEmpty() && settings().learnFromIntact) {
                val same = withContext(Dispatchers.IO) { this@AppViewModel.crops.labelsFromSameShipper(fd.tracking) }
                if (same >= LEARN_LABELS_PER_SHIPPER) {
                    // Enough of this shipper's printer and font: more would only crowd out other fonts.
                    _message.value = "Learn mode: already have $same labels from this shipper, so this one isn't saved. Labels from other shippers help more." + layoutNote
                } else {
                    val res = withContext(Dispatchers.IO) { runCatching { this@AppViewModel.crops.save(fd.tracking, out, frames, trusted = true) }.getOrNull() }
                    _message.value = "Learn mode: ${res?.saved ?: 0} digit crops saved from this intact label" +
                        (res?.skipped?.takeIf { it > 0 }?.let { " ($it skipped)" } ?: "") + " (${same + 1} of $LEARN_LABELS_PER_SHIPPER from this shipper)." + layoutNote
                }
            }
        }
    }

    fun cancelRecovery() { job?.cancel(); _progress.value = null; _status.value = null }

    // ---- UPS routing sticker (when both the UPS and the USPS number are unsure) ----
    private val _stickerUps = MutableStateFlow<com.labelrescue.core.ups.UpsNumber?>(null)
    /** The 1Z number read from a photo of the UPS sticker, for the current scan. */
    val stickerUps: StateFlow<com.labelrescue.core.ups.UpsNumber?> = _stickerUps
    private val _stickerAsked = MutableStateFlow(false)
    /** The sticker question was answered for the current scan (don't ask again). */
    val stickerAsked: StateFlow<Boolean> = _stickerAsked
    private val _stickerBusy = MutableStateFlow(false)
    val stickerBusy: StateFlow<Boolean> = _stickerBusy

    fun stickerAnswered() { _stickerAsked.value = true }

    /** Where the camera app saves the sticker photo (a content URI to the app's cache). */
    fun stickerPhotoUri(): Uri {
        val app = getApplication<Application>()
        val f = java.io.File(java.io.File(app.cacheDir, "sticker").apply { mkdirs() }, "sticker.jpg")
        return androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", f)
    }

    /** Reads the 1Z number off the sticker photo; it then fills the UPS card with its confidence. */
    fun readUpsSticker(uri: Uri) {
        viewModelScope.launch {
            _stickerBusy.value = true
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    val img = Images.fromUri(getApplication(), uri) ?: return@runCatching null
                    MlKitText().use { com.labelrescue.core.ups.UpsSticker.read(it, img) }
                }.getOrNull()
            }
            _stickerBusy.value = false
            if (r == null) {
                _stickerAsked.value = false
                _message.value = "Couldn't read a UPS number on that photo. Take it closer, straight on, with the 1Z number sharp."
            } else {
                _stickerUps.value = r.number
                currentLog?.line("UPS sticker: ${r.number.tracking}, ${r.agreeing} of ${r.reads} reads agree, ${(r.number.confidence * 100).toInt()}%")
                _message.value = "UPS sticker read: ${r.number.grouped} (${(r.number.confidence * 100).toInt()}% sure)."
            }
            runCatching { java.io.File(java.io.File(getApplication<Application>().cacheDir, "sticker"), "sticker.jpg").delete() }
        }
    }

    private val _liveLog = MutableStateFlow<List<String>>(emptyList())
    /** The latest diagnostic lines while a scan is processed (redacted). */
    val liveLog: StateFlow<List<String>> = _liveLog

    private companion object {
        /** Lines kept for the live view on the progress screen. */
        const val LIVE_LINES = 300
        /** Learn mode keeps at most this many intact labels per shipper (Mailer ID). */
        const val LEARN_LABELS_PER_SHIPPER = 5
    }

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
