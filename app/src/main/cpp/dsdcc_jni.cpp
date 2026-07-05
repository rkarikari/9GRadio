// dsdcc_jni.cpp — Full JNI bridge for dsdcc digital voice (DMR/DSTAR/YSF).
//
// Vocoder: mbelib-neo — vendored in cpp/mbelib-neo/, compiled with DSD_USE_MBELIB.
//          enableMbelib(true) → dsdcc calls mbe_process* internally.
//          getAudio1()/getAudio2() returns real PCM.  Always available.
//
// Audio:  feedSamples() polls getAudio1()/getAudio2() after every DV frame and
//         accumulates PCM into hdl.pcmBuf.  getPcm() drains that buffer and
//         returns a jshortArray to Kotlin, which writes it to AudioTrack.
//
// Protocol integers match DigitalFrame.Protocol ordinals in Kotlin:
//   0 DMR  1 P25P1  2 P25P2  3 NXDN48  4 NXDN96  5 DSTAR  6 YSF  7 AUTO  8 DPMR

#include <jni.h>
#include <android/log.h>
#include <string>
#include <memory>
#include <mutex>
#include <vector>
#include <cstring>
#include <cstdint>

#include "dsdcc/dsd_decoder.h"
#include "dsdcc/dmr.h"
#include "dsdcc/dstar.h"
#include "dsdcc/ysf.h"
#include "dsdcc/nxdn.h"
#include "dsdcc/dpmr.h"
#include "mbelib-neo/mbelib.h"

#define LOG_TAG "DsdccJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Per-handle decoder state ──────────────────────────────────────────────────
struct DecoderHandle {
    std::unique_ptr<DSDcc::DSDDecoder> dec;
    std::mutex mtx;
    int  protocol;
    // PCM accumulation buffer (drains via getPcm())
    std::vector<int16_t> pcmBuf;
    // DV frame bytes (slot 1)
    static constexpr int MAX_DV = 18;
    uint8_t dvBuf1[MAX_DV];
    uint8_t dvBuf2[MAX_DV];
    bool dvReady1, dvReady2;
    // Metadata
    int  srcId, dstId;
    bool isGroup, encrypted;
    char srcCall[64];
    char dstCall[64];
    bool hasPcm;

    // ── DMR active-slot lock ──────────────────────────────────────────────
    // Two independent AMBE decoders exist per handle (m_mbeDecoder1/2 inside
    // dsdcc, one per physical TDMA slot). Many hotspots (incl. OpenSpot-class
    // devices) mirror a single active call onto BOTH TS1 and TS2 so any radio
    // parked on either slot hears it. Both decoder-1 and decoder-2 then
    // legitimately produce valid, differently-timed PCM for the SAME
    // underlying speech, and both were being appended to the same linear
    // pcmBuf -- i.e. the same words played twice in close succession from two
    // independently-clocked AMBE decoder instances. That is heard as a
    // steady, TDMA-rate-locked warble/"beat" riding under otherwise-clean
    // audio: constant frequency (tied to the fixed slot period, not content),
    // present for the whole transmission, RSSI/SNR/frame-count all normal
    // because sync and FEC are fine -- only the PCM-assembly stage is wrong.
    // D-STAR/YSF have no second slot and were never affected.
    //
    // Fix: once a slot starts producing voice, lock onto it and ignore DV
    // audio from the other slot until the locked slot goes quiet for
    // kSlotLockTimeoutSamples (call ended / handed off), at which point
    // either slot may claim the lock again. This still allows legitimate
    // slot switching between successive, different calls.
    int  activeSlot = 0;                 // 0 = unclaimed, 1 or 2 = locked
    long samplesSinceActiveAudio = 0;     // samples since locked slot last produced PCM
    static constexpr long kSlotLockTimeoutSamples = 24000; // 500 ms @ 48 kHz

    // Claim debounce: a single DV frame on an unclaimed slot used to lock
    // (and collect) that slot immediately. Since a mirrored OpenSpot-style
    // duplicate typically starts within a few ms of the real transmission
    // (not offset by a full slot period), whichever physical slot's frame
    // happens to be processed first in the per-sample loop could win the
    // race non-deterministically and get one or two frames of the WRONG
    // (mirrored) stream spliced into pcmBuf before the real slot reclaims
    // on the next lock cycle -- audible as a brief warble at claim time,
    // and, if it recurs at every lock re-acquisition (e.g. after a natural
    // speech gap), as a low-rate periodic beat for the whole transmission.
    // Requiring kSlotConfirmFrames consecutive frames from the SAME slot
    // before committing the lock (and before any audio is collected) means
    // a lone stray/mirrored frame can never reach pcmBuf.
    // BUG (found in the wild as a steady, content-independent DMR "beat"
    // that never happens on D-STAR/YSF): candidateSlot/candidateFrameCount
    // used to be a SINGLE shared pair of fields for both slot 1's and slot
    // 2's debounce progress. Any lone stray/mirrored frame on the opposite
    // slot -- arriving at the fixed TDMA burst cadence, totally independent
    // of audio content or link quality -- overwrote the real slot's
    // in-progress candidate count back to the other slot's count of 1. If
    // that happens repeatedly (e.g. every time the lock has to re-acquire
    // after a normal syllable gap or embedded-signalling late-entry within
    // one transmission), the real slot's confirm count never reaches
    // kSlotConfirmFrames on schedule, delaying/jittering lock acquisition
    // at a rate tied to the fixed slot period -- audible as a steady,
    // constant-frequency beat/warble for the whole transmission, while
    // RSSI/SNR/frame-count all stay normal because sync and FEC really are
    // fine; only this shared-state debounce logic was wrong. Fixed by
    // giving each slot its own, independent confirm counter so progress on
    // one slot can never be clobbered by the other.
    int  candidateFrameCount1 = 0;        // slot 1 consecutive-frame confirm progress
    int  candidateFrameCount2 = 0;        // slot 2 consecutive-frame confirm progress
    static constexpr int kSlotConfirmFrames = 2;

