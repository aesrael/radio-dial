#!/usr/bin/env python3
"""Build stations.json for Radio Dial.

Pinned, hand-tuned stations (stations.pinned.json) always come first and are
never rewritten. Everything after them is discovered from the radio-browser
API as a *select set* per group — a handful of the best-ranked stations for
each genre, plus the strongest UK extras — filtered to what a browser can play
from an https page and probed over the network before being written.

Usage:
    python3 tools/build_stations.py                  # full rebuild
    python3 tools/build_stations.py --no-probe       # skip live probing
    python3 tools/build_stations.py --limit-uk 40 --per-genre 6
    python3 tools/build_stations.py --dry-run        # report, write nothing
"""

from __future__ import annotations

import argparse
import concurrent.futures as futures
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PINNED = ROOT / "stations.pinned.json"
OUTPUT = ROOT / "stations.json"

BY_COUNTRY = "https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/{}"
BY_TAG = "https://de1.api.radio-browser.info/json/stations/bytagexact/{}"
UA = "RadioDial/1.0 (+https://radio-dial.pages.dev)"

# group -> (tags in priority order, how many to keep, name must match this).
# UK and US are hand-picked. Name matching keeps genre groups from filling with
# stations that merely carry the tag (a general station tagged "classical").
GENRES = {
    "Hip-Hop & R&B":   (["hip hop", "r&b", "rap"], 12, r"hip.?hop|\brap\b|rnb|r&b|urban|grime|trap|soul"),
    "Rock":            (["rock", "alternative", "indie"], 12, r"rock|metal|punk|indie|alternat|grunge|guitar"),
    "Electronic":      (["electronic", "house", "techno"], 12, r"dance|techno|house|trance|electro|drum|\bdnb\b|bass|club|rave|mix|beats"),
    "Jazz":            (["jazz"], 12, r"jazz|swing|bebop|bossa"),
    "Blues":           (["blues"], 10, r"blues"),
    "Soul & Funk":     (["soul", "funk"], 10, r"soul|funk|disco|boogie|motown|groove"),
    "Reggae":          (["reggae", "dub", "ska"], 10, r"reggae|dub|ska|dancehall|roots|jah"),
    "Latin":           (["latin", "salsa", "bachata"], 10, r"latin|salsa|bachata|merengue|reggaeton|cumbia|tropical|urbano"),
    "Classical":       (["classical", "opera"], 8, r"classic|baroque|opera|symphon|philharmon|chamber|piano|concert|orchestr"),
    "Ambient & Chill": (["ambient", "chillout", "lounge"], 10, r"ambient|chill|lounge|drone|space|relax|sleep|nature|calm"),
}
GROUP_ORDER = ["UK", "US", *GENRES]

# Handful of well-known stations that raise a genre's quality above tag search.
SEEDS = {
    "Jazz": ["FIP", "WBGO", "WWOZ", "Jazz24", "Radio Swiss Jazz", "TSF Jazz", "Jazz FM"],
    "Blues": ["Blues Radio", "BluesMen Channel", "Belly Up 4 Blues"],
    "Classical": ["Radio Swiss Classic", "WQXR", "Venice Classic Radio", "YourClassical"],
    "Soul & Funk": ["Soul Radio", "Funkstar Radio", "Solar Radio"],
}

# General/talk stations are only wanted from home: UK first, then a few from the US.
# Genre stations are universal, so they are picked by tag, not by country.
SELECTED = {
    "UK": (["BBC World Service", "BBC Radio 3", "BBC Radio Scotland", "BBC Radio Wales",
            "BBC Radio Ulster", "BBC Asian Network", "BBC Radio 5 Sports Extra",
            "KISS", "Kiss Fresh", "Capital XTRA", "Capital Dance", "Capital Chill",
            "Heart Dance", "Heart 70s", "Smooth Chill", "Smooth Relax", "Smooth Country",
            "Magic Soul", "Magic Chilled", "Absolute Radio 80s", "Absolute Radio 90s", "Absolute Classic Rock",
            "Virgin Radio Chilled", "Virgin Radio Anthems", "Virgin Radio Groove",
            "Gold London", "Radio X Classic Rock", "Classic FM Calm", "Classic FM Movies",
            "Boom Radio", "Fun Kids", "Rinse", "Reprezent Radio", "Kool FM"], 45),
    "US": (["WNYC", "KCRW", "WFMU", "KQED", "WBEZ", "WGBH", "WBUR", "KUOW"], 8),
}

