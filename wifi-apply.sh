#!/bin/zsh
# Copy the Wi-Fi details in home/wifi.json (private, not in git) to the TV for its QR code.
cd "${0:A:h}"
if ! grep -qE '"password": *"[^"]+"' home/wifi.json 2>/dev/null; then
  echo "Add the password to home/wifi.json first"
  exit 1
fi
adb connect 192.168.0.10:5555 >/dev/null
adb push home/wifi.json /sdcard/TVHome/wifi.json | tail -1