    // ── Vocoder diagnostic telemetry (mbelib-neo path only) ─────────────────
    // Cumulative counts of MBE_PROCESS_FLAG_* outcomes per slot, plus the
    // most recent frame's error counts, so the debug panel can show exactly
    // what the vocoder is doing without guessing from the decoded audio.
    // Exposed to Kotlin via getMetadata()/getVocoderStats().
    long toneCount1 = 0, toneCount2 = 0;
    long erasureCount1 = 0, erasureCount2 = 0;
    long repeatCount1 = 0, repeatCount2 = 0;
    long muteCount1 = 0, muteCount2 = 0;
    long framesSeen1 = 0, framesSeen2 = 0;
    int  lastC0Errors1 = 0, lastC0Errors2 = 0;
    int  lastTotalErrors1 = 0, lastTotalErrors2 = 0;

    // ── Auto/DIG-mode data-rate cycling (NXDN48 / dPMR fix) ─────────────────
    // dsdcc's DSDDecodeAuto is *not* a true blind scanner: setDecodeMode()
    // only flips on the frame_* flags for whichever single DSDRate the
    // decoder happens to be set to at that moment (see dsd_decoder.cpp
    // setDecodeMode(), the "case DSDDecodeAuto" block). The decoder is
    // constructed at DSDRate4800 and nothing thereafter ever changes that,
    // so createDecoder(PROTO_AUTO) permanently enables frame_dmr,
    // frame_dstar, frame_x2tdma, frame_p25p1, frame_nxdn96 and frame_ysf --
    // but frame_nxdn48 and frame_dpmr are NEVER enabled, because those only
    // get set in the DSDRate2400 branch of that same switch, a branch Auto
    // mode never reaches on its own. That matches the upstream DSD/DSD-FME/
    // dsd-neo family's documented behaviour: NXDN (specifically the 6.25 kHz
    // /2400 baud "NXDN48" variant used by most conventional/IDAS repeaters)
    // and dPMR are explicitly called out as NOT auto-detectable, requiring a
    // dedicated mode switch instead -- which is exactly the symptom of
    // "NXDN voice traffic is not auto identified (Dig)".
    //
    // Fix: while running in PROTO_AUTO with no active sync (getSyncType()
    // == DSDSyncNone), periodically toggle the decoder between DSDRate4800
    // and DSDRate2400 and re-issue setDecodeMode(DSDDecodeAuto, true) so the
    // frame_nxdn48/frame_dpmr flags for the 2400-baud branch get their turn
    // to be tried too -- exactly as if the user had briefly switched to the
    // NXDN48/dPMR mode and back. As soon as ANY sync locks (regardless of
    // which rate/protocol), the idle counter resets and cycling stops for
    // the duration of that call, so an in-progress decode is never
    // interrupted by a rate flip.
    long autoIdleSamples = 0;
    bool autoAt2400 = false;
    // 500 ms @ 48 kHz -- long enough to let a real sync lock in before
    // giving up and trying the other rate, short enough that a receiver
    // parked mid-transmission on a NXDN48/dPMR signal still catches the
    // next short-sync or superframe boundary within a fraction of a second.
    static constexpr long kAutoRateToggleSamples = 24000;
};

static constexpr int MAX_HANDLES = 4;
static DecoderHandle g_handles[MAX_HANDLES];
static std::mutex    g_tableMtx;

static int allocHandle() {
    std::lock_guard<std::mutex> lk(g_tableMtx);
    for (int i = 0; i < MAX_HANDLES; i++)
        if (!g_handles[i].dec) return i;
    return -1;
}

static DecoderHandle* getHandle(jlong h) {
    int i = (int)h;
    if (i < 0 || i >= MAX_HANDLES || !g_handles[i].dec) return nullptr;
    return &g_handles[i];
}

// ── Protocol → DSDDecodeMode ─────────────────────────────────────────────────
static DSDcc::DSDDecoder::DSDDecodeMode protocolToMode(int proto) {
    switch (proto) {
        case 0:  return DSDcc::DSDDecoder::DSDDecodeDMR;
        case 1:  return DSDcc::DSDDecoder::DSDDecodeP25P1;
        case 3:  return DSDcc::DSDDecoder::DSDDecodeNXDN48;
        case 4:  return DSDcc::DSDDecoder::DSDDecodeNXDN96;
        case 5:  return DSDcc::DSDDecoder::DSDDecodeDStar;
        case 6:  return DSDcc::DSDDecoder::DSDDecodeYSF;
        case 8:  return DSDcc::DSDDecoder::DSDDecodeDPMR;
        default: return DSDcc::DSDDecoder::DSDDecodeAuto;
    }
}

