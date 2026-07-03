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

# max-packet boundary sanity
MAX = 4*1024*1024
big_len = MAX
print(f"max-packet: {MAX} bytes = 0x{MAX:08X} (length field carries type+payload)")

print()
print("ALL CHECKS PASS" if failures==0 else f"{failures} FAILURE(S)")
raise SystemExit(0 if failures==0 else 1)