PLAYABLE_CODECS = {"MP3", "AAC", "AAC+", "MP3,HLS", "HLS"}
AUDIO_TYPES = ("audio/", "application/ogg", "application/octet-stream",
               "application/vnd.apple.mpegurl", "application/x-mpegurl")

SHORT_COUNTRY = {
    "The United States Of America": "USA",
    "United States of America": "USA",
    "United States": "USA",
    "The Republic Of Korea": "South Korea",
    "Republic of Korea": "South Korea",
    "The United Kingdom Of Great Britain And Northern Ireland": "UK",
    "United Kingdom": "UK",
    "Russian Federation": "Russia",
    "Czech Republic": "Czechia",
}

NAME_NOISE = [
    re.compile(r"\s*[\(\[]\s*\d+\s*kbps?\s*[\)\]]", re.I),
    re.compile(r"\s*[\(\[]\s*\d+\s*k(\s*(mp3|aac|aac\+|hls))?\s*[\)\]]", re.I),
    re.compile(r"\s*[-–|]\s*\d+\s*kbps?\s*$", re.I),
    re.compile(r"\s+UK\s*$", re.I),
    re.compile(r"\s{2,}"),
]


def name_key(name: str) -> str:
    """Fuzzy identity: first 12 alphanumerics, so '(128k)' variants collapse."""
    return re.sub(r"[^a-z0-9]", "", name.lower())[:12]


def clean_name(raw: str) -> str:
    name = (raw or "").strip()
    for pattern in NAME_NOISE:
        name = pattern.sub(" ", name)
    name = name.strip(" -–|,:")
    return re.sub(r"\s{2,}", " ", name).strip(" -–|,:#")


def norm_key(url: str) -> str:
    return re.sub(r"^https?://", "", url.split("?")[0].strip().lower()).rstrip("/")


def short_code(name: str) -> str:
    """Dial-face label: a frequency if we can find one, else the name."""
    freq = re.search(r"\b(8\d\.\d|9\d\.\d|10\d\.\d|1[0-6]\d\d)\b", name)
    if freq:
        return freq.group(1)
    if len(name) <= 16:
        return name.strip(" -–|,:#")
    words, out = name.split(), ""
    for word in words:
        if len(out) + len(word) + 1 > 16:
            break
        out = f"{out} {word}".strip()
    return (out or name[:16]).strip(" -–|,:#")


def describe(station: dict, country_name: str, fallback_group: str) -> str:
    tags = [t.strip().lstrip("#") for t in (station.get("tags") or "").split(",")]
    seen, picked = set(), []
    for tag in tags:
        tag = tag.replace("-", " ").strip()
        if (len(tag) < 3 or tag.isdigit() or tag.lower() in seen
                or tag.lower() in {"radio", "music", "live"} or len(tag) > 18):
            continue
        seen.add(tag.lower())
        picked.append(tag)
        if len(picked) == 2:
            break
    bits = picked or [fallback_group]
    country = SHORT_COUNTRY.get(country_name, country_name or "")
    if country:
        bits.append(country)
    bitrate = station.get("bitrate") or 0
    if bitrate:
        bits.append(f"{bitrate}k")
    return " · ".join(bits)


def http_json(url: str, timeout: int = 25):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as res:
        return json.loads(res.read().decode("utf-8", "replace"))


def fetch(url: str, limit: int, label: str):
    """Top stations for a url, best-ranked first, skipping known-broken ones."""
    query = urllib.parse.urlencode({
        "limit": limit, "order": "votes", "reverse": "true", "hidebroken": "true",
    })
    try:
        return http_json(f"{url}?{query}")
    except (urllib.error.URLError, TimeoutError, ValueError) as exc:
        print(f"  ! {label}: {exc}", file=sys.stderr)
        return []


def fetch_country(code: str, limit: int):
    return fetch(BY_COUNTRY.format(code), limit, f"country {code}")


def fetch_tag(tag: str, limit: int):
    return fetch(BY_TAG.format(urllib.parse.quote(tag)), limit, f"tag {tag}")


SEARCH = "https://de1.api.radio-browser.info/json/stations/search"
VIDEO_NAME = re.compile(r"\b(HD|FHD|UHD|720p?|1080p?|4K|TV)\b", re.I)

IMAGE_EXTS = (".png", ".jpg", ".jpeg", ".webp", ".svg", ".gif")


