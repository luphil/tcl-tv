#!/bin/zsh
# Turn the TV's microphones off: Android's system-wide mic block (apps only ever hear silence),
# Google Assistant disabled (the "Hey Google" listener and remote voice search), and record-audio
# taken away from the apps that had it. Survives reboots. Undo with ./mic-off.sh undo
cd "${0:A:h}"
adb connect 192.168.0.10:5555 >/dev/null
APPS=(com.google.android.youtube.tv com.google.android.gms com.google.android.tts com.tcl.gamebar com.tcl.miracast com.tcl.ui_mediaCenter)

if [ "$1" = undo ]; then
  adb shell cmd sensor_privacy disable 0 microphone
  adb shell pm enable --user 0 com.google.android.katniss
  for p in $APPS; do adb shell appops set $p RECORD_AUDIO default; done
  echo "Microphone on"
  exit
fi

adb shell cmd sensor_privacy enable 0 microphone
adb shell pm disable-user --user 0 com.google.android.katniss
for p in $APPS; do
  adb shell pm revoke $p android.permission.RECORD_AUDIO 2>/dev/null
  adb shell appops set $p RECORD_AUDIO ignore
done
adb shell dumpsys sensor_privacy | grep -A1 "sensor=1" | grep -q "is_enabled=true" && echo "Microphone off"
