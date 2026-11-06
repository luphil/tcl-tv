#!/usr/bin/env python3
"""Builds the drum kits for the metronome from free (CC0) sample libraries.

Downloads the recordings, mixes each drum from its real microphones, keeps every velocity
layer and alternate take, and writes one pack per kit to build/drums/out/<kit>/ (pack.bin:
48 kHz 16-bit stereo; index.json: where each hit is). ./drums-apply.sh then copies them to the TV.
The samples are too big for this repo, so they're rebuilt from source. Needs numpy (build/venv)."""
import concurrent.futures as futures
import json, os, re, struct, subprocess, sys, urllib.parse, urllib.request
import numpy as np

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "build/drums/src")
WAV = os.path.join(ROOT, "build/drums/wav")
OUT = os.path.join(ROOT, "build/drums/out")
RATE = 48000

REPOS = {
    "rusty": "karoryfer.big-rusty-drums",
    "virtuosity": "virtuosity_drums",
    "gogodze": "karoryfer.gogodze-phu-vol-ii",
}
VL = r"_vl(?P<v>\d+)"
RR = r"_rr(?P<r>\d+)"
AUDIO = r"\.(?:flac|wav)"


def virt(folder, name, mics, rr=True):
    return ("virtuosity", rf"Samples/{{mic}}/{folder}/{{mic}}_{name}{VL}{RR if rr else ''}{AUDIO}", mics)


def rusty(path, stem, mics):
    return ("rusty", rf"Samples/{path}/{{mic}}/{stem}{VL}{RR}{AUDIO}", mics)


def gogo(code, mics):
    return ("gogodze", rf"Samples/{{mic}}/{code}{VL}{RR}{AUDIO}", mics)


def perc(name, mics=None, layers=True):
    v = r"_v(?P<v>\d+)" if layers else ""
    return ("virtuosity", rf"Samples/perc/{{mic}}/(?:.*/)?{name}{v}{RR}_\w+{AUDIO}", mics or PERC_MICS)


V_KICK = {"kickmic": 1.0, "oh": 0.45, "room": 0.35}
V_SNARE = {"snaremic": 1.0, "oh": 0.5, "room": 0.4}
V_TOM = {"mid": 1.0, "oh": 0.8, "room": 0.4}
V_HAT = {"oh": 1.0, "mid": 0.7, "room": 0.25}
V_CYMBAL = {"oh": 1.0, "room": 0.4}
R_KICK = {"kick": 1.0, "oh": 0.5}
R_SNARE = {"top": 1.0, "btm": 0.35, "oh": 0.55}
R_TOM = {"cl": 1.0, "oh": 0.5}
R_HAT = {"cl": 0.8, "oh": 0.8}
R_CYMBAL = {"cl": 0.5, "oh": 1.0}
G_KICK = {"kick_mic": 1.0, "front_mic": 0.4}
G_SNARE = {"snare_mic": 1.0, "oh_mic": 0.5}
G_ROOM = {"oh_mic": 1.0, "front_mic": 0.6}
PERC_MICS = {"close": 1.0, "oh": 0.5, "room": 0.3}

VIRT_CYMBALS = {
    "ride": [virt("ride", "ride_ride", V_CYMBAL)],
    "ride_bell": [virt("ride", "ride_bell", V_CYMBAL)],
    "crash": [virt("crash", "crash_crash", V_CYMBAL)],
    "crash2": [virt("crash", "crash_sizzle", V_CYMBAL)],
}

