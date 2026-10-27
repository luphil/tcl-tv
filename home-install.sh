#!/bin/zsh
# Build and (re)install the TV Home launcher, restoring the permissions an update resets.
cd "${0:A:h}"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH ANDROID_HOME=$HOME/Library/Android/sdk
adb connect 192.168.0.10:5555 >/dev/null

(cd launcher && ./gradlew --no-daemon -q assembleRelease) || exit 1
cp launcher/app/build/outputs/apk/release/app-release.apk tv-home.apk
adb install -r tv-home.apk | tail -1

adb shell appops set local.tvhome MANAGE_EXTERNAL_STORAGE allow
adb shell cmd package set-home-activity --user 0 local.tvhome/.HomeActivity >/dev/null
# Reinstalling unbinds the remote-button service; toggle it to rebind, with pauses so
# Android has finished processing the update first (instant toggles get ignored)
SERVICE=local.tvhome/local.tvhome.SettingsKeyService
for attempt in 1 2 3; do
  sleep 3
  adb shell settings put secure enabled_accessibility_services '""'
  sleep 1
  adb shell settings put secure enabled_accessibility_services $SERVICE
  adb shell settings put secure accessibility_enabled 1
  sleep 2
  adb shell dumpsys accessibility | grep -q "TV Home remote buttons" && { echo "Remote buttons: on"; break; }
done

./home-apply.sh
