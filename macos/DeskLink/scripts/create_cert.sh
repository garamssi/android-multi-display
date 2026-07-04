#!/bin/bash
#
# Creates a self-signed CODE-SIGNING identity named "DeskLink Dev" and imports it
# into the login keychain as a single PKCS#12 bundle (so the certificate and its
# private key are paired into a real identity that codesign can use). build_app.sh
# then signs DeskLink.app with it, so Screen Recording / Accessibility permissions
# persist across rebuilds.
#
# Run once:  ./scripts/create_cert.sh
#
set -euo pipefail

NAME="DeskLink Dev"
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
CN = DeskLink Dev
[ ext ]
basicConstraints = critical, CA:false
keyUsage         = critical, digitalSignature
extendedKeyUsage = critical, codeSigning
EOF

echo "==> Generating key + self-signed code-signing certificate…"
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$TMP/desklink.key" -out "$TMP/desklink.crt" \
  -days 3650 -config "$TMP/openssl.cnf"

echo "==> Bundling certificate + key into a PKCS#12 identity…"
# OpenSSL 3.x needs -legacy so macOS/Security can read the .p12; LibreSSL (default
# on macOS) doesn't know -legacy, so fall back without it.
if ! openssl pkcs12 -export -inkey "$TMP/desklink.key" -in "$TMP/desklink.crt" \
      -name "$NAME" -out "$TMP/desklink.p12" -passout "pass:$P12PASS" -legacy 2>/dev/null; then
  openssl pkcs12 -export -inkey "$TMP/desklink.key" -in "$TMP/desklink.crt" \
      -name "$NAME" -out "$TMP/desklink.p12" -passout "pass:$P12PASS"
fi

echo "==> Importing identity into login keychain (you may be asked for your Mac password)…"
security import "$TMP/desklink.p12" -k "$KEYCHAIN" -P "$P12PASS" -T /usr/bin/codesign

echo ""
echo "==> Identities codesign can see:"
security find-identity -p codesigning | grep "$NAME" || {
  echo "  (name not shown by find-identity, but codesign may still work — try the build)"
}
echo ""
echo "Next:  ./scripts/build_and_run.command"
