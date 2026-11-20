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
import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.candidates.StructurePrior
import com.labelrescue.core.fusion.NormRect
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.Variants
import com.labelrescue.core.ocr.OcrInterpreter
import com.labelrescue.core.usps.ServiceHints
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

    private var photoPrior = StructurePrior()
    val photo = ResultController(viewModelScope, { prior(photoPrior) })
    private var job: Job? = null

    /** Stage 2: OCR every frame and variant, vote per position. */
    fun startRecovery(onResult: () -> Unit) {
        job?.cancel()
        photo.clear()
        job = viewModelScope.launch {
            _status.value = "Reading…"
            val result = withContext(Dispatchers.Default) {
                val text = MlKitText()
                val obs = ArrayList<com.labelrescue.core.fusion.Observation>()
                val labelText = StringBuilder()
                text.use {
                    for ((fi, f) in _frames.value.withIndex()) {
                        if (!isActive) break
                        val region = _numberCrop.value?.toPixels(f.width, f.height)
                        for (v in Variants.make(f, region)) {
                            val page = it.recognize(v.image)
                            if (fi == 0) labelText.append(page.text).append('\n')
                            obs += OcrInterpreter.interpret(page, v.toFrame, fi, v.name)
                        }
                    }
                }
                val format = obs.groupBy { it.format }.maxByOrNull { e -> e.value.sumOf { it.weight } }?.key
                format?.let { fmt ->
                    Triple(fmt, List(fmt.length) { i ->
                        val acc = DoubleArray(10); var w = 0.0
                        for (o in obs) if (o.format == fmt) o.slots[i]?.let { s -> for (k in 0..9) acc[k] += o.weight * s.probs[k]; w += o.weight }
                        if (w > 0) SlotDist(SlotDist.DIGITS, acc) else SlotDist.uniform()
                    }, labelText.toString())
                }
            }
            _status.value = null
            if (result == null) { _status.value = "No tracking number found. Try cropping or typing the digits."; return@launch }
            val (format, slots, label) = result
            photoPrior = StructurePrior(ServiceHints.serviceHints(label), ServiceHints.zips(label))
            photo.set(format, slots)
            onResult()
        }
    }

    fun cancelRecovery() { job?.cancel(); _status.value = null }
}
