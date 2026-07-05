package com.radiosport.ninegradio.dsp

/**
 * DigitalFrameFilter
 * ==================
 * Stateful per-protocol filter applied to every [DigitalFrame] produced by
 * [DigitalVoiceDecoder] before it is emitted on the shared flow.
 *
 * ## What counts as "garbage"?
 *
 * ### Numeric IDs (DMR / YSF / NXDN)
 * DMR / YSF radio IDs are 24-bit unsigned integers: valid range 1..16 777 215.
 * Talkgroup IDs share the same range.  Any srcId or dstId outside that range is
 * a FEC error artefact and the whole frame is rejected.
 *
 * ### Callsign strings (YSF / D-STAR)
 * - Valid characters in an ITU amateur / land-mobile callsign:
 *   A–Z, 0–9, '/', '-', ' ' (space used as padding).
 * - After trimming trailing spaces the callsign must be non-empty and at least
 *   2 printable non-space characters long (single-char results are never valid).
 * - A callsign that is all one repeated character (e.g. all spaces, all '@')
 *   is rejected.
 * - D-STAR: "CQCQCQ" is the universal CQ destination — valid, kept as-is.
 *
 * ### DMR slot-display text
 * The dsdcc library stores an internal slot-status display string (e.g.
 * " 01 DAT", " 02 VOX VCH") in the srcCall / dstCall fields for DMR — this
 * is a formatted diagnostic label, NOT a callsign.  It is stripped here so
 * the [DigitalFrame.talkerAlias] field never contains those internal strings.
 *
 * ### Rapid duplicates / bursts
 * dsdcc sometimes emits the same metadata multiple times on a good sync lock.
 * Frames with identical (srcId, dstId, protocol) within [DUPLICATE_WINDOW_MS]
 * of the previous one are collapsed to a single emission (the first is kept).
 * The SYNC_ONLY fallback frames are never de-duplicated (they carry no ID).
 *
 * ### Minimum frame rate gate
 * A single isolated frame that cannot be followed by a second frame of the
 * same protocol within [BURST_GATE_MS] is very likely a noise spike.
 * SYNC_ONLY frames are exempt (they are already low-confidence markers).
 * The gate is implemented as a tiny ring-buffer of recent timestamps so
 * it never delays or drops a real voice call.
 */
class DigitalFrameFilter {

    // ── tuneable constants ────────────────────────────────────────────────────

    companion object {
        /** DMR / YSF numeric ID valid range (24-bit, 1-based). */
        private const val ID_MIN = 1
        private const val ID_MAX = 16_777_215

        /**
         * Collapse duplicate (srcId, dstId, protocol) frames received within
         * this window.  One DMR super-frame = 6 × 20 ms = 120 ms; use 200 ms
         * to be safe across frame boundaries.
         */
        private const val DUPLICATE_WINDOW_MS = 200L

        /**
         * Minimum number of frames of the same protocol that must appear within
         * this window before the first one is forwarded.  This rejects lone-spike
         * glitch frames while still letting a real call through within ~60 ms.
         * Set to 1 to disable the gate entirely.
         */
        private const val BURST_GATE_REQUIRED = 2
        private const val BURST_GATE_MS = 400L

        /** Maximum length accepted for a cleaned callsign string. */
        private const val MAX_CALLSIGN_LEN = 32

        /**
         * Characters permitted in ITU callsigns / D-STAR / YSF identifiers.
         * Lowercase letters are normalised to uppercase before this check.
         */
        private val CALLSIGN_CHARS = (('A'..'Z') + ('0'..'9') + listOf('/', '-', ' ')).toSet()

        /** Well-known D-STAR / YSF broadcast destination — always valid. */
        private val KNOWN_DESTINATIONS = setOf("CQCQCQ", "ALL", "YSFGATEWAY")
    }

    // ── state ─────────────────────────────────────────────────────────────────

