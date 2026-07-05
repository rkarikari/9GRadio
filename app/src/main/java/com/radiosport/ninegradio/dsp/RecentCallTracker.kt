package com.radiosport.ninegradio.dsp

/**
 * Groups a stream of [DigitalFrame]s from a digital-voice mode (DMR, YSF,
 * D-STAR, dPMR, NXDN, P25, etc. -- anything decoded by [DigitalVoiceDecoder];
 * this deliberately does NOT include APRS, which is a packet-data mode, not
 * a voice-call mode) into "calls": one entry per transmission, updated in
 * place as more frames of that same transmission arrive, instead of one
 * entry per individual frame.
 *
 * A frame continues the most recent call if it shares the same
 * protocol/srcId/dstId/isGroup key AND arrived within [callGapMs] of the
 * previous frame in that call. Anything else -- a different talker or
 * destination, or the same one resuming after a gap long enough that the
 * previous transmission had clearly ended -- starts a new call entry.
 *
 * Only the most-recently-added call (index 0) is ever a candidate for
 * continuation. Calls further back in the list are already "closed" and are
 * never reopened even if a matching key reappears later -- that's a new call
 * (e.g. the same talkgroup keying up again after someone else's
 * transmission), not a continuation of an old one.
 */
class RecentCallTracker(
    private val maxEntries: Int = 200,
    private val callGapMs: Long = 3_000L
) {
    data class CallRecord(
        val protocol: DigitalFrame.Protocol,
        val srcId: Int,
        val dstId: Int,
        val isGroup: Boolean,
        var talkerAlias: String,
        var encrypted: Boolean,
        var emergency: Boolean,
        var frameType: DigitalFrame.FrameType,
        val firstTimestamp: Long,
        var lastTimestamp: Long,
        var frameCount: Int,
        var rssi: Float,
        var snr: Float,
        var pcmSamples: Long
    )

    private val _calls = mutableListOf<CallRecord>()

    /** Most-recent call first, same ordering the old per-frame lists used. */
    val calls: List<CallRecord> get() = _calls

    private fun continuesCall(r: CallRecord, f: DigitalFrame): Boolean =
        r.protocol == f.protocol && r.srcId == f.srcId && r.dstId == f.dstId &&
        r.isGroup == f.isGroup && (f.timestamp - r.lastTimestamp) <= callGapMs

    /**
     * Feeds one decoded frame in.
     *
     * @return true if this frame started a brand-new call entry (inserted at
     *   index 0), false if it updated the existing call already at index 0
     *   in place (so callers replace the corresponding row instead of
     *   inserting a new one).
     */
    fun addFrame(frame: DigitalFrame): Boolean {
        val current = _calls.firstOrNull()
        if (current != null && continuesCall(current, frame)) {
            current.lastTimestamp = frame.timestamp
            current.frameCount++
            current.pcmSamples += frame.pcmAudio.size
            current.rssi = frame.rssi
            current.snr = frame.snr
            current.frameType = frame.frameType
            if (frame.talkerAlias.isNotBlank()) current.talkerAlias = frame.talkerAlias
            if (frame.encrypted) current.encrypted = true
            if (frame.emergency) current.emergency = true
            return false
        }

        _calls.add(0, CallRecord(
            protocol       = frame.protocol,
            srcId          = frame.srcId,
            dstId          = frame.dstId,
            isGroup        = frame.isGroup,
            talkerAlias    = frame.talkerAlias,
            encrypted      = frame.encrypted,
            emergency      = frame.emergency,
            frameType      = frame.frameType,
            firstTimestamp = frame.timestamp,
            lastTimestamp  = frame.timestamp,
            frameCount     = 1,
            rssi           = frame.rssi,
            snr            = frame.snr,
            pcmSamples     = frame.pcmAudio.size.toLong()
        ))
        if (_calls.size > maxEntries) _calls.removeAt(_calls.size - 1)
        return true
    }

    fun clear() = _calls.clear()
}
