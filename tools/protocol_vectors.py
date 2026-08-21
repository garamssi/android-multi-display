#!/usr/bin/env python3
"""
Language-neutral golden-vector generator + conformance test for the DeskLink wire protocol.
All multibyte values are Big-Endian ('>'). Runs on plain Python (no platform deps).
The emitted hex vectors are authoritative: Swift XCTest and Kotlin JUnit tests must match them.
"""
import struct

failures = 0

def h(b: bytes) -> str:
    return b.hex().upper()

def check(name, actual: bytes, expected_hex: str | None):
    global failures
    got = h(actual)
    print(f"{name:24} {got}")
    if expected_hex is not None and got != expected_hex:
        print(f"   !! MISMATCH expected {expected_hex}")
        failures += 1

# ---- Framing: [len uint32 BE = 1+payload][type u8][payload] ----
def frame(mtype: int, payload: bytes) -> bytes:
    length = 1 + len(payload)
    return struct.pack(">I", length) + bytes([mtype]) + payload

# ---- TOUCH_EVENT (0x20): Action(1)+X(f32)+Y(f32)+Pressure(u16)+PointerID(1)+Timestamp(i64) = 20B ----
def touch(action, x, y, pressure, pointer_id, ts_us) -> bytes:
    return struct.pack(">B f f H B q", action, x, y, pressure, pointer_id, ts_us)

# ---- SCROLL (0x22) payload: DeltaX(f32)+DeltaY(f32) = 8B, normalized deltas ----
def scroll(dx, dy) -> bytes:
    return struct.pack(">f f", dx, dy)

# ---- POINTER_BUTTON (0x23) payload: Button(1)+Action(1)+X(f32)+Y(f32) = 10B ----
# Button: 0x00 LEFT, 0x01 RIGHT. Action: 0x00 DOWN, 0x01 UP. X/Y normalized 0..1.
def pointer_button(button, action, x, y) -> bytes:
    return struct.pack(">B B f f", button, action, x, y)

# ---- VIDEO_FRAME (0x10) payload: Timestamp(i64 us,8)+Flags(1)+FrameNumber(u32,4)+NAL ----
def video_frame(ts_us, flags, frame_no, nal) -> bytes:
    return struct.pack(">q B I", ts_us, flags, frame_no) + nal

# ---- VIDEO_CONFIG (0x11) payload: CodecID(1)+ConfigLength(u16,2)+ConfigData ----
def video_config(codec_id, cfg) -> bytes:
    return struct.pack(">B H", codec_id, len(cfg)) + cfg

# AUDIO_CONFIG (0x30): SampleRate(u32,4)+Channels(1)+BitsPerSample(1)+Encoding(1) = 7B
def audio_config(sample_rate, channels, bits_per_sample, encoding) -> bytes:
    return struct.pack(">I B B B", sample_rate, channels, bits_per_sample, encoding)

# AUDIO_FRAME (0x31): Timestamp(i64 us,8)+FrameCount(u32,4)+PCM. Header fields are BE like
# everything else; the PCM block is opaque and signed 16-bit LITTLE-endian (declared by
# AUDIO_CONFIG) so Android can hand it straight to AudioTrack, whose PCM16 is native-endian.
def audio_frame(ts_us, frame_count, pcm) -> bytes:
    return struct.pack(">q I", ts_us, frame_count) + pcm

# Scale by 32768 so -1.0 maps exactly onto Int16.min and the full range is used; the clamp
# absorbs +1.0 landing one step past Int16.max, and any overdriven sample.
def pcm_s16le(samples) -> bytes:
    out = bytearray()
    for value in samples:
        out += struct.pack("<h", max(-32768, min(32767, round(value * 32768.0))))
    return bytes(out)

print("=== DeskLink protocol golden vectors (Big-Endian) ===\n")

t = touch(0x02, 0.5, 0.25, 32768, 1, 1234567890123456)
check("TOUCH_EVENT(20B)", t, None)
assert len(t) == 20, "touch must be 20 bytes"

