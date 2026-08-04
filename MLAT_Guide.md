# 9GRadio MLAT — Private Network Setup Guide

This guide covers running 9GRadio's **own** multilateration stack (`MlatClient` /
`MlatServer`, protocol `MlatWire`) entirely on a private network — no public
aggregator, no internet-facing service. It does **not** cover "Real network"
(`switchMlatUpstream` / `UpstreamMlatClient`), which feeds a public MLAT
aggregator like ADSBExchange/adsb.lol using the genuine upstream wire
protocol. Leave that switch **off** for everything below.

There are two things you can do with a private MLAT network:

1. **Test the network with only 2 stations** (self-test, using a
   server-synthesized virtual 3rd/4th station)
2. **Run real MLAT fixes with 3+ real stations** (actual multilateration
   of live ADS-B/AIS/ACARS traffic)

---

## Supported protocols

MLAT here is protocol-agnostic — every decoder below funnels into the
same `MlatManager.submitDetection` pipeline and the same
correlate/solve logic (3-station minimum, assumed-altitude below 4
stations, solved 3D altitude at 4+). Turning **Client** on is enough;
no protocol-specific MLAT setup is needed beyond having that decoder
actively feeding traffic:

| Protocol | Wire tag | Correlation key | Altitude |
|---|---|---|---|
| ADS-B | `ADSB` | Mode S message content (via readsb/Beast, hardware-timestamped) | Self-reported barometric altitude (real z) |
| AIS | `AIS` | Hash of decoded target report (MMSI + changed fields) | None — sea level assumed below 4 stations |
| ACARS | `ACARS` | Hash of (registration, label, block id, text) | None — sea level assumed below 4 stations |
| Digital Voice (DMR / P25 / NXDN / D-STAR / YSF / M17 / dPMR / DIG auto-detect) | `DV` | Decoded srcId/dstId content | None — assumed below 4 stations |
| FM broadcast (RDS) | `RDS` | Decoded RDS PI (station identity) code | None — assumed below 4 stations |
| Plain analog RF (AM / FM / NFM / WFM / WFM Stereo / USB / LSB / CW / CWR / DSB) | `RF` | Carrier onset (squelch rising edge) on the tuned frequency — coarser than a content-hash key, since it's the only signal a mode with no demodulated payload can offer | None — assumed below 4 stations |

Everything in Parts 1 and 2 below applies identically regardless of
which of these protocols a station is actively decoding — a mixed
mesh (e.g. one station hearing ADS-B, another Digital Voice, another
FM/RDS) is fine; MLAT correlates per-protocol/per-target, not
per-station.

---

## How the private network is put together

- One device runs the **embedded MLAT server** (`Server` switch, listen
  port default **31090**). This is the aggregation point every other
  station's `MlatClient` connects to.
- Every station (including the server device itself, if it also wants to
  contribute observations) runs the **MLAT client** (`Client` switch),
  pointed at the server's host:port.
- This is a **star topology, not a mesh**: every client only needs a
  route to the **server**, never to each other. Confirm each station can
  reach the server device's IP on the listen port (same Wi‑Fi/LAN,
  hotspot, or VPN — whatever lets that one TCP connection out to the
  server succeed). Stations never open connections between themselves.
- Every client needs a **real GPS fix** before it will announce a
  position. Until `gpsFixAcquired` is true, the client stays connected
  but withholds its `hello` — this avoids a station poisoning every
  correlation group with a `(0,0,0)` phantom position.

---

## Part 1 — Test with 2 stations (network self-test)

With only 2 real stations connected, there aren't enough receivers for a
real 3-station-minimum MLAT solve. `MlatServer.runSelfTestCycle` covers
this gap automatically: it synthesizes a **virtual 3rd station** (and a
virtual 4th too, if the real pair is too close together for a useful
baseline) positioned using real GDOP math, and folds it into the same
correlation/solve pipeline a real fix uses. This validates your network's
clock sync and link health without needing a 3rd physical receiver yet.

### 1.1 Set up the server device

1. Open **Settings → MLAT** (the "MLAT" button on the Settings drawer
   tab) on the device that will act as the aggregation point.
2. Turn on the **Server** switch.
3. Leave **listen port** at `31090` unless you have a reason to change
   it.
4. Tap **Apply**.
5. Note the address shown next to **CONNECT →** on the dashboard — this
   is the IP:port the other station will connect to. (Tap the copy
   button next to it if you need to send it to the other device.)

### 1.2 Set up both client stations

On **each** of the 2 physical stations (the server device can be one of
them, connecting to itself over `127.0.0.1`, or you can run the server on
a 3rd device that isn't itself a station):

1. Open **Settings → MLAT**.
2. Turn on the **Client** switch.
3. Set the **host** field to the server's IP (use `127.0.0.1` only if
   this device *is* the server).
4. Set the **port** field to match the server's listen port (`31090` by
   default).
5. Tap **Apply**.
6. Confirm **Location permission** is granted and GPS has a lock — the
   `GPS FIX` tile at the top of the dashboard should show a real
   coordinate, not `—`. The client won't announce a position (and won't
   count toward the self-test) until this is true.

### 1.3 Confirm both stations joined

On the server device's dashboard (or either client's — the roster is
broadcast to everyone):

- **STATIONS / PEERS** should read `2 / 2`.
- **STATION ROSTER** (tap to expand) should list both receiver IDs with
  real, non-`(~0,~0)` coordinates. A `⚠ no real fix?` flag means that
  station's GPS hadn't locked when it connected — fix that first.
