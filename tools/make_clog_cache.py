#!/usr/bin/env python3
"""
make_clog_cache.py - builds the cache files for the in-game Collection Log list from collection_log.json.

WHY: the server only needs collection_log.json to record items, but the in-game list (left side) and the item
grid (right side) are drawn by the client from cache enums/structs. This script writes those, so the log shows
exactly what is in collection_log.json and nothing else.

USAGE (run from the repository root, with Python 3):
    python tools/make_clog_cache.py
    python tools/make_clog_cache.py --json kronos-server/data/collection_log.json --out raw-cache/files

It writes raw files in the layout kronos-cachetools packs:
    raw-cache/files/2/8/<id>.dat    enums  (2103-2107 = the five tab lists, 2639+ = item lists)
    raw-cache/files/2/34/<id>.dat   structs (719+ = one per entry: its name and its item list)
Then pack them:  gradlew :kronos-cachetools:packCache -PcacheArgs="--dry-run"   (then again without --dry-run)

Re-run it and re-pack every time you add or change an entry or an item in collection_log.json.
Entry i of the whole file (Bosses first, then Raids, Clues, Minigames, Other) uses struct 719+i and item
enum 2639+i, so ids stay stable as long as you only add entries at the END of the file's order.
Nothing is ever deleted. If you remove an entry, its old files are simply left unused.
"""
import argparse, json, os, struct, sys

TABS = [("Bosses", 2103), ("Raids", 2104), ("Clues", 2105), ("Minigames", 2106), ("Other", 2107)]
STRUCT_BASE = 719    # first free struct id in the rev-184 cache (cache has 0..718)
ENUM_BASE = 2639     # first free enum id in the rev-184 cache (cache has 0..2638)
PARAM_NAME, PARAM_ITEMS_ENUM = 689, 690   # struct params the collection log scripts read


def load_json(path):
    # Same rule as the server loader: lines starting with # are comments.
    with open(path, encoding="utf-8") as f:
        text = "".join(l for l in f if not l.lstrip().startswith("#"))
    return json.loads(text)


def enum_bytes(value_type, pairs):
    # byte layout copied from the cache's own collection log enums: key type 'i', value type, entries, default -1
    out = bytearray([1, ord("i"), 2, ord(value_type), 6])
    out += struct.pack(">H", len(pairs))
    for k, v in pairs:
        out += struct.pack(">ii", k, v)
    out += bytes([4]) + struct.pack(">i", -1) + b"\x00"
    return bytes(out)


def struct_bytes(name, items_enum):
    raw = name.encode("cp1252")
    out = bytearray([249, 2])
    out += b"\x01" + PARAM_NAME.to_bytes(3, "big") + raw + b"\x00"
    out += b"\x00" + PARAM_ITEMS_ENUM.to_bytes(3, "big") + struct.pack(">i", items_enum)
    out += b"\x00"
    return bytes(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default="kronos-server/data/collection_log.json")
    ap.add_argument("--out", default="raw-cache/files")
    a = ap.parse_args()

    data = load_json(a.json)
    unknown = [k for k in data if k not in dict(TABS)]
    if unknown:
        sys.exit("Unknown categories in the JSON: %s (expected %s)" % (unknown, [t for t, _ in TABS]))

    enum_dir = os.path.join(a.out, "2", "8")
    struct_dir = os.path.join(a.out, "2", "34")
    os.makedirs(enum_dir, exist_ok=True)
    os.makedirs(struct_dir, exist_ok=True)

    def write(d, fid, blob):
        with open(os.path.join(d, "%d.dat" % fid), "wb") as f:
            f.write(blob)

    n = 0
    for tab, tab_enum in TABS:
        entries = data.get(tab, [])
        pairs = []
        for slot, entry in enumerate(entries):
            name = entry["name"]
            item_ids = [int(i["itemId"]) for i in entry.get("items", [])]
            sid, eid = STRUCT_BASE + n, ENUM_BASE + n
            write(struct_dir, sid, struct_bytes(name, eid))
            write(enum_dir, eid, enum_bytes("O", list(enumerate(item_ids))))
            pairs.append((slot, sid))
            print("%-9s slot %-2d  %-28s struct %d  items-enum %d  (%d items)" % (tab, slot, name, sid, eid, len(item_ids)))
            n += 1
        write(enum_dir, tab_enum, enum_bytes("J", pairs))
        if not entries:
            print("%-9s (empty tab)" % tab)
    print("done: %d entries -> %s" % (n, os.path.abspath(a.out)))


if __name__ == "__main__":
    main()
