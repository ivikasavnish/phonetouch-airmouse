#!/bin/bash
# Builds PhoneTouchBar.app (menu bar control for the PhoneTouch daemon) next to the repo root
set -euo pipefail
cd "$(dirname "$0")"
APP=../PhoneTouchBar.app
mkdir -p "$APP/Contents/MacOS"

# Some Command Line Tools installs ship both bridging.modulemap and module.modulemap
# defining SwiftBridging ("redefinition of module"); hide the duplicate via a VFS overlay.
SWIFT_FLAGS=()
DUP=/Library/Developer/CommandLineTools/usr/include/swift/module.modulemap
if [[ -f "$DUP" && -f "$(dirname "$DUP")/bridging.modulemap" ]]; then
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT
    : > "$TMP/empty.modulemap"
    printf '{"version":0,"roots":[{"type":"file","name":"%s","external-contents":"%s"}]}\n' \
        "$DUP" "$TMP/empty.modulemap" > "$TMP/overlay.yaml"
    SWIFT_FLAGS=(-vfsoverlay "$TMP/overlay.yaml" -Xcc -ivfsoverlay -Xcc "$TMP/overlay.yaml")
fi

/usr/bin/xcrun swiftc -O ${SWIFT_FLAGS[@]+"${SWIFT_FLAGS[@]}"} PhoneTouchBar.swift -o "$APP/Contents/MacOS/PhoneTouchBar"
cat > "$APP/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key>
    <string>com.phonetouch.menubar</string>
    <key>CFBundleName</key>
    <string>PhoneTouchBar</string>
    <key>CFBundleExecutable</key>
    <string>PhoneTouchBar</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>LSUIElement</key>
    <true/>
</dict>
</plist>
EOF
codesign --force --sign - "$APP" >/dev/null 2>&1 || true
echo "Built $APP"

# Install into /Applications (the login agent launches it from there)
ditto "$APP" /Applications/PhoneTouchBar.app
echo "Installed /Applications/PhoneTouchBar.app"