- If the two stations are physically very close together, you'll see a
  geometry warning (`stations within Xm of each other…`) — this is
  expected on a bench test and doesn't block the self-test (it's exactly
  the case the virtual 4th station compensates for).

### 1.4 Let the self-test run

The server runs this cycle automatically and periodically once real
traffic is idle — no button to press. Watch the **NETWORK SELF-TEST**
tile on either dashboard:

- **est. accuracy** rows show `[incl. virtual 3rd/4th]` when running
  in this 2-station mode.
- The result line shows `OK` or `FAILED`, the reason (e.g.
  `scenario=... stations=3 rms=Nm virtual=1x`), and — on success — the
  solved test coordinates.
- **STATION MAP** (tap to expand) plots the two real stations plus the
  synthetic virtual station(s), drawn with a distinct "⚠ VIRTUAL
  STATION (synthetic)" marker/tooltip so they're never mistaken for real
  receivers.
- A passing result here means a real fix under this exact station
  configuration would also succeed — the self-test runs through the
  identical `runSolve` convergence/residual-quality gates a live fix
  does.

### What this test does *not* do

It does **not** produce a real located-transmitter fix — the self-test's
`PROTOCOL_TEST` result is tagged and rendered separately (a dashed amber
star + "TEST" badge on the map, not a normal aircraft/ship diamond) so it
is never confused with a real detection. To actually solve live traffic
you need a 3rd real station — see Part 2.

---

## Part 2 — Run real MLAT with 3+ stations

Once you have **3 or more real, GPS-locked stations** connected to the
same embedded server, `MlatServer` stops needing any virtual stations at
all — real observations from real receivers correlate directly.

### 2.1 Server device

Same as step 1.1 above — one device with **Server** on, others pointed
at it. No change needed if it's already running from the test above.

### 2.2 Every station

On each of the 3+ physical receivers:

1. **Settings → MLAT → Client** on, host/port pointed at the server,
   **Apply**.
2. Confirm GPS lock (`GPS FIX` tile populated).
3. Make sure the receiver is actually decoding live traffic it can feed
   into MLAT — any of the protocols listed above (ADS-B, AIS, ACARS,
   Digital Voice, FM/RDS, or plain RF carrier onset). For ADS-B this
   means the readsb/Beast pipeline is running (feed IQ into the ADS-B
   decoder as normal); the others work the same way through their own
   decoders. Every decoder funnels into the same
   `MlatManager.submitDetection` pipeline automatically — no
   protocol-specific MLAT setup needed beyond having the Client switch
   on and that decoder actively running.

### 2.3 Confirm the mesh

- **STATIONS / PEERS** should read `3 / 3` (or however many you've
  connected).
- **STATION ROSTER** lists every station's real position — no synthetic
  entries should appear once you have 3+ real, eligible stations; the
  self-test only synthesizes virtual stations below that count.
- Check **STATION ROSTER**'s geometry warning is clear (no "stations
  within Xm of each other" message) — real MLAT accuracy depends on
  actual physical spacing between receivers, ideally several km apart
  and not in a straight line.
- **SYNC STATUS** (tap to expand) shows each station pair's clock-sync
  confidence. This is GNSS-only — a station needs a locked GPS chipset
  clock reading to contribute a trustworthy timestamp; without it its
  observations are dropped before they ever reach correlation.

### 2.4 Watch real fixes appear

Once 3+ eligible stations all see the *same* transmission (same ICAO24
squitter, same AIS report hash, etc.) within the correlation window:

- **RESULTS** on the dashboard ticks up.
- **LAST FIX** shows the most recent solved position, protocol, station
  count, and residual RMS.
- The solved position flows automatically into the app's normal
  aircraft/ship pipeline and appears on the **ADS-B map** (tagged
  `source = "MLAT"` internally so it's distinguishable from a directly
  self-reported ADS-B position) or the AIS map, exactly like any other
  detection — no separate MLAT map/screen needed for real fixes.
- With exactly 3 stations, altitude is assumed (ground level for
  AIS/ACARS, or the target's own reported barometric altitude for
  ADS-B) and the solver works in x,y only. Add a **4th** real station to
  get a true 3D solve plus an extra per-fix clock-bias term — this
  measurably improves the residual-RMS accuracy figure, especially if
  any pair of your stations is close together.

---

## Quick reference

| | 2-station test (Part 1) | Real MLAT (Part 2) |
|---|---|---|
| Real stations needed | 2 | 3+ |
| Virtual stations used | Yes (auto, server-side) | No (only kicks in below 3 eligible real stations) |
| Produces real located-transmitter fixes | No — synthetic test fix only | Yes |
| Appears on ADS-B/AIS map as a normal detection | No (dashed "TEST" marker only) | Yes |
| Where to watch | MLAT Settings → NETWORK SELF-TEST tile | MLAT Settings → RESULTS/LAST FIX tiles, or the ADS-B/AIS map directly |

### Settings reference (Settings → MLAT)

| Field | Purpose | Default |
|---|---|---|
| Client switch | Forward this device's observations to the server | off |
| Client host / port | Server's address for this client to connect to | `127.0.0.1` / `31090` |
| Server switch | Run the embedded aggregation server on this device | off |
| Server listen port | Port the embedded server listens on | `31090` |
| Real network switch | Upstream feed to a *public* MLAT aggregator (not covered here) | off |

Keep **Real network** off for a fully private setup — only **Client** and
**Server** are needed for both parts of this guide.
