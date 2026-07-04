#!/bin/bash
#
# Double-click this file in Finder to: build + sign DeskLink.app and launch it.
# (First time only: give it permission to run — see docs/BUILD_AND_TEST.md, or run
#  `chmod +x` on it once. Also create the "DeskLink Dev" signing certificate once.)
#
# Because DeskLink.app is signed with a stable identity, Screen Recording /
# Accessibility permissions persist across rebuilds — no Xcode re-prompt.
#
set -euo pipefail

# Move to the package root (this file lives in macos/DeskLink/scripts).
cd "$(dirname "$0")/.."

# Stop any previously-running instance so the ports (7100-7102) are free.
pkill -x DeskLink 2>/dev/null || true
sleep 0.3

# Build + sign.
./scripts/build_app.sh

# Launch the signed app.
open "build/DeskLink.app"

echo ""
echo "DeskLink launched. Click the menu-bar icon → Start Server."
echo "(You can close this window.)"
