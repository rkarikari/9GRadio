package com.radiosport.ninegradio.usb

import android.hardware.usb.*
import android.util.Log
import com.radiosport.ninegradio.debug.DebugBus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.Closeable
import kotlin.math.*

/**
 * RTL-SDR V4 low-level USB driver (used by 9GRadio).
 *
 * Faithfully ported from rtlsdrblog/rtl-sdr-blog (librtlsdr.c + tuner_r82xx.c).
 *
 * Implements full register-level access to the RTL2832U demodulator and R828D tuner.
 * Supports all V4-specific features:
 *   - Hardware HF upconverter (auto-applied when tuning ≤ 28.8 MHz on V4)
 *   - Three-input band switching: HF / VHF / UHF with per-band notch filters
 *   - GPIO-5 hardware upconverter switch (newer V4 batches)
 *   - Bias-tee control (5V on antenna port)
 *   - TCXO reference (28.8 MHz)
 *   - Full three-stage gain ladder: LNA / Mixer / VGA (manual / AGC)
 *   - Frequency tuning 500 kHz – 1766 MHz
 *   - Sample rate 225001 – 3200000 S/s
 *
 * Also supports standard R828D (non-V4) two-input switching at 345 MHz,
 * and generic R820T2 dongles.
 */
class RtlSdrDevice(
    private val usbManager: UsbManager,
    private val usbDevice: UsbDevice
) : Closeable {

    companion object {
        private const val TAG = "RtlSdrDevice"

        // RTL2832U USB vendor/product IDs
        val SUPPORTED_DEVICES = mapOf(
            Pair(0x0BDA, 0x2838) to "RTL-SDR V4",
            Pair(0x0BDA, 0x2832) to "RTL2832U Generic",
            Pair(0x0BDA, 0x2837) to "RTL2832U (no tuner)",
            Pair(0x0BDA, 0x2840) to "RTL2832U (Fitipower)",
            Pair(0x0BDA, 0x2836) to "RTL2832U (RTL-SDR Blog V3)"
        )

        // USB control request constants
        const val CTRL_IN: Int   = (UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_IN)
        const val CTRL_OUT: Int  = (UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_OUT)
        const val CTRL_TIMEOUT = 300

        // RTL2832U register blocks — match librtlsdr enum blocks
        const val BLOCK_DEMOD  = 0   // DEMODB
        const val BLOCK_USB    = 1   // USBB
        const val BLOCK_SYS    = 2   // SYSB
        const val BLOCK_TUNER  = 3   // TUNB
        const val BLOCK_REMOTE = 4   // ROMB (also IICB=6 used for I2C)

        // Important SYS block registers (librtlsdr enum sys_reg)
        const val REG_DEMOD_CTL    = 0x3000   // DEMOD_CTL
        const val REG_GPO          = 0x3001   // GPO
        const val REG_GPI          = 0x3002   // GPI
        const val REG_GPOE         = 0x3003   // GPOE
        const val REG_GPD          = 0x3004   // GPD
        const val REG_SYSCTL       = 0x3005   // SYSINTE
        const val REG_CLK_CTL      = 0x3006   // SYSINTS
        const val REG_USB_STAT     = 0x3012
        const val REG_ZERO_IF      = 0x1B1
        const val REG_DVBT_IQ_EST  = 0x1C7
        const val REG_DCLK_PLL_RATIO = 0x2E
        const val REG_RSSI_V4      = 0x3E5
        const val REG_DEMOD_CTL_1  = 0x300B   // DEMOD_CTL_1

        // USB block registers (librtlsdr enum usb_reg)
        const val USB_SYSCTL      = 0x2000
        const val USB_EPA_CTL     = 0x2148
        const val USB_EPA_MAXPKT  = 0x2158

        // RTL2832U I2C block index (IICB = 6)
        // wIndex for reads : IICB << 8       = 0x0600
        // wIndex for writes: (IICB << 8)|0x10 = 0x0610
        const val IICB_READ_INDEX  = 0x0600
        const val IICB_WRITE_INDEX = 0x0610

        // R828D / R820T2 I2C addresses (8-bit form as expected by RTL2832U bridge)
        // librtlsdr: R828D_I2C_ADDR=0x74 (7-bit 0x3A), R820T_I2C_ADDR=0x34 (7-bit 0x1A)
        const val R828D_I2C_ADDR   = 0x74
        const val R820T2_I2C_ADDR  = 0x34

        // R82xx chip-ID (register 0x00 returns 0x69 on both R820T/T2 and R828D)
        const val R82XX_CHIP_ID    = 0x69

        // GPIO pins
        const val BIAS_TEE_GPIO_BIT = 0x08   // GPIO bit 3 — bias-tee
        const val UPCONV_GPIO_PIN   = 5      // GPIO pin 5 — upconverter switch

        // XTAL / TCXO frequency
        const val XTAL_FREQ = 28_800_000L

        // Frequency limits
        const val MIN_FREQ_HZ   =       500_000L
        const val MAX_FREQ_HZ   = 1_766_000_000L
        const val HF_CUTOFF_HZ  =    28_800_000L   // V4 upconverter threshold (28.8 MHz)

        // V4 band boundaries (Hz)
        const val VHF_UHF_BOUNDARY_HZ    = 250_000_000L
        // Standard R828D two-input switch threshold
        const val R828D_BAND_BOUNDARY_HZ = 345_000_000L

        // V4 band IDs
        const val BAND_UNKNOWN = 0
        const val BAND_HF      = 1   // HF constant from tuner_r82xx.c
        const val BAND_VHF     = 2   // VHF constant
        const val BAND_UHF     = 3   // UHF constant

        // Direct-sampling modes
        const val DIRECT_SAMPLING_OFF = 0
        const val DIRECT_SAMPLING_I   = 1
        const val DIRECT_SAMPLING_Q   = 2

        // Gain modes
        const val GAIN_MODE_AGC    = 0
        const val GAIN_MODE_MANUAL = 1

        // Valid sample rates for the RTL2832U with 28.8 MHz crystal.
        //
        // The RTL-SDR supports two non-contiguous bands separated by a dead zone
        // (~300–900 kS/s) where the demod register ratio aliases and streaming
        // becomes unreliable:
        //
        //   Low-rate band  : 225 001 – 300 000 Hz
        //   Main band      : 900 001 – 2 400 000 Hz
        //
        // Low-rate entries use clean integer divisors to the DSP IF rate. Rates that are
        // exact multiples of 48 000 Hz decimate to 48 kHz with zero resampling error;
        // 250 000 and 300 000 Hz decimate to 50 kHz (50 000 × 5/6); others land near
        // 48–57 kHz via their nearest exact integer divisor (narrowIfRateFor):
        //
        //   240 000 = 48 000 × 5   → narrowIfRate 48 000 Hz (÷5)
        //   242 000                 → narrowIfRate 48 400 Hz (÷5)
        //   250 000 = 50 000 × 5   → narrowIfRate 50 000 Hz (÷5)
        //   256 000 = 32 000 × 8   → narrowIfRate 51 200 Hz (÷5)
        //   264 000                 → narrowIfRate 52 800 Hz (÷5)
        //   272 000                 → narrowIfRate 54 400 Hz (÷5)
        //   286 000                 → narrowIfRate 57 200 Hz (÷5)
        //   288 000 = 48 000 × 6   → narrowIfRate 48 000 Hz (÷6)
        //   300 000 = 50 000 × 6   → narrowIfRate 50 000 Hz (÷6)
        //
        //   Main-band: 912 000 = 48 000 × 19  …  2 400 000 = 48 000 × 50
        //              (only the subset specified below — intelligently spaced)
        //
        // The main-band selection covers the full 912 kS/s–2.4 MS/s range at
        // practical spacing: every 48 kHz step from 912–960 kS/s, then the subset
        // 1152 / 1200 / 1296 / 1440 / 1536 / 1680 / 1824 / 1920 / 2016 / 2160 /
        // 2256 / 2400 kS/s — chosen so adjacent entries span no more than ~200 kHz,
        // preserving fine-grained user control across the full usable range.
        //
        // Source: librtlsdr.c rtlsdr_set_sample_rate() range checks.
        val SAMPLE_RATES = intArrayOf(
            // ── Low-rate band (225 001–300 000 Hz) ── narrow-mode / low-CPU ──
            // NOTE: the demod rsampRatio register is programmed for these rates and the
            // RTL2832U USB streaming output delivers samples at exactly these rates.
            // All rsampRatio values verified within 0x0020_0000–0x0FFF_FFFF after masking.
            240_000,    // 48 000 × 5  → narrowIfRate 48 000 Hz (÷5)
            242_000,    //              → narrowIfRate 48 400 Hz (÷5)
            250_000,    // 50 000 × 5  → narrowIfRate 50 000 Hz (÷5)
            256_000,    // 32 000 × 8  → narrowIfRate 51 200 Hz (÷5)
            264_000,    //              → narrowIfRate 52 800 Hz (÷5)
            272_000,    //              → narrowIfRate 54 400 Hz (÷5)
            286_000,    //              → narrowIfRate 57 200 Hz (÷5)
            288_000,    // 48 000 × 6  → narrowIfRate 48 000 Hz (÷6)
            300_000,    // 50 000 × 6  → narrowIfRate 50 000 Hz (÷6)
            // ── Main band (900 001–2 400 000 Hz) ── full-spectrum capability ──
             912_000,   // 48 000 × 19
             960_000,   // 48 000 × 20
           1_000_000,   // 1.0 MS/s — common rounded SDR rate; not a 48 kHz multiple —
                         // narrowIfRateFor() picks exact divisor ≥ sink rate (e.g. ÷20 → 50 000 Hz).
           1_024_000,   // common SDR rate (2^10 × 1000); not a 48 kHz multiple —
                         // narrowIfRateFor() picks an exact divisor ≥ sink rate
                         // (e.g. 32 000 Hz direct, ÷32) for this rate.
           1_152_000,   // 48 000 × 24
           1_200_000,   // 48 000 × 25
           1_296_000,   // 48 000 × 27
           1_440_000,   // 48 000 × 30
           1_536_000,   // 48 000 × 32
           1_680_000,   // 48 000 × 35
           1_824_000,   // 48 000 × 38
           1_920_000,   // 48 000 × 40
           2_000_000,   // 2.0 MS/s — common rounded SDR rate; not a 48 kHz multiple —
                         // narrowIfRateFor() picks exact divisor ≥ sink rate (e.g. ÷40 → 50 000 Hz).
           2_016_000,   // 48 000 × 42
           2_048_000,   // common SDR rate (2^11 × 1000); not a 48 kHz multiple —
                         // narrowIfRateFor() picks an exact divisor ≥ sink rate
                         // (e.g. 32 000 Hz direct, ÷64) for this rate.
           2_160_000,   // 48 000 × 45
           2_256_000,   // 48 000 × 47
           2_400_000,   // 48 000 × 50
           2_500_000    // 2.5 MS/s — within RTL-SDR valid range (≤ 3.2 MS/s); not a 48 kHz
                         // multiple — narrowIfRateFor() picks e.g. ÷50 → 50 000 Hz.
        )

        /**
         * Return the entry in [SAMPLE_RATES] nearest to [rateHz], **staying
         * within the same valid band** as the requested rate.
         *
         * The RTL-SDR has a dead zone between ~300 kS/s and ~900 kS/s.
         * Snapping blindly by absolute distance would cross band boundaries and
         * programme a rate that aliases just as badly as the original invalid value.
         *
         *   • If [rateHz] ≤ 300 000 → snap within the low-rate band (≤ 300 000).
         *   • If [rateHz] ≥ 900 000 → snap within the main band (≥ 900 000).
         *   • Dead zone (300 001–899 999) → snap to the nearer band boundary.
         */
        fun nearestSampleRate(rateHz: Int): Int {
            val lowBand  = SAMPLE_RATES.filter { it <= 300_000 }
            val mainBand = SAMPLE_RATES.filter { it >= 900_000 }
            return when {
                rateHz <= 300_000 ->
                    lowBand.minByOrNull  { kotlin.math.abs(it - rateHz) } ?: 240_000
                rateHz >= 900_000 ->
                    mainBand.minByOrNull { kotlin.math.abs(it - rateHz) } ?: 1_920_000
                else -> {
                    // Dead zone: pick the nearer band boundary
                    val bestLow  = lowBand.maxOrNull()  ?: 288_000
                    val bestMain = mainBand.minOrNull() ?: 912_000
                    if (kotlin.math.abs(rateHz - bestLow) <= kotlin.math.abs(rateHz - bestMain))
                        bestLow else bestMain
                }
            }
        }

        // R82xx gain table in tenths of dB
        // Source: rtlsdrblog/rtl-sdr-blog librtlsdr.c r82xx_gains[]
        val GAIN_TABLE_DB_TENTHS = intArrayOf(
            0, 9, 14, 27, 37, 77, 87, 125, 144, 157,
            166, 197, 207, 229, 254, 280, 297, 328,
            338, 364, 372, 386, 402, 421, 434, 439,
            445, 480, 496
        )

        // R82xx measured LNA gain steps (tenths of dB per index)
        // Source: tuner_r82xx.c r82xx_lna_gain_steps[]
        private val LNA_GAIN_STEPS = intArrayOf(
            0, 9, 13, 40, 38, 13, 31, 22, 26, 31, 26, 14, 19, 5, 35, 13
        )

        // R82xx measured Mixer gain steps (tenths of dB per index)
        // Source: tuner_r82xx.c r82xx_mixer_gain_steps[]
        private val MIX_GAIN_STEPS = intArrayOf(
            0, 5, 10, 10, 19, 9, 10, 25, 17, 10, 8, 16, 13, 6, 3, -8
        )

        // Cumulative LNA and Mixer gain (pre-computed from step tables)
        private val LNA_GAIN_CUMUL: IntArray by lazy {
            IntArray(16).also { arr ->
                var sum = 0
                for (i in arr.indices) { sum += LNA_GAIN_STEPS[i]; arr[i] = sum }
            }
        }
        private val MIX_GAIN_CUMUL: IntArray by lazy {
            IntArray(16).also { arr ->
                var sum = 0
                for (i in arr.indices) { sum += MIX_GAIN_STEPS[i]; arr[i] = sum }
            }
        }

        // ── R82xx tuner frequency range table ─────────────────────────────────────
        // Faithfully ported from tuner_r82xx.c freq_ranges[].
        // Each entry: (startMHz, open_d, rf_mux_ploy, tf_c, xtal_cap_sel)
        // Used in r82xx_set_mux() to configure tracking filter + RF MUX + XTAL cap.
        private data class FreqRange(
            val freqMHz: Int,
            val openD: Int,        // reg 0x17 bit 3 (open drain)
            val rfMuxPloy: Int,    // reg 0x1A bits [7:6],[1:0]
            val tfC: Int,          // reg 0x1B  (TF band)
            val xtalCap20p: Int,   // reg 0x10 bits [1:0] for 20pF cap
            val xtalCap10p: Int,   // reg 0x10 for 10pF
            val xtalCap0p: Int     // reg 0x10 for 0pF
        )

        private val FREQ_RANGES = arrayOf(
            FreqRange(  0, 0x08, 0x02, 0xDF, 0x02, 0x01, 0x00),
            FreqRange( 50, 0x08, 0x02, 0xBE, 0x02, 0x01, 0x00),
            FreqRange( 55, 0x08, 0x02, 0x8B, 0x02, 0x01, 0x00),
            FreqRange( 60, 0x08, 0x02, 0x7B, 0x02, 0x01, 0x00),
            FreqRange( 65, 0x08, 0x02, 0x69, 0x02, 0x01, 0x00),
            FreqRange( 70, 0x08, 0x02, 0x58, 0x02, 0x01, 0x00),
            FreqRange( 75, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00),
            FreqRange( 80, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00),
            FreqRange( 90, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00),
            FreqRange(100, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00),
            FreqRange(110, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00),
            FreqRange(120, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00),
            FreqRange(140, 0x00, 0x02, 0x14, 0x01, 0x01, 0x00),
            FreqRange(180, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00),
            FreqRange(220, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00),
            FreqRange(250, 0x00, 0x02, 0x11, 0x00, 0x00, 0x00),
            FreqRange(280, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00),
            FreqRange(310, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00),
            FreqRange(450, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00),
            FreqRange(588, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00),
            FreqRange(650, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00)
        )

        // XTAL capacitor selection — matches r82xx_xtal_capacitor table
        // V4 uses 28.8 MHz TCXO → XTAL_HIGH_CAP_0P (value 0x10)
        private const val XTAL_HIGH_CAP_0P = 4   // index into xtal_cap table

        // V4 notch bands (notch OFF when tuning within; ON outside)
        // Source: tuner_r82xx.c r82xx_set_freq() open_d calculation
        const val V4_NOTCH_HF_MAX_HZ   =   2_200_000L
        const val V4_NOTCH_FM_MIN_HZ   =  85_000_000L
        const val V4_NOTCH_FM_MAX_HZ   = 112_000_000L
        const val V4_NOTCH_VHF_MIN_HZ  = 172_000_000L
        const val V4_NOTCH_VHF_MAX_HZ  = 242_000_000L

        // R82xx IF frequency for SDR mode.
        // Both the tuner LO calculation and the RTL2832U demod DDC register
        // must use the same 3.57 MHz value so the mixer output is centred
        // on the DDC passband and the tuned frequency matches the received
        // frequency exactly.  The previous 3.77 MHz LO value introduced a
        // 200 kHz upward shift (e.g. tuning to 87.9 MHz received 88.1 MHz).
        private const val R82XX_IF_FREQ       = 3_570_000L  // Hz — tuner LO offset
        private const val R82XX_IF_FREQ_DEMOD = 3_570_000L  // Hz — demod DDC register value

        fun isSupported(device: UsbDevice): Boolean =
            SUPPORTED_DEVICES.containsKey(Pair(device.vendorId, device.productId))

        fun getDeviceName(device: UsbDevice): String =
            SUPPORTED_DEVICES[Pair(device.vendorId, device.productId)] ?: "Unknown RTL-SDR"
    }

    // --- State ---
    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epBulkIn: UsbEndpoint? = null
    private var epBulkOut: UsbEndpoint? = null
    private var isOpen = false

    /**
     * USB bulk-transfer helper (SDR++ / rtl_tcp_andro / keesj pattern).
     * Three-tier strategy: bulkTransfer() → USBDEVFS_BULK ioctl (JNI) → fd fallback.
     */
    private var usbHelper: RtlSdrUsbHelper? = null

    private var _centerFreqHz: Long = 100_000_000L
    private var _sampleRate: Int = 1_920_000
    private var _gain: Int = 0
    private var _gainMode: Int = GAIN_MODE_AGC
    private var _tunerAgcEnabled: Boolean = true
    private var _hardwareAgcEnabled: Boolean = true
    private var _directSampling: Int = DIRECT_SAMPLING_OFF
    private var _biasTee: Boolean = false
    private var _ppmCorrection: Int = 0
    private var _agcMode: Boolean = true
    private var _rtlXtalFreq: Long = XTAL_FREQ
    private var _tunerXtalFreq: Long = XTAL_FREQ
    private var _tunerType: TunerType = TunerType.UNKNOWN

    // V4-specific state
    private var _isV4: Boolean = false
    val isV4: Boolean get() = _isV4
    // Tracks current V4 RF band to avoid redundant I2C writes
    private var _currentBand: Int = BAND_UNKNOWN

    // Shadow registers for R82xx tuner (indexed from reg 0x05 = index 0)
    // Mirrors priv->regs[] in librtlsdr tuner_r82xx.c
    private val r82xxShadow = IntArray(27) { 0xFF }

    // Calibration code from r82xx_set_tv_standard()
    private var filCalCode: Int = 0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _iqFlow = MutableSharedFlow<ByteArray>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val iqFlow: SharedFlow<ByteArray> = _iqFlow.asSharedFlow()

    val iqSubscriberCount: Int get() = _iqFlow.subscriptionCount.value

    private val _statusFlow = MutableStateFlow(DeviceStatus())
    val statusFlow: StateFlow<DeviceStatus> = _statusFlow.asStateFlow()

    private var streamingJob: Job? = null
    private var isStreaming = false

    enum class TunerType { UNKNOWN, E4000, FC0012, FC0013, FC2580, R820T, R820T2, R828D }

    data class DeviceStatus(
        val connected: Boolean = false,
        val tunerType: TunerType = TunerType.UNKNOWN,
        val centerFreqHz: Long = 0L,
        val sampleRate: Int = 0,
        val gainDb10: Int = 0,
        val gainMode: Int = GAIN_MODE_AGC,
        val tunerAgcEnabled: Boolean = true,
        val hardwareAgcEnabled: Boolean = true,
        val biasTee: Boolean = false,
        val directSampling: Int = DIRECT_SAMPLING_OFF,
        val ppmCorrection: Int = 0,
        val signalStrengthDb: Float = -120f,
        val overload: Boolean = false,
        val error: String? = null,
        val streamRestartRequired: Boolean = false
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  OPEN / CLOSE
    // ─────────────────────────────────────────────────────────────────────────

    fun open(): Boolean {
        if (isOpen) return true
        DebugBus.setStatus(DebugBus.STAGE_USB_OPEN, DebugBus.StageStatus.OK, "Opening ${usbDevice.productName ?: usbDevice.deviceName}…")
        val conn = usbManager.openDevice(usbDevice) ?: run {
            Log.e(TAG, "Cannot open USB device - permission not granted?")
            DebugBus.setError(DebugBus.STAGE_USB_OPEN, "openDevice() returned null — permission denied or device busy")
            return false
        }
        connection = conn

        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    when (ep.direction) {
                        UsbConstants.USB_DIR_IN  -> epBulkIn  = ep
                        UsbConstants.USB_DIR_OUT -> epBulkOut = ep
                    }
                }
            }
            if (epBulkIn != null) {
                iface = intf
                conn.claimInterface(intf, true)
                break
            }
        }

        if (epBulkIn == null) {
            Log.e(TAG, "No bulk endpoint found")
            DebugBus.setError(DebugBus.STAGE_USB_OPEN, "No BULK_IN endpoint found on any interface")
            conn.close()
            return false
        }

        isOpen = true
        usbHelper = RtlSdrUsbHelper(conn, epBulkIn!!)
        Log.i(TAG, "UsbHelper created: ${RtlSdrUsbHelper.endpointDesc(epBulkIn!!)}")
        DebugBus.setStatus(DebugBus.STAGE_USB_OPEN, DebugBus.StageStatus.OK,
            "Claimed iface ${iface?.id}  bulkIn ep=0x${(epBulkIn?.address ?: 0).and(0xFF).toString(16)}")
        DebugBus.tick(DebugBus.STAGE_USB_OPEN)

        DebugBus.setStatus(DebugBus.STAGE_DEVICE_INIT, DebugBus.StageStatus.OK, "Initialising baseband & tuner…")
        initDevice()
        _statusFlow.value = _statusFlow.value.copy(
            connected = true,
            tunerType = _tunerType,
            centerFreqHz = _centerFreqHz,
            sampleRate = _sampleRate
        )
        DebugBus.setStatus(DebugBus.STAGE_DEVICE_INIT, DebugBus.StageStatus.OK,
            "Tuner: $_tunerType  V4: $_isV4")
        DebugBus.tick(DebugBus.STAGE_DEVICE_INIT)
        Log.i(TAG, "RTL-SDR device opened. Tuner: $_tunerType, V4: $_isV4")
        return true
    }

    override fun close() {
        isStreaming = false
        streamingJob?.cancel()
        usbHelper?.close()
        usbHelper = null
        streamingJob?.let { job ->
            try {
                kotlinx.coroutines.runBlocking { job.join() }
            } catch (_: Exception) {}
        }
        streamingJob = null
        scope.cancel()
        iface?.let { connection?.releaseInterface(it) }
        connection?.close()
        isOpen = false
        _statusFlow.value = _statusFlow.value.copy(connected = false)
        DebugBus.resetAll()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DEVICE INITIALIZATION  (matches librtlsdr.c rtlsdr_open + rtlsdr_init_baseband)
    // ─────────────────────────────────────────────────────────────────────────

    private fun initDevice() {
        // ── USB system reset (dummy write to detect if device needs reset) ─────────
        // Source: librtlsdr.c rtlsdr_open():
        //   if (rtlsdr_write_reg(dev, USBB, USB_SYSCTL, 0x09, 1) < 0) libusb_reset_device()
        writeReg(BLOCK_USB, USB_SYSCTL, 0x09, 1)
        Thread.sleep(10)

        // Detect V4 via USB descriptor strings.
        // Source: librtlsdr.c rtlsdr_check_dongle_model(dev, "RTLSDRBlog", "Blog V4")
        _isV4 = usbDevice.manufacturerName?.contains("RTLSDRBlog", ignoreCase = true) == true ||
                usbDevice.productName?.contains("Blog V4", ignoreCase = true) == true
        if (!_isV4 && usbDevice.vendorId == 0x0BDA && usbDevice.productId == 0x2838) {
            _isV4 = true  // confirmed by tuner detection (R828D required)
        }

        // ── Initialize baseband (matches rtlsdr_init_baseband exactly) ─────────────
        initBaseband()

        // Detect tuner
        _tunerType = detectTuner()
        if (_isV4 && _tunerType != TunerType.R828D) _isV4 = false
        Log.i(TAG, "Tuner detected: $_tunerType, V4: $_isV4")

        // Apply tuner-specific post-init (matches rtlsdr_open tuner switch)
        when (_tunerType) {
            TunerType.R828D -> {
                // Source: rtlsdr_open() case RTLSDR_TUNER_R828D:
                //   if NOT V4: dev->tun_xtal = R828D_XTAL_FREQ (16 MHz)
                //   Otherwise keep at 28.8 MHz (TCXO)
                if (!_isV4) _tunerXtalFreq = 16_000_000L
                // fallthrough to R820T setup
                applyR82xxDemodSettings()
            }
            TunerType.R820T, TunerType.R820T2 -> applyR82xxDemodSettings()
            TunerType.UNKNOWN -> {
                Log.w(TAG, "No supported tuner found — enabling direct sampling")
                setDirectSampling(DIRECT_SAMPLING_I)
            }
            else -> Log.w(TAG, "Tuner $_tunerType: partial support only")
        }

        // Read EEPROM force-bias-tee flag (bit 1 of byte 7).
        // Source: rtlsdr_open(): dev->force_bt = (buf[7] & 0x02) ? 0 : 1
        val eepromBuf = ByteArray(256)
        val eepromR = connection?.controlTransfer(
            CTRL_IN, 0, 0, 0x0020, eepromBuf, eepromBuf.size, CTRL_TIMEOUT) ?: -1
        val forceBiasTee = if (eepromR >= 8) (eepromBuf[7].toInt() and 0x02) == 0 else false
        if (forceBiasTee) setBiasTee(true)

        // Initialize the tuner (r820t_init / r82xx_init equivalent)
        when (_tunerType) {
            TunerType.R828D, TunerType.R820T, TunerType.R820T2 -> r82xxInit()
            else -> Unit
        }

        setI2cRepeater(false)

        // Apply defaults
        setSampleRate(_sampleRate)
        setCenterFrequency(_centerFreqHz)
        setGainMode(_gainMode)
        if (!forceBiasTee) setBiasTee(_biasTee)
    }

    /**
     * Initialize RTL2832U baseband.
     * Source: librtlsdr.c rtlsdr_init_baseband() — faithful line-by-line port.
     */
    private fun initBaseband() {
        // Initialize USB
        writeReg(BLOCK_USB, USB_SYSCTL,     0x09,   1)
        // USB_EPA_MAXPKT = 512 bytes (USB 2.0 high-speed bulk max packet size).
        // The USB/SYS block registers are little-endian, so writeReg encodes 2-byte
        // values LSB-first.  The C driver encodes those same values MSB-first (via
        // rtlsdr_write_reg data[0]=val>>8, data[1]=val&0xff), passing the
        // byte-swapped constant 0x0002 to produce the same [0x00, 0x02] wire bytes
        // that the LE-encoder produces for 0x0200.  We pass the actual intended
        // value 0x0200 (= 512) and let the LE encoder place [0x00, 0x02] on the
        // wire, which the RTL2832U reads as LE 0x0200 = 512. ✓
        // NOTE: the demod registers (writeDemodReg) are big-endian — a separate
        // issue fixed in writeDemodReg itself.
        writeReg(BLOCK_USB, USB_EPA_MAXPKT, 0x0200, 2)
        writeReg(BLOCK_USB, USB_EPA_CTL,    0x1002, 2)

        // Power on demod
        writeReg(BLOCK_SYS, REG_DEMOD_CTL_1, 0x22, 1)
        writeReg(BLOCK_SYS, REG_DEMOD_CTL,   0xe8, 1)

        // Reset demod (bit 3 = soft_rst)
        writeDemodReg(1, 0x01, 0x14, 1)
        writeDemodReg(1, 0x01, 0x10, 1)

        // Disable spectrum inversion and adjacent channel rejection
        writeDemodReg(1, 0x15, 0x00, 1)
        writeDemodReg(1, 0x16, 0x0000, 2)

        // Clear DDC shift and IF frequency registers
        for (i in 0 until 6) writeDemodReg(1, 0x16 + i, 0x00, 1)

        // Program default FIR coefficients
        setFir()

        // Enable SDR mode, disable DAGC (bit 5)
        writeDemodReg(0, 0x19, 0x05, 1)

        // Init FSM state-holding registers
        writeDemodReg(1, 0x93, 0xf0, 1)
        writeDemodReg(1, 0x94, 0x0f, 1)

        // Disable AGC (en_dagc, bit 0)
        writeDemodReg(1, 0x11, 0x00, 1)

        // Disable RF and IF AGC loop
        writeDemodReg(1, 0x04, 0x00, 1)

        // Disable PID filter (enable_PID = 0)
        writeDemodReg(0, 0x61, 0x60, 1)

        // opt_adc_iq = 0, default ADC_I/ADC_Q datapath
        writeDemodReg(0, 0x06, 0x80, 1)

        // Enable Zero-IF mode, DC cancellation, IQ estimation/compensation
        writeDemodReg(1, 0xb1, 0x1b, 1)

        // Disable 4.096 MHz clock output on pin TP_CK0
        writeDemodReg(0, 0x0d, 0x83, 1)
    }

    /**
     * Program RTL2832U FIR filter with default coefficients.
     * Source: librtlsdr.c rtlsdr_set_fir() with fir_default[].
     */
    private fun setFir() {
        // Default FIR: -54,-36,-41,-40,-32,-14,14,53 (int8) + 101,156,215,273,327,372,404,421 (int12)
        val firDefault = intArrayOf(
            -54, -36, -41, -40, -32, -14, 14, 53,
            101, 156, 215, 273, 327, 372, 404, 421
        )
        val fir = ByteArray(20)
        for (i in 0 until 8) fir[i] = firDefault[i].toByte()
        for (i in 0 until 8 step 2) {
            val v0 = firDefault[8 + i]
            val v1 = firDefault[8 + i + 1]
            fir[8 + i * 3 / 2]     = (v0 shr 4).toByte()
            fir[8 + i * 3 / 2 + 1] = ((v0 shl 4) or ((v1 shr 8) and 0x0f)).toByte()
            fir[8 + i * 3 / 2 + 2] = v1.toByte()
        }
        for (i in fir.indices) writeDemodReg(1, 0x1c + i, fir[i].toInt() and 0xFF, 1)
    }

    /**
     * Apply RTL2832U demod settings for R82xx tuners.
     * Source: librtlsdr.c rtlsdr_open() case RTLSDR_TUNER_R828D / R820T.
     */
    private fun applyR82xxDemodSettings() {
        // Disable Zero-IF mode
        writeDemodReg(1, 0xb1, 0x1a, 1)
        // Only enable In-phase ADC input
        writeDemodReg(0, 0x08, 0x4d, 1)
        // Set RTL2832U DDC to the tuner's physical IF output center (3.57 MHz).
        setIfFreq(R82XX_IF_FREQ_DEMOD)
        // Enable spectrum inversion
        writeDemodReg(1, 0x15, 0x01, 1)
    }

    /**
     * Enable/disable the RTL2832U I2C repeater bridge.
     * Source: librtlsdr.c rtlsdr_set_i2c_repeater()
     *   writeDemodReg(1, 0x01, on ? 0x18 : 0x10, 1)
     */
    private fun setI2cRepeater(enable: Boolean) {
        writeDemodReg(1, 0x01, if (enable) 0x18 else 0x10, 1)
    }

    private fun detectTuner(): TunerType {
        setI2cRepeater(true)

        // Source: librtlsdr.c rtlsdr_open() tuner probe sequence
        // E4000
        if (i2cRead(0xC8, 0x02) == 0x40) {
            setI2cRepeater(false); return TunerType.E4000
        }
        // FC0013
        if (i2cRead(0xC6, 0x00) == 0xA3) {
            setI2cRepeater(false); return TunerType.FC0013
        }
        // R820T / R820T2 at 0x34
        if (i2cRead(R820T2_I2C_ADDR, 0x00) == R82XX_CHIP_ID) {
            setI2cRepeater(false); return TunerType.R820T2
        }
        // R828D at 0x74 (RTL-SDR Blog V4)
        if (i2cRead(R828D_I2C_ADDR, 0x00) == R82XX_CHIP_ID) {
            setI2cRepeater(false); return TunerType.R828D
        }
        // GPIO4 hard-reset before probing FC2580/FC0012
        setGpioOutput(4); setGpio(4, true); setGpio(4, false)
        // FC2580 — mask bit7
        if ((i2cRead(0xAC, 0x01) and 0x7F) == 0x56) {
            setI2cRepeater(false); return TunerType.FC2580
        }
        // FC0012
        if (i2cRead(0xC6, 0x00) == 0xA1) {
            setI2cRepeater(false); return TunerType.FC0012
        }

        setI2cRepeater(false)
        return TunerType.UNKNOWN
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  R82xx TUNER INITIALIZATION  (faithful port of r82xx_init)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * R82xx initialization register array (shadow: regs 0x05–0x1F).
     * Source: tuner_r82xx.c r82xx_init_array[NUM_REGS].
     * Indices 0–26 correspond to physical registers 0x05–0x1F.
     */
    private val r82xxInitArray = intArrayOf(
        0x83, 0x30, 0x75,                         // 0x05 – 0x07
        0xC0, 0x40, 0xD6, 0x6C,                   // 0x08 – 0x0B
        0xF5, 0x63, 0x75, 0x68,                   // 0x0C – 0x0F
        0x6C, 0x83, 0x80, 0x00,                   // 0x10 – 0x13
        0x0F, 0x00, 0xC0, 0x30,                   // 0x14 – 0x17
        0x48, 0xCC, 0x60, 0x00,                   // 0x18 – 0x1B
        0x54, 0xAE, 0x4A, 0xC0                    // 0x1C – 0x1F
    )

    /**
     * Initialize the R82xx tuner.
     * Source: tuner_r82xx.c r82xx_init().
     * Writes init registers, runs TV-standard calibration, selects system frequency.
     */
    private fun r82xxInit() {
        val addr = tunerAddr()
        setI2cRepeater(true)

        // priv->xtal_cap_sel = XTAL_HIGH_CAP_0P (index 4)
        // Initialize shadow registers
        for (i in r82xxInitArray.indices) r82xxShadow[i] = r82xxInitArray[i]

        // Write initialization registers starting at 0x05
        // Source: r82xx_write(priv, 0x05, r82xx_init_array, sizeof(r82xx_init_array))
        r82xxWriteBurst(addr, 0x05, r82xxInitArray)

        // BUG FIX (V4 HF regression): the burst write above resets registers
        // 0x05/0x06 — the same registers r828dSetFreq() uses for the V4
        // three-input band switch (cable_2_in / cable_1_in / air_in) — back to
        // their power-on defaults, and also leaves GPIO-5 (the upconverter
        // switch) in whatever state it was last driven to. r828dSetFreq() only
        // rewrites those registers "on band change" (`if (band != _currentBand)`),
        // so if the dial frequency's band hasn't changed since the last tune,
        // the stale _currentBand cache makes it skip rewriting them — leaving
        // the hardware in the post-init (non-HF) input path even though the
        // tuner was just told the dial is still on HF.
        //
        // r82xxInit() is called from initDevice() (fine — _currentBand is still
        // BAND_UNKNOWN at first boot) AND from setDirectSampling(OFF), which
        // fires on every demod-mode switch that doesn't require direct
        // sampling (DspEngine.setDemodMode()). On RTL-SDR Blog V4 hardware via
        // the USB source this silently disconnected the HF input path on the
        // very next mode change after the app/tuner was initialized, while the
        // TCP source (talking to an external rtl_tcp server running the real
        // librtlsdr) was unaffected — matching the "HF broken on USB, works on
        // TCP" symptom.
        //
        // Fix: force the next r828dSetFreq() call to unconditionally rewrite
        // the band-switch registers and GPIO-5 by invalidating the cache here.
        _currentBand = BAND_UNKNOWN

        // r82xx_set_tv_standard (calibration + standard setup)
        r82xxSetTvStandard(addr)

        // r82xx_sysfreq_sel with TUNER_DIGITAL_TV / SYS_DVBT
        r82xxSysfreqSel(addr)

        setI2cRepeater(false)
    }

    /**
     * R82xx TV-standard / calibration setup.
     * Source: tuner_r82xx.c r82xx_set_tv_standard() with if_khz=3570, bw=3 (SDR mode).
     * This runs the filter calibration loop required for correct bandwidth.
     */
    private fun r82xxSetTvStandard(addr: Int) {
        // For SDR (BW < 6 MHz) mode:
        val ifKhz = 3570
        val filtCalLo = 56_000   // kHz — calibration LO
        val filtGain    = 0x30   // +3dB, 6MHz on
        val imgR        = 0x00   // image negative
        val filtQ       = 0x10   // r10[4]: low Q
        val hpCor       = 0x6B   // 1.7m disable, +2cap, 1.0 MHz
        val extEnable   = 0x60   // r30[6]=1 ext enable; r30[5]=1 ext at lna max-1
        val loopThrough = 0x80   // r5[7]: LT off
        val ltAtt       = 0x00   // r31[7]: LT att enable
        val fltExtWidest= 0x00   // r15[7]: flt_ext_wide off
        val polyfil_cur = 0x60   // r25[6:5]: min

        // Re-copy init array into shadow
        for (i in r82xxInitArray.indices) r82xxShadow[i] = r82xxInitArray[i]

        // Init Flag & Xtal_check Result (VGA gain)
        r82xxWriteMask(addr, 0x0C, 0x00, 0x0F)

        // version (VER_NUM = 49 = 0x31, mask 0x3F)
        r82xxWriteMask(addr, 0x13, 49, 0x3F)

        // for LT Gain test (TUNER_DIGITAL_TV)
        r82xxWriteMask(addr, 0x1D, 0x00, 0x38)

        // Filter calibration loop (need_calibration=1 always in librtlsdr)
        repeat(2) { iteration ->
            // Set filt_cap
            r82xxWriteMask(addr, 0x0B, hpCor, 0x60)
            // Set cali clk on
            r82xxWriteMask(addr, 0x0F, 0x04, 0x04)
            // X'tal cap 0pF for PLL
            r82xxWriteMask(addr, 0x10, 0x00, 0x03)
            // Set PLL to calibration frequency
            r82xxSetPll(addr, filtCalLo * 1000L)
            // Start trigger
            r82xxWriteMask(addr, 0x0B, 0x10, 0x10)
            // Stop trigger
            r82xxWriteMask(addr, 0x0B, 0x00, 0x10)
            // Set cali clk off
            r82xxWriteMask(addr, 0x0F, 0x00, 0x04)
            // Read calibration result
            val data = r82xxRead(addr, 0x00, 5)
            filCalCode = data[4] and 0x0F
            if (filCalCode != 0 && filCalCode != 0x0F) return@repeat
        }
        if (filCalCode == 0x0F) filCalCode = 0   // narrowest

        // r82xx_write_reg_mask(priv, 0x0A, filt_q | fil_cal_code, 0x1F)
        r82xxWriteMask(addr, 0x0A, filtQ or filCalCode, 0x1F)
        // Set BW, Filter_gain, HP corner
        r82xxWriteMask(addr, 0x0B, hpCor, 0xEF)
        // Set Img_R
        r82xxWriteMask(addr, 0x07, imgR, 0x80)
        // Set filt_3dB, V6MHz
        r82xxWriteMask(addr, 0x06, filtGain, 0x30)
        // channel filter extension
        r82xxWriteMask(addr, 0x1E, extEnable, 0x60)
        // Loop through
        r82xxWriteMask(addr, 0x05, loopThrough, 0x80)
        // Loop through attenuation
        r82xxWriteMask(addr, 0x1F, ltAtt, 0x80)
        // filter extension widest
        r82xxWriteMask(addr, 0x0F, fltExtWidest, 0x80)
        // RF poly filter current
        r82xxWriteMask(addr, 0x19, polyfil_cur, 0x60)
    }

    /**
     * R82xx system frequency selection for SDR mode.
     * Source: tuner_r82xx.c r82xx_sysfreq_sel() with TUNER_DIGITAL_TV / SYS_DVBT.
     */
    private fun r82xxSysfreqSel(addr: Int) {
        // Default DVB-T 8M values (used for SDR):
        val mixerTop    = 0x24   // mixer top:13
        val lnaTop      = 0xE5   // detect bw 3, lna top:4, predet top:2
        val lnaVthL     = 0x53   // lna vth 0.84, vtl 0.64
        val mixerVthL   = 0x75   // mixer vth 1.04, vtl 0.84
        val lnaDischarge = 14
        val cpCur       = 0x38   // 111, auto
        // RTL-SDR Blog Hack: improve L-band perf — set PLL dropout to 2.0V
        val divBufCur   = 0xA0
        val filterCur   = 0x40   // 10, low

        r82xxWriteMask(addr, 0x1D, lnaTop,    0xC7)
        r82xxWriteMask(addr, 0x1C, mixerTop,  0xF8)
        r82xxWrite(addr, 0x0D, lnaVthL)
        r82xxWrite(addr, 0x0E, mixerVthL)

        // Air-IN (0x00), Cable2 (0x00) for non-Astrometa
        r82xxWriteMask(addr, 0x05, 0x00, 0x60)
        r82xxWriteMask(addr, 0x06, 0x00, 0x08)

        r82xxWriteMask(addr, 0x11, cpCur,     0x38)
        r82xxWriteMask(addr, 0x17, divBufCur, 0x30)   // RTL-SDR Blog PLL dropout hack
        r82xxWriteMask(addr, 0x0A, filterCur, 0x60)

        // LNA TOP: lowest (TUNER_DIGITAL_TV path)
        r82xxWriteMask(addr, 0x1D, 0, 0x38)
        // normal mode
        r82xxWriteMask(addr, 0x1C, 0, 0x04)
        // PRE_DECT off
        r82xxWriteMask(addr, 0x06, 0, 0x40)
        // AGC clk 250 Hz
        r82xxWriteMask(addr, 0x1A, 0x30, 0x30)
        // Write LNA TOP = 3
        r82xxWriteMask(addr, 0x1D, 0x18, 0x38)
        // discharge mode
        r82xxWriteMask(addr, 0x1C, mixerTop, 0x04)
        // LNA discharge current
        r82xxWriteMask(addr, 0x1E, lnaDischarge, 0x1F)
        // AGC clk 60 Hz
        r82xxWriteMask(addr, 0x1A, 0x20, 0x30)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  R82xx PLL  (faithful port of r82xx_set_pll from tuner_r82xx.c)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Set the R82xx PLL to [freqHz].
     * Source: tuner_r82xx.c r82xx_set_pll() — SDM-based fractional-N PLL.
     *
     * The upstream algorithm:
     *  1. Choose mix_div (2,4,8,…64) so freq_kHz × mix_div ∈ [vco_min, vco_max).
     *     vco_min = 1 770 000 kHz, vco_max = 3 540 000 kHz.
     *  2. Fine-tune div_num from vco_fine_tune register.
     *  3. Compute nint, vco_fra and encode via 16-bit SDM word.
     *  4. Write: div_num → reg 0x10 [7:5]; ni,si → reg 0x14; sdm → regs 0x15-0x16.
     *
     * ── Precision note ──────────────────────────────────────────────────────────
     * The original librtlsdr port computed nint and vco_fra in kHz, discarding the
     * sub-kHz portion of [freqHz] before the SDM calculation.  At 100 MHz this meant
     * a PPM correction of <10 ppm produced zero LO shift (1 ppm × 100 MHz = 100 Hz,
     * invisible below the 1 kHz truncation boundary), causing the "no effect then
     * sudden jump" symptom observed on the PPM slider.
     *
     * Fix: all VCO arithmetic now uses Hz (Long), so the SDM captures the full
     * fractional part.  The SDM 16-bit word gives 2×pllRef/65536/mixDiv Hz resolution
     * at the output port — typically 24–440 Hz depending on band.  mix_div selection
     * still uses kHz (only MHz-level band boundaries matter there).
     *
     * Returns true if PLL locked.
     */
    private fun r82xxSetPll(addr: Int, freqHz: Long): Boolean {
        val freqKhz    = ((freqHz + 500) / 1000).toInt()   // kHz — used only for mix_div selection
        val pllRef     = _tunerXtalFreq                     // Hz

        // refdiv2 = 0 (no reference divider)
        r82xxWriteMask(addr, 0x10, 0, 0x10)

        // PLL autotune = 128 kHz
        r82xxWriteMask(addr, 0x1A, 0x00, 0x0C)

        // RTL-SDR Blog modification: set VCO current to MAX (0x06, full 8-bit mask)
        r82xxWriteMask(addr, 0x12, 0x06, 0xFF)

        // ── Choose mix_div ──────────────────────────────────────────────────────
        // Band boundary check only needs kHz precision — no sub-kHz impact here.
        val vcoMin = 1_770_000   // kHz
        val vcoMax = vcoMin * 2  // kHz
        var mixDiv = 2
        var divBuf = 0
        var divNum = 0
        while (mixDiv <= 64) {
            if (freqKhz.toLong() * mixDiv >= vcoMin && freqKhz.toLong() * mixDiv < vcoMax) {
                divBuf = mixDiv
                while (divBuf > 2) { divBuf = divBuf shr 1; divNum++ }
                break
            }
            mixDiv = mixDiv shl 1
        }

        // Read vco_fine_tune from register 0x00 (data[4] bits [5:4])
        val data = r82xxRead(addr, 0x00, 5)
        // R828D and V4L: vco_power_ref = 1; others: vco_power_ref = 2
        val vcoPowerRef = if (_tunerType == TunerType.R828D) 1 else 2
        val vcoFineTune = (data[4] and 0x30) shr 4
        if (vcoFineTune > vcoPowerRef) divNum--
        else if (vcoFineTune < vcoPowerRef) divNum++

        // Write div_num → reg 0x10 bits [7:5]
        r82xxWriteMask(addr, 0x10, divNum shl 5, 0xE0)

        // ── Compute nint, vco_fra using SDM — Hz-precision arithmetic ───────────
        //
        // Previous code: vcoFreqKhz = freqKhz * mixDiv  (loses sub-kHz portion of freqHz)
        //                vcoFraKhz  = vcoFreqKhz - 2*pllRefKhz*nint
        // Fix: work in Hz throughout so the SDM encodes the full fractional remainder.
        //
        // freqHz * mixDiv can reach ~1766 MHz × 64 = ~113 GHz — must use Long.
        // vcoFraHz = vcoFreqHz − 2×pllRef×nint: max ≈ 2×28.8 MHz = 57.6 MHz → fits Int.
        val vcoFreqHz = freqHz * mixDiv.toLong()
        val nint      = (vcoFreqHz / (2L * pllRef)).toInt()
        val vcoFraHz  = (vcoFreqHz - 2L * pllRef * nint).toInt()   // max ~57.6 MHz, fits Int

        // Validate: nint must fit in 7 bits (max 127 / vco_power_ref - 1)
        if (nint > (128 / vcoPowerRef - 1)) {
            Log.w(TAG, "[R82XX] No valid PLL values for ${freqHz} Hz!")
            return false
        }

        val ni = (nint - 13) / 4
        val si = nint - 4 * ni - 13
        r82xxWrite(addr, 0x14, ni + (si shl 6))

        // pw_sdm: if no fractional part (Hz-level), set bit 3 to disable SDM noise
        r82xxWriteMask(addr, 0x12, if (vcoFraHz == 0) 0x08 else 0x00, 0x08)

        // SDM calculator: encode fractional VCO Hz remainder as 16-bit word.
        // Each iteration tests whether the remaining fraction exceeds the current
        // SDM weight (2×pllRef/nSdm Hz) and accumulates the corresponding bit.
        // This is a binary approximation of vcoFraHz / (2×pllRef) scaled to 65536.
        // Resolution at PLL output = 2×pllRef / 65536 / mixDiv Hz
        //   (e.g. 100 MHz band, mixDiv≈36: ≈24 Hz; 1 GHz band, mixDiv≈2: ≈440 Hz).
        var nSdm = 2
        var sdm = 0
        var fra = vcoFraHz                      // Hz, not kHz
        while (fra > 1) {
            val sdmStep = 2 * pllRef.toInt() / nSdm   // Hz per SDM weight (max 28.8 MHz, fits Int)
            if (fra > sdmStep) {
                sdm += 32768 / (nSdm / 2)
                fra -= sdmStep
                if (nSdm >= 0x8000) break
            }
            nSdm = nSdm shl 1
        }

        r82xxWrite(addr, 0x16, sdm shr 8)
        r82xxWrite(addr, 0x15, sdm and 0xFF)

        // Allow PLL to settle then verify lock (two attempts)
        Thread.sleep(5)
        var locked = false
        for (attempt in 0 until 2) {
            val lockData = r82xxRead(addr, 0x00, 3)
            if (lockData[2] and 0x40 != 0) { locked = true; break }
            if (attempt == 0) {
                // Increase VCO current (RTL-SDR Blog: set max)
                r82xxWriteMask(addr, 0x12, 0x06, 0xFF)
            }
        }

        if (!locked) {
            Log.w(TAG, "[R82XX] PLL not locked for ${freqHz} Hz!")
        } else {
            // PLL autotune = 8 kHz (post-lock fine mode)
            r82xxWriteMask(addr, 0x1A, 0x08, 0x08)
        }
        return locked
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  R82xx MUX (tracking filter + RF MUX)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Configure R82xx tracking filter / RF MUX for [loFreqHz].
     * Source: tuner_r82xx.c r82xx_set_mux().
     */
    private fun r82xxSetMux(addr: Int, loFreqHz: Long) {
        val freqMHz = (loFreqHz / 1_000_000L).toInt()

        // Find the highest freq_range entry whose .freq <= freqMHz
        var rangeIdx = 0
        for (i in 0 until FREQ_RANGES.size - 1) {
            if (freqMHz < FREQ_RANGES[i + 1].freqMHz) break
            rangeIdx = i + 1
        }
        // Back off to last entry that fits
        for (i in 0 until FREQ_RANGES.size - 1) {
            if (freqMHz < FREQ_RANGES[i + 1].freqMHz) { rangeIdx = i; break }
        }
        val range = FREQ_RANGES[rangeIdx]

        // Open Drain (reg 0x17 bit 3)
        r82xxWriteMask(addr, 0x17, range.openD, 0x08)
        // RF_MUX, Polymux (reg 0x1A bits [7:6],[1:0])
        r82xxWriteMask(addr, 0x1A, range.rfMuxPloy, 0xC3)
        // TF BAND (reg 0x1B)
        r82xxWrite(addr, 0x1B, range.tfC)
        // XTAL CAP & Drive — V4 uses XTAL_HIGH_CAP_0P (no extra cap, reg val = xtal_cap0p | 0x00)
        val xtalCapVal = range.xtalCap0p or 0x00   // XTAL_HIGH_CAP_0P path
        r82xxWriteMask(addr, 0x10, xtalCapVal, 0x0B)
        // Clear IF mixer harmonics
        r82xxWriteMask(addr, 0x08, 0x00, 0x3F)
        r82xxWriteMask(addr, 0x09, 0x00, 0x3F)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  R82xx SET FREQUENCY  (faithful port of r82xx_set_freq)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Configure R82xx PLL + RF front-end for [reqFreqHz].
     * Source: tuner_r82xx.c r82xx_set_freq() + r82xx_set_mux() + r82xx_set_pll().
     *
     * V4 specific (isV4 && R828D):
     *  - Auto-upconvert HF ≤ 28.8 MHz by adding XTAL_FREQ
     *  - Three-input band switch (HF/VHF/UHF)
     *  - GPIO-5 upconverter switch
     *  - Notch filter via reg 0x17 bit 3
     *  - Tracking filter bypass for HF band
     *
     * Returns true if PLL locked.
     */
    private fun r828dSetFreq(reqFreqHz: Long, sampleRate: Int): Boolean {
        setI2cRepeater(true)
        val addr = tunerAddr()
        val isV4active = _isV4 && _tunerType == TunerType.R828D

        // Auto-upconvert HF on V4/V4L
        val upconvertFreqHz = if ((isV4active) && reqFreqHz < HF_CUTOFF_HZ)
            reqFreqHz + XTAL_FREQ else reqFreqHz

        // LO = upconvert_freq + int_freq (3.57 MHz)
        val loFreqHz = upconvertFreqHz + R82XX_IF_FREQ

        // Set RF MUX / tracking filter
        r82xxSetMux(addr, loFreqHz)

        // Set VGA gain (source: r82xx_set_vga_gain — fixed 16.3 dB)
        r82xxWriteMask(addr, 0x0C, 0x08, 0x9F)

        // Set PLL
        val locked = r82xxSetPll(addr, loFreqHz)

        if (isV4active) {
            // ── V4 notch filter ──────────────────────────────────────────────────────
            // open_d = 0x00 when within a notch band; 0x08 otherwise
            val openD = if (
                reqFreqHz <= V4_NOTCH_HF_MAX_HZ ||
                (reqFreqHz >= V4_NOTCH_FM_MIN_HZ  && reqFreqHz <= V4_NOTCH_FM_MAX_HZ) ||
                (reqFreqHz >= V4_NOTCH_VHF_MIN_HZ && reqFreqHz <= V4_NOTCH_VHF_MAX_HZ)
            ) 0x00 else 0x08
            r82xxWriteMask(addr, 0x17, openD, 0x08)

            // ── V4 three-input band switch ───────────────────────────────────────────
            val band = when {
                reqFreqHz <= HF_CUTOFF_HZ        -> BAND_HF
                reqFreqHz < VHF_UHF_BOUNDARY_HZ -> BAND_VHF
                else                             -> BAND_UHF
            }

            // Tracking filter bypass for HF (insertion loss reduction)
            // Source: tuner_r82xx.c — "bypass tracking filter for HF"
            if (band == BAND_HF) {
                r82xxWriteMask(addr, 0x1A, 0x40, 0xC3)
                r82xxWrite(addr, 0x1B, 0x00)
            }

            // Only write input-path registers on band change
            if (band != _currentBand) {
                _currentBand = band

                // cable_2 = HF input: reg 0x06 bit 3
                val cable2In = if (band == BAND_HF) 0x08 else 0x00
                r82xxWriteMask(addr, 0x06, cable2In, 0x08)

                // GPIO-5: LOW when HF (upconverter active), HIGH otherwise
                // Source: rtlsdr_set_bias_tee_gpio(priv->rtl_dev, 5, !cable_2_in)
                setGpioOutput(UPCONV_GPIO_PIN)
                setGpio(UPCONV_GPIO_PIN, band != BAND_HF)

                // cable_1 = VHF input: reg 0x05 bit 6
                val cable1In = if (band == BAND_VHF) 0x40 else 0x00
                r82xxWriteMask(addr, 0x05, cable1In, 0x40)

                // air_in = UHF input: reg 0x05 bit 5 active-low
                val airIn = if (band == BAND_UHF) 0x00 else 0x20
                r82xxWriteMask(addr, 0x05, airIn, 0x20)
            }

        } else if (_tunerType == TunerType.R828D) {
            // ── Standard R828D two-input switching at 345 MHz ──────────────────────
            val airCable1In = if (reqFreqHz > R828D_BAND_BOUNDARY_HZ) 0x00 else 0x60
            r82xxWriteMask(addr, 0x05, airCable1In, 0x60)
        }

        // ── IF bandwidth filter ──────────────────────────────────────────────────
        // Source: r82xx_set_bandwidth() adapted for SDR use.
        // In librtlsdr, r820t_set_bw() is called from rtlsdr_set_sample_rate().
        // We approximate here with a rate-matched filter selection.
        val bwHz = sampleRate
        val (reg0a, reg0b) = when {
            bwHz > 7_000_000 -> Pair(0x10, 0x0B)   // 8 MHz
            bwHz > 6_000_000 -> Pair(0x10, 0x2A)   // 7 MHz
            bwHz > 2_730_000 -> Pair(0x10, 0x6B)   // 6 MHz
            else             -> Pair(0x00, 0x80)   // ≤ 6 MHz, narrowband
        }
        r82xxWriteMask(addr, 0x0A, reg0a, 0x10)
        r82xxWriteMask(addr, 0x0B, reg0b, 0xEF)

        setI2cRepeater(false)

        // ── DDC IF register — nominal IF centre ──────────────────────────────────
        //
        // The PLL SDM works in Hz (see r82xxSetPll), so it accurately tracks the
        // requested loFreqHz with sub-kHz precision (≈7–440 Hz LSB depending on
        // band/mixDiv).  The correct DDC IF value is therefore always the nominal
        // R82XX_IF_FREQ_DEMOD (3.570 MHz) — i.e. the hardware demodulator centre.
        //
        // Historical note: an earlier version of this code applied a sub-kHz
        // correction here:
        //
        //   loFreqKhzRounded = round(loFreqHz / 1000) × 1000
        //   ddcIfHz = R82XX_IF_FREQ_DEMOD + (loFreqHz − loFreqKhzRounded)
        //
        // That correction made sense when the PLL arithmetic was done in kHz and
        // the SDM only captured the kHz-level remainder, leaving a ±500 Hz "dead
        // zone" at every kHz boundary.  The DDC shift was designed to paper over
        // that gap.
        //
        // After the PLL was converted to Hz arithmetic the SDM already encodes the
        // full sub-kHz fractional part, so the PLL output tracks loFreqHz exactly
        // (within ±½ SDM step).  Applying the old kHz-rounding DDC offset on top
        // of an already-accurate PLL introduced an equal-and-opposite error:
        // every sub-kHz frequency change moved the DDC by the same amount in the
        // opposite direction, cancelling the PLL's sub-kHz precision and producing
        // the "no effect below 1 kHz, then a sudden jump" symptom.
        //
        // Fix: always use the nominal IF centre; no sub-kHz DDC correction needed.
        setIfFreq(R82XX_IF_FREQ_DEMOD)

        return locked
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PUBLIC API — FREQUENCY CONTROL
    // ─────────────────────────────────────────────────────────────────────────

    fun setCenterFrequency(freqHz: Long): Boolean {
        if (!isOpen) return false
        val clamped  = freqHz.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ)
        // Use round-half-away-from-zero instead of toLong() truncation-toward-zero.
        // Truncation discarded the fractional Hz remainder on every call, which is
        // harmless at large |ppm| but biases small corrections toward 0 and can
        // erase up to ~1 Hz of an already-tiny offset — rounding keeps the full
        // precision the Hz-based PLL math below is able to resolve.
        val corrected = clamped + Math.round(clamped.toDouble() * _ppmCorrection / 1_000_000.0)

        val ok = when (_tunerType) {
            TunerType.R828D, TunerType.R820T, TunerType.R820T2 ->
                r828dSetFreq(corrected, _sampleRate)
            else -> false
        }

        _centerFreqHz = clamped
        _statusFlow.value = _statusFlow.value.copy(centerFreqHz = clamped)
        return ok
    }

    fun getCenterFrequency(): Long = _centerFreqHz

    // ─────────────────────────────────────────────────────────────────────────
    //  PUBLIC API — SAMPLE RATE
    // ─────────────────────────────────────────────────────────────────────────

    fun setSampleRate(rateHz: Int): Boolean {
        if (!isOpen) return false
        // Snap to the nearest valid entry within the correct band.
        // The RTL-SDR supports a low-rate band (225–300 kS/s) and a main band
        // (900 kS/s – 3.2 MS/s) separated by a dead zone where the demod ratio
        // aliases.  nearestSampleRate() keeps the value within the caller's
        // intended band instead of crossing into the dead zone.
        val rate = nearestSampleRate(rateHz)

        // Source: librtlsdr.c rtlsdr_set_sample_rate()
        //
        // rsampRatio is the 28-bit aligned register value written to the RTL2832U demod.
        // For the low-rate band (225–300 kS/s), back-calculating XTAL*2^22/rsampRatio
        // yields a value that differs from the requested rate (e.g. 562 500 Hz for a
        // 250 000 Hz request) because the 0x0FFFFFFC mask truncates the full 29-bit ratio.
        // The hardware USB streaming rate equals the REQUESTED rate (after quantisation to
        // the nearest valid rsampRatio), not the back-calculated value.  Use `rate` (the
        // nearestSampleRate-quantised request) so that _sampleRate, the DebugBus chain
        // snapshot, and the DSP engine all agree with the UI and with what the host
        // actually receives over USB.
        val rsampRatio = (XTAL_FREQ * (1L shl 22) / rate) and 0x0FFFFFFCL

        writeDemodReg(1, 0x9F, ((rsampRatio shr 16) and 0xFFFF).toInt(), 2)
        writeDemodReg(1, 0xA1, (rsampRatio and 0xFFFF).toInt(), 2)

        // Bandwidth filter — based on rate (the configured USB streaming rate)
        // Source: r820t_set_bw() / r82xx_set_bandwidth()
        val addr = tunerAddr()
        if (_tunerType == TunerType.R828D || _tunerType == TunerType.R820T2 || _tunerType == TunerType.R820T) {
            setI2cRepeater(true)
            val (reg0a, reg0b) = when {
                rate > 7_000_000 -> Pair(0x10, 0x0B)
                rate > 6_000_000 -> Pair(0x10, 0x2A)
                rate > 2_730_000 -> Pair(0x10, 0x6B)
                else             -> Pair(0x00, 0x80)
            }
            r82xxWriteMask(addr, 0x0A, reg0a, 0x10)
            r82xxWriteMask(addr, 0x0B, reg0b, 0xEF)
            setI2cRepeater(false)
        }

        // PPM correction — use unsigned shift to avoid sign-extension on negative offsets.
        // Scale factor is (1 shl 22) = 4_194_304, matching librtlsdr.c
        // rtlsdr_set_sample_freq_correction(): offs = (int)(-ppm * (1<<22) / 1e6).
        // Using (1 shl 24) here was a 4× overcorrection that also overflowed the
        // 14-bit register for |ppm| ≥ 4.  Double arithmetic avoids integer-division
        // rounding error present in the original expression.
        val ppm = _ppmCorrection
        val offs = Math.round(-ppm.toDouble() * (1 shl 22) / 1_000_000.0).toInt().toShort().toInt()
        writeDemodReg(1, 0x3F,  offs         and 0xFF, 1)   // low  byte [7:0]
        writeDemodReg(1, 0x3E, (offs ushr 8) and 0x3F, 1)   // high byte [13:8]

        // Demod soft-reset to latch new rate
        writeDemodReg(1, 0x01, 0x14, 1)
        writeDemodReg(1, 0x01, 0x10, 1)

        // NOTE: do NOT reset the USB endpoint here.  librtlsdr's rtlsdr_set_sample_rate()
        // does not call reset_buffer() after a rate change — the demod soft-reset above
        // is sufficient to latch the new RSAMP_RATIO.  A partial endpoint reset fired
        // asynchronously while bulkTransfer() is in-flight causes the streaming loop to
        // collect −1 errors and retry with 3 s timeouts (up to 10 × 3 s = 30 s stall).
        // The DSP pipeline rebuilds its PolyphaseResampler lazily on the next IQ block.

        _sampleRate = rate
        _statusFlow.value = _statusFlow.value.copy(sampleRate = _sampleRate)
        return true
    }

    fun getSampleRate(): Int = _sampleRate

    // ─────────────────────────────────────────────────────────────────────────
    //  PUBLIC API — GAIN CONTROL
    // ─────────────────────────────────────────────────────────────────────────

    fun setGainMode(mode: Int) {
        _gainMode = mode
        when (mode) {
            GAIN_MODE_AGC -> {
                _hardwareAgcEnabled = true
                _tunerAgcEnabled    = true
                setAgc(true)
                setTunerAgc(true)
            }
            GAIN_MODE_MANUAL -> {
                _hardwareAgcEnabled = false
                _tunerAgcEnabled    = false
                setAgc(false)
                setTunerAgc(false)
                setGain(_gain)
            }
        }
        _statusFlow.value = _statusFlow.value.copy(
            gainMode           = _gainMode,
            tunerAgcEnabled    = _tunerAgcEnabled,
            hardwareAgcEnabled = _hardwareAgcEnabled
        )
    }

    /**
     * Set manual gain by index into [GAIN_TABLE_DB_TENTHS].
     * Source: tuner_r82xx.c r82xx_set_gain() — greedy LNA-first allocation.
     *
     * All gain values — target AND step accumulator — are in TENTHS of dB,
     * matching the unit used throughout tuner_r82xx.c.  The previous code
     * computed  targetDb = targetDb10 / 10  (integer dB) but then compared
     * it against totalGain which accumulated tenths of dB from LNA_GAIN_STEPS
     * and MIX_GAIN_STEPS.  That 10× unit mismatch caused the loop to exit on
     * the very first or second step for every gain level, collapsing all 29
     * gain positions to at most LNA index 3 / mixer index 2 — meaning the
     * slider had virtually no effect on actual hardware gain.
     */
    fun setGain(gainIndex: Int) {
        _gain = gainIndex.coerceIn(0, GAIN_TABLE_DB_TENTHS.lastIndex)
        val addr = tunerAddr()
        // Target in TENTHS of dB — same unit as LNA_GAIN_STEPS / MIX_GAIN_STEPS.
        // Do NOT divide by 10; the reference r82xx_set_gain() uses tenths throughout.
        val targetDb10 = GAIN_TABLE_DB_TENTHS[_gain]

        setI2cRepeater(true)

        // LNA auto off (bit 4 = 1)
        r82xxWriteMask(addr, 0x05, 0x10, 0x10)
        // Mixer auto off (bit 4 = 0)
        r82xxWriteMask(addr, 0x07, 0x00, 0x10)

        // Greedy LNA-first allocation — identical to r82xx_set_gain() in tuner_r82xx.c.
        // All values in tenths of dB; loop runs at most 15 iterations (16 LNA steps).
        var lnaIdx = 0
        var mixIdx = 0
        var totalGain = 0
        for (i in 0 until 15) {
            if (totalGain >= targetDb10) break
            totalGain += LNA_GAIN_STEPS[++lnaIdx]
            if (totalGain >= targetDb10) break
            totalGain += MIX_GAIN_STEPS[++mixIdx]
        }

        // LNA gain: reg 0x05 bits [3:0]
        r82xxWriteMask(addr, 0x05, lnaIdx, 0x0F)
        // Mixer gain: reg 0x07 bits [3:0]
        r82xxWriteMask(addr, 0x07, mixIdx, 0x0F)

        // VGA gain: fixed at 16.3 dB (index 8) for manual mode
        // Source: r82xx_set_vga_gain() → r82xx_write_reg_mask(priv, 0x0c, 0x08, 0x9f)
        r82xxWriteMask(addr, 0x0C, 0x08, 0x9F)

        setI2cRepeater(false)
        _statusFlow.value = _statusFlow.value.copy(gainDb10 = GAIN_TABLE_DB_TENTHS[_gain])
    }

    fun getGainIndex(): Int = _gain
    fun getGainDb(): Float = GAIN_TABLE_DB_TENTHS[_gain] / 10f
    fun getGainCount(): Int = GAIN_TABLE_DB_TENTHS.size

    private fun setAgc(enable: Boolean) {
        // Source: librtlsdr.c rtlsdr_set_agc_mode()
        writeDemodReg(0, 0x19, if (enable) 0x25 else 0x05, 1)
    }

    /**
     * Enable/disable R82xx internal (tuner) AGC.
     * Source: tuner_r82xx.c r82xx_set_gain() AGC path.
     */
    private fun setTunerAgc(enable: Boolean) {
        val addr = tunerAddr()
        setI2cRepeater(true)
        if (enable) {
            // LNA auto (bit 4 = 0)
            r82xxWriteMask(addr, 0x05, 0x00, 0x10)
            // Mixer auto (bit 4 = 1)
            r82xxWriteMask(addr, 0x07, 0x10, 0x10)
            // VGA fixed ~26.5 dB (index 0x0B, mask 0x9F)
            r82xxWriteMask(addr, 0x0C, 0x0B, 0x9F)
        } else {
            // LNA manual (bit 4 = 1)
            r82xxWriteMask(addr, 0x05, 0x10, 0x10)
            // Mixer manual (bit 4 = 0)
            r82xxWriteMask(addr, 0x07, 0x00, 0x10)
        }
        setI2cRepeater(false)
    }

    // ─── Public AGC component controls ───────────────────────────────────────

    /**
     * Independently enable/disable the R82xx tuner (LNA+Mixer) AGC.
     * When disabled, the manual gain slider takes effect.
     * When enabled, the LNA and Mixer gain is controlled automatically by the tuner.
     */
    fun setTunerAgcEnabled(enable: Boolean) {
        if (!isOpen) return
        _tunerAgcEnabled = enable
        setTunerAgc(enable)
        // Derive overall gain mode from combined AGC state.
        val nowManual = !_tunerAgcEnabled && !_hardwareAgcEnabled
        _gainMode = if (nowManual) GAIN_MODE_MANUAL else GAIN_MODE_AGC
        // Whenever the tuner (LNA/Mixer) AGC is disabled the R82xx registers are
        // placed in manual mode by setTunerAgc(false) above, but the actual gain
        // step values are NOT written at that point.  Push them now so the slider
        // position immediately takes effect rather than leaving whatever value the
        // AGC last wrote in the LNA/Mixer registers.
        if (!enable) setGain(_gain)
        _statusFlow.value = _statusFlow.value.copy(
            gainMode           = _gainMode,
            tunerAgcEnabled    = _tunerAgcEnabled,
            hardwareAgcEnabled = _hardwareAgcEnabled
        )
    }

    /**
     * Independently enable/disable the RTL2832U hardware (digital IF) AGC.
     * This controls the baseband demodulator's automatic gain loop independently
     * of the tuner's LNA/Mixer AGC.
     */
    fun setHardwareAgcEnabled(enable: Boolean) {
        if (!isOpen) return
        _hardwareAgcEnabled = enable
        setAgc(enable)
        val nowManual = !_tunerAgcEnabled && !_hardwareAgcEnabled
        _gainMode = if (nowManual) GAIN_MODE_MANUAL else GAIN_MODE_AGC
        _statusFlow.value = _statusFlow.value.copy(
            gainMode           = _gainMode,
            tunerAgcEnabled    = _tunerAgcEnabled,
            hardwareAgcEnabled = _hardwareAgcEnabled
        )
    }

    fun getTunerAgcEnabled(): Boolean  = _tunerAgcEnabled
    fun getHardwareAgcEnabled(): Boolean = _hardwareAgcEnabled

    // ─────────────────────────────────────────────────────────────────────────
    //  PUBLIC API — V4 / DEVICE FEATURES
    // ─────────────────────────────────────────────────────────────────────────

    fun setBiasTee(enable: Boolean) {
        if (!isOpen) return
        _biasTee = enable
        // Source: librtlsdr.c rtlsdr_set_bias_tee_gpio(dev, 0, on)
        setGpioOutput(0)
        setGpio(0, enable)
        _statusFlow.value = _statusFlow.value.copy(biasTee = enable)
        Log.i(TAG, "Bias tee ${if (enable) "ENABLED" else "DISABLED"}")
    }

    fun isBiasTeeEnabled(): Boolean = _biasTee

    fun setDirectSampling(mode: Int) {
        if (!isOpen) return
        _directSampling = mode
        // Source: librtlsdr.c _rtlsdr_set_direct_sampling()
        when (mode) {
            DIRECT_SAMPLING_OFF -> {
                if (_tunerType == TunerType.R828D || _tunerType == TunerType.R820T || _tunerType == TunerType.R820T2) {
                    setI2cRepeater(true)
                    r82xxInit()
                    setIfFreq(R82XX_IF_FREQ_DEMOD)
                    writeDemodReg(1, 0x15, 0x01, 1)   // spectrum inversion on
                    // r82xxInit() clobbers the V4 three-input band-switch
                    // registers (0x05/0x06) and GPIO-5 but already invalidated
                    // _currentBand, so this unconditionally rewrites them for
                    // whatever frequency we're actually tuned to — restoring
                    // the HF upconverter input path on V4 hardware without
                    // depending on a caller retuning afterward.
                    r828dSetFreq(_centerFreqHz, _sampleRate)
                }
                writeDemodReg(0, 0x06, 0x80, 1)       // opt_adc_iq = 0
                writeDemodReg(1, 0xB1, 0x1B, 1)       // re-enable Zero-IF
            }
            DIRECT_SAMPLING_I -> {
                if (_tunerType == TunerType.R828D || _tunerType == TunerType.R820T || _tunerType == TunerType.R820T2) {
                    setI2cRepeater(false)
                }
                writeDemodReg(1, 0xB1, 0x1A, 1)       // disable Zero-IF
                writeDemodReg(1, 0x15, 0x00, 1)       // disable spectrum inversion
                writeDemodReg(0, 0x08, 0x4D, 1)       // In-phase ADC only
                writeDemodReg(0, 0x06, 0x80, 1)       // ADC_I/Q swap = 0 (I)
            }
            DIRECT_SAMPLING_Q -> {
                if (_tunerType == TunerType.R828D || _tunerType == TunerType.R820T || _tunerType == TunerType.R820T2) {
                    setI2cRepeater(false)
                }
                writeDemodReg(1, 0xB1, 0x1A, 1)
                writeDemodReg(1, 0x15, 0x00, 1)
                writeDemodReg(0, 0x08, 0x4D, 1)
                writeDemodReg(0, 0x06, 0x90, 1)       // ADC_I/Q swap = 1 (Q)
            }
        }
        _statusFlow.value = _statusFlow.value.copy(directSampling = mode)
        Log.i(TAG, "Direct sampling: $mode")
    }

    fun getDirectSampling(): Int = _directSampling

    fun setPpmCorrection(ppm: Int) {
        _ppmCorrection = ppm
        // Source: librtlsdr.c rtlsdr_set_sample_freq_correction()
        // The RTL2832U PCLK correction register is 14-bit two's complement split across
        // two 8-bit demod registers: 0x3E holds bits [13:8] (masked 0x3F) and 0x3F holds
        // bits [7:0].  We compute the offset as a signed 16-bit value then extract each
        // byte using unsigned right-shift (ushr) to avoid sign-extension artifacts that
        // would corrupt the high-byte when the offset is negative.
        // Scale factor is (1 shl 22) = 4_194_304, matching librtlsdr exactly.
        // The previous (1 shl 24) caused 4× overcorrection.  Double arithmetic avoids
        // integer-division rounding divergence vs. the reference C float expression.
        val offs = Math.round(-ppm.toDouble() * (1 shl 22) / 1_000_000.0).toInt().toShort().toInt()
        writeDemodReg(1, 0x3F,  offs          and 0xFF, 1)   // low  byte [7:0]
        writeDemodReg(1, 0x3E, (offs ushr 8)  and 0x3F, 1)   // high byte [13:8]
        setCenterFrequency(_centerFreqHz)
        _statusFlow.value = _statusFlow.value.copy(ppmCorrection = ppm)
    }

    fun getPpmCorrection(): Int = _ppmCorrection

    fun readSignalStrength(): Float {
        if (!isOpen) return -120f
        val agc = readDemodReg(3, 0x19, 1) and 0xFF
        return -120f + agc * (120f / 255f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  STREAMING
    // ─────────────────────────────────────────────────────────────────────────

    fun startStreaming(bufferSize: Int = com.radiosport.ninegradio.dsp.DspEngine.USB_STREAMING_BUF) {
        if (!isOpen || isStreaming) return
        isStreaming = true

        val conn = connection ?: run { isStreaming = false; return }
        val ep   = epBulkIn  ?: run { isStreaming = false; return }

        streamingJob = scope.launch {
            val subscribeDeadline = System.currentTimeMillis() + 2_000L
            while (_iqFlow.subscriptionCount.value == 0) {
                if (System.currentTimeMillis() > subscribeDeadline) {
                    Log.w(TAG, "startStreaming: no subscriber after 2 s — proceeding")
                    break
                }
                delay(5)
            }

            resetEndpoint(fullReset = true)

            val TRANSFER_TIMEOUT_MS = 3_000
            // ── Chunk pool ─────────────────────────────────────────────────────────
            // Pre-allocate chunk slots so the hot path never calls copyOfRange().
            //
            // Each pipeline result (bufferSize = 32,768 B) splits into chunksPerTransfer
            // DSP_CHUNK_SIZE chunks before SharedFlow emit.  With NUM_PIPELINE_THREADS=3
            // threads, at most 3×2 + 17 (SharedFlow) + 1 (consumer) = 24 chunks can be
            // live simultaneously.  NUM_CHUNK_BUFS=60 gives ample headroom.
            //
            // Pipeline threads allocate their own ByteArray per bulkTransfer() call;
            // those are not pooled here.  This pool only covers DSP_CHUNK_SIZE slices
            // emitted to the SharedFlow — zero heap allocation in the hot emit loop.
            val DSP_CHUNK_SIZE      = com.radiosport.ninegradio.dsp.DspEngine.DSP_CHUNK_SIZE
            val chunksPerTransfer   = (bufferSize + DSP_CHUNK_SIZE - 1) / DSP_CHUNK_SIZE
            val NUM_CHUNK_BUFS      = RtlSdrUsbHelper.NUM_PIPELINE_THREADS * chunksPerTransfer * 10
            val chunkPool           = Array(NUM_CHUNK_BUFS) { ByteArray(DSP_CHUNK_SIZE) }
            var chunkPoolIdx        = 0
            // poolIdx strides chunk slots; NUM_POOL_BUFS = number of pipeline-result strides.
            val NUM_POOL_BUFS       = NUM_CHUNK_BUFS / chunksPerTransfer
            var poolIdx             = 0

            val helper: RtlSdrUsbHelper = usbHelper ?: RtlSdrUsbHelper(conn, ep).also { usbHelper = it }

            DebugBus.setStatus(DebugBus.STAGE_IQ_STREAM, DebugBus.StageStatus.OK,
                "buf=${bufferSize}B  sampleRate=${_sampleRate}Hz  pipeline=${RtlSdrUsbHelper.NUM_PIPELINE_THREADS}x")

            var consecutiveErrors = 0
            val MAX_CONSECUTIVE_ERRORS = 10
            var bwWindowBytes = 0L
            var bwWindowStartMs = System.currentTimeMillis()

            // ── PIPELINED MULTI-URB STREAMING ────────────────────────────────────────
            // Root cause of USB stutter (confirmed from rtl_tcp_andro librtlsdr.c):
            //
            //   rtl_tcp_andro submits DEFAULT_BUF_NUMBER=15 async libusb URBs
            //   simultaneously.  The USB host controller always has a URB queued in
            //   the kernel — when one completes, the next is already in-flight.
            //   Zero gap → RTL2832U FIFO drains at the true hardware rate.
            //
            //   Single-thread synchronous bulkTransfer() leaves a gap between
            //   transfers: while the coroutine copies chunks and updates DebugBus,
            //   no URB is waiting in the kernel.  The FIFO refills for ~4 ms before
            //   the next bulkTransfer() can be satisfied.  Total: ~8 ms per 32 KB
            //   transfer (4 ms of data) — exactly "USB avg transfer 7.92 ms" in the
            //   diagnostic — data delivered at half the expected rate → audio stutter.
            //
            // Fix: start NUM_PIPELINE_THREADS=3 Java threads, each blocking on its
            // own bulkTransfer() call concurrently.  Completed buffers arrive in a
            // LinkedBlockingQueue; this coroutine takes() them in order and emits to
            // the SharedFlow.  With 3 threads in flight, one always has a URB in the
            // kernel while others are being processed → no gap → no stutter.
            //
            // The chunk pool and DSP_CHUNK_SIZE slicing remain: each pipeline result
            // (bufferSize bytes) is still split into DSP_CHUNK_SIZE chunks so the
            // processIqBlock() budget is the same as the TCP path.  The yield() call
            // between chunks is also kept so the Default-pool DSP coroutine gets a
            // scheduling slot between chunk 1 and chunk 2 of each transfer.
            helper.startPipeline(bufferSize, TRANSFER_TIMEOUT_MS)

            while (isActive && isStreaming) {
                // take() blocks until a pipeline thread delivers a completed transfer.
                // Poll with a generous timeout so the coroutine stays cancellable.
                val result = helper.takePipelineResult(pollTimeoutMs = 500L)
                if (result == null) {
                    // Timeout — pipeline still running but nothing arrived yet.
                    // This can happen at startup; just retry.
                    continue
                }

                val buf = result.buf
                val bytesRead = result.bytesRead
                // Pipeline transfers happen on background threads so wall-clock timing
                // measured here would include queue wait time, not USB transfer time.
                // Use xferMs=0 to record the arrival without polluting transfer stats.
                DebugBus.recordIqTransfer(bytesRead, bufferSize, xferMs = 0L)

                when {
                    bytesRead > 0 -> {
                        consecutiveErrors = 0
                        // Emit in DSP_CHUNK_SIZE slices (matches RtlTcpSource.READ_BUF_SIZE).
                        // Each pipeline buffer (32,768 B) splits into chunksPerTransfer chunks.
                        val totalBytes = bytesRead.coerceAtMost(bufferSize)
                        var chunkStart = 0
                        while (chunkStart < totalBytes) {
                            val chunkEnd   = minOf(chunkStart + DSP_CHUNK_SIZE, totalBytes)
                            val chunkBytes = chunkEnd - chunkStart
                            val chunk      = chunkPool[chunkPoolIdx]
                            System.arraycopy(buf, chunkStart, chunk, 0, chunkBytes)
                            chunkPoolIdx   = (chunkPoolIdx + 1) % NUM_CHUNK_BUFS
                            chunkStart     = chunkEnd
                            if (!_iqFlow.tryEmit(chunk)) {
                                DebugBus.incrementIqFlowDrops()
                            }
                            DebugBus.tick(DebugBus.STAGE_IQ_STREAM)
                            // yield() between chunks: gives Dispatchers.Default a scheduling
                            // slot to run processIqBlock() on chunk N before chunk N+1 is
                            // emitted, preventing back-to-back burst processing.
                            yield()
                        }
                        poolIdx = (poolIdx + 1) % NUM_POOL_BUFS
                        _statusFlow.value = _statusFlow.value.copy(
                            signalStrengthDb = estimateSignalStrength(buf)
                        )
                        bwWindowBytes += bytesRead
                        if (DebugBus.snapshot()[DebugBus.STAGE_IQ_STREAM].counter % 10L == 0L) {
                            val mode = "pipeline×${RtlSdrUsbHelper.NUM_PIPELINE_THREADS}"
                            val nowMs = System.currentTimeMillis()
                            val elapsedSec = (nowMs - bwWindowStartMs).coerceAtLeast(1L) / 1000.0f
                            val kbps = (bwWindowBytes / 1024.0f / elapsedSec).toInt()
                            bwWindowBytes = 0L
                            bwWindowStartMs = nowMs
                            val rate = DebugBus.snapshot()[DebugBus.STAGE_IQ_STREAM].ratePerSec
                            DebugBus.setDetail(DebugBus.STAGE_IQ_STREAM,
                                "${bytesRead}B/buf  $kbps kB/s  ${rate.toInt()} pkt/s  [$mode]")

                            val perf = DebugBus.getIqPerf()
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_XFER_AVG,
                                "%.1f ms".format(perf.avgXferMs))
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_XFER_MAX,
                                "${perf.maxXferMs} ms")
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_JITTER,
                                "%.1f ms".format(perf.jitterMs))
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_DROPS,
                                "${perf.dropCount}")
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_SHORT_READS,
                                "${perf.shortReadCount}")
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_DROP_RATE,
                                "%.2f%%".format(perf.dropRatePct))
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_RATING,
                                perf.rating)
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_POOL_SIZE,
                                NUM_POOL_BUFS.toString())
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_FLOW_DEPTH,
                                "17")
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_IN_FLIGHT,
                                RtlSdrUsbHelper.NUM_PIPELINE_THREADS.toString())
                            val flowDrops = DebugBus.getIqPerf().flowDropCount
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_FLOW_DROPS,
                                "$flowDrops")
                        }
                        DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_POOL_IDX,
                            poolIdx.toString())
                    }
                    bytesRead == 0 -> Log.v(TAG, "pipeline result bytesRead=0 — skipping")
                    else -> {
                        if (isStreaming) {
                            consecutiveErrors++
                            Log.w(TAG, "pipeline error bytesRead=$bytesRead ($consecutiveErrors/$MAX_CONSECUTIVE_ERRORS)")
                            DebugBus.setError(DebugBus.STAGE_IQ_STREAM,
                                "pipeline error $bytesRead — USB stall ($consecutiveErrors)")
                            val perf = DebugBus.getIqPerf()
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_DROPS,
                                "${perf.dropCount}")
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_DROP_RATE,
                                "%.2f%%".format(perf.dropRatePct))
                            DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, DebugBus.EXTRA_IQ_RATING,
                                perf.rating)
                            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                                Log.w(TAG, "USB streaming lost after $consecutiveErrors errors — restart required")
                                _statusFlow.value = _statusFlow.value.copy(streamRestartRequired = true)
                                isStreaming = false
                                break
                            }
                            val escalate = consecutiveErrors >= 3
                            try {
                                // Stop the pipeline before resetting the endpoint so
                                // in-flight bulkTransfer() calls are not racing the
                                // controlTransfer() that clears the halt bit.
                                helper.stopPipeline()
                                resetEndpoint(fullReset = escalate)
                                if (escalate) {
                                    Log.i(TAG, "stall recovery: re-applying freq=${_centerFreqHz} gainMode=${_gainMode}")
                                    setCenterFrequency(_centerFreqHz)
                                    setGainMode(_gainMode)
                                }
                                delay(if (escalate) 200L else 150L)
                                // Restart the pipeline after recovery.
                                helper.startPipeline(bufferSize, TRANSFER_TIMEOUT_MS)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
            helper.stopPipeline()
        }
        Log.i(TAG, "Streaming started (${usbHelper?.transferMode() ?: "?"}), bufferSize=$bufferSize")
    }

    private suspend fun resetEndpoint(fullReset: Boolean = false) {
        val ep = epBulkIn ?: return
        // Source: librtlsdr.c rtlsdr_reset_buffer()
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)
        delay(5)
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x0000, 2)
        if (fullReset) usbHelper?.resetPath()
        // USB CLEAR_FEATURE(ENDPOINT_HALT) — Java HAL layer
        connection?.controlTransfer(0x02, 3, 0, ep.address.toInt() and 0xFF, null, 0, CTRL_TIMEOUT)
        // Kernel-level USBDEVFS_CLEAR_HALT via JNI (bypasses OEM HAL bugs)
        usbHelper?.clearHaltNative()
        delay(10)
    }

    fun stopStreaming() {
        isStreaming = false
        streamingJob?.cancel()
        streamingJob = null
        usbHelper?.resetPath()
        scope.launch {
            try { resetEndpoint(fullReset = false) } catch (_: Exception) {}
        }
        DebugBus.setStatus(DebugBus.STAGE_IQ_STREAM, DebugBus.StageStatus.IDLE, "Streaming stopped")
        Log.i(TAG, "Streaming stopped")
    }

    fun isStreaming(): Boolean = isStreaming

    suspend fun restartStreaming(bufferSize: Int = com.radiosport.ninegradio.dsp.DspEngine.USB_STREAMING_BUF): Boolean {
        if (!isOpen) {
            Log.e(TAG, "restartStreaming() called but device is not open")
            return false
        }
        _statusFlow.value = _statusFlow.value.copy(streamRestartRequired = false, error = null)
        Log.i(TAG, "restartStreaming(): resetting endpoint, re-applying sample rate")
        DebugBus.setStatus(DebugBus.STAGE_IQ_STREAM, DebugBus.StageStatus.OK, "Stream restart in progress…")
        streamingJob?.cancel()
        streamingJob = null

        val savedRate   = _sampleRate
        val savedFreq   = _centerFreqHz
        val savedGain   = _gainMode
        val wasStreaming = isStreaming
        isStreaming = false
        setSampleRate(savedRate)
        isStreaming = wasStreaming
        // Re-apply frequency and gain — sample rate alone is not enough after a USB stall
        // because the RTL2832U/R828D may have lost their register state.
        setCenterFrequency(savedFreq)
        setGainMode(savedGain)
        Log.i(TAG, "restartStreaming(): re-applied rate=${savedRate}Hz freq=${savedFreq}Hz gainMode=${savedGain}")

        try { resetEndpoint(fullReset = true) } catch (_: Exception) {}
        startStreaming(bufferSize)
        return true
    }

    private fun estimateSignalStrength(iqData: ByteArray): Float {
        var power = 0.0
        val len = iqData.size.coerceAtMost(1024)
        for (i in 0 until len step 2) {
            val i_s = (iqData[i].toInt() and 0xFF) - 127.5
            val q_s = if (i + 1 < len) (iqData[i + 1].toInt() and 0xFF) - 127.5 else 0.0
            power += i_s * i_s + q_s * q_s
        }
        val rms = sqrt(power / (len / 2))
        return if (rms > 0) (20 * log10(rms / 128.0)).toFloat() else -100f
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LOW-LEVEL REGISTER ACCESS  (librtlsdr.c rtlsdr_read/write_array/reg)
    // ─────────────────────────────────────────────────────────────────────────

    fun writeReg(block: Int, addr: Int, value: Int, length: Int): Int {
        // wIndex = (block << 8) | 0x10  — write-direction flag mandatory
        val wIndex = (block shl 8) or 0x10
        val buf = if (length == 1) byteArrayOf((value and 0xFF).toByte())
                  else byteArrayOf((value and 0xFF).toByte(), (value shr 8).toByte())  // LE
        return connection?.controlTransfer(CTRL_OUT, 0, addr, wIndex, buf, length, CTRL_TIMEOUT) ?: -1
    }

    fun readReg(block: Int, addr: Int, length: Int): Int {
        val wIndex = block shl 8   // reads: no 0x10 flag
        val buf = ByteArray(length)
        connection?.controlTransfer(CTRL_IN, 0, addr, wIndex, buf, length, CTRL_TIMEOUT)
        return buf[0].toInt() and 0xFF
    }

    fun writeDemodReg(page: Int, addr: Int, value: Int, length: Int): Int {
        // Source: librtlsdr.c rtlsdr_demod_write_reg()
        //   wValue = (addr << 8) | 0x20
        //   wIndex = 0x10 | page
        //
        // The RTL2832U demodulator register bus is big-endian: the C driver encodes
        // 2-byte values as data[0] = val >> 8 (MSB), data[1] = val & 0xff (LSB).
        // Unlike the USB/SYS block registers (which are little-endian), the demod
        // block requires MSB-first byte order on the wire.  Using little-endian here
        // swaps the bytes of every 2-byte write (e.g. rsampRatio high-word 0x03C0
        // arrives at the demod as 0xC003), which programs an entirely wrong sample
        // rate and causes all IQ framing / protocol decoding to fail.
        val wValue = (addr shl 8) or 0x20
        val wIndex = 0x10 or page
        val buf = if (length == 1) byteArrayOf((value and 0xFF).toByte())
                  else byteArrayOf((value shr 8).toByte(), (value and 0xFF).toByte())  // BE — matches librtlsdr
        val r = connection?.controlTransfer(CTRL_OUT, 0, wValue, wIndex, buf, length, CTRL_TIMEOUT) ?: -1
        // Mandatory post-write acknowledge: rtlsdr_demod_read_reg(dev, 0x0a, 0x01, 1)
        readDemodReg(0x0a, 0x01, 1)
        return r
    }

    fun readDemodReg(page: Int, addr: Int, length: Int): Int {
        val wValue = (addr shl 8) or 0x20
        val wIndex = page   // reads: no 0x10
        val buf = ByteArray(length)
        connection?.controlTransfer(CTRL_IN, 0, wValue, wIndex, buf, length, CTRL_TIMEOUT)
        return buf[0].toInt() and 0xFF
    }

    /**
     * Set the RTL2832U IF frequency register.
     * Source: librtlsdr.c rtlsdr_set_if_freq()
     *   if_freq = -(freq * 2^22 / rtl_xtal)  (negative because of frequency inversion)
     */
    private fun setIfFreq(freqHz: Long) {
        val ifFreq = ((freqHz * (1L shl 22)) / _rtlXtalFreq) * (-1L)
        writeDemodReg(1, 0x19, ((ifFreq shr 16) and 0x3F).toInt(), 1)
        writeDemodReg(1, 0x1A, ((ifFreq shr  8) and 0xFF).toInt(), 1)
        writeDemodReg(1, 0x1B, (ifFreq and 0xFF).toInt(), 1)
    }

    // ── I2C helpers (IICB block, matching librtlsdr IICB=6 addressing) ────────

    private fun i2cWrite(addr: Int, reg: Byte, value: Byte): Int {
        val buf = byteArrayOf(reg, value)
        return connection?.controlTransfer(CTRL_OUT, 0, addr, IICB_WRITE_INDEX, buf, 2, CTRL_TIMEOUT) ?: -1
    }

    private fun i2cRead(addr: Int, reg: Int): Int {
        val wbuf = byteArrayOf(reg.toByte())
        val rbuf = ByteArray(1)
        // Write register address first, then read value
        // Source: librtlsdr.c rtlsdr_i2c_read_reg()
        connection?.controlTransfer(CTRL_OUT, 0, addr, IICB_WRITE_INDEX, wbuf, 1, CTRL_TIMEOUT)
        connection?.controlTransfer(CTRL_IN,  0, addr, IICB_READ_INDEX,  rbuf, 1, CTRL_TIMEOUT)
        return rbuf[0].toInt() and 0xFF
    }

    // ── R82xx shadow-register-aware I2C helpers ───────────────────────────────

    private fun tunerAddr(): Int = if (_tunerType == TunerType.R828D) R828D_I2C_ADDR else R820T2_I2C_ADDR

    /**
     * Write a single R82xx register.
     * Updates shadow (priv->regs[]) and issues I2C write.
     * Source: tuner_r82xx.c r82xx_write_reg().
     */
    private fun r82xxWrite(addr: Int, reg: Int, value: Int) {
        val shadowIdx = reg - 0x05
        if (shadowIdx in r82xxShadow.indices) r82xxShadow[shadowIdx] = value and 0xFF
        i2cWrite(addr, reg.toByte(), value.toByte())
    }

    /**
     * Burst-write R82xx registers starting at [startReg].
     * Source: tuner_r82xx.c r82xx_write() with I2C message chunking.
     * max_i2c_msg_len = 8 in librtlsdr, so we send 7 data bytes per I2C packet.
     */
    private fun r82xxWriteBurst(addr: Int, startReg: Int, values: IntArray) {
        // Update shadow
        val shadowBase = startReg - 0x05
        for (i in values.indices) {
            val idx = shadowBase + i
            if (idx in r82xxShadow.indices) r82xxShadow[idx] = values[i] and 0xFF
        }
        // Chunk into I2C packets of up to 7 bytes (8 - 1 for register byte)
        val maxPayload = 7
        var pos = 0
        var reg = startReg
        while (pos < values.size) {
            val chunkSize = minOf(maxPayload, values.size - pos)
            val buf = ByteArray(chunkSize + 1)
            buf[0] = reg.toByte()
            for (i in 0 until chunkSize) buf[i + 1] = values[pos + i].toByte()
            connection?.controlTransfer(CTRL_OUT, 0, addr, IICB_WRITE_INDEX, buf, buf.size, CTRL_TIMEOUT)
            reg += chunkSize
            pos += chunkSize
        }
    }

    /**
     * Read-modify-write a R82xx register (shadow-backed).
     * Source: tuner_r82xx.c r82xx_write_reg_mask().
     * Uses shadow cache to avoid round-trip I2C read when possible.
     */
    private fun r82xxWriteMask(addr: Int, reg: Int, value: Int, mask: Int) {
        val shadowIdx = reg - 0x05
        val cur = if (shadowIdx in r82xxShadow.indices && r82xxShadow[shadowIdx] != 0xFF)
            r82xxShadow[shadowIdx]
        else
            i2cRead(addr, reg)
        val newVal = (cur and mask.inv()) or (value and mask)
        r82xxWrite(addr, reg, newVal)
    }

    /**
     * Read [len] bytes from R82xx starting at [reg].
     * Source: tuner_r82xx.c r82xx_read() — applies bit-reversal (r82xx_bitrev).
     */
    private fun r82xxRead(addr: Int, reg: Int, len: Int): IntArray {
        // Write register address
        val wbuf = byteArrayOf(reg.toByte())
        connection?.controlTransfer(CTRL_OUT, 0, addr, IICB_WRITE_INDEX, wbuf, 1, CTRL_TIMEOUT)
        // Read bytes
        val rbuf = ByteArray(len)
        connection?.controlTransfer(CTRL_IN, 0, addr, IICB_READ_INDEX, rbuf, len, CTRL_TIMEOUT)
        // Apply bit reversal (r82xx_bitrev)
        return IntArray(len) { i -> bitrev(rbuf[i].toInt() and 0xFF) }
    }

    /** Bit-reverse a byte. Source: tuner_r82xx.c r82xx_bitrev(). */
    private fun bitrev(b: Int): Int {
        val lut = intArrayOf(0x0,0x8,0x4,0xC,0x2,0xA,0x6,0xE,0x1,0x9,0x5,0xD,0x3,0xB,0x7,0xF)
        return (lut[b and 0xF] shl 4) or lut[b shr 4]
    }

    // ── GPIO helpers ──────────────────────────────────────────────────────────

    /**
     * Configure a GPIO pin as output.
     * Source: librtlsdr.c rtlsdr_set_gpio_output().
     */
    private fun setGpioOutput(pin: Int) {
        val mask = (1 shl pin) and 0xFF
        val gpd  = readReg(BLOCK_SYS, REG_GPD, 1)
        writeReg(BLOCK_SYS, REG_GPD, gpd and mask.inv() and 0xFF, 1)
        val gpoe = readReg(BLOCK_SYS, REG_GPOE, 1)
        writeReg(BLOCK_SYS, REG_GPOE, (gpoe or mask) and 0xFF, 1)
    }

    /**
     * Set/clear a GPIO pin on the RTL2832U GPO register.
     * Source: librtlsdr.c rtlsdr_set_gpio_bit().
     */
    private fun setGpio(pin: Int, high: Boolean) {
        val mask = (1 shl pin) and 0xFF
        val gpo  = readReg(BLOCK_SYS, REG_GPO, 1)
        val newGpo = if (high) (gpo or mask) and 0xFF else (gpo and mask.inv()) and 0xFF
        writeReg(BLOCK_SYS, REG_GPO, newGpo, 1)
    }

    // ── EEPROM / info ─────────────────────────────────────────────────────────

    private fun readEepromSerial(): String {
        if (!isOpen) return "N/A"
        val eeprom = ByteArray(256)
        val r = connection?.controlTransfer(
            CTRL_IN, 0, 0, 0x0020, eeprom, eeprom.size, CTRL_TIMEOUT) ?: -1
        if (r < 0x2A) return "N/A"
        val descLen  = eeprom[0x28].toInt() and 0xFF
        val descType = eeprom[0x29].toInt() and 0xFF
        if (descLen < 2 || descType != 0x03 || (0x28 + descLen) > eeprom.size) return "N/A"
        val sb = StringBuilder()
        var i = 0x2A
        while (i + 1 <= 0x28 + descLen - 1) {
            val lo = eeprom[i].toInt() and 0xFF
            val hi = eeprom[i + 1].toInt() and 0xFF
            val c  = (hi shl 8) or lo
            if (c != 0) sb.append(c.toChar())
            i += 2
        }
        return if (sb.isNotEmpty()) sb.toString() else "N/A"
    }

    fun getDeviceInfo(): String = buildString {
        appendLine("Device: ${getDeviceName(usbDevice)}")
        appendLine("VID/PID: 0x${usbDevice.vendorId.toString(16).uppercase()}/0x${usbDevice.productId.toString(16).uppercase()}")
        val serial = usbDevice.serialNumber?.takeIf { it.isNotBlank() } ?: readEepromSerial()
        appendLine("Serial: $serial")
        appendLine("Tuner: $_tunerType${if (_isV4) " (RTL-SDR Blog V4)" else ""}")
        appendLine("XTAL: ${XTAL_FREQ / 1_000_000} MHz (TCXO)")
        appendLine("Frequency: ${_centerFreqHz / 1_000_000.0} MHz")
        appendLine("Sample Rate: ${_sampleRate / 1_000} kS/s")
        appendLine("Gain: ${GAIN_TABLE_DB_TENTHS[_gain] / 10.0} dB")
        appendLine("Bias Tee: ${if (_biasTee) "ON" else "OFF"}")
        appendLine("Direct Sampling: ${when (_directSampling) {
            DIRECT_SAMPLING_OFF -> "Off"
            DIRECT_SAMPLING_I   -> "I-branch"
            DIRECT_SAMPLING_Q   -> "Q-branch"
            else -> "Unknown"
        }}")
        append("PPM Correction: $_ppmCorrection")
    }
}