KITS = {
    "big": ("Big Rock Kit", {
        "kick": [rusty("kick_24/kick", "k", R_KICK)],
        "snare": [rusty("snare_14/center", "sn_center", R_SNARE)],
        "rimshot": [rusty("snare_14/rimshot", "sn_rims", R_SNARE)],
        "sidestick": [rusty("snare_14/sidestick", "sn_ss", R_SNARE)],
        "hat_closed": [rusty("hihat_14/tc", "ht_tc", R_HAT)],
        "hat_loose": [rusty("hihat_14/lc", "ht_lc", R_HAT)],
        "hat_open": [rusty("hihat_14/open", "ht_open", R_HAT)],
        "hat_pedal": [rusty("hihat_14/chik", "ht_chik", R_HAT)],
        "ride": [rusty("ride_22/rd", "rd", R_CYMBAL)],
        "ride_bell": [rusty("ride_22/bl", "rd_bl", R_CYMBAL)],
        "crash": [rusty("crash_17/cr", "cr", R_CYMBAL)],
        "crash2": [rusty("china_18/cn", "cn", R_CYMBAL)],
        "tom_high": [rusty("tom_14/center", "t14", R_TOM)],
        "tom_mid": [rusty("tom_18/center", "t18", R_TOM)],
        "tom_low": [rusty("tom_22/center", "t22", R_TOM)],
    }),
    "studio": ("Studio Kit", {
        "kick": [virt("kick", "kick_snon", V_KICK)],
        "snare": [virt("snare", "snare_center", V_SNARE, rr=False)],
        "rimshot": [virt("snare", "snare_rimshot", V_SNARE, rr=False)],
        "sidestick": [virt("snare", "snare_crossstick", V_SNARE, rr=False)],
        "hat_closed": [virt("hh", "hh_closed", V_HAT)],
        "hat_loose": [virt("hh", "hh_half", V_HAT)],
        "hat_open": [virt("hh", "hh_open", V_HAT)],
        "hat_pedal": [virt("hh", "hh_pedal", V_HAT)],
        **VIRT_CYMBALS,
        "tom_high": [virt("htom", "htom_center", V_TOM, rr=False)],
        "tom_mid": [virt("ltom", "ltom_offcenter", V_TOM, rr=False)],
        "tom_low": [virt("ltom", "ltom_center", V_TOM, rr=False)],
    }),
    "vintage": ("Vintage 70s Kit", {
        "kick": [gogo("ks", G_KICK)],
        "snare": [gogo("sc", G_SNARE)],
        "rimshot": [gogo("se", G_SNARE)],
        "sidestick": [gogo("ss", G_SNARE)],
        "hat_closed": [gogo("hc", G_ROOM)],
        "hat_loose": [gogo("hl", G_ROOM)],
        "hat_open": [gogo("ho", G_ROOM)],
        "hat_pedal": [gogo("hf", G_ROOM)],
        **VIRT_CYMBALS,
        "tom_high": [gogo("th", G_ROOM)],
        "tom_mid": [gogo("tm", G_ROOM)],
        "tom_low": [gogo("tl", G_ROOM)],
    }),
    "brushes": ("Brushes", {
        "kick": [rusty("kick_24/kick", "k", R_KICK)],
        "snare": [rusty("snare_14/brush", "sn_b", R_SNARE)],
        "hat_closed": [rusty("hihat_14/cl_brush", "ht_cl_b", R_HAT)],
        "hat_loose": [rusty("hihat_14/lc_brush", "ht_lc_b", R_HAT)],
        "hat_open": [rusty("hihat_14/open_brush", "ht_open_b", R_HAT)],
        "ride": [rusty("ride_22/brush", "rd_b", R_CYMBAL)],
        "crash": [rusty("crash_17/brush", "cr_b", R_CYMBAL)],
        "tom_high": [rusty("tom_14/brush", "t14_b", R_TOM)],
        "tom_mid": [rusty("tom_18/brush", "t18_b", R_TOM)],
        "tom_low": [rusty("tom_22/brush", "t22_b", R_TOM)],
    }),
    "perc": ("Percussion", {
        "cowbell": [perc("Cowbell1_Normal")],
        "tambourine": [perc("Tamb1_Shake", layers=False)],
        "shaker": [perc("LShaker_Shake1D", layers=False)],
        "claves": [perc("Claves1_Hit")],
        "triangle": [perc("Triangle1_Hit")],
        "agogo_high": [perc("Agogo_High")],
        "agogo_low": [perc("Agogo_Low")],
        "bongo_high": [perc("BongoH_Hit1")],
        "bongo_low": [perc("BongoL_Hit1")],
        "conga_high": [perc("Conga_17_HitHM1_1_70", layers=False), perc("Conga_17_HitHM1_71_127", layers=False)],
        "conga_low": [perc("Conga_22_HitN_1_50", layers=False), perc("Conga_22_HitN_51_100", layers=False), perc("Conga_22_HitN_101_127", layers=False)],
        "guiro": [perc("Guiro_Med", layers=False)],
        "cabasa": [perc("Cabasa1_Rub")],
    }),
}


def tree(repo):
    cache = os.path.join(SRC, repo + ".tree")
    if not os.path.exists(cache):
        os.makedirs(SRC, exist_ok=True)
        full = "sfzinstruments/" + repo
        branch = subprocess.check_output(["gh", "api", f"repos/{full}", "--jq", ".default_branch"], text=True).strip()
        paths = subprocess.check_output(["gh", "api", f"repos/{full}/git/trees/{branch}?recursive=1", "--jq", '.tree[]|select(.type=="blob")|.path'], text=True)
        open(cache, "w").write(branch + "\n" + paths)
    lines = open(cache).read().splitlines()
    return lines[0], lines[1:]


def fetch(repo, branch, path):
    """Downloads a recording and converts it to 48 kHz float, keeping both on disk."""
    wav = os.path.join(WAV, repo, path + ".wav")
    if os.path.exists(wav):
        return wav
    raw = os.path.join(SRC, repo, path)
    os.makedirs(os.path.dirname(raw), exist_ok=True)
    os.makedirs(os.path.dirname(wav), exist_ok=True)
    if not os.path.exists(raw):
        url = f"https://raw.githubusercontent.com/sfzinstruments/{repo}/{branch}/" + urllib.parse.quote(path)
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
        open(raw + ".part", "wb").write(data)
        os.rename(raw + ".part", raw)
    subprocess.check_call(["afconvert", "-f", "WAVE", "-d", f"LEF32@{RATE}", raw, wav + ".tmp"])
    os.rename(wav + ".tmp", wav)
    return wav