framed = frame(0x20, t)
check("FRAMED_TOUCH", framed, None)

batch = struct.pack(">H", 2) + t + t
check("TOUCH_BATCH(count=2)", batch, None)

s = scroll(0.25, -0.5)
check("SCROLL(8B)", s, None)
assert len(s) == 8, "scroll must be 8 bytes"
framed_scroll = frame(0x22, s)
check("FRAMED_SCROLL", framed_scroll, None)

# RIGHT(0x01) DOWN(0x00) at x=0.5, y=0.25 -> 01 00 3F000000 3E800000
pb = pointer_button(0x01, 0x00, 0.5, 0.25)
check("POINTER_BUTTON(10B)", pb, "01003F0000003E800000")
assert len(pb) == 10, "pointer_button must be 10 bytes"
framed_pb = frame(0x23, pb)
check("FRAMED_POINTER_BUTTON", framed_pb, "0000000B2301003F0000003E800000")

nal = bytes([0x00,0x00,0x00,0x01,0x26,0x00])
vf = video_frame(1000000, 0x01, 42, nal)
check("VIDEO_FRAME_HDR+nal", vf, None)
assert len(vf) == 13 + len(nal)

cfg = bytes([0x00,0x00,0x00,0x01,0x40])
vc = video_config(0x01, cfg)
check("VIDEO_CONFIG", vc, None)

ping = struct.pack(">q", 1700000000000)
check("PING(i64 ms)", ping, None)

# ---- LAN mutual auth (P3): challenge-response over TLS, keyed by HKDF(PIN) ----
# AUTH_CHALLENGE(0x0D): serverNonce(16). AUTH_RESPONSE(0x0E): clientNonce(16)+clientProof(32).
# AUTH_CONFIRM(0x0F): serverProof(32). proof = HMAC-SHA256(K, context || serverNonce || clientNonce),
# K = HKDF(PIN) (see pairing_vectors.py). Fixed PIN/nonces below make the proofs deterministic.
import os as _os
import sys as _sys
import hmac as _hmac
import hashlib as _hashlib
_sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
from pairing_vectors import derive_psk as _derive_psk

def _hmac256(key: bytes, msg: bytes) -> bytes:
    return _hmac.new(key, msg, _hashlib.sha256).digest()

AUTH_K = _derive_psk("123456")
AUTH_SNONCE = bytes(range(0, 16))     # 000102...0F
AUTH_CNONCE = bytes(range(16, 32))    # 101112...1F
client_proof = _hmac256(AUTH_K, b"desklink-auth-client" + AUTH_SNONCE + AUTH_CNONCE)
server_proof = _hmac256(AUTH_K, b"desklink-auth-server" + AUTH_SNONCE + AUTH_CNONCE)

auth_challenge = AUTH_SNONCE
check("AUTH_CHALLENGE(16B)", auth_challenge, "000102030405060708090A0B0C0D0E0F")
assert len(auth_challenge) == 16
check("FRAMED_AUTH_CHALLENGE", frame(0x0D, auth_challenge), None)

auth_response = AUTH_CNONCE + client_proof
check("AUTH_RESPONSE(48B)", auth_response,
      "101112131415161718191A1B1C1D1E1F625675E556C49C7C3D7696FC998AF1A1B08E566770ADE8535BCE854F70A197C7")
assert len(auth_response) == 48
check("FRAMED_AUTH_RESPONSE", frame(0x0E, auth_response),
      "000000310E101112131415161718191A1B1C1D1E1F625675E556C49C7C3D7696FC998AF1A1B08E566770ADE8535BCE854F70A197C7")

auth_confirm = server_proof
check("AUTH_CONFIRM(32B)", auth_confirm,
      "021DEF164DBF188F2C926FD01EE7063C26DD682113F7A561D027CEE5D34C38E8")