// ── Metadata extraction ───────────────────────────────────────────────────────
static void extractMetadata(DecoderHandle& hdl) {
    DSDcc::DSDDecoder* dec = hdl.dec.get();
    // BUG FIX: hdl.protocol is fixed at creation time. For a dedicated
    // single-protocol handle that's also the right value to switch on below,
    // but for a PROTO_AUTO (DIG mode) handle it is permanently 7 -- which
    // matches none of the cases below, so metadata (srcId/dstId/callsign)
    // never populated for ANY protocol while auto-detecting. Resolve the
    // actual locked protocol from the sync type dsdcc just matched instead,
    // falling back to the fixed value for dedicated-mode handles.
    int proto = hdl.protocol;
    if (proto == 7) { // PROTO_AUTO / DIG
        switch (dec->getSyncType()) {
            case DSDcc::DSDDecoder::DSDSyncDMRDataP:
            case DSDcc::DSDDecoder::DSDSyncDMRDataMS:
            case DSDcc::DSDDecoder::DSDSyncDMRVoiceP:
            case DSDcc::DSDDecoder::DSDSyncDMRVoiceMS:
                proto = 0; break; // DMR
            case DSDcc::DSDDecoder::DSDSyncP25p1P:
            case DSDcc::DSDDecoder::DSDSyncP25p1N:
                proto = 1; break; // P25 Phase 1
            case DSDcc::DSDDecoder::DSDSyncDStarP:
            case DSDcc::DSDDecoder::DSDSyncDStarN:
            case DSDcc::DSDDecoder::DSDSyncDStarHeaderP:
            case DSDcc::DSDDecoder::DSDSyncDStarHeaderN:
                proto = 5; break; // D-STAR
            case DSDcc::DSDDecoder::DSDSyncYSF:
                proto = 6; break; // YSF
            case DSDcc::DSDDecoder::DSDSyncNXDNP:
            case DSDcc::DSDDecoder::DSDSyncNXDNN:
            case DSDcc::DSDDecoder::DSDSyncNXDNDataP:
            case DSDcc::DSDDecoder::DSDSyncNXDNDataN:
                proto = (dec->getDataRate() == DSDcc::DSDDecoder::DSDRate2400) ? 3 : 4; break; // NXDN48 : NXDN96
            case DSDcc::DSDDecoder::DSDSyncDPMR:
            case DSDcc::DSDDecoder::DSDSyncDPMRPacket:
            case DSDcc::DSDDecoder::DSDSyncDPMRPayload:
            case DSDcc::DSDDecoder::DSDSyncDPMREnd:
                proto = 8; break; // dPMR
            default:
                proto = -1; break; // not yet locked -- leave metadata cleared below
        }
    }
    hdl.srcId = 0; hdl.dstId = 0;
    hdl.isGroup = false; hdl.encrypted = false;
    hdl.srcCall[0] = '\0'; hdl.dstCall[0] = '\0';

    if (proto == 0) { // DMR
        const DSDcc::DSDDMR& dmr = dec->getDMRDecoder();
        const auto& s1 = dmr.getSlot1Addresses();
        const auto& s2 = dmr.getSlot2Addresses();
        hdl.srcId   = (int)s1.m_source;
        hdl.dstId   = (int)s1.m_target;
        hdl.isGroup = s1.m_group;
        if (hdl.srcId == 0 && s2.m_source != 0) {
            hdl.srcId   = (int)s2.m_source;
            hdl.dstId   = (int)s2.m_target;
            hdl.isGroup = s2.m_group;
        }
        const char* t0 = dmr.getSlot0Text();
        const char* t1 = dmr.getSlot1Text();
        if (t0 && t0[0]) { strncpy(hdl.srcCall, t0, 63); hdl.srcCall[63]='\0'; }
        if (t1 && t1[0]) { strncpy(hdl.dstCall, t1, 63); hdl.dstCall[63]='\0'; }
    } else if (proto == 5) { // D-STAR
        const DSDcc::DSDDstar& ds = dec->getDStarDecoder();
        const std::string& my = ds.getMySign();
        strncpy(hdl.srcCall, my.c_str(), 63); hdl.srcCall[63]='\0';
    } else if (proto == 6) { // YSF
        const DSDcc::DSDYSF& ysf = dec->getYSFDecoder();
        strncpy(hdl.srcCall, ysf.getSrc(),  63); hdl.srcCall[63]='\0';
        strncpy(hdl.dstCall, ysf.getDest(), 63); hdl.dstCall[63]='\0';
    } else if (proto == 3 || proto == 4) { // NXDN48 / NXDN96
        const DSDcc::DSDNXDN& nxdn = dec->getNXDNDecoder();
        hdl.srcId = (int)nxdn.getSourceId();
        hdl.dstId = (int)nxdn.getDestinationId();
        // NXDN has no group/individual flag exposed at this layer; leave as-is.
        if (nxdn.getRFChannelStr() && nxdn.getRFChannelStr()[0]) {
            strncpy(hdl.srcCall, nxdn.getRFChannelStr(), 63); hdl.srcCall[63]='\0';
        }
    } else if (proto == 8) { // dPMR
        const DSDcc::DSDdPMR& dpmr = dec->getDPMRDecoder();
        hdl.srcId = (int)dpmr.getOwnId();
        hdl.dstId = (int)dpmr.getCalledId();
    } else if (proto == 1) { // P25 Phase 1
        const DSDcc::DSDP25P1& p25 = dec->getP25P1Decoder();
        hdl.srcId   = (int)p25.getSourceId();
        hdl.dstId   = (int)p25.getDestinationId();
        hdl.isGroup = p25.isGroupCall();
        hdl.encrypted = p25.isEncrypted();
    }
}

