#!/bin/zsh
# Restore removed TV packages. Usage: ./restore.sh [package ...]  (no args = restore everything in removed-packages.txt)
cd "${0:A:h}"
adb connect 192.168.0.10:5555 >/dev/null
pkgs=("$@")
[ ${#pkgs} -eq 0 ] && pkgs=(${(f)"$(grep -v '^#' removed-packages.txt | grep .)"})
for p in $pkgs; do
  echo "$p: $(adb shell cmd package install-existing --user 0 $p 2>&1)"
done
# Re-enable the stock Google TV homescreen if it was disabled
# Note: disabling setupwraith made Android forget Wi-Fi networks it had saved during initial setup.
if [ ${#@} -eq 0 ]; then
  adb shell pm enable --user 0 com.google.android.apps.tv.launcherx
  adb shell pm enable --user 0 com.google.android.tungsten.setupwraith
  ./mic-off.sh undo
fi