assert len(auth_confirm) == 32
check("FRAMED_AUTH_CONFIRM", frame(0x0F, auth_confirm),
      "000000210F021DEF164DBF188F2C926FD01EE7063C26DD682113F7A561D027CEE5D34C38E8")

# ---- Round-trip decode ----
print("\n=== Round-trip decode ===")
action, x, y, pressure, pid, ts = struct.unpack(">B f f H B q", t)
rt = (action==0x02 and x==0.5 and y==0.25 and pressure==32768 and pid==1 and ts==1234567890123456)
print(f"touch RT: action={action} x={x} y={y} pressure={pressure} pid={pid} ts={ts} -> {'OK' if rt else 'FAIL'}")
failures += 0 if rt else 1

length, = struct.unpack(">I", framed[0:4]); mtype = framed[4]
fr = (length==21 and mtype==0x20 and len(framed)==4+length)
print(f"unframe: len={length} type=0x{mtype:02X} total={len(framed)} -> {'OK' if fr else 'FAIL'}")
failures += 0 if fr else 1

sdx, sdy = struct.unpack(">f f", s)
srt = (abs(sdx-0.25) < 1e-6 and abs(sdy-(-0.5)) < 1e-6)
print(f"scroll RT: dx={sdx} dy={sdy} -> {'OK' if srt else 'FAIL'}")
failures += 0 if srt else 1

pbtn, pact, pbx, pby = struct.unpack(">B B f f", pb)
pbrt = (pbtn==0x01 and pact==0x00 and abs(pbx-0.5) < 1e-6 and abs(pby-0.25) < 1e-6)
print(f"pointer_button RT: button={pbtn} action={pact} x={pbx} y={pby} -> {'OK' if pbrt else 'FAIL'}")
failures += 0 if pbrt else 1

vts, flags, fn = struct.unpack(">q B I", vf[0:13]); nal_out = vf[13:]
vok = (vts==1000000 and (flags&0x01)!=0 and fn==42 and nal_out==nal)
print(f"video header: ts={vts} keyframe={bool(flags&1)} frameNo={fn} nalLen={len(nal_out)} -> {'OK' if vok else 'FAIL'}")
failures += 0 if vok else 1

ac = audio_config(48000, 2, 16, 0x01)
check("AUDIO_CONFIG(7B)", ac, "0000BB80021001")
assert len(ac) == 7, "audio config must be 7 bytes"
check("FRAMED_AUDIO_CONFIG", frame(0x30, ac), "00000008300000BB80021001")

apcm = bytes([0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08])
af = audio_frame(1000000, 2, apcm)
check("AUDIO_FRAME_HDR+pcm", af, "00000000000F4240" "00000002" + h(apcm))

check("PCM_S16LE(silence)", pcm_s16le([0.0]), "0000")
check("PCM_S16LE(full+)", pcm_s16le([1.0]), "FF7F")
check("PCM_S16LE(full-)", pcm_s16le([-1.0]), "0080")
check("PCM_S16LE(clamped)", pcm_s16le([1.5, -1.5]), "FF7F0080")

ats, acount = struct.unpack(">q I", af[0:12]); apcm_out = af[12:]
aok = (ats == 1000000 and acount == 2 and apcm_out == apcm)
print(f"audio header: ts={ats} frameCount={acount} pcmLen={len(apcm_out)} -> {'OK' if aok else 'FAIL'}")
failures += 0 if aok else 1

sr, ch, bps, enc = struct.unpack(">I B B B", ac)
acok = (sr == 48000 and ch == 2 and bps == 16 and enc == 0x01)
print(f"audio config RT: rate={sr} ch={ch} bits={bps} enc=0x{enc:02X} -> {'OK' if acok else 'FAIL'}")
failures += 0 if acok else 1