def search_name(name: str, country: str | None = None, limit: int = 25):
    """Whole-database name search — the country listing is capped and misses stations."""
    params = {"name": name, "limit": limit,
              "order": "votes", "reverse": "true", "hidebroken": "true"}
    if country:
        params["countrycode"] = country
    query = urllib.parse.urlencode(params)
    try:
        time.sleep(0.2)   # be polite: this endpoint is shared infrastructure
        return http_json(f"{SEARCH}?{query}")
    except (urllib.error.URLError, TimeoutError, ValueError) as exc:
        print(f"  ! search {name}: {exc}", file=sys.stderr)
        return []


def looks_like_video(name: str) -> bool:
    return bool(VIDEO_NAME.search(name))


def logo_ok(url: str) -> bool:
    """Hotlinkable from our https page: https + a renderable image format."""
    if not url or not url.startswith("https://"):
        return False
    path = urllib.parse.urlparse(url).path.lower()
    if path.endswith(IMAGE_EXTS):
        return True
    if "." not in path.rsplit("/", 1)[-1]:
        return logo_is_image(url)   # extensionless image URLs (firebase, CDNs)
    return False


def logo_is_image(url: str) -> bool:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA}, method="HEAD")
        with urllib.request.urlopen(req, timeout=10) as res:
            return (res.headers.get("Content-Type") or "").lower().startswith("image/")
    except Exception:
        return False


def resolve_logo(name: str, url: str) -> str | None:
    """radio-browser favicon for this exact stream URL, or None. Name matches
    are never trusted — Capital UK must not get an Italian station's logo."""
    for row in search_name(name, None):
        if norm_key(row.get("url_resolved") or "") != norm_key(url):
            continue
        favicon = (row.get("favicon") or "").strip()
        if logo_ok(favicon):
            return favicon
        if favicon.startswith("http://"):
            upgraded = "https://" + favicon[len("http://"):]
            if logo_is_image(upgraded):
                return upgraded
    return None


def rank(station: dict) -> tuple:
    return (-(station.get("votes") or 0), -(station.get("bitrate") or 0))


def probe(url: str, timeout: int = 8) -> tuple[bool, str]:
    """Open the stream and read a few bytes. True if it looks like live audio."""
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Icy-MetaData": "0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            if res.status != 200:
                return False, f"HTTP {res.status}"
            ctype = (res.headers.get("Content-Type") or "").lower()
            res.read(2048)
            if not any(ctype.startswith(t) for t in AUDIO_TYPES):
                if ".m3u8" in url:
                    return True, ctype or "hls"
                return False, f"content-type {ctype or 'none'}"
            return True, ctype
    except Exception as exc:  # noqa: BLE001 - any failure means unusable
        return False, type(exc).__name__


