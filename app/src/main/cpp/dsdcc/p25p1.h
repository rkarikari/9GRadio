///////////////////////////////////////////////////////////////////////////////////
// P25 Phase 1 (TIA-102.BAAA-A) voice frame decoder.
//
// Faithfully ported from the classic DSD "process_IMBE" / "processLDU1" /
// "processLDU2" / NID-decode algorithm shared, unchanged, across the entire
// DSD lineage this project already draws from -- szechyjs/dsd, dsd-fme, and
// arancormonk/dsd-neo (https://github.com/arancormonk/dsd-neo,
// src/protocol/p25/phase1/{p25p1_ldu.c,p25p1_ldu1.c,p25p1_ldu2.c} and
// src/protocol/p25/phase1/p25p1_check_nid.cpp). The IMBE interleave tables,
// per-frame bit layout, and hard-decision FEC (BCH(63,16,23) NID, shortened
// Hamming(10,6,3) hex-word protection, Reed-Solomon(24,12,13)/(24,16,9) LC/ESS
// protection) are numerically identical across all of those projects --
// verified by diffing dsd-neo's tables against the pre-itpp classic dsd
// source during this port.
//
// dsd-neo's modern implementation additionally carries a soft-decision LLR
// retry layer, full trunking state machine, and MAC/PDU parsing bolted around
// this same core algorithm; those are out of scope here and are not ported --
// this class implements the hard-decision voice path only (NID -> LDU1/LDU2
// -> IMBE audio + the common Group/Individual Voice Channel User LC and the
// Encryption Sync Sequence), matching the depth of the other protocols
// (NXDN, dPMR, D-STAR, YSF) already implemented in this dsdcc fork.
//
// The BCH(63,16,11)/Hamming(10,6,3)/Reed-Solomon(24,12,13 & 24,16,9) codecs
// themselves (p25p1_bch.h, p25p1_hamming.h/.cpp, p25p1_reedsolomon.h) are
// vendored verbatim from dsd-neo's src/fec/ and include/dsd-neo/fec/ (ISC
// licensed, self-contained, no dsd-neo runtime dependencies).
///////////////////////////////////////////////////////////////////////////////////

#ifndef P25P1_H_
#define P25P1_H_

#include "export.h"

namespace DSDcc
{

class DSDDecoder;

class DSDCC_API DSDP25P1
{
public:
    DSDP25P1(DSDDecoder *dsdDecoder);
    ~DSDP25P1();

    void init();    //!< initializations not consuming a live symbol (mirrors DSDNXDN::init / DSDdPMR::init)
    void process(); //!< consumes exactly one dibit per call, called once per symbol while the FSM is in DSDprocessP25p1

    // ── Identification (best-effort; only populated when the corresponding
    //    LC/ESS word cleared its FEC and used a recognized opcode) ──────────
    int getNac() const { return m_nac; }
    bool isGroupCall() const { return m_group; }
    unsigned int getSourceId() const { return m_sourceId; }
    unsigned int getDestinationId() const { return m_talkgroupOrDest; }
    unsigned char getAlgId() const { return m_algId; }
    bool isEncrypted() const { return m_algId != 0 && m_algId != 0x80; }
    unsigned int getKeyId() const { return m_keyId; }

private:
    typedef enum
    {
        P25NID,        //!< reading the 32-dibit NID (frame sync already consumed by the generic sync engine)
        P25LDU1,       //!< reading an LDU1 (voice + Link Control) logical frame
        P25LDU2,       //!< reading an LDU2 (voice + Encryption Sync Sequence) logical frame
        P25Skip        //!< non-voice DUID (HDU/TDU/TDULC/TSBK/PDU/unknown): not decoded, just resync
    } P25State;

public:
    typedef enum
    {
        BlockImbe,     //!< 72 "content" dibits -> one IMBE 7200x4400 voice frame
        BlockHexWord,  //!< 6 data dibits + 4 Hamming(10,6,3) parity dibits
        BlockLsdHalf,  //!< 4 data dibits + 4 cyclic-parity dibits (low speed data; not decoded further)
        BlockTrailingStatus //!< one unconditional status-symbol dibit, bypasses the status_count gate entirely
    } LduBlockType;

private:

    void reset();
    void processNidDibit(int dibit);
    void finishNid();

    void processLduDibit();               //!< dispatches to the status-gate, then to the current block
    void feedImbeDibit(int dibit);
    void feedHexWordDibit(int dibit);
    void feedLsdDibit(int dibit);
    void advanceLduBlock();                //!< called when the current block's dibit quota is met

    void finishImbeFrame();                //!< have a complete imbe_fr[8][23]; decode audio via mbelib
    void finishHexWord();                  //!< have 6+4 dibits; Hamming-correct and store into hex_data/hex_parity
    void finishLdu1();                     //!< all blocks read; Reed-Solomon-correct LC and extract fields
    void finishLdu2();                     //!< all blocks read; Reed-Solomon-correct ESS and extract fields
    void decodeLdu1LinkControl();
    void decodeLdu2Ess();

    DSDDecoder *m_dsdDecoder;

    P25State m_state;

    // NID (BCH(63,16,23)) reading state
    int m_nidStep;                 //!< 0..26, see p25p1.cpp processNidDibit() for the exact classic bit layout
    char m_bchCode[63];
    char m_duidRaw[2];
    unsigned char m_parityBit;
    int m_nac;

    // LDU1/LDU2 body reading state
    static const LduBlockType m_lduProgram[36]; //!< shared 36-block sequence (9x IMBE, interleaved hex/LSD groups)
    int m_statusCount;             //!< persistent across the whole LDU body; status dibit skipped when it hits 35
    int m_blockIdx;                //!< index into m_lduProgram
    int m_blockPos;                //!< dibit position within the current block

    char m_imbeFr[8][23];
    int m_imbeFrameIdx;            //!< which of the 9 IMBE frames in this LDU (0-8)

    char m_hexWord[6];
    char m_hexParity[4];
    int m_hexWordIdx;              //!< which hex-word slot the *next completed* word goes into (counts down, see .cpp)

    char m_hexData[16][6];         //!< LDU1 uses 12, LDU2 uses 16
    char m_hexDataParity[12][6];   //!< LDU1's parity words (also reused, first 8, for LDU2's ESS parity)

    // Identification extracted from LC (LDU1) / ESS (LDU2)
    bool m_group;
    unsigned int m_sourceId;
    unsigned int m_talkgroupOrDest;
    unsigned char m_algId;
    unsigned int m_keyId;
};

} // namespace DSDcc

#endif // P25P1_H_