consistent = (len(apcm_out) == acount * ch * (bps // 8))
print(f"audio frameCount consistency: {len(apcm_out)} == {acount}*{ch}*{bps//8} -> {'OK' if consistent else 'FAIL'}")
failures += 0 if consistent else 1

neg = audio_frame(-1, 1, b"\xAA\xBB")
negok = (neg[0:8] == b"\xFF" * 8 and struct.unpack(">q", neg[0:8])[0] == -1)
print(f"audio negative ts: -1 -> {h(neg[0:8])} -> {'OK' if negok else 'FAIL'}")
failures += 0 if negok else 1

# A frameCount that cannot describe the payload must be rejected. Read as a signed int32 a
# large uint32 arrives negative, and a huge positive value overflows frameCount*bytesPerFrame
# in 32-bit arithmetic so a naive consistency check passes.
def frame_count_is_valid(fc_u32, pcm_len, channels, bits) -> bool:
    signed = fc_u32 - (1 << 32) if fc_u32 >= (1 << 31) else fc_u32
    if signed <= 0 or signed > pcm_len:
        return False
    return pcm_len == signed * channels * (bits // 8)

bad = [(0x40000001, 4), (0xFFFFFFFF, 4), (0, 4), (5, 4)]
bad_ok = all(not frame_count_is_valid(fc, n, 2, 16) for fc, n in bad)
print(f"audio frameCount rejection: {[hex(c[0]) for c in bad]} -> {'OK' if bad_ok else 'FAIL'}")
failures += 0 if bad_ok else 1
good_ok = frame_count_is_valid(2, 8, 2, 16)
print(f"audio frameCount acceptance: 2 frames / 8 bytes stereo16 -> {'OK' if good_ok else 'FAIL'}")
failures += 0 if good_ok else 1

# Several frames delivered in ONE read must all parse. A reader that keeps per-instance
# buffer state and is then replaced mid-stream loses the tail of the buffer and desynchronizes
# the connection for good, so this is the case that must never regress.
batched = b"".join(
    len(body).to_bytes(4, "big") + body
    for body in (bytes([0x02]) + b"{}", bytes([0x04]) + b"{}", bytes([0x05]), bytes([0x0A]))
)
parsed, offset = [], 0
while offset + 4 <= len(batched):
    frame_len = int.from_bytes(batched[offset:offset + 4], "big")
    offset += 4
    parsed.append(batched[offset])
    offset += frame_len
batch_ok = parsed == [0x02, 0x04, 0x05, 0x0A] and offset == len(batched)
print(
    f"batched frames: {len(parsed)} of 4 parsed from one buffer, "
    f"types {[hex(t) for t in parsed]} -> {'OK' if batch_ok else 'FAIL'}"
)
failures += 0 if batch_ok else 1

# Error codes both sides must agree on. 1004 existed only in the client until the server
# gained it, so a rejected pairing was signalled by silence -- indistinguishable from an
# unreachable Mac.
ERROR_CODES = {
    1000: "CONNECTION_REFUSED",
    1001: "PROTOCOL_MISMATCH",
    1002: "TIMEOUT",
    1003: "CONNECTION_LOST",
    1004: "PAIRING_REJECTED",
    1005: "PAIRING_LOCKED_OUT",
}
# 1004 and 1005 must stay distinct: one asks for a new code, the other asks the user to wait,
# and collapsing them sends the user to re-read a PIN that was never the problem.
codes_ok = (
    ERROR_CODES[1004] == "PAIRING_REJECTED"
    and ERROR_CODES[1005] == "PAIRING_LOCKED_OUT"
    and len(set(ERROR_CODES.values())) == len(ERROR_CODES)
)
print(f"error codes: {len(ERROR_CODES)} connection-level, 1004={ERROR_CODES[1004]} -> {'OK' if codes_ok else 'FAIL'}")
failures += 0 if codes_ok else 1

# HANDSHAKE_RESPONSE videoScaling: how the tablet fits the picture to its panel. Absent or
# unknown means fit, because cropping on a value the client does not understand hides part of
# the screen for a reason the user cannot see.
VIDEO_SCALINGS = {"fit", "fill"}

def video_scaling_or_default(value):
    return value if value in VIDEO_SCALINGS else "fit"

scaling_ok = (
    video_scaling_or_default("fit") == "fit"
    and video_scaling_or_default("fill") == "fill"
    and video_scaling_or_default(None) == "fit"
    and video_scaling_or_default("stretch") == "fit"
)
print(f"videoScaling: fit/fill known, absent+unknown -> fit -> {'OK' if scaling_ok else 'FAIL'}")
failures += 0 if scaling_ok else 1

# Fit stays inside the panel, fill covers it, and both keep the picture's aspect. Stretching
# is the answer neither gives.
def layout(vw, vh, pw, ph, cover):
    pick = max if cover else min
    scale = pick(pw / vw, ph / vh)
    return round(vw * scale), round(vh * scale)

fit_w, fit_h = layout(1512, 982, 3200, 2000, cover=False)
fill_w, fill_h = layout(1512, 982, 3200, 2000, cover=True)
layout_ok = (
    (fit_w, fit_h) == (3079, 2000)
    and (fill_w, fill_h) == (3200, 2078)
    and fit_w <= 3200 and fit_h <= 2000
    and fill_w >= 3200 and fill_h >= 2000
    and abs(fit_w / fit_h - 1512 / 982) < 0.001
    and abs(fill_w / fill_h - 1512 / 982) < 0.001
)
print(
    f"video layout 1512x982 on 3200x2000: fit={fit_w}x{fit_h} fill={fill_w}x{fill_h} "
    f"-> {'OK' if layout_ok else 'FAIL'}"
)
failures += 0 if layout_ok else 1

# Reconnect schedule: the first retry has to be fast enough for a server that restarted its
# own session, without shortening the total window a slow-to-enumerate device needs.
RECONNECT_DELAYS_MS = [200, 400, 800, 1600, 2000]
schedule_ok = (
    RECONNECT_DELAYS_MS[0] <= 250
    and sum(RECONNECT_DELAYS_MS) >= 5000
    and RECONNECT_DELAYS_MS == sorted(RECONNECT_DELAYS_MS)
)
print(
    f"reconnect: first={RECONNECT_DELAYS_MS[0]}ms total={sum(RECONNECT_DELAYS_MS)}ms "
    f"non-decreasing -> {'OK' if schedule_ok else 'FAIL'}"
)
failures += 0 if schedule_ok else 1

# DISCONNECT is what removes the client's detection latency; it must stay a control-channel
# message with an empty payload, so the framed packet is length 1 (type only).
DISCONNECT = 0x0A
disconnect_packet = (1).to_bytes(4, "big") + bytes([DISCONNECT])
disconnect_ok = disconnect_packet == bytes.fromhex("000000010a")
print(f"DISCONNECT: {disconnect_packet.hex()} -> {'OK' if disconnect_ok else 'FAIL'}")
failures += 0 if disconnect_ok else 1

# HANDSHAKE_RESPONSE displayMode: the wire values both platforms agree on. Absent means
# extend, so an older server keeps working and touch is not disabled by accident.
DISPLAY_MODES = {"extend": True, "mirror": False}   # value -> accepts input

def display_mode_accepts_input(value) -> bool:
    return DISPLAY_MODES.get(value if value in DISPLAY_MODES else "extend", True)

mode_ok = (
    display_mode_accepts_input("extend") is True
    and display_mode_accepts_input("mirror") is False
    and display_mode_accepts_input(None) is True
    and display_mode_accepts_input("hologram") is True
)
print(f"displayMode: extend=input, mirror=no-input, unknown/absent=extend -> {'OK' if mode_ok else 'FAIL'}")
failures += 0 if mode_ok else 1

# max-packet boundary sanity
MAX = 4*1024*1024
big_len = MAX
print(f"max-packet: {MAX} bytes = 0x{MAX:08X} (length field carries type+payload)")

print()
print("ALL CHECKS PASS" if failures==0 else f"{failures} FAILURE(S)")
raise SystemExit(0 if failures==0 else 1)