def build(limit_uk: int, per_genre: int, do_probe: bool, workers: int,
          do_logos: bool = True):
    pinned = json.loads(PINNED.read_text())
    used_urls = {norm_key(s["url"]) for s in pinned}
    used_names = {name_key(s["name"]) for s in pinned}
    # group -> [(rank, station, entry)]; we over-collect then keep the best alive.
    proposals: dict[str, list[tuple]] = {group: [] for group in GROUP_ORDER}

    def consider(station: dict, group: str, country_name: str) -> bool:
        url = (station.get("url_resolved") or "").strip()
        name = clean_name(station.get("name"))
        if not url.startswith("https://") or not name:
            return False   # http:// is blocked as mixed content on the https site
        if (station.get("codec") or "").upper() not in PLAYABLE_CODECS:
            return False   # Safari can't do OGG/WebM/FLAC
        key, key_name = norm_key(url), name_key(name)
        if key in used_urls or key_name in used_names or looks_like_video(name):
            return False
        if len(name) > 40 or name.count(" - ") >= 3 or "@" in name:
            return False   # SEO soup: "TOP 100 CHARTS - DANCE - HOUSE - ... @ TikTok"
        used_urls.add(key)
        used_names.add(key_name)
        proposals[group].append((
            rank(station), station,
            {
                "name": name,
                "code": short_code(name),
                "description": describe(station, country_name, group),
                "url": url,
                "group": group,
                "country": station.get("countrycode") or "",
            },
        ))
        return True

    def resolve(name: str, group: str, country: str | None = None) -> bool:
        """Find a station by name (https first, exact before prefix) and keep it."""
        rows = search_name(name, country)
        rows.sort(key=lambda s: (not (s.get("url_resolved") or "").startswith("https"),
                                 rank(s)))
        pool = [s for s in rows if clean_name(s.get("name")).lower() == name.lower()]
        pool += [s for s in rows if clean_name(s.get("name")).lower().startswith(name.lower())]
        for station in pool:
            if consider(station, group, station.get("country") or ""):
                return True
        return False

    # Hand-picked UK/US stations, resolved by name search.
    for group, (wanted, cap) in SELECTED.items():
        code = "GB" if group == "UK" else "US"
        matched = set()
        for name in wanted:
            if len(proposals[group]) >= cap * 2:
                break
            if resolve(name, group, code):
                matched.add(name)
        missing = [name for name in wanted if name not in matched]
        note = f" | not found: {missing}" if missing else ""
        print(f"  {group:<5} {code}: {len(proposals[group])} candidates{note}")

    # Well-known stations first in each genre, then the tag sweeps below.
    for group, names in SEEDS.items():
        cap = GENRES[group][1]
        for name in names:
            if len(proposals[group]) >= cap * 2:
                break
            resolve(name, group)

    for group, (tags, cap, name_re) in GENRES.items():
        pattern = re.compile(name_re, re.I)
        networks: dict[str, int] = {}
        for tag in tags:
            if len(proposals[group]) >= cap * 2:
                break
            for station in sorted(fetch_tag(tag, 60), key=rank):
                if len(proposals[group]) >= cap * 2:
                    break
                if (station.get("votes") or 0) < 3:
                    continue
                name = clean_name(station.get("name"))
                if not pattern.search(name):
                    continue
                network = name.split(" - ")[0].strip().lower()[:12]
                if networks.get(network, 0) >= 2 and " - " in name:
                    continue   # at most two channels from the same brand per group
                if consider(station, group, station.get("country") or ""):
                    networks[network] = networks.get(network, 0) + 1
        print(f"  {group:<16} {len(proposals[group])} candidates")

    candidates = [entry for group in GROUP_ORDER for _, _, entry in proposals[group]]
    print(f"proposing {len(candidates)} new stations ({len(pinned)} pinned kept)")

    if do_probe:
        print(f"probing {len(candidates)} streams with {workers} workers...")
        started = time.time()
        with futures.ThreadPoolExecutor(max_workers=workers) as pool:
            verdicts = dict(zip(
                (entry["url"] for entry in candidates),
                pool.map(lambda entry: probe(entry["url"])[0], candidates),
            ))
        alive_count = sum(1 for ok in verdicts.values() if ok)
        print(f"probe done in {time.time() - started:.0f}s: "
              f"{alive_count} alive, {len(verdicts) - alive_count} dead")
    else:
        verdicts = {entry["url"]: True for entry in candidates}

    # keep the best *alive* picks per group, so dropping a dead one promotes the next
    caps = {"UK": limit_uk, "US": 8, **{group: min(cap, per_genre) for group, (_, cap, _re) in GENRES.items()}}
    kept = []
    for group in GROUP_ORDER:
        rows = [(r, e) for r, _, e in proposals[group] if verdicts.get(e["url"])]
        rows.sort(key=lambda pair: pair[0])
        kept.extend(entry for _, entry in rows[:caps[group]])

    if do_logos:
        targets = [(s["name"], s["url"], s) for s in pinned + kept]
        print(f"resolving logos for {len(targets)} stations...")
        found = 0
        for name, url, entry in targets:
            logo = resolve_logo(name, url)
            if logo:
                entry["logo"] = logo
                found += 1
        print(f"  logos: {found}/{len(targets)}")
    return pinned, kept


def write(pinned: list[dict], generated: list[dict]) -> list[dict]:
    order = {group: i for i, group in enumerate(GROUP_ORDER)}
    ranked = [(s, 0) for s in pinned] + [(s, 1) for s in generated]
    ranked.sort(key=lambda pair: (order.get(pair[0].get("group", "UK"), 99), pair[1]))
    all_stations = [station for station, _ in ranked]
    OUTPUT.write_text(json.dumps(all_stations, indent=2, ensure_ascii=False) + "\n")
    return all_stations


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit-uk", type=int, default=45)
    parser.add_argument("--per-genre", type=int, default=99,
                        help="cap per genre (default: the tuned cap in GENRES)")
    parser.add_argument("--no-probe", action="store_true")
    parser.add_argument("--no-logos", action="store_true")
    parser.add_argument("--workers", type=int, default=24)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    pinned, kept = build(args.limit_uk, args.per_genre, not args.no_probe, args.workers, not args.no_logos)
    groups: dict[str, int] = {}
    for station in pinned + kept:
        groups[station["group"]] = groups.get(station["group"], 0) + 1
    print("\n" + ", ".join(f"{g}: {n}" for g, n in groups.items()),
          f"= {len(pinned) + len(kept)} stations total")

    if args.dry_run:
        print("dry run — stations.json not written")
        return
    written = write(pinned, kept)
    print(f"wrote {len(written)} stations to {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