// ── PCM collection ────────────────────────────────────────────────────────────
// Drain mbelib audio from decoder and append to hdl.pcmBuf.
//
// IMPORTANT: only drains the slot that actually triggered this call.
// Previously this unconditionally drained BOTH getAudio1() AND getAudio2()
// every time it ran -- so even with the DMR timeslot lock in feedSamples()
// preventing an explicit collectMbelibAudio() call on the *inactive* slot's
// ready event, the *active* slot's own call would still reach into the
// inactive slot's decoder-internal audio accumulator and splice whatever
// had built up there onto the end of the active slot's audio, in the same
// buffer. That's two independent, unsynchronised 8 kHz voice streams
// concatenated together -- exactly the high-pitched beat/warble artifact.
// D-STAR/YSF never populate a slot-2 buffer, so they were never affected.
static void collectMbelibAudio(DecoderHandle& hdl, int slot /* 1 or 2 */) {
    DSDcc::DSDDecoder* dec = hdl.dec.get();
    if (slot == 1) {
        int n1 = 0;
        short* a1 = dec->getAudio1(n1);
        if (n1 > 0 && a1) {
            hdl.pcmBuf.insert(hdl.pcmBuf.end(), a1, a1 + n1);
            dec->resetAudio1();
            hdl.hasPcm = true;
        }
        const mbe_process_result& r = dec->getLastMbeResult1();
        hdl.framesSeen1++;
        if (r.flags & MBE_PROCESS_FLAG_TONE)    hdl.toneCount1++;
        if (r.flags & MBE_PROCESS_FLAG_ERASURE) hdl.erasureCount1++;
        if (r.flags & MBE_PROCESS_FLAG_REPEAT)  hdl.repeatCount1++;
        if (r.flags & MBE_PROCESS_FLAG_MUTE)    hdl.muteCount1++;
        hdl.lastC0Errors1 = (r.flags & MBE_PROCESS_FLAG_C0_VALID) ? r.c0_errors : 0;
        hdl.lastTotalErrors1 = r.total_errors;
    } else {
        int n2 = 0;
        short* a2 = dec->getAudio2(n2);
        if (n2 > 0 && a2) {
            hdl.pcmBuf.insert(hdl.pcmBuf.end(), a2, a2 + n2);
            dec->resetAudio2();
            hdl.hasPcm = true;
        }
        const mbe_process_result& r = dec->getLastMbeResult2();
        hdl.framesSeen2++;
        if (r.flags & MBE_PROCESS_FLAG_TONE)    hdl.toneCount2++;
        if (r.flags & MBE_PROCESS_FLAG_ERASURE) hdl.erasureCount2++;
        if (r.flags & MBE_PROCESS_FLAG_REPEAT)  hdl.repeatCount2++;
        if (r.flags & MBE_PROCESS_FLAG_MUTE)    hdl.muteCount2++;
        hdl.lastC0Errors2 = (r.flags & MBE_PROCESS_FLAG_C0_VALID) ? r.c0_errors : 0;
        hdl.lastTotalErrors2 = r.total_errors;
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  JNI exports — DsdccNative (handle-based streaming API)
// ═════════════════════════════════════════════════════════════════════════════
extern "C" {

// ── createDecoder ─────────────────────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_radiosport_ninegradio_dsp_DsdccNative_createDecoder(
        JNIEnv*, jclass, jint protocol)
{
    int h = allocHandle();
    if (h < 0) { LOGW("No free handles"); return -1; }

    DecoderHandle& hdl = g_handles[h];
    hdl.dec      = std::make_unique<DSDcc::DSDDecoder>();
    hdl.protocol = (int)protocol;
    hdl.dvReady1 = hdl.dvReady2 = false;
    hdl.srcId = hdl.dstId = 0;
    hdl.isGroup = hdl.encrypted = false;
    hdl.srcCall[0] = hdl.dstCall[0] = '\0';
    hdl.pcmBuf.reserve(4096);
    hdl.hasPcm = false;
    hdl.activeSlot = 0;
    hdl.samplesSinceActiveAudio = 0;
    hdl.toneCount1 = hdl.toneCount2 = 0;
    hdl.erasureCount1 = hdl.erasureCount2 = 0;
    hdl.repeatCount1 = hdl.repeatCount2 = 0;
    hdl.muteCount1 = hdl.muteCount2 = 0;
    hdl.framesSeen1 = hdl.framesSeen2 = 0;
    hdl.lastC0Errors1 = hdl.lastC0Errors2 = 0;
    hdl.lastTotalErrors1 = hdl.lastTotalErrors2 = 0;
    hdl.autoIdleSamples = 0;
    hdl.autoAt2400 = false;

    DSDcc::DSDDecoder* dec = hdl.dec.get();
    dec->setQuiet();
    dec->setLogVerbosity(0);

    // Always decode via the built-in mbelib-neo vocoder.
    // NOTE: do NOT call setUpsampling() here.  mbelib must output raw 8 kHz PCM
    // (160 samples per 20 ms frame).  The Kotlin layer in startDigVoiceAudio()
    // performs a single linear-interpolation upsample from 8 kHz to audioSinkRate
    // (typically 48 kHz, ratio = 6).  Calling setUpsampling(6) here caused mbelib
    // to produce 960 samples already at 48 kHz, and startDigVoiceAudio() then
    // upsampled those again by 6×, producing 5760-sample buffers that took 120 ms
    // each to drain from the DROP_OLDEST channel while frames arrived every 20 ms —
    // resulting in continuous channel overflow and complete silence.
    dec->enableMbelib(true);

    // Decode mode
    DSDcc::DSDDecoder::DSDDecodeMode mode = protocolToMode((int)protocol);
    dec->setDecodeMode(DSDcc::DSDDecoder::DSDDecodeNone, true);
    dec->setDecodeMode(mode, true);

    // Symbol clock recovery strategy — protocol-dependent.
    //
    // D-STAR (GMSK / 2FSK): the ringing filter squares a near-binary signal,
    // producing a clean 4800 Hz spectral component.  The PLL locks reliably and
    // improves sampling accuracy over fixed-center timing.  Leave PLL enabled.
    //
    // DMR / YSF (C4FM / 4FSK): squaring a 4-level signal yields TWO amplitude
    // levels (d² and 9d²), weakening the 4800 Hz ringing-filter component and
    // making PLL lock unreliable.  A mis-locked PLL nudges the sampling point
    // away from the symbol centre, collapsing the inner-symbol eye and causing
    // systematic dibit misclassification.  Disable PLL for these modes and rely
    // on the fixed centre-of-window sampling (samples 4–5 of the 10-sample
    // window), which is robust for any 4FSK signal at the correct symbol rate.
    bool use4FSK = (protocol != 5); // protocol 5 = D-STAR (2FSK); all others are 4FSK
    dec->setSymbolPLLLock(!use4FSK);

    LOGI("createDecoder proto=%d handle=%d", (int)protocol, h);
    return (jlong)h;
}

// ── destroyDecoder ────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_DsdccNative_destroyDecoder(
        JNIEnv*, jclass, jlong handle)
{
    DecoderHandle* hdl = getHandle(handle);
    if (!hdl) return;
    std::lock_guard<std::mutex> lk(hdl->mtx);
    hdl->dec.reset();
    hdl->pcmBuf.clear();
    LOGI("destroyDecoder handle=%d", (int)handle);
}

// ── feedSamples ───────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_DsdccNative_feedSamples(
        JNIEnv* env, jclass, jlong handle,
        jshortArray samples, jint length)
{
    DecoderHandle* hdl = getHandle(handle);
    if (!hdl) return;

    jshort* buf = env->GetShortArrayElements(samples, nullptr);
    if (!buf) return;

    int len = (int)length;
    {
        std::lock_guard<std::mutex> lk(hdl->mtx);
        DSDcc::DSDDecoder* dec = hdl->dec.get();

        for (int i = 0; i < len; i++) {
            dec->run((short)buf[i]);

            // ── Slot 1 (single-slot modes: DSTAR, YSF; DMR slot 1) ──────────
            //
            // An earlier version of this code dropped ALL cross-slot
            // suppression, reasoning that dsdcc's own DMR parser re-tags
            // frames between its internal decoder-1/decoder-2 slots as part
            // of its sync-recovery/superframe bookkeeping ("fake slot
            // reversal" in dmr.cpp's processSyncOrSkip()), so any locking
            // would drop legitimate continuation audio. That's only half
            // the picture: m_mbeDecoder1/m_mbeDecoder2 are genuinely
            // separate AMBE decoders for the two *physical* TDMA slots, and
            // several hotspots (OpenSpot-class devices included) mirror one
            // active call onto BOTH TS1 and TS2 so a radio parked on either
            // slot hears it. With no lock at all, both decoders validly
            // decode the SAME speech and both get appended to hdl->pcmBuf --
            // two independently-clocked copies of the same words spliced
            // together, heard as a constant, TDMA-rate-locked beat/warble
            // for the whole transmission (frame count, RSSI, SNR all look
            // normal because sync/FEC really are fine; only PCM assembly is
            // wrong). D-STAR/YSF have no second slot and were never
            // affected. The activeSlot lock below claims whichever slot
            // starts a call and ignores DV audio arriving on the other slot
            // until the locked slot goes quiet (see
            // DecoderHandle::kSlotLockTimeoutSamples) -- so mirrored/
            // duplicate traffic is ignored, while dsdcc's internal frame
            // re-tagging within ONE physical slot's own continuation still
            // plays normally, since that never toggles activeSlot away from
            // the slot that's actually carrying the call.
            if (dec->mbeDVReady1()) {
                const unsigned char* frame = dec->getMbeDVFrame1();
                memcpy(hdl->dvBuf1, frame, DecoderHandle::MAX_DV);
                hdl->dvReady1 = true;
                dec->resetMbeDV1();

                // Active-slot lock: claim slot 1 if unclaimed, otherwise only
                // play it if it's the slot we're already locked to. See the
                // kSlotLockTimeoutSamples comment on DecoderHandle for why --
                // this stops a mirrored/duplicate TS2 copy of the same call
                // from being spliced into the same PCM stream as TS1.
                // Debounced for dual-slot protocols only (DMR / auto-detect):
                // an unclaimed slot needs kSlotConfirmFrames consecutive
                // frames before it locks, so a single stray frame (possibly
                // from a mirrored duplicate on the other physical slot) never
                // reaches pcmBuf. D-STAR/YSF never populate slot 2, so there
                // is no mirroring risk there -- skip the debounce for them to
                // avoid delaying/dropping the first frame of every call.
                bool dualSlot = (hdl->protocol == 0 || hdl->protocol == 7);
                if (hdl->activeSlot == 0) {
                    if (!dualSlot) {
                        hdl->activeSlot = 1;
                    } else {
                        hdl->candidateFrameCount1++;
                    }
                    if (dualSlot && hdl->candidateFrameCount1 >= DecoderHandle::kSlotConfirmFrames) {
                        hdl->activeSlot = 1;
                        hdl->candidateFrameCount1 = 0;
                        hdl->candidateFrameCount2 = 0;
                    }
                }
                if (hdl->activeSlot == 1) {
                    extractMetadata(*hdl);
                    // mbelib path: dsdcc already called processFrame internally
                    collectMbelibAudio(*hdl, 1);
                    hdl->samplesSinceActiveAudio = 0;
                }
            }

            // ── Slot 2 (DMR time-slot 2) ─────────────────────────────────────
            if (dec->mbeDVReady2()) {
                const unsigned char* frame = dec->getMbeDVFrame2();
                memcpy(hdl->dvBuf2, frame, DecoderHandle::MAX_DV);
                hdl->dvReady2 = true;
                dec->resetMbeDV2();

                if (hdl->activeSlot == 0) {
                    hdl->candidateFrameCount2++;
                    if (hdl->candidateFrameCount2 >= DecoderHandle::kSlotConfirmFrames) {
                        hdl->activeSlot = 2;
                        hdl->candidateFrameCount1 = 0;
                        hdl->candidateFrameCount2 = 0;
                    }
                }
                if (hdl->activeSlot == 2) {
                    collectMbelibAudio(*hdl, 2);
                    hdl->samplesSinceActiveAudio = 0;
                }
            }

            // Release the lock once the locked slot has been quiet long
            // enough that the call is over (or handed off) -- lets either
            // slot claim the next call instead of sticking forever.
            if (hdl->activeSlot != 0) {
                if (++hdl->samplesSinceActiveAudio > DecoderHandle::kSlotLockTimeoutSamples) {
                    hdl->activeSlot = 0;
                    hdl->samplesSinceActiveAudio = 0;
                    hdl->candidateFrameCount1 = 0;
                    hdl->candidateFrameCount2 = 0;
                }
            }
        }

        // ── Auto/DIG-mode data-rate cycling (see DecoderHandle comment) ──────
        // Only meaningful for PROTO_AUTO handles; dedicated single-protocol
        // handles never change rate. Checked once per feedSamples() batch
        // (not per-sample) -- the toggle only needs ~2 Hz granularity and
        // this keeps the per-sample hot loop above untouched.
        if (hdl->protocol == 7 && hdl->dec) {
            if (dec->getSyncType() != DSDcc::DSDDecoder::DSDSyncNone) {
                // Actively locked (any protocol/rate) -- leave it alone.
                hdl->autoIdleSamples = 0;
            } else {
                hdl->autoIdleSamples += len;
                if (hdl->autoIdleSamples >= DecoderHandle::kAutoRateToggleSamples) {
                    hdl->autoIdleSamples = 0;
                    hdl->autoAt2400 = !hdl->autoAt2400;
                    dec->setDataRate(hdl->autoAt2400 ? DSDcc::DSDDecoder::DSDRate2400
                                                      : DSDcc::DSDDecoder::DSDRate4800);
                    dec->setDecodeMode(DSDcc::DSDDecoder::DSDDecodeAuto, true);
                }
            }
        }
    }

    env->ReleaseShortArrayElements(samples, buf, JNI_ABORT);
}

