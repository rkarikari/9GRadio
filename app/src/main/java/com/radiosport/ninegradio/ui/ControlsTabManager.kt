package com.radiosport.ninegradio.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.radiosport.ninegradio.R

/**
 * Holds references to all per-tab control views so the existing
 * [MainActivity.setupControlsPanel] / [MainActivity.syncGainLabel] /
 * [MainActivity.applyAllSettings] code can reach them without knowing
 * which tab fragment they live in.
 *
 * Call [bind] once after [ControlsPagerAdapter] has inflated each tab view
 * (all tabs except the immediately-visible one are available after the pager
 * does its first layout pass).  Re-call when the adapter re-inflates views.
 */
class ControlsTabManager(private val adapter: ControlsPagerAdapter) {

    // ── Mode tab ──────────────────────────────────────────────────────────────
    val chipGroupDemod: ChipGroup? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_MODE)?.findViewById(R.id.chipGroupDemod)
    val btnShutdown: android.widget.Button? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_MODE)?.findViewById(R.id.btnShutdown)
    val spinnerShutdownTimer: android.widget.Spinner? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_MODE)?.findViewById(R.id.spinnerShutdownTimer)
    val tvShutdownTimerStatus: android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_MODE)?.findViewById(R.id.tvShutdownTimerStatus)

    // ── Tune tab ──────────────────────────────────────────────────────────────
    val tuningDial:      TuningDialView?   get() = adapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById(R.id.tuningDial)
    val chipGroupStep:   ChipGroup?        get() = adapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById(R.id.chipGroupStep)
    val chipGroupBands:  ChipGroup?        get() = adapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById(R.id.chipGroupBands)
    val switchFineTune:  SwitchMaterial?   get() = adapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById(R.id.switchFineTune)
    val switchSnap:      SwitchMaterial?   get() = adapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById(R.id.switchSnapChannel)

    // ── RF tab ────────────────────────────────────────────────────────────────
    val sliderGain:            Slider?     get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.sliderGain)
    val tvGainValue:           TextView?   get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.tvGainValue)
    val switchTunerAgc:        Switch?     get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.switchTunerAgc)
    val switchHardwareAgc:     Switch?     get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.switchHardwareAgc)
    val sliderSquelch:         Slider?     get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.sliderSquelch)
    val tvSquelchValue:        TextView?   get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.tvSquelchValue)
    val sliderVolume:          Slider?     get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.sliderVolume)
    val tvVolumeValue:         TextView?   get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.tvVolumeValue)
    val sliderIfBandwidth:     Slider?     get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.sliderIfBandwidth)
    val tvIfBandwidthValue:    TextView?   get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.tvIfBandwidthValue)
    val switchNoiseBlanker:    Switch?     get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.switchNoiseBlanker)
    val switchNoiseReducer:    Switch?     get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.switchNoiseReducer)
    val switchBiasTee:         Switch?     get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.switchBiasTee)
    val spinnerDirectSampling: Spinner?    get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.spinnerDirectSampling)
    val sliderPpm:                 Slider?     get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.sliderPpm)
    val tvPpmValue:                TextView?   get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.tvPpmValue)
    val layoutSampleRateChips: LinearLayout? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.layoutSampleRateChips)
    val tvSampleRateLabel:     TextView?   get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.tvSampleRateLabel)
    val layoutAudioSinkRateChips: LinearLayout? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.layoutAudioSinkRateChips)
    val tvAudioSinkRateLabel:     TextView?     get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById(R.id.tvAudioSinkRateLabel)

    // ── Display tab ───────────────────────────────────────────────────────────
    private fun displayView() = adapter.getViewAt(ControlsPagerAdapter.TAB_DISPLAY)

    // Existing
    val spinnerFftSize:    Spinner?  get() = displayView()?.findViewById(R.id.spinnerFftSize)
    val spinnerDecimation: Spinner?  get() = displayView()?.findViewById(R.id.spinnerDecimation)
    val spinnerTheme:      Spinner?  get() = displayView()?.findViewById(R.id.spinnerTheme)
    val spinnerPalette:    Spinner?  get() = displayView()?.findViewById(R.id.spinnerPalette)
    val switchPeakHold:    Switch?   get() = displayView()?.findViewById(R.id.switchPeakHold)
    val sliderSpecWfRatio: Slider?   get() = displayView()?.findViewById(R.id.sliderSpecWfRatio)
    val tvSpecWfRatioValue:android.widget.TextView? get() = displayView()?.findViewById(R.id.tvSpecWfRatioValue)

    // ── New advanced Display controls ─────────────────────────────────────────
    // Spectrum
    val sliderFillOpacity:   Slider?   get() = displayView()?.findViewById(R.id.sliderFillOpacity)
    val tvFillOpacityValue:  android.widget.TextView? get() = displayView()?.findViewById(R.id.tvFillOpacityValue)
    val sliderSpecFloor:     Slider?   get() = displayView()?.findViewById(R.id.sliderSpecFloor)
    val tvSpecFloorValue:    android.widget.TextView? get() = displayView()?.findViewById(R.id.tvSpecFloorValue)
    val sliderSpecCeiling:   Slider?   get() = displayView()?.findViewById(R.id.sliderSpecCeiling)
    val tvSpecCeilingValue:  android.widget.TextView? get() = displayView()?.findViewById(R.id.tvSpecCeilingValue)
    val switchAutoRange:     Switch?   get() = displayView()?.findViewById(R.id.switchAutoRange)
    val btnResetDbRange:     android.widget.Button? get() = displayView()?.findViewById(R.id.btnResetDbRange)
    val switchNoiseFloor:    Switch?   get() = displayView()?.findViewById(R.id.switchNoiseFloor)
    val sliderPeakDecay:     Slider?   get() = displayView()?.findViewById(R.id.sliderPeakDecay)
    val tvPeakDecayValue:    android.widget.TextView? get() = displayView()?.findViewById(R.id.tvPeakDecayValue)
    val switchPeakAnnotations: Switch? get() = displayView()?.findViewById(R.id.switchPeakAnnotations)
    val switchCrosshair:     Switch?   get() = displayView()?.findViewById(R.id.switchCrosshair)
    // Waterfall
    val sliderWfBrightness:  Slider?   get() = displayView()?.findViewById(R.id.sliderWfBrightness)
    val tvWfBrightnessValue: android.widget.TextView? get() = displayView()?.findViewById(R.id.tvWfBrightnessValue)
    val sliderWfContrast:    Slider?   get() = displayView()?.findViewById(R.id.sliderWfContrast)
    val tvWfContrastValue:   android.widget.TextView? get() = displayView()?.findViewById(R.id.tvWfContrastValue)
    val switchAutoStretch:   Switch?   get() = displayView()?.findViewById(R.id.switchAutoStretch)
    val switchWfPause:       Switch?   get() = displayView()?.findViewById(R.id.switchWfPause)
    val switchTimeRuler:     Switch?   get() = displayView()?.findViewById(R.id.switchTimeRuler)
    val switchCentreMarker:  Switch?   get() = displayView()?.findViewById(R.id.switchCentreMarker)
    // FFT
    val spinnerFrameAvg:     Spinner?  get() = displayView()?.findViewById(R.id.spinnerFrameAvg)

    // ── Recording tab ─────────────────────────────────────────────────────────
    val btnRecordIq:        android.widget.Button? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RECORDING)?.findViewById(R.id.btnRecordIq)
    val btnRecordAudio:     android.widget.Button? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RECORDING)?.findViewById(R.id.btnRecordAudio)
    val spinnerRecordStop:  Spinner? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RECORDING)?.findViewById(R.id.spinnerRecordStop)
    val containerRecordingsTabList: LinearLayout? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RECORDING)?.findViewById(R.id.containerRecordingsTabList)
    val recyclerRecordingsTab: androidx.recyclerview.widget.RecyclerView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_RECORDING)?.findViewById(R.id.recyclerRecordingsTab)

    // ── Settings tab ──────────────────────────────────────────────────────────
    val switchReverseTuning: Switch?  get() = adapter.getViewAt(ControlsPagerAdapter.TAB_SETTINGS)?.findViewById(R.id.switchReverseTuning)
    val switchOutOfBound:    Switch?  get() = adapter.getViewAt(ControlsPagerAdapter.TAB_SETTINGS)?.findViewById(R.id.switchOutOfBound)
    val btnOpenSettings:     android.widget.Button? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_SETTINGS)?.findViewById(R.id.btnOpenSettings)
    val btnDeviceInfo:       android.widget.Button? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_SETTINGS)?.findViewById(R.id.btnDeviceInfo)
    val btnScanner:          android.widget.Button? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_SETTINGS)?.findViewById(R.id.btnScanner)
    val btnDebugPanel:       android.widget.Button? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_SETTINGS)?.findViewById(R.id.btnDebugPanel)
    val btnBookmarks:        android.widget.Button? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_SETTINGS)?.findViewById(R.id.btnBookmarks)
    val tvAppVersion:        android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_SETTINGS)?.findViewById(R.id.tvAppVersion)


    // ── APRS tab ──────────────────────────────────────────────────────────────
    val tvAprsTabStatus:  android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_APRS)?.findViewById(R.id.tvAprsTabStatus)
    val tvAprsTabCount:   android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_APRS)?.findViewById(R.id.tvAprsTabCount)
    val tvAprsTabMode:    android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_APRS)?.findViewById(R.id.tvAprsTabMode)
    val listAprsTabPackets: android.widget.ListView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_APRS)?.findViewById(R.id.listAprsTabPackets)


    // ── ACARS tab ─────────────────────────────────────────────────────────────
    val tvAcarsTabStatus:  android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_ACARS)?.findViewById(R.id.tvAcarsTabStatus)
    val tvAcarsTabCount:   android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_ACARS)?.findViewById(R.id.tvAcarsTabCount)
    val tvAcarsTabMode:    android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_ACARS)?.findViewById(R.id.tvAcarsTabMode)
    val listAcarsTabMessages: android.widget.ListView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_ACARS)?.findViewById(R.id.listAcarsTabMessages)

    // ── DMR tab ───────────────────────────────────────────────────────────────
    val tvDmrTabStatus:   android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DMR)?.findViewById(R.id.tvDmrTabStatus)
    val tvDmrTabCount:    android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DMR)?.findViewById(R.id.tvDmrTabCount)
    val tvDmrTabMode:     android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DMR)?.findViewById(R.id.tvDmrTabMode)
    val tvDmrTabVocoder:  android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DMR)?.findViewById(R.id.tvDmrTabVocoder)
    val tvDmrTabCallInfo: android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DMR)?.findViewById(R.id.tvDmrTabCallInfo)
    val listDmrTabFrames: android.widget.ListView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DMR)?.findViewById(R.id.listDmrTabFrames)

    // ── YSF tab ───────────────────────────────────────────────────────────────
    val tvYsfTabStatus:   android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_YSF)?.findViewById(R.id.tvYsfTabStatus)
    val tvYsfTabCount:    android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_YSF)?.findViewById(R.id.tvYsfTabCount)
    val tvYsfTabMode:     android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_YSF)?.findViewById(R.id.tvYsfTabMode)
    val tvYsfTabVocoder:  android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_YSF)?.findViewById(R.id.tvYsfTabVocoder)
    val tvYsfTabCallInfo: android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_YSF)?.findViewById(R.id.tvYsfTabCallInfo)
    val listYsfTabFrames: android.widget.ListView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_YSF)?.findViewById(R.id.listYsfTabFrames)

    // ── D-STAR tab ────────────────────────────────────────────────────────────
    val tvDstarTabStatus:   android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DSTAR)?.findViewById(R.id.tvDstarTabStatus)
    val tvDstarTabCount:    android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DSTAR)?.findViewById(R.id.tvDstarTabCount)
    val tvDstarTabMode:     android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DSTAR)?.findViewById(R.id.tvDstarTabMode)
    val tvDstarTabVocoder:  android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DSTAR)?.findViewById(R.id.tvDstarTabVocoder)
    val tvDstarTabCallInfo: android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DSTAR)?.findViewById(R.id.tvDstarTabCallInfo)
    val listDstarTabFrames: android.widget.ListView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DSTAR)?.findViewById(R.id.listDstarTabFrames)

    // ── Dig tab ───────────────────────────────────────────────────────────────
    val tvDigTabStatus:   android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DIG)?.findViewById(R.id.tvDigTabStatus)
    val tvDigTabCount:    android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DIG)?.findViewById(R.id.tvDigTabCount)
    val tvDigTabMode:     android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DIG)?.findViewById(R.id.tvDigTabMode)
    val tvDigTabVocoder:  android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DIG)?.findViewById(R.id.tvDigTabVocoder)
    val tvDigTabCallInfo: android.widget.TextView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DIG)?.findViewById(R.id.tvDigTabCallInfo)
    val listDigTabFrames: android.widget.ListView? get() = adapter.getViewAt(ControlsPagerAdapter.TAB_DIG)?.findViewById(R.id.listDigTabFrames)

    // ── Scan tab ──────────────────────────────────────────────────────────────
    private fun scanView() = adapter.getViewAt(ControlsPagerAdapter.TAB_SCAN)

    val etScanTabStart:        android.widget.EditText? get() = scanView()?.findViewById(R.id.etScanTabStart)
    val etScanTabStop:         android.widget.EditText? get() = scanView()?.findViewById(R.id.etScanTabStop)
    val etScanTabStep:         android.widget.EditText? get() = scanView()?.findViewById(R.id.etScanTabStep)
    val etScanTabSquelch:      android.widget.EditText? get() = scanView()?.findViewById(R.id.etScanTabSquelch)
    val etScanTabDwell:        android.widget.EditText? get() = scanView()?.findViewById(R.id.etScanTabDwell)
    val spinnerScanTabMode:    Spinner?  get() = scanView()?.findViewById(R.id.spinnerScanTabMode)
    val switchScanTabDirection: Switch?  get() = scanView()?.findViewById(R.id.switchScanTabDirection)
    val switchScanTabAdaptive:  Switch?  get() = scanView()?.findViewById(R.id.switchScanTabAdaptive)
    val btnScanTabStartStop:   android.widget.Button? get() = scanView()?.findViewById(R.id.btnScanTabStartStop)
    val btnScanTabReset:       android.widget.Button? get() = scanView()?.findViewById(R.id.btnScanTabReset)
    val spinnerScanTabMemorySlot: Spinner? get() = scanView()?.findViewById(R.id.spinnerScanTabMemorySlot)
    val btnScanTabMemSave:     android.widget.Button? get() = scanView()?.findViewById(R.id.btnScanTabMemSave)
    val btnScanTabMemLoad:     android.widget.Button? get() = scanView()?.findViewById(R.id.btnScanTabMemLoad)
    val btnScanTabPause:       android.widget.Button? get() = scanView()?.findViewById(R.id.btnScanTabPause)
    val btnScanTabSearch:      android.widget.Button? get() = scanView()?.findViewById(R.id.btnScanTabSearch)
    val btnScanTabBusiest:     android.widget.Button? get() = scanView()?.findViewById(R.id.btnScanTabBusiest)
    val btnScanTabClose:       android.widget.Button? get() = scanView()?.findViewById(R.id.btnScanTabClose)
    val progressScanTab:       android.widget.ProgressBar? get() = scanView()?.findViewById(R.id.progressScanTab)
    val tvScanTabCurrentFreq:  android.widget.TextView? get() = scanView()?.findViewById(R.id.tvScanTabCurrentFreq)
    val tvScanTabSignal:       android.widget.TextView? get() = scanView()?.findViewById(R.id.tvScanTabSignal)
    val tvScanTabHits:         android.widget.TextView? get() = scanView()?.findViewById(R.id.tvScanTabHits)
    val tvScanTabNoiseFloor:   android.widget.TextView? get() = scanView()?.findViewById(R.id.tvScanTabNoiseFloor)
    val tvScanTabRate:         android.widget.TextView? get() = scanView()?.findViewById(R.id.tvScanTabRate)
    val listScanTabHits:       android.widget.ListView? get() = scanView()?.findViewById(R.id.listScanTabHits)
    val listScanTabChannelTable: android.widget.ListView? get() = scanView()?.findViewById(R.id.listScanTabChannelTable)
}
