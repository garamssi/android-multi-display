#!/bin/bash
#
# Creates a self-signed TLS SERVER identity named "DeskLink TLS Server" and imports it
# into the login keychain as a PKCS#12 bundle (cert + private key paired). The DeskLink
# app loads this identity at runtime to serve the LAN (Wi-Fi) channels over TLS; USB
# (loopback) stays plaintext and does not use it.
#
# This is SEPARATE from create_cert.sh (that one is a CODE-SIGNING identity for signing
# the .app). This cert is for network server auth (extendedKeyUsage = serverAuth).
#
# Security note: the TLS cert provides ENCRYPTION. Peer authentication (proving it is
# YOUR Mac) is done by the PIN pairing step on top of TLS, so this self-signed cert does
# not need to be trusted by a CA. The tablet pins it on first connect (TOFU).
#
# Run once:  ./scripts/create_tls_cert.sh
#
set -euo pipefail

NAME="DeskLink TLS Server"
KEYCHAIN="$HOME/Library/Keychains/login.keychain-db"
P12PASS="desklink"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/openssl.cnf" <<'EOF'
[ req ]
distinguished_name = dn
x509_extensions    = ext
prompt             = no
[ dn ]
CN = DeskLink TLS Server
[ ext ]
basicConstraints = critical, CA:false
keyUsage         = critical, digitalSignature, keyEncipherment
extendedKeyUsage = critical, serverAuth
EOF

echo "==> Generating EC key + self-signed TLS server certificate…"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout "$TMP/tls.key" -out "$TMP/tls.crt" \
  -days 3650 -config "$TMP/openssl.cnf"

echo "==> Bundling certificate + key into a PKCS#12 identity…"
# OpenSSL 3.x needs -legacy so macOS/Security can read the .p12; LibreSSL (default on
# macOS) doesn't know -legacy, so fall back without it.
if ! openssl pkcs12 -export -inkey "$TMP/tls.key" -in "$TMP/tls.crt" \
      -name "$NAME" -out "$TMP/tls.p12" -passout "pass:$P12PASS" -legacy 2>/dev/null; then
  openssl pkcs12 -export -inkey "$TMP/tls.key" -in "$TMP/tls.crt" \
      -name "$NAME" -out "$TMP/tls.p12" -passout "pass:$P12PASS"
fi

echo "==> Importing TLS identity into login keychain (you may be asked for your Mac password)…"
# -A lets any app use the private key without a per-use access prompt. This is a
# throwaway dev TLS key (encryption only; auth is the PIN pairing), and without it the
# TLS handshake can be blocked by a keychain ACL prompt at runtime.
security import "$TMP/tls.p12" -k "$KEYCHAIN" -P "$P12PASS" -A

echo ""
echo "==> Certificates named \"$NAME\" now in the login keychain:"
security find-certificate -a -c "$NAME" "$KEYCHAIN" | grep -c "keychain:" || true
echo ""
echo "Done. The DeskLink app will use this identity for Wi-Fi (LAN) TLS. Re-run to rotate."