// ── getMetadata ───────────────────────────────────────────────────────────────
// Returns int[8]: [srcId, dstId, isGroup, encrypted, dvReady, hasPcm,
//                  fichError, mbeRate]
//
// fichError (index 6) — YSF FICH decode status (diagnostic only, no gate):
//   0=OK  1=Golay FEC failed  2=CRC16 mismatch  -1=N/A (non-YSF)
//
// mbeRate (index 7) — DSDMBERate ordinal for last frame:
//   3=DSDMBERate3600x2450 (YSF VD1)  6=DSDMBERate2450 (YSF VD2)
//   7=DSDMBERate4400 (YSF VFR)       -1=N/A
JNIEXPORT jintArray JNICALL
Java_com_radiosport_ninegradio_dsp_DsdccNative_getMetadata(
        JNIEnv* env, jclass, jlong handle)
{
    DecoderHandle* hdl = getHandle(handle);
    jintArray arr = env->NewIntArray(8);
    if (!hdl || !arr) return arr;

    std::lock_guard<std::mutex> lk(hdl->mtx);
    bool dv = hdl->dvReady1 || hdl->dvReady2;

    jint fichErr = -1;
    jint mbeRate = -1;
    if (hdl->protocol == 6 && hdl->dec) {
        const DSDcc::DSDYSF& ysf = hdl->dec->getYSFDecoder();
        switch (ysf.getFICHError()) {
            case DSDcc::DSDYSF::FICHNoError:    fichErr = 0; break;
            case DSDcc::DSDYSF::FICHErrorGolay: fichErr = 1; break;
            case DSDcc::DSDYSF::FICHErrorCRC:   fichErr = 2; break;
        }
        mbeRate = (jint)hdl->dec->getMbeRate();
    }

    jint data[8] = {
        (jint)hdl->srcId,
        (jint)hdl->dstId,
        (jint)(hdl->isGroup   ? 1 : 0),
        (jint)(hdl->encrypted ? 1 : 0),
        (jint)(dv             ? 1 : 0),
        (jint)(hdl->hasPcm    ? 1 : 0),
        fichErr,
        mbeRate
    };
    env->SetIntArrayRegion(arr, 0, 8, data);
    hdl->dvReady1 = hdl->dvReady2 = false;
    if (hdl->pcmBuf.empty()) hdl->hasPcm = false;
    return arr;
}

