#!/bin/zsh
# Push the homescreen config, fonts and wallpaper photos to the TV, then show home.
# Usage: ./home-apply.sh            (photos from ~/Desktop/TV wallpapers)
#        ./home-apply.sh /some/dir  (photos from another folder)
cd "${0:A:h}"
PHOTOS=${1:-"$HOME/Desktop/TV wallpapers"}
adb connect 192.168.0.10:5555 >/dev/null

# Photos are shared with the screensaver
adb shell rm -rf /sdcard/Pictures/Wallpapers
adb shell mkdir -p /sdcard/Pictures/Wallpapers
adb push "$PHOTOS/." /sdcard/Pictures/Wallpapers/ | tail -1

# Fonts (drop .ttf/.otf files in ~/tcl-tv/home/fonts) and the config last,
# since a new config is what makes the homescreen reload
adb shell mkdir -p /sdcard/TVHome/fonts
[ -d home/fonts ] && adb push home/fonts/. /sdcard/TVHome/fonts/ >/dev/null
adb push home/config.json /sdcard/TVHome/config.json | tail -1
# adb push keeps the Mac's timestamp; stamp it now so the homescreen always reloads
adb shell touch /sdcard/TVHome/config.json
adb shell input keyevent KEYCODE_HOME
