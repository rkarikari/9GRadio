/*
 * vocoder.h
 *
 * C bridge functions called by dsd_neo.cpp for AMBE+2/AMBE/IMBE decode.
 * Implemented in vocoder_jni.cpp using the statically-linked mbelib-neo
 * library. Declared here so dsd_neo.cpp can call them without depending
 * on mbelib-neo's internal headers directly.
 */

#ifndef VOCODER_H
#define VOCODER_H

#include <cinttypes>

#ifdef __cplusplus
extern "C" {
#endif

/** Returns 1 when the built-in vocoder is ready to decode (always 1 — the
 *  mbelib-neo library is statically linked into the app). */
int vocoder_ready(void);

/** DMR / NXDN / YSF — AMBE+2 3600×2400.
 *  codec: 9 bytes input.  pcm: 160 × int16_t output.
 *  Returns 1 on success. */
int vocoder_decode_2400(uint8_t *codec, int16_t *pcm);

/** P25 Phase 1 — IMBE 7200×4400.
 *  codec: 18 bytes input.  pcm: 160 × int16_t output.
 *  Returns 1 on success. */
int vocoder_decode_2450x1150(uint8_t *codec, int16_t *pcm);

/** D-STAR — AMBE 3600×2450.
 *  codec: 9 bytes input.  pcm: 160 × int16_t output.
 *  Returns 1 on success. */
int vocoder_decode_2450(uint8_t *codec, int16_t *pcm);

/** D-STAR — AMBE 3600×2450, bit-exact dsdcc path.
 *  bits: 72 bytes input, ONE BIT PER BYTE (value 0 or 1), in the exact
 *  receive order dsdcc's DSDDstar::processVoice() consumes them (i.e. the
 *  raw GMSK-demodulated bit for symbol k goes in bits[k], k=0..71 — no
 *  byte packing, no bit reversal). This matches dsdcc exactly: it assigns
 *  ambe_fr[dW[k]][dX[k]] = bits[k] directly, one bit at a time, as the
 *  symbols arrive. Preferred over vocoder_decode_2450() because it has no
 *  byte-packing round trip to get wrong.
 *  pcm: 160 × int16_t output. Returns 1 on success. */
int vocoder_decode_2450_bits(const uint8_t *bits, int16_t *pcm);

#ifdef __cplusplus
} // extern "C"
#endif

#endif // VOCODER_H
