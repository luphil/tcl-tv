#!/usr/bin/env python3
"""Checks the drum pattern library: every line the right length and only known drums and levels."""
import os, re, sys
PATH = os.path.join(os.path.dirname(__file__), "../launcher/app/src/main/assets/drumbeats.txt")
KEYS = set("ksCrthlopybcd123wmevnaAgGqQuf")
problems, beats, names, current = [], [], set(), None
for number, line in enumerate(open(PATH, encoding="utf8"), 1):
    line = line.rstrip("\n")
    if not line or line.startswith("#"):
        continue
    if line.startswith("@"):
        style, name, size, swing, kit = [part.strip() for part in line[1:].split("|")]
        count, sub = map(int, size.split())
        current = {"name": f"{style}/{name}", "bar": count * sub, "lengths": set(), "keys": set()}
        beats.append(current)
        if current["name"] in names:
            problems.append(f"{number}: duplicate {current['name']}")
        names.add(current["name"])
        if kit not in ("big", "studio", "vintage", "brushes", "808", "909", "trap"):
            problems.append(f"{number}: unknown kit {kit}")
        if not 0 <= float(swing) <= 1:
            problems.append(f"{number}: swing out of range")
        continue
    key, steps = line.split(" ", 1)
    if key not in KEYS:
        problems.append(f"{number}: unknown drum {key}")
    if key in current["keys"]:
        problems.append(f"{number}: drum {key} twice in {current['name']}")
    current["keys"].add(key)
    if not re.fullmatch(r"[Xxog.]+", steps):
        problems.append(f"{number}: bad steps {steps!r}")
    current["lengths"].add(len(steps))
for beat in beats:
    if len(beat["lengths"]) != 1 or next(iter(beat["lengths"])) % beat["bar"]:
        problems.append(f"{beat['name']}: lines are {sorted(beat['lengths'])} steps, bar is {beat['bar']}")
styles = {b["name"].split("/")[0] for b in beats}
print(f"{len(beats)} beats in {len(styles)} styles checked")
print("\n".join(problems) if problems else "All correct")
sys.exit(1 if problems else 0)
