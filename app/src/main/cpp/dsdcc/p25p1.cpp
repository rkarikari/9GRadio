///////////////////////////////////////////////////////////////////////////////////
// See p25p1.h for provenance notes (ported from the classic DSD / dsd-neo
// P25 Phase 1 algorithm: process_IMBE / processLDU1 / processLDU2 / NID BCH
// decode). Source line-references below point at dsd-neo's
// src/protocol/p25/phase1/{p25p1_ldu.c,p25p1_ldu1.c,p25p1_ldu2.c,
// p25p1_check_nid.cpp} and its classic (pre-soft-decision) ancestor,
// szechyjs/dsd's src/{dsd_frame.c,p25p1_ldu.c,p25p1_ldu1.c,p25p1_ldu2.c}.
///////////////////////////////////////////////////////////////////////////////////

#include <cstring>

#include "p25p1.h"
#include "dsd_decoder.h"
#include "p25p1_bch.h"
#include "p25p1_hamming.h"
#include "p25p1_reedsolomon.h"

namespace DSDcc
{

// P25 Phase1 IMBE interleave schedule (TIA-102.BAAA-A). Numerically identical
// across szechyjs/dsd, dsd-fme and dsd-neo's p25p1_const.h -- verified by diff
// during this port.
static const int p25p1_iW[72] = {
    0, 2, 4, 1, 3, 5,  0, 2, 4, 1, 3, 6,  0, 2, 4, 1, 3, 6,  0, 2, 4, 1, 3, 6,
    0, 2, 4, 1, 3, 6,  0, 2, 4, 1, 3, 6,  0, 2, 5, 1, 3, 6,  0, 2, 5, 1, 3, 6,
    0, 2, 5, 1, 3, 7,  0, 2, 5, 1, 3, 7,  0, 2, 5, 1, 4, 7,  0, 3, 5, 2, 4, 7
};
static const int p25p1_iX[72] = {
    22, 20, 10, 20, 18, 0,  20, 18, 8,  18, 16, 13, 18, 16, 6,  16, 14, 11,
    16, 14, 4,  14, 12, 9,  14, 12, 2,  12, 10, 7,  12, 10, 0,  10, 8,  5,
    10, 8,  13, 8,  6,  3,  8,  6,  11, 6,  4,  1,  6,  4,  9,  4,  2,  6,
    4,  2,  7,  2,  0,  4,  2,  0,  5,  0,  13, 2,  0,  21, 3,  21, 11, 0
};
static const int p25p1_iY[72] = {
    1, 3, 5, 0, 2, 4,  1, 3, 6, 0, 2, 4,  1, 3, 6, 0, 2, 4,  1, 3, 6, 0, 2, 4,
    1, 3, 6, 0, 2, 4,  1, 3, 6, 0, 2, 5,  1, 3, 6, 0, 2, 5,  1, 3, 6, 0, 2, 5,
    1, 3, 6, 0, 2, 5,  1, 3, 7, 0, 2, 5,  1, 4, 7, 0, 3, 5,  2, 4, 7, 1, 3, 5
};
static const int p25p1_iZ[72] = {
    21, 19, 1,  21, 19, 9,  19, 17, 14, 19, 17, 7,  17, 15, 12, 17, 15, 5,
    15, 13, 10, 15, 13, 3,  13, 11, 8,  13, 11, 1,  11, 9,  6,  11, 9,  14,
    9,  7,  4,  9,  7,  12, 7,  5,  2,  7,  5,  10, 5,  3,  0,  5,  3,  8,
    3,  1,  5,  3,  1,  6,  1,  14, 3,  1,  22, 4,  22, 12, 1,  22, 20, 2
};

// Shared 36-block sequence for both LDU1 and LDU2 (identical framing; only
// what the hex words *mean* differs, handled in finishHexWord()/finishLdu*()).
const DSDP25P1::LduBlockType DSDP25P1::m_lduProgram[36] = {
    BlockImbe,                                             // IMBE #1
    BlockImbe,                                             // IMBE #2
    BlockHexWord, BlockHexWord, BlockHexWord, BlockHexWord,
    BlockImbe,                                             // IMBE #3
    BlockHexWord, BlockHexWord, BlockHexWord, BlockHexWord,
    BlockImbe,                                             // IMBE #4
    BlockHexWord, BlockHexWord, BlockHexWord, BlockHexWord,
    BlockImbe,                                             // IMBE #5
    BlockHexWord, BlockHexWord, BlockHexWord, BlockHexWord,
    BlockImbe,                                             // IMBE #6
    BlockHexWord, BlockHexWord, BlockHexWord, BlockHexWord,
    BlockImbe,                                             // IMBE #7
    BlockHexWord, BlockHexWord, BlockHexWord, BlockHexWord,
    BlockImbe,                                             // IMBE #8
    BlockLsdHalf, BlockLsdHalf,
    BlockImbe,                                             // IMBE #9
    BlockTrailingStatus
};

static int p25p1BlockLength(DSDP25P1::LduBlockType t)
{
    switch (t)
    {
    case DSDP25P1::BlockImbe:            return 72; // 72 dibits -> imbe_fr[8][23] (144 bits)
    case DSDP25P1::BlockHexWord:         return 5;  // 3 dibits (6-bit hex word) + 2 dibits (4-bit Hamming parity)
    case DSDP25P1::BlockLsdHalf:         return 8;  // 4 dibits data + 4 dibits cyclic parity (not decoded further)
    case DSDP25P1::BlockTrailingStatus:  return 1;  // unconditional, bypasses the status_count gate
    default:                             return 0;
    }
}

// ── Vendored FEC codecs (dsd-neo src/fec/, ISC licensed) ─────────────────────
// Function-local statics so the (non-trivial) GF table construction in each
// codec's constructor only ever runs once.

static int p25p1CheckAndFixHamming1063(char *hex, char *parity)
{
    static Hamming_10_6_3_TableImpl hamming;
    return hamming.decode(hex, parity);
}

static int p25p1CheckAndFixReedSolomon241213(char *data, const char *parity)
{
    static DSDReedSolomon_24_12_13 rs;
    return rs.decode(data, parity);
}

static int p25p1CheckAndFixReedSolomon24169(char *data, const char *parity)
{
    static DSDReedSolomon_24_16_9 rs;
    return rs.decode(data, parity);
}

// DUID validity table, TIA-102.BAAA-A Table 8-4 (see dsd-neo's
// p25p1_check_nid.cpp DUID_VALID -- reproduced here to keep this port
// self-contained rather than pulling in dsd-neo's full check_nid.cpp, which
// also carries a soft-decision Chase-retry layer this port does not use).
static bool p25p1DuidValid(unsigned char duidValue)
{
    static const bool valid[16] = {
        true,  false, false, true,  // 0=HDU, 3=TDU
        false, true,  false, true,  // 5=LDU1, 7=TSBK
        false, false, true,  false, // A=LDU2
        true,  false, false, true   // C=PDU, F=TDULC
    };
    return valid[duidValue & 0xF];
}

static unsigned char p25p1DuidParity(unsigned char duidValue)
{
    // P=1 for LDU1 (0x5) and LDU2 (0xA), P=0 for all other defined DUIDs.
    return (duidValue == 0x5 || duidValue == 0xA) ? 1 : 0;
}

// Hard-decision NID decode: BCH(63,16,23) correct, validate DUID, check parity.
// Returns true (and fills *nac / duid[0..1]) on success; parity disagreement
// after a valid BCH+DUID decode is accepted (matches dsd-neo's
// NID_PARITY_OVERRIDE acceptance -- the final parity bit sits outside the
// BCH-protected codeword).
static bool p25p1CheckNid(const char *bchCode, int *nac, char *duid, unsigned char parity)
{
    static BCH_63_16_11 bch;
    char decoded[16];

    BCH_63_16_Result result = bch.decode_with_result(bchCode, decoded);

    if (!result.success) {
        return false;
    }

    int nacValue = 0;
    for (int i = 0; i < 12; i++) {
        nacValue = (nacValue << 1) | (int) decoded[i];
    }
    *nac = nacValue;

    unsigned char duid0 = (unsigned char) ((decoded[12] << 1) + decoded[13]);
    unsigned char duid1 = (unsigned char) ((decoded[14] << 1) + decoded[15]);
    unsigned char duidValue = (unsigned char) ((duid0 << 2) | duid1);

    if (!p25p1DuidValid(duidValue)) {
        return false;
    }

    duid[0] = (char) duid0;
    duid[1] = (char) duid1;

    (void) p25p1DuidParity(duidValue); // parity disagreement is advisory only; see comment above
    (void) parity;

    return true;
}

// Packs nbits raw 0/1 chars (MSB first) into an unsigned int. Used for the
// fixed-width LC/ESS subfields (talkgroup, source/dest address, algid, kid).
static unsigned int p25p1BitsToUInt(const char *bits, int nbits)
{
    unsigned int value = 0;
    for (int i = 0; i < nbits; i++) {
        value = (value << 1) | (unsigned int) (bits[i] & 1);
    }
    return value;
}

DSDP25P1::DSDP25P1(DSDDecoder *dsdDecoder) :
    m_dsdDecoder(dsdDecoder)
{
    reset();
}

DSDP25P1::~DSDP25P1()
{}

void DSDP25P1::reset()
{
    m_state = P25NID;
    m_nidStep = 0;
    m_nac = 0;
    m_parityBit = 0;
    m_statusCount = 21; // matches classic "36-14-1=21" comment: first IMBE frame starts 14 symbols before next status
    m_blockIdx = 0;
    m_blockPos = 0;
    m_imbeFrameIdx = 0;
    m_hexWordIdx = 0;
    m_group = false;
    m_sourceId = 0;
    m_talkgroupOrDest = 0;
    m_algId = 0;
    m_keyId = 0;

    for (int i = 0; i < 63; i++) { m_bchCode[i] = 0; }

    // See feedImbeDibit() for why this matters: only 144 of this array's 184
    // cells are ever written by the interleave tables, and it is otherwise
    // never zero-initialized.
    memset(m_imbeFr, 0, sizeof(m_imbeFr));
}

void DSDP25P1::init()
{
    reset();
}

void DSDP25P1::process()
{
    if (m_state == P25NID)
    {
        int dibit = m_dsdDecoder->m_dsdSymbol.getDibit();
        processNidDibit(dibit);
    }
    else if (m_state == P25LDU1 || m_state == P25LDU2)
    {
        processLduDibit();
    }
    else // P25Skip: shouldn't normally be ticked (finishNid() resyncs immediately), but be defensive
    {
        m_dsdDecoder->resetFrameSync();
    }
}

// Exact classic bit layout (dsd_frame.c NID read, unchanged in dsd-neo):
//   dibits 0-1   -> DUID (2 dibits, also feed bch_code[0..3])
//   dibits 2-4   -> partial BCH (3 dibits -> bch_code[4..9])
//   dibit  5     -> status symbol, UNCONDITIONALLY discarded here (does not
//                   participate in bch_code and is not the generic LDU
//                   status_count mechanism -- the NID has its own single
//                   fixed-position status symbol)
//   dibits 6-25  -> more BCH (20 dibits -> bch_code[10..49])
//   dibit  26    -> final dibit: bit1 -> bch_code[50], bit0 -> parity
// bch_code[51..62] stay zero (shortened-code padding).
void DSDP25P1::processNidDibit(int dibit)
{
    if (m_nidStep < 2)
    {
        int base = m_nidStep * 2;
        m_bchCode[base]     = (char) (1 & (dibit >> 1));
        m_bchCode[base + 1] = (char) (1 & dibit);
        m_duidRaw[m_nidStep] = (char) dibit;
    }
    else if (m_nidStep < 5)
    {
        int base = 4 + (m_nidStep - 2) * 2;
        m_bchCode[base]     = (char) (1 & (dibit >> 1));
        m_bchCode[base + 1] = (char) (1 & dibit);
    }
    else if (m_nidStep == 5)
    {
        // status dibit: discarded, no bch_code contribution
    }
    else if (m_nidStep < 26)
    {
        int base = 10 + (m_nidStep - 6) * 2;
        m_bchCode[base]     = (char) (1 & (dibit >> 1));
        m_bchCode[base + 1] = (char) (1 & dibit);
    }
    else // m_nidStep == 26
    {
        m_bchCode[50] = (char) (1 & (dibit >> 1));
        m_parityBit   = (unsigned char) (1 & dibit);
    }

    m_nidStep++;

    if (m_nidStep == 27) {
        finishNid();
    }
}

void DSDP25P1::finishNid()
{
    int nac = 0;
    char duid[2] = {0, 0};

    if (!p25p1CheckNid(m_bchCode, &nac, duid, m_parityBit))
    {
        // Uncorrectable NID: don't guess: go straight back to sync search
        // rather than risk parsing an unrelated bit stream as a frame body.
        m_dsdDecoder->resetFrameSync();
        return;
    }

    m_nac = nac;

    // duid[0],duid[1] are raw dibit values 0-3 (not ASCII), matching the
    // classic "duid=='11'"-style two-dibit DUID encoding:
    //   00=HDU  11=LDU1  22=LDU2  33=TDULC  03=TDU  13=TSBK
    if (duid[0] == 1 && duid[1] == 1) // LDU1
    {
        m_state = P25LDU1;
        m_statusCount = 21;
        m_blockIdx = 0;
        m_blockPos = 0;
        m_imbeFrameIdx = 0;
        m_hexWordIdx = 0;
    }
    else if (duid[0] == 2 && duid[1] == 2) // LDU2
    {
        m_state = P25LDU2;
        m_statusCount = 21;
        m_blockIdx = 0;
        m_blockPos = 0;
        m_imbeFrameIdx = 0;
        m_hexWordIdx = 0;
    }
    else
    {
        // HDU / TDU / TDULC / TSBK / PDU: not decoded by this port (see
        // p25p1.h provenance note). The frame's own re-transmitted sync +
        // NID precedes the *next* logical unit regardless, so simply
        // re-acquiring sync from scratch (as NXDN/dPMR already do at the
        // end of their own frames in this codebase) is both simpler and
        // more robust than tracking each skipped DUID's exact body length.
        m_dsdDecoder->resetFrameSync();
    }
}

void DSDP25P1::processLduDibit()
{
    if (m_lduProgram[m_blockIdx] == BlockTrailingStatus)
    {
        // Unconditional read, bypasses the status_count gate entirely
        // (matches classic code's direct getDibit() call here).
        m_dsdDecoder->m_dsdSymbol.getDibit();

        if (m_state == P25LDU1) { finishLdu1(); } else { finishLdu2(); }
        m_dsdDecoder->resetFrameSync();
        return;
    }

    if (m_statusCount == 35)
    {
        m_dsdDecoder->m_dsdSymbol.getDibit(); // status symbol: discarded
        m_statusCount = 1;
        return; // doesn't count against the current block's dibit quota
    }
    m_statusCount++;

    int dibit = m_dsdDecoder->m_dsdSymbol.getDibit();

    switch (m_lduProgram[m_blockIdx])
    {
    case BlockImbe:    feedImbeDibit(dibit); break;
    case BlockHexWord: feedHexWordDibit(dibit); break;
    case BlockLsdHalf: feedLsdDibit(dibit); break;
    default: break;
    }
}

void DSDP25P1::feedImbeDibit(int dibit)
{
    // Clear the frame at the start of each new IMBE block. The interleave
    // tables below only ever populate 144 of this array's 184 cells --
    // rows 0-3 use all 23 columns but rows 4-6 use only 15 and row 7 only 7,
    // matching the true IMBE 23/23/23/23/15/15/15/7 codeword layout. The
    // remaining ~40 "padding" cells are never written by this loop, so
    // without an explicit reset they retain whatever indeterminate bytes
    // were in m_imbeFr's backing memory (this member is never
    // zero-initialized elsewhere). mbelib-neo's mbe_validate_bits() requires
    // every one of the 184 bytes to be exactly 0 or 1 or it rejects the
    // whole frame -- so any non-binary garbage sitting in the padding cells
    // causes every single P25 voice frame to fail decode, while dsd_mbe.cpp
    // still unconditionally runs its (unfilled/stale) audio buffer out to
    // the speaker, producing garbled audio. Upstream dsd-neo avoids this by
    // memset-ing imbe_fr to zero at the top of every process_IMBE() call
    // (see p25p1_ldu.c); replicate that here at the start of each block.
    if (m_blockPos == 0) {
        memset(m_imbeFr, 0, sizeof(m_imbeFr));
    }

    int w = p25p1_iW[m_blockPos];
    int x = p25p1_iX[m_blockPos];
    int y = p25p1_iY[m_blockPos];
    int z = p25p1_iZ[m_blockPos];

    m_imbeFr[w][x] = (char) (1 & (dibit >> 1));
    m_imbeFr[y][z] = (char) (1 & dibit);

    advanceLduBlock();
}

void DSDP25P1::feedHexWordDibit(int dibit)
{
    if (m_blockPos < 3)
    {
        int base = m_blockPos * 2;
        m_hexWord[base]     = (char) (1 & (dibit >> 1));
        m_hexWord[base + 1] = (char) (1 & dibit);
    }
    else
    {
        int base = (m_blockPos - 3) * 2;
        m_hexParity[base]     = (char) (1 & (dibit >> 1));
        m_hexParity[base + 1] = (char) (1 & dibit);
    }

    advanceLduBlock();
}

void DSDP25P1::feedLsdDibit(int dibit)
{
    // Low speed data: read and discarded, matching the classic decoder's
    // own "TODO: do something useful with the LSD bytes" -- LSD carries no
    // information needed for audio or basic call identification.
    (void) dibit;
    advanceLduBlock();
}

void DSDP25P1::advanceLduBlock()
{
    m_blockPos++;

    if (m_blockPos < p25p1BlockLength(m_lduProgram[m_blockIdx])) {
        return; // current block not finished yet
    }

    // Current block complete: run its finalization, then move to the next.
    switch (m_lduProgram[m_blockIdx])
    {
    case BlockImbe:    finishImbeFrame(); break;
    case BlockHexWord: finishHexWord(); break;
    default: break; // LSD: nothing to finalize
    }

    m_blockPos = 0;
    m_blockIdx++;
}

void DSDP25P1::finishImbeFrame()
{
    // Non-standard c0 word guard, ported verbatim from the classic decoder
    // (see https://github.com/szechyjs/dsd/issues/24): a specific bogus c0
    // pattern shows up on some recordings/muted frames and would otherwise
    // just look like a badly-errored IMBE frame to the vocoder.
    static const char nonStandardC0[23] =
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0};
    bool nonStandard = true;
    for (int i = 0; i < 23; i++) {
        if (m_imbeFr[0][i] != nonStandardC0[i]) { nonStandard = false; break; }
    }

