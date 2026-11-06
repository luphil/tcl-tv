#!/bin/zsh
# Copy the drum kits made by tools/build-drumkits.py to the TV (about 1 GB, so only when they change).
cd "${0:A:h}"
adb connect 192.168.0.10:5555 >/dev/null
adb shell mkdir -p /sdcard/TVHome/drums
adb push build/drums/out/. /sdcard/TVHome/drums/ | tail -1
