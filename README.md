# TCL TV, de-corporatised

A TCL Google TV (55T7B, Android 12) with the ads, tracking and sponsor apps removed, Google's
homescreen replaced by a small homescreen app built from scratch, and a tracking-free screensaver.
Everything is controlled from a Mac over Wi-Fi with `adb`.

## What's here

| Path | What it is |
|---|---|
| `launcher/` | **TV Home**, the homescreen app (Kotlin, no third-party libraries). A row of app and HDMI-input tiles (sized to fit the screen; inputs can show a logo on brand colours), your photos as a slowly zooming slideshow, clock, date and weather with today's high/low (Open-Meteo, no account), quiet all-apps and settings icons, an ambient mode that hides the tiles when idle, skipping to the next photo by typing 6 6 6 quickly on the remote, a metronome with sampled drums (88 beats and a beat generator) opened by typing 5 5 5 within 1.5 seconds, a guitar chord and scale book opened with 7 7 7, 33 live feeds from around the world opened with 1 1 1 (`launcher/app/src/main/assets/livefeeds.json`; feeds find their current video themselves, with YouTube's overlays, ads and tracking removed), a QR code to join the Wi-Fi opened with 9 9 9 (the password stays in private `home/wifi.json`, kept out of git; copy it over with `wifi-apply.sh`), and remote-button remapping. Weather and settings icons are from [Lucide](https://lucide.dev) (ISC); the Apple and PlayStation logos from [Simple Icons](https://simpleicons.org) (CC0). |
| `home/config.json` | Everything the homescreen shows: tile order, HDMI inputs, layout, font, wallpaper timing, weather location, remote buttons. |
| `home/fonts/` | Space Grotesk ([SIL Open Font License](home/fonts/OFL.txt)). |
| `aerial-views/notracking-openmeteo.patch` | Changes to [Aerial Views](https://github.com/theothernt/AerialViews) 1.8.5: weather via Open-Meteo instead of OpenWeather (no API key), plus a system-wallpaper rotator. Build its `fdroid` flavour to leave out Firebase. |
| `aerial-views/settings.avsettings` | Screensaver settings: local photos, clock, temperature. |
| `removed-packages.txt` | Every app removed, grouped by why. |
| `*.sh` | The tools (below). |

## Scripts

| Script | Does |
|---|---|
| `debloat.sh` | Removes everything in `removed-packages.txt`. |
| `restore.sh` | Puts every removed app back, re-enables Google's homescreen, and turns the microphones back on. |
| `home-install.sh` | Builds and installs TV Home, then grants its permissions. |
| `home-apply.sh` | Pushes `home/config.json`, fonts and the photos in `~/Desktop/TV wallpapers`. The homescreen reloads by itself. |
| `screensaver-apply.sh` | Pushes photos and screensaver settings to Aerial Views. |
| `mic-off.sh` | Turns the microphones off (Android's system-wide mic block, Google Assistant disabled). `./mic-off.sh undo` turns them back on. |
| `tools/build-drumkits.py` | Builds the drum kits from free CC0 recordings ([Big Rusty Drums](https://github.com/sfzinstruments/karoryfer.big-rusty-drums), [Virtuosity Drums](https://github.com/sfzinstruments/virtuosity_drums), [Gogodze Phu Vol II](https://github.com/sfzinstruments/karoryfer.gogodze-phu-vol-ii)). Needs numpy; the samples aren't kept in this repo. |
| `tools/build-electronic-kits.py` | Generates the 808, 909 and trap kits by modelling the drum machines' circuits. |
| `drums-apply.sh` | Copies the built kits (about 1 GB) to the TV. |
| `tools/check-drumbeats.py`, `tools/check-chordbook.py` | Check the drum patterns and the chord and scale data. |
| `tvkey.sh` | Virtual remote, including real long-presses (`./tvkey.sh hold:enter`). |

## Doing this to your own TV

You'll need a TCL (or other) Google TV, a Mac on the same network, and
`brew install --cask android-platform-tools`.

1. On the TV, turn on developer mode: **Settings → System → About**, then press OK on
   **Android TV OS build** seven times. Next, turn on **Settings → System → Developer options →
   USB debugging**.
2. Run `adb connect <tv-ip>:5555` and accept the prompt on the TV.
3. Edit `removed-packages.txt` to suit you, then run `TV=<tv-ip> ./debloat.sh`.
4. Build and install the homescreen. You need `brew install openjdk@21` and the Android SDK, plus
   your own signing key; see `launcher/app/build.gradle.kts`. Edit `home/config.json` for your apps
   and HDMI inputs. Find input IDs with `adb shell dumpsys tv_input`.
5. Make it the homescreen by disabling Google's:
   `adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx`
   and `com.google.android.tungsten.setupwraith`.

**Gotchas:**
- Disabling `setupwraith` makes the TV forget Wi-Fi networks saved during initial setup, so you'll
  need to re-enter the Wi-Fi password once.
- Key codes for remote buttons vary between remotes. To find one, run `adb logcat -c`, press the
  button, then run `adb logcat -d | grep handleKeyBeforeQueueing`.
- Changes from `adb` are per-user and reversible; a factory reset undoes all of it.