    if (!nonStandard)
    {
        m_dsdDecoder->setMbeRate(DSDDecoder::DSDMBERate7200x4400);
        m_dsdDecoder->m_mbeDecoder1.processFrame(m_imbeFr, 0, 0);
        m_dsdDecoder->m_mbeDVReady1 = true;
    }

    m_imbeFrameIdx++;
}

void DSDP25P1::finishHexWord()
{
    // Hamming(10,6,3): corrects the hex word in place using the parity dibits
    // just read; return value (0/1/2 errors) isn't used further here since
    // the subsequent Reed-Solomon pass (finishLdu1()/finishLdu2()) is the
    // authoritative check for whether the LC/ESS word as a whole is usable.
    p25p1CheckAndFixHamming1063(m_hexWord, m_hexParity);

    if (m_state == P25LDU1)
    {
        if (m_hexWordIdx < 12) {
            for (int i = 0; i < 6; i++) { m_hexData[11 - m_hexWordIdx][i] = m_hexWord[i]; }
        } else {
            for (int i = 0; i < 6; i++) { m_hexDataParity[23 - m_hexWordIdx][i] = m_hexWord[i]; }
        }
    }
    else // P25LDU2
    {
        if (m_hexWordIdx < 16) {
            for (int i = 0; i < 6; i++) { m_hexData[15 - m_hexWordIdx][i] = m_hexWord[i]; }
        } else {
            for (int i = 0; i < 6; i++) { m_hexDataParity[23 - m_hexWordIdx][i] = m_hexWord[i]; }
        }
    }

    m_hexWordIdx++;
}