// ── getVocoderStats ────────────────────────────────────────────────────────────
// Diagnostic telemetry for the debug panel: proves what the vocoder is doing
// frame-by-frame instead of guessing from the decoded audio. mbelib-neo path
// only.
//
// Returns long[15]:
//   [0] activeSlot          — 0=unclaimed, 1 or 2 = which slot is currently locked
//   [1] framesSeen1         — cumulative slot-1 vocoder frames processed
//   [2] toneCount1          — cumulative MBE_PROCESS_FLAG_TONE hits, slot 1
//   [3] erasureCount1       — cumulative MBE_PROCESS_FLAG_ERASURE hits, slot 1
//   [4] repeatCount1        — cumulative MBE_PROCESS_FLAG_REPEAT hits, slot 1
//   [5] muteCount1          — cumulative MBE_PROCESS_FLAG_MUTE hits, slot 1
//   [6] lastC0Errors1       — corrected C0 errors, most recent slot-1 frame
//   [7] lastTotalErrors1    — total corrected errors, most recent slot-1 frame
//   [8] framesSeen2         — cumulative slot-2 vocoder frames processed
//   [9] toneCount2          — cumulative MBE_PROCESS_FLAG_TONE hits, slot 2
//   [10] erasureCount2      — cumulative MBE_PROCESS_FLAG_ERASURE hits, slot 2
//   [11] repeatCount2       — cumulative MBE_PROCESS_FLAG_REPEAT hits, slot 2
//   [12] muteCount2         — cumulative MBE_PROCESS_FLAG_MUTE hits, slot 2
//   [13] lastC0Errors2      — corrected C0 errors, most recent slot-2 frame
//   [14] lastTotalErrors2   — total corrected errors, most recent slot-2 frame
//
// If toneCount1/2 climbs in step with an audible beat, the AMBE tone-frame
// path (ambe3600x2400.c) is the cause. If it stays at 0 while the beat is
// present, the vocoder isn't the culprit and the bug is upstream (demod) or
// downstream (DspEngine audio pipeline).
JNIEXPORT jlongArray JNICALL
Java_com_radiosport_ninegradio_dsp_DsdccNative_getVocoderStats(
        JNIEnv* env, jclass, jlong handle)
{
    DecoderHandle* hdl = getHandle(handle);
    jlongArray arr = env->NewLongArray(15);
    if (!hdl || !arr) return arr;

    std::lock_guard<std::mutex> lk(hdl->mtx);
    jlong data[15] = {
        (jlong)hdl->activeSlot,
        (jlong)hdl->framesSeen1, (jlong)hdl->toneCount1, (jlong)hdl->erasureCount1,
        (jlong)hdl->repeatCount1, (jlong)hdl->muteCount1,
        (jlong)hdl->lastC0Errors1, (jlong)hdl->lastTotalErrors1,
        (jlong)hdl->framesSeen2, (jlong)hdl->toneCount2, (jlong)hdl->erasureCount2,
        (jlong)hdl->repeatCount2, (jlong)hdl->muteCount2,
        (jlong)hdl->lastC0Errors2, (jlong)hdl->lastTotalErrors2
    };
    env->SetLongArrayRegion(arr, 0, 15, data);
    return arr;
}

