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
import com.labelrescue.core.barcode.ZxingDecoder
import com.labelrescue.core.fusion.CancelledException
import com.labelrescue.core.fusion.Progress
import com.labelrescue.core.fusion.RecoveryOutput
import com.labelrescue.core.fusion.RecoveryPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val historyStats = MutableStateFlow(HistoryStats.EMPTY)

    private fun prior(structure: StructurePrior = StructurePrior()): RankingPrior =
        CompositePrior(listOf(structure, HistoryPrior(historyStats.value)))

    // ---- Manual entry ----
    private val _manualText = MutableStateFlow("")
    val manualText: StateFlow<String> = _manualText
    private val _manualError = MutableStateFlow<String?>(null)
    val manualError: StateFlow<String?> = _manualError
    val manual = ResultController(viewModelScope, { prior() })

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
    val photo = ResultController(
        viewModelScope,
        prior = { photoOutput?.prior(historyStats.value) ?: prior() },
        exactEvidence = { photoOutput?.exactEvidence() },
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
        job = viewModelScope.launch {
            _progress.value = Progress("Starting", 0, frames.size)
            val out = try {
                withContext(Dispatchers.Default) {
                    MlKitBarcode().use { mb ->
                        MlKitText().use { mt ->
                            RecoveryPipeline(listOf(mb, ZxingDecoder()), mt).run(
                                frames,
                                RecoveryPipeline.Options(barcodeCrop = _barcodeCrop.value, numberCrop = _numberCrop.value),
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
            photo.set(format, out.slots) { copy(photo = PhotoEvidence(out, crops)) }
            onResult()
        }
    }

    fun cancelRecovery() { job?.cancel(); _progress.value = null; _status.value = null }
}