    /** Last accepted frame key for duplicate suppression. */
    private data class FrameKey(
        val protocol: DigitalFrame.Protocol,
        val srcId:    Int,
        val dstId:    Int
    )

    private var lastKey:  FrameKey = FrameKey(DigitalFrame.Protocol.UNKNOWN, -1, -1)
    private var lastKeyTs: Long    = 0L

    /**
     * Per-protocol ring-buffer of recent frame arrival times for the burst gate.
     * Key: Protocol. Value: circular list of timestamps.
     */
    private val recentTs = HashMap<DigitalFrame.Protocol, ArrayDeque<Long>>()

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Decide whether [frame] should be forwarded to the UI.
     *
     * Returns a possibly-mutated copy of [frame] with cleaned string fields if
     * the frame is valid, or **null** if the frame should be discarded entirely.
     */
    fun accept(frame: DigitalFrame): DigitalFrame? {
        val now = frame.timestamp

        // ── 1. SYNC_ONLY frames bypass most checks (no ID / callsign data) ──
        if (frame.frameType == DigitalFrame.FrameType.SYNC_ONLY) {
            return bumpAndGate(frame, now)
        }

        // ── 2. Validate numeric IDs where the protocol uses them ─────────────
        if (frame.protocol == DigitalFrame.Protocol.DMR  ||
            frame.protocol == DigitalFrame.Protocol.YSF  ||
            frame.protocol == DigitalFrame.Protocol.NXDN96 ||
            frame.protocol == DigitalFrame.Protocol.NXDN48 ||
            frame.protocol == DigitalFrame.Protocol.DPMR ||
            frame.protocol == DigitalFrame.Protocol.P25_PHASE1) {

            // srcId==0 is valid (not yet decoded); srcId outside 24-bit → garbage
            val srcOk = frame.srcId == 0 || frame.srcId in ID_MIN..ID_MAX
            val dstOk = frame.dstId == 0 || frame.dstId in ID_MIN..ID_MAX
            if (!srcOk || !dstOk) return null
        }

        // ── 3. Clean and validate callsign / alias strings ───────────────────
        val cleanedAlias = cleanCallsign(frame.talkerAlias, frame.protocol)

        // ── 4. DMR: discard frames where the only "data" is a zero srcId and
        //    an empty (or slot-display-only) alias — nothing useful to show.
        if (frame.protocol == DigitalFrame.Protocol.DMR &&
            frame.srcId == 0 && frame.dstId == 0 && cleanedAlias.isEmpty()) {
            return null
        }

        // ── 5. Duplicate suppression ─────────────────────────────────────────
        val key = FrameKey(frame.protocol, frame.srcId, frame.dstId)
        if (key == lastKey && (now - lastKeyTs) < DUPLICATE_WINDOW_MS) {
            // Same call still in progress — audio is welcome, metadata already shown.
            // Forward only if it carries new PCM (voice continuation); otherwise drop.
            if (frame.pcmAudio.isEmpty()) return null
            // Keep frame but skip the burst-gate re-check for continuation audio.
            return frame.copy(talkerAlias = cleanedAlias)
        }
        lastKey   = key
        lastKeyTs = now

        // ── 6. Burst gate: require ≥ BURST_GATE_REQUIRED frames in the window ─
        val gated = bumpAndGate(frame.copy(talkerAlias = cleanedAlias), now)
        return gated
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Push the current timestamp into the per-protocol ring-buffer and decide
     * whether the frame clears the burst gate.
     */
    private fun bumpAndGate(frame: DigitalFrame, now: Long): DigitalFrame? {
        val deque = recentTs.getOrPut(frame.protocol) { ArrayDeque() }
        deque.addLast(now)
        // Prune entries outside the window
        while (deque.isNotEmpty() && now - deque.first() > BURST_GATE_MS) {
            deque.removeFirst()
        }
        return if (deque.size >= BURST_GATE_REQUIRED) frame else null
    }

    /**
     * Validate and clean a callsign / alias string for the given protocol.
     *
     * Steps:
     *  1. Null-safe, trim trailing/leading whitespace.
     *  2. Truncate to [MAX_CALLSIGN_LEN].
     *  3. Normalise to uppercase.
     *  4. Replace any character outside [CALLSIGN_CHARS] with '?'.
     *  5. Collapse runs of spaces to a single space.
     *  6. Reject if fewer than 2 distinct non-space characters remain.
     *  7. Reject if all characters are the same (all-X garbage).
     *  8. Strip known-garbage DMR slot display prefixes (e.g. " 01 DAT").
     *
     * Returns the cleaned string, or "" if the input is invalid.
     */
    private fun cleanCallsign(raw: String, protocol: DigitalFrame.Protocol): String {
        if (raw.isBlank()) return ""

        // Step 1-3: trim, truncate, uppercase
        var s = raw.trim().take(MAX_CALLSIGN_LEN).uppercase()

        // Step 4: replace invalid chars
        s = s.map { c -> if (c in CALLSIGN_CHARS) c else '?' }.joinToString("")

        // Reject if substitution rate is high (> 30% '?' chars → likely binary garbage)
        val questionCount = s.count { it == '?' }
        if (questionCount > 0 && questionCount.toFloat() / s.length > 0.30f) return ""

        // Remove substituted chars entirely (they add no information)
        s = s.replace("?", "").trim()

        // Step 5: collapse runs of spaces
        s = s.replace(Regex("  +"), " ").trim()

        if (s.isEmpty()) return ""

        // Step 6: at least 2 distinct non-space chars
        val nonSpace = s.filter { it != ' ' }
        if (nonSpace.length < 2) return ""

        // Step 7: reject all-same-character strings (e.g. "AAAAAAA", "       ")
        if (nonSpace.all { it == nonSpace[0] }) return ""

        // Step 8: strip DMR internal slot-display strings.
        // dsdcc formats slot0light/slot1light as " CC XYZ" where CC is the
        // colour-code (2 digits) and XYZ is a 3-letter type token.
        // Pattern: optional space, 1-2 digits, space, 2-3 uppercase letters.
        if (protocol == DigitalFrame.Protocol.DMR) {
            if (s.matches(Regex("""^\d{1,2}\s+[A-Z]{2,4}.*"""))) return ""
            // Slot light full format: "01 VOX VCH", "02 DAT", etc.
            if (s.matches(Regex("""^\d{1,2}\s+\w{2,4}(\s+\w{2,4})?$"""))) return ""
        }

        // Well-known valid broadcast identifiers — pass through regardless
        if (s in KNOWN_DESTINATIONS) return s

        // D-STAR: mySign is "CALLSIGN/SUFX" — the suffix after '/' may be spaces
        if (protocol == DigitalFrame.Protocol.DSTAR) {
            s = sanitiseDstarCallsign(s)
        }

        return s
    }

    /**
     * D-STAR callsigns from dsdcc arrive as "CALLSIGN/SUFX" (8 + '/' + 4 chars).
     * Normalise: trim the suffix, keep only the base callsign if the suffix is blank.
     */
    private fun sanitiseDstarCallsign(s: String): String {
        val slash = s.indexOf('/')
        if (slash < 0) return s.trim()
        val base   = s.substring(0, slash).trim()
        val suffix = s.substring(slash + 1).trim()
        return when {
            base.isEmpty()              -> ""
            suffix.isEmpty()            -> base
            suffix.all { it == ' ' }   -> base
            else                        -> "$base/$suffix"
        }
    }

    /** Reset all state (e.g. on mode change). */
    fun reset() {
        lastKey   = FrameKey(DigitalFrame.Protocol.UNKNOWN, -1, -1)
        lastKeyTs = 0L
        recentTs.clear()
    }
}