def read(path):
    data = open(path, "rb").read()
    i, channels = 12, 2
    while i < len(data):
        chunk = data[i:i + 4]
        size = struct.unpack("<I", data[i + 4:i + 8])[0]
        if chunk == b"fmt ":
            channels = struct.unpack("<H", data[i + 10:i + 12])[0]
        if chunk == b"data":
            audio = np.frombuffer(data[i + 8:i + 8 + size], "<f4").reshape(-1, channels)
            return np.repeat(audio, 2, axis=1) if channels == 1 else audio[:, :2]
        i += 8 + size + (size & 1)
    raise ValueError(path)


def takes(spec, layer_index):
    """Finds a spec's recordings: {(layer, take): {mic: path}}."""
    repo, pattern, mics = spec
    branch, paths = tree(REPOS[repo])
    found = {}
    for mic in mics:
        rule = re.compile(pattern.replace("{mic}", mic) + "$")
        for path in paths:
            match = rule.fullmatch(path)
            if match:
                groups = match.groupdict()
                key = (int(groups.get("v") or layer_index + 1), int(groups.get("r") or 1))
                found.setdefault(key, {})[mic] = path
    return repo, branch, mics, {key: value for key, value in found.items() if set(value) == set(mics)}


def mix(repo, branch, mics, paths):
    parts = [(read(fetch(REPOS[repo], branch, path)), mics[mic]) for mic, path in paths.items()]
    length = max(len(audio) for audio, _ in parts)
    out = np.zeros((length, 2), dtype=np.float64)
    for audio, gain in parts:
        out[: len(audio)] += audio * gain
    return out


def trim(audio):
    """Cuts silence only: before the hit (leaving 1 ms) and after it has fully died away."""
    level = np.abs(audio).max(axis=1)
    peak = level.max()
    start = max(0, int(np.argmax(level > peak * 0.02)) - RATE // 1000)
    quiet = np.nonzero(level > max(peak * 0.0003, 2e-5))[0]
    end = min(len(audio), int(quiet[-1]) + RATE // 50) if len(quiet) else len(audio)
    out = audio[start:end].copy()
    fade = min(RATE // 50, len(out))
    out[-fade:] *= np.linspace(1, 0, fade)[:, None]
    return out


def build(kit, label, roles, pool):
    folder = os.path.join(OUT, kit)
    os.makedirs(folder, exist_ok=True)
    rng = np.random.default_rng(1)
    index = {"label": label, "rate": RATE, "roles": {}, "levels": {}}
    offset = 0
    with open(os.path.join(folder, "pack.bin.tmp"), "wb") as pack:
        for role, specs in roles.items():
            layers = {}
            for i, spec in enumerate(specs):
                repo, branch, mics, found = takes(spec, i)
                if not found:
                    raise SystemExit(f"{kit}/{role}: no recordings match {spec[1]}")
                jobs = {key: pool.submit(mix, repo, branch, mics, paths) for key, paths in sorted(found.items())}
                for (layer, take), job in jobs.items():
                    layers.setdefault((i, layer), []).append(trim(job.result()))
            # Softest to loudest, by measured level rather than trusting the numbering
            ordered = sorted(layers.values(), key=lambda hits: np.mean([np.abs(h).max() for h in hits]))
            gain = 0.9 / max(np.abs(h).max() for hits in ordered for h in hits)
            entry = []
            # Each layer's level in dB below the loudest, so hits can be chosen by loudness
            index["levels"][role] = [round(20 * np.log10(np.mean([np.abs(h).max() for h in hits]) * gain / 0.9), 1) for hits in ordered]
            for hits in ordered:
                row = []
                for hit in hits:
                    noise = (rng.random(hit.shape) - rng.random(hit.shape)) / 32768
                    pcm = np.clip((hit * gain + noise) * 32767, -32768, 32767).astype("<i2")
                    pack.write(pcm.tobytes())
                    row.append([offset, len(pcm)])
                    offset += len(pcm)
                entry.append(row)
            index["roles"][role] = entry
            print(f"  {kit}/{role}: {len(entry)} layers, {sum(len(r) for r in entry)} hits", flush=True)
    os.replace(os.path.join(folder, "pack.bin.tmp"), os.path.join(folder, "pack.bin"))
    json.dump(index, open(os.path.join(folder, "index.json"), "w"), separators=(",", ":"))
    print(f"{kit}: {offset * 4 / 1e6:.0f} MB", flush=True)


if __name__ == "__main__":
    wanted = sys.argv[1:] or list(KITS)
    with futures.ThreadPoolExecutor(12) as pool:
        for kit in wanted:
            build(kit, KITS[kit][0], KITS[kit][1], pool)