// ── getPcm ────────────────────────────────────────────────────────────────────
// Drain the PCM accumulation buffer.  Returns a ShortArray (may be empty).
JNIEXPORT jshortArray JNICALL
Java_com_radiosport_ninegradio_dsp_DsdccNative_getPcm(
        JNIEnv* env, jclass, jlong handle)
{
    DecoderHandle* hdl = getHandle(handle);
    if (!hdl) return env->NewShortArray(0);

    std::lock_guard<std::mutex> lk(hdl->mtx);
    int n = (int)hdl->pcmBuf.size();
    jshortArray arr = env->NewShortArray(n);
    if (arr && n > 0) {
        env->SetShortArrayRegion(arr, 0,  n,
            reinterpret_cast<const jshort*>(hdl->pcmBuf.data()));
        hdl->pcmBuf.clear();
        hdl->hasPcm = false;
    }
    return arr;
}

// ── getSrcCall / getDstCall ──────────────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_radiosport_ninegradio_dsp_DsdccNative_getSrcCall(
        JNIEnv* env, jclass, jlong handle)
{
    DecoderHandle* hdl = getHandle(handle);
    if (!hdl) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lk(hdl->mtx);
    return env->NewStringUTF(hdl->srcCall);
}

JNIEXPORT jstring JNICALL
Java_com_radiosport_ninegradio_dsp_DsdccNative_getDstCall(
        JNIEnv* env, jclass, jlong handle)
{
    DecoderHandle* hdl = getHandle(handle);
    if (!hdl) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lk(hdl->mtx);
    return env->NewStringUTF(hdl->dstCall);
}

// ── getDetectedProtocol ───────────────────────────────────────────────────────
// Returns the protocol ordinal that dsdcc locked onto in DSDDecodeAuto mode,
// derived from the current DSDSyncType.  Returns -1 if no sync has been found.
// Mapping: 0=DMR, 1=P25 Phase 1, 3=NXDN48, 4=NXDN96, 5=D-STAR, 6=YSF, 8=dPMR.
JNIEXPORT jint JNICALL
Java_com_radiosport_ninegradio_dsp_DsdccNative_getDetectedProtocol(
        JNIEnv*, jclass, jlong handle)
{
    DecoderHandle* hdl = getHandle(handle);
    if (!hdl || !hdl->dec) return -1;
    std::lock_guard<std::mutex> lk(hdl->mtx);
    switch (hdl->dec->getSyncType()) {
        case DSDcc::DSDDecoder::DSDSyncP25p1P:
        case DSDcc::DSDDecoder::DSDSyncP25p1N:
            return 1; // P25 Phase 1
        case DSDcc::DSDDecoder::DSDSyncDStarP:
        case DSDcc::DSDDecoder::DSDSyncDStarN:
        case DSDcc::DSDDecoder::DSDSyncDStarHeaderP:
        case DSDcc::DSDDecoder::DSDSyncDStarHeaderN:
            return 5; // D-STAR
        case DSDcc::DSDDecoder::DSDSyncNXDNP:
        case DSDcc::DSDDecoder::DSDSyncNXDNN:
        case DSDcc::DSDDecoder::DSDSyncNXDNDataP:
        case DSDcc::DSDDecoder::DSDSyncNXDNDataN:
            // dsdcc uses the same DSDSyncNXDN* constants for both NXDN48 (2400
            // baud) and NXDN96 (4800 baud) -- the variant is only distinguished
            // by which data rate the decoder was on when it locked (see the
            // "if (m_dataRate == DSDRate2400)" branches in dsd_decoder.cpp's
            // NXDN sync handlers, and the auto-rate-cycling comment on
            // DecoderHandle above).
            return (hdl->dec->getDataRate() == DSDcc::DSDDecoder::DSDRate2400) ? 3 : 4; // NXDN48 : NXDN96
        case DSDcc::DSDDecoder::DSDSyncDMRDataP:
        case DSDcc::DSDDecoder::DSDSyncDMRDataMS:
        case DSDcc::DSDDecoder::DSDSyncDMRVoiceP:
        case DSDcc::DSDDecoder::DSDSyncDMRVoiceMS:
            return 0; // DMR
        case DSDcc::DSDDecoder::DSDSyncYSF:
            return 6; // YSF
        case DSDcc::DSDDecoder::DSDSyncDPMR:
        case DSDcc::DSDDecoder::DSDSyncDPMRPacket:
        case DSDcc::DSDDecoder::DSDSyncDPMRPayload:
        case DSDcc::DSDDecoder::DSDSyncDPMREnd:
            return 8; // dPMR
        default:
            return -1;
    }
}

