#!/usr/bin/env python3
"""Golden vectors for the LAN pairing key derivation (P3, TLS-PSK).

The Mac (Swift, CryptoKit) and Android (Kotlin, HMAC-SHA256) MUST derive the exact
same pre-shared key from the same 6-digit PIN, or the TLS-PSK handshake fails. This
script is the language-independent reference (RFC 5869 HKDF-SHA256); the Swift and
Kotlin unit tests assert against the vectors printed here.

Contract (must match ProtocolConstants on both platforms):
  IKM  = UTF-8 bytes of the PIN string
  salt = "desklink-pairing-v1"
  info = "desklink-psk"
  L    = 32 (bytes; the PSK)

Run: python3 tools/pairing_vectors.py
"""

import hmac
import hashlib

SALT = b"desklink-pairing-v1"
INFO = b"desklink-psk"
PSK_LENGTH = 32


def hkdf_extract(salt: bytes, ikm: bytes) -> bytes:
    return hmac.new(salt, ikm, hashlib.sha256).digest()


def hkdf_expand(prk: bytes, info: bytes, length: int) -> bytes:
    okm = b""
    block = b""
    counter = 1
    while len(okm) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        okm += block
        counter += 1
    return okm[:length]


def derive_psk(pin: str) -> bytes:
    prk = hkdf_extract(SALT, pin.encode("utf-8"))
    return hkdf_expand(prk, INFO, PSK_LENGTH)


if __name__ == "__main__":
    for pin in ("000000", "123456", "987654"):
        print(f'{pin} -> {derive_psk(pin).hex()}')
