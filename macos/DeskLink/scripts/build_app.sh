#!/bin/bash
#
# Builds DeskLink as a signed .app bundle so macOS Screen Recording / Accessibility
# permissions PERSIST across rebuilds.
#
# One-time setup (see docs/BUILD_AND_TEST.md): create a self-signed code-signing
# certificate named exactly as $SIGN_ID below (Keychain Access → Certificate
# Assistant → Create a Certificate → Code Signing, Self Signed Root).
#
# Usage:
#   ./scripts/build_app.sh          # build + sign
#   open build/DeskLink.app         # run it (grant permissions ONCE)
#
set -euo pipefail

# --- config ---
APP_NAME="DeskLink"
SIGN_ID="DeskLink Dev"          # must match your self-signed certificate's name
# --------------

# Move to the package root (this script lives in macos/DeskLink/scripts).
cd "$(dirname "$0")/.."

BUILD_DIR=".build/release"
APP_DIR="build/${APP_NAME}.app"
INFO_PLIST="DeskLink-Info.plist"

echo "==> Building (swift build -c release)…"
swift build -c release

echo "==> Assembling ${APP_DIR}…"
rm -rf "${APP_DIR}"
mkdir -p "${APP_DIR}/Contents/MacOS"
mkdir -p "${APP_DIR}/Contents/Resources"
cp "${BUILD_DIR}/${APP_NAME}" "${APP_DIR}/Contents/MacOS/${APP_NAME}"
cp "${INFO_PLIST}" "${APP_DIR}/Contents/Info.plist"

# App icon: compile the .iconset into AppIcon.icns (referenced by CFBundleIconFile in
# the Info.plist) so Finder shows the brand icon for the .app. iconutil ships with
# macOS. If the iconset is missing, warn but don't fail the build.
ICONSET="AppIcon.iconset"
if [ -d "${ICONSET}" ]; then
  echo "==> Building AppIcon.icns from ${ICONSET}…"
  iconutil -c icns "${ICONSET}" -o "${APP_DIR}/Contents/Resources/AppIcon.icns"
else
  echo "WARNING: ${ICONSET} not found; the app will use the default icon."
fi

# Copy the SwiftPM resource bundle (bundled IBM Plex fonts) into the .app so
# `Bundle.module` resolves inside the signed bundle. SwiftPM names it
# "<Package>_<Target>.bundle" → "DeskLink_DeskLink.bundle".
RESOURCE_BUNDLE="${APP_NAME}_${APP_NAME}.bundle"
if [ -d "${BUILD_DIR}/${RESOURCE_BUNDLE}" ]; then
  echo "==> Copying resource bundle ${RESOURCE_BUNDLE}…"
  rm -rf "${APP_DIR}/Contents/Resources/${RESOURCE_BUNDLE}"
  cp -R "${BUILD_DIR}/${RESOURCE_BUNDLE}" "${APP_DIR}/Contents/Resources/"
else
  echo "WARNING: ${RESOURCE_BUNDLE} not found in ${BUILD_DIR}; the UI will fall back"
  echo "         to system fonts (IBM Plex won't be embedded)."
fi

echo "==> Signing with '${SIGN_ID}'…"
# codesign can use a self-signed identity even when it is not "trusted" (which is
# why `security find-identity -v` may show 0). Let codesign be the real test.
if ! codesign --force --sign "${SIGN_ID}" "${APP_DIR}"; then
  echo ""
  echo "ERROR: codesign failed with identity '${SIGN_ID}'."
  echo "  1) Create the cert if you haven't:  ./scripts/create_cert.sh"
  echo "  2) If it still fails, trust it: Keychain Access → double-click 'DeskLink Dev'"
  echo "     → expand Trust → set 'Code Signing: Always Trust' → close (enter password)."
  exit 1
fi

echo "==> Verifying signature…"
codesign --verify --verbose "${APP_DIR}"

echo ""
echo "Done. Run it with:"
echo "    open '$(pwd)/${APP_DIR}'"
echo "Grant Screen Recording + Accessibility to DeskLink ONCE; it will persist across rebuilds."