// ── Legacy single-shot decode (DigitalVoiceJni.nativeDecode) ─────────────────
static jobject makeDsdNeoResult(JNIEnv* env,
                                jint srcId, jint dstId,
                                jboolean isGroup, jboolean encrypted,
                                jboolean emergency, const char* talkerAlias,
                                jshortArray pcm)
{
    jclass cls = env->FindClass(
        "com/radiosport/ninegradio/dsp/DigitalVoiceJni$DsdNeoResult");
    if (!cls) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>",
        "(IIZZZLjava/lang/String;[S)V");
    if (!ctor) return nullptr;
    jstring jAlias = env->NewStringUTF(talkerAlias ? talkerAlias : "");
    jobject obj = env->NewObject(cls, ctor,
        srcId, dstId, isGroup, encrypted, emergency, jAlias, pcm);
    env->DeleteLocalRef(jAlias);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_com_radiosport_ninegradio_dsp_DigitalVoiceJni_nativeDecode(
        JNIEnv* env, jclass,
        jbyteArray symbols, jint protocol)
{
    jbyte* sym = env->GetByteArrayElements(symbols, nullptr);
    int    len = (int)env->GetArrayLength(symbols);

    DSDcc::DSDDecoder dec;
    dec.setQuiet();
    dec.setLogVerbosity(0);
    dec.enableMbelib(true);
    // NOTE: do NOT call setUpsampling() here — same reason as createDecoder().
    // mbelib must output raw 8 kHz PCM (160 samples/frame); callers handle
    // upsampling once.  setUpsampling(6) here would produce 960-sample buffers
    // that callers would then mistakenly upsample again by 6×.

    DSDcc::DSDDecoder::DSDDecodeMode mode = protocolToMode((int)protocol);
    dec.setDecodeMode(DSDcc::DSDDecoder::DSDDecodeNone, true);
    dec.setDecodeMode(mode, true);
    // PLL: enabled for D-STAR (2FSK), disabled for all 4FSK protocols — same
    // rationale as createDecoder(); see comment there for full explanation.
    dec.setSymbolPLLLock(protocol == 5);

    bool isDstar = (protocol == 5);
    std::vector<int16_t> pcmOut;

    for (int i = 0; i < len; i++) {
        int d = (int)(sym[i]) & 0x03;
        short s;
        if (isDstar) { s = (d == 0) ? 16384 : -16384; }
        else {
            switch (d) {
                case 0: s =  10920; break;
                case 1: s =   3640; break;
                case 2: s =  -3640; break;
                default: s = -10920; break;
            }
        }
        for (int j = 0; j < 10; j++) dec.run(s);

        if (dec.mbeDVReady1()) {
            dec.resetMbeDV1();
            int n = 0; short* a = dec.getAudio1(n);
            if (n > 0) { pcmOut.insert(pcmOut.end(), a, a + n); dec.resetAudio1(); }
        }
        if (dec.mbeDVReady2()) {
            dec.resetMbeDV2();
            int n = 0; short* a = dec.getAudio2(n);
            if (n > 0) { pcmOut.insert(pcmOut.end(), a, a + n); dec.resetAudio2(); }
        }
    }

    env->ReleaseByteArrayElements(symbols, sym, JNI_ABORT);

    // Build PCM array
    jshortArray jpcm = env->NewShortArray((jsize)pcmOut.size());
    if (!pcmOut.empty() && jpcm)
        env->SetShortArrayRegion(jpcm, 0, (jsize)pcmOut.size(),
            reinterpret_cast<const jshort*>(pcmOut.data()));
    if (!jpcm) jpcm = env->NewShortArray(0);

    // Metadata
    int srcId = 0, dstId = 0;
    bool isGroup = false, encrypted = false;
    char talkerAlias[128] = "";
    int proto = (int)protocol;
    if (proto == 5) {
        const DSDcc::DSDDstar& ds = dec.getDStarDecoder();
        strncpy(talkerAlias, ds.getMySign().c_str(), 127);
    } else if (proto == 6) {
        const DSDcc::DSDYSF& ysf = dec.getYSFDecoder();
        strncpy(talkerAlias, ysf.getSrc(), 127);
    } else if (proto == 3 || proto == 4) {
        const DSDcc::DSDNXDN& nxdn = dec.getNXDNDecoder();
        srcId = (int)nxdn.getSourceId();
        dstId = (int)nxdn.getDestinationId();
    } else if (proto == 8) {
        const DSDcc::DSDdPMR& dpmr = dec.getDPMRDecoder();
        srcId = (int)dpmr.getOwnId();
        dstId = (int)dpmr.getCalledId();
    } else if (proto == 1) {
        const DSDcc::DSDP25P1& p25 = dec.getP25P1Decoder();
        srcId = (int)p25.getSourceId();
        dstId = (int)p25.getDestinationId();
        isGroup = p25.isGroupCall();
        encrypted = p25.isEncrypted();
    }

    return makeDsdNeoResult(env, srcId, dstId, (jboolean)isGroup,
                            (jboolean)encrypted, JNI_FALSE, talkerAlias, jpcm);
}

} // extern "C" (decoder exports)