void DSDP25P1::finishLdu1()
{
    // hex_data[12][6] (72 bits) + hex_data_parity[12][6] (72 bits) is exactly
    // the classic algorithm's Reed-Solomon(24,12,13) codeword split -- see
    // szechyjs/dsd & dsd-neo's processLDU1()/p25p1_ldu1.c.
    int irrecoverable = p25p1CheckAndFixReedSolomon241213(
        reinterpret_cast<char *>(m_hexData), reinterpret_cast<char *>(m_hexDataParity));

    if (irrecoverable == 0) {
        decodeLdu1LinkControl();
    }
    // else: leave the last successfully-decoded identification in place --
    // audio for this LDU already played out frame-by-frame above regardless
    // of whether its header/LC parses.
}

void DSDP25P1::finishLdu2()
{
    // hex_data[16][6] (96 bits) + hex_data_parity[8][6] (48 bits): Reed-
    // Solomon(24,16,9) codeword split for the Encryption Sync Sequence.
    int irrecoverable = p25p1CheckAndFixReedSolomon24169(
        reinterpret_cast<char *>(m_hexData), reinterpret_cast<char *>(m_hexDataParity));

    if (irrecoverable == 0) {
        decodeLdu2Ess();
    }
}

// Link Control Word = 72 bits: lcformat(8) + mfid(8) + lcinfo(56), split
// across hex_data[11..0] exactly as in the classic decoder's processLDU1()
// (see p25p1.h/.cpp provenance note). Only the by-far most common opcode,
// Group Voice Channel User (0x00, TIA-102.BAAA-A Table 7.2), is decoded here;
// any other lcformat leaves the previous identification untouched -- audio
// decoding is unaffected either way.
void DSDP25P1::decodeLdu1LinkControl()
{
    char lcformat[8];
    for (int i = 0; i < 6; i++) { lcformat[i] = m_hexData[11][i]; }
    lcformat[6] = m_hexData[10][0];
    lcformat[7] = m_hexData[10][1];

    unsigned char opcode = (unsigned char) p25p1BitsToUInt(lcformat, 8);

    if (opcode == 0x00) // Group Voice Channel User
    {
        char lcinfo[56];
        int idx = 0;
        lcinfo[idx++] = m_hexData[9][4]; lcinfo[idx++] = m_hexData[9][5];
        for (int w = 8; w >= 0; w--) {
            for (int i = 0; i < 6; i++) { lcinfo[idx++] = m_hexData[w][i]; }
        }
        // lcinfo[0..7]   = service options (not exposed further)
        // lcinfo[8..23]  = group (talkgroup) address, 16 bits
        // lcinfo[24..47] = source address, 24 bits
        m_group = true;
        m_talkgroupOrDest = p25p1BitsToUInt(lcinfo + 8, 16);
        m_sourceId = p25p1BitsToUInt(lcinfo + 24, 24);
    }
}

// Encryption Sync Sequence = 96 bits: MI(72) + ALGID(8) + KID(16), split
// across hex_data[15..0] exactly as in the classic decoder's processLDU2().
void DSDP25P1::decodeLdu2Ess()
{
    char algid[8];
    algid[0] = m_hexData[3][0]; algid[1] = m_hexData[3][1]; algid[2] = m_hexData[3][2];
    algid[3] = m_hexData[3][3]; algid[4] = m_hexData[3][4]; algid[5] = m_hexData[3][5];
    algid[6] = m_hexData[2][0]; algid[7] = m_hexData[2][1];

    char kid[16];
    int idx = 0;
    kid[idx++] = m_hexData[2][2]; kid[idx++] = m_hexData[2][3];
    kid[idx++] = m_hexData[2][4]; kid[idx++] = m_hexData[2][5];
    for (int i = 0; i < 6; i++) { kid[idx++] = m_hexData[1][i]; }
    for (int i = 0; i < 6; i++) { kid[idx++] = m_hexData[0][i]; }

    m_algId = (unsigned char) p25p1BitsToUInt(algid, 8);
    m_keyId = p25p1BitsToUInt(kid, 16);
}

} // namespace DSDcc
