#!/bin/zsh
# Remove the apps listed in removed-packages.txt (ads, tracking, sponsor apps) for the TV's user.
# Nothing is deleted from the system: ./restore.sh or a factory reset brings it all back.
# Edit removed-packages.txt first — it includes a few apps only this TV's owner didn't want.
cd "${0:A:h}"
TV=${TV:-192.168.0.10}
adb connect $TV:5555 >/dev/null

# Swap the ambient-mode screensaver for the plain one before removing it
adb shell settings put secure screensaver_components com.android.dreams.basic/com.android.dreams.basic.Colors
for p in ${(f)"$(grep -v '^#' removed-packages.txt | grep .)"}; do
  echo "$p: $(adb shell pm uninstall -k --user 0 $p 2>&1)"
done
