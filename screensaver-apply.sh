#!/bin/zsh
# Sync photos and apply aerial-views/settings.avsettings to the TV's screensaver.
# Usage: ./screensaver-apply.sh            (photos from ~/Desktop/TV wallpapers)
#        ./screensaver-apply.sh /some/dir  (photos from another folder)
cd "${0:A:h}"
PHOTOS=${1:-"$HOME/Desktop/TV wallpapers"}
adb connect 192.168.0.10:5555 >/dev/null

# Mirror the photo folder: clear the old set, push the new one
adb shell rm -rf /sdcard/Pictures/Wallpapers
adb shell mkdir -p /sdcard/Pictures/Wallpapers
adb push "$PHOTOS/." /sdcard/Pictures/Wallpapers/ | tail -1

# Hand the settings file to Aerial Views, then confirm its import dialog.
# Scoped storage only lets the app read media files it didn't create, so the
# settings travel as a temporary ".jpg" outside the wallpaper folder.
F=/sdcard/Pictures/aerial-views-settings.jpg
adb push aerial-views/settings.avsettings $F >/dev/null
adb shell am force-stop com.neilturner.aerialviews
adb shell am start -a android.intent.action.VIEW -d file://$F -t text/avsettings \
  -n com.neilturner.aerialviews/.ui.MainActivity >/dev/null
echo "Confirm the Import dialog on the TV, then run: adb shell rm $F"
