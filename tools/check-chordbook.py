#!/usr/bin/env python3
"""Checks every chord shape and scale in the chord book: the right notes, and playable.
Run after editing launcher/app/src/main/assets/chordbook.json. Exits non-zero on any problem."""
import json, os, re, sys

BOOK = os.path.join(os.path.dirname(__file__), "../launcher/app/src/main/assets/chordbook.json")
TUNING = [4, 9, 2, 7, 11, 4]  # E A D G B E, as pitch classes
PC = {"C": 0, "C#": 1, "D": 2, "Eb": 3, "E": 4, "F": 5, "F#": 6, "G": 7, "Ab": 8, "A": 9, "Bb": 10, "B": 11}
MAJOR = [0, 2, 4, 5, 7, 9, 11]
book = json.load(open(BOOK))
problems = []


def moved(shape, root, string_pc):
    """A movable shape placed on a root: returns frets (None = muted), an octave up if needed."""
    offsets = [None if o == "x" else int(o) for o in shape.split()]
    fret = (root - string_pc) % 12
    if min(o for o in offsets if o is not None) + fret < 0:
        fret += 12
    return [None if o is None else o + fret for o in offsets]


def opened(shape):
    return [None if c == "x" else int(c) for c in shape]


def fingers(frets):
    """Fingers needed: one finger can flatten across neighbouring strings at the same fret,
    or the first finger can barre the lowest fret under everything else."""
    pressed = [(s, f) for s, f in enumerate(frets) if f]
    runs = sum(1 for i, (s, f) in enumerate(pressed) if i == 0 or pressed[i - 1] != (s - 1, f))
    low = min(f for _, f in pressed)
    barre = 1 + sum(1 for _, f in pressed if f > low)
    open_under_barre = any(f == 0 for s, f in enumerate(frets) if pressed[0][0] < s)
    return runs if open_under_barre else min(runs, barre)


def check(label, frets, root, intervals, optional=()):
    sounded = [(s, f) for s, f in enumerate(frets) if f is not None]
    notes = [(TUNING[s] + f) % 12 for s, f in sounded]
    relative = {(n - root) % 12 for n in notes}
    chord = {i % 12 for i in intervals}
    if len(frets) != 6:
        problems.append(f"{label}: {len(frets)} strings")
    extra = relative - chord
    if extra:
        problems.append(f"{label} {frets}: wrong notes {sorted(extra)}")
    # Bigger chords may leave out the 5th, and any note marked optional
    needed = chord - set(optional) - ({7} if len(chord) > 3 else set())
    missing = needed - relative
    if missing:
        problems.append(f"{label} {frets}: missing {sorted(missing)}")
    if notes[0] != root:
        problems.append(f"{label} {frets}: bass isn't the root")
    pressed = [f for _, f in sounded if f > 0]
    if pressed:
        if max(pressed) - min(pressed) > 3:
            problems.append(f"{label} {frets}: spans more than 4 frets")
        if fingers(frets) > 4:
            problems.append(f"{label} {frets}: needs more than 4 fingers")


for chord in book["chords"]:
    for name, root in PC.items():
        label = name + chord["suffix"]
        check(label + " (E shape)", moved(chord["e"], root, 4), root, chord["intervals"], chord.get("optional", []))
        check(label + " (A shape)", moved(chord["a"], root, 9), root, chord["intervals"], chord.get("optional", []))
    for name, shape in chord.get("open", {}).items():
        check(name + chord["suffix"] + " (open)", opened(shape), PC[name], chord["intervals"], chord.get("optional", []))

# Scales: degrees must name distinct notes, ascending
for scale in book["scales"]:
    semis = []
    for degree in scale["degrees"].split():
        m = re.fullmatch(r"([b#]*)(\d)", degree)
        semis.append(MAJOR[int(m[2]) - 1] + m[1].count("#") - m[1].count("b"))
    if semis != sorted(set(semis)):
        problems.append(f"{scale['name']}: degrees out of order {semis}")

# Well-known scales, spelled out independently of their degree names
known = {
    "Major (Ionian)": [0, 2, 4, 5, 7, 9, 11], "Natural Minor (Aeolian)": [0, 2, 3, 5, 7, 8, 10],
    "Harmonic Minor": [0, 2, 3, 5, 7, 8, 11], "Melodic Minor": [0, 2, 3, 5, 7, 9, 11],
    "Harmonic Major": [0, 2, 4, 5, 7, 8, 11], "Double Harmonic": [0, 1, 4, 5, 7, 8, 11],
    "Hungarian Minor": [0, 2, 3, 6, 7, 8, 11], "Neapolitan Minor": [0, 1, 3, 5, 7, 8, 11],
    "Neapolitan Major": [0, 1, 3, 5, 7, 9, 11], "Bebop Dominant": [0, 2, 4, 5, 7, 9, 10, 11],
    "Bebop Major": [0, 2, 4, 5, 7, 8, 9, 11], "Hirajoshi": [0, 2, 3, 7, 8], "In Sen": [0, 1, 5, 7, 10],
}
for scale in book["scales"]:
    if scale["name"] in known:
        semis = [MAJOR[int(d[-1]) - 1] + d.count("#") - d.count("b") for d in scale["degrees"].split()]
        if semis != known.pop(scale["name"]):
            problems.append(f"{scale['name']}: wrong formula {semis}")
problems += [f"missing scale: {name}" for name in known]
names = [scale["name"] for scale in book["scales"]]
problems += [f"duplicate scale: {name}" for name in set(names) if names.count(name) > 1]

# Well-known chords, as a check on the checker and on the data
expected = {"C": "x32010", "Am": "x02210", "G7": "320001", "Dm7": "xx0211", "Cmaj7": "x32000"}
for name, shape in expected.items():
    root = name[0]
    suffix = name[1:]
    chord = next(c for c in book["chords"] if c["suffix"] == suffix)
    if chord["open"].get(root) != shape:
        problems.append(f"expected {name} = {shape}")

shapes = len(book["chords"]) * 24 + sum(len(c.get("open", {})) for c in book["chords"])
print(f"{len(book['chords'])} chord types, {shapes} shapes, {len(book['scales'])} scales checked")
print("\n".join(problems) if problems else "All correct")
sys.exit(1 if problems else 0)
