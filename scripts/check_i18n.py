#!/usr/bin/env python3
"""i18n parity + format checker.

For every res/values-XX locale, verifies that each strings_*.xml has exactly the same
<string> keys as the base res/values/ file, and that the set of printf-style format
specifiers (%1$s, %1$d, %1$.1f, %%, …) per key matches the base. Catches missing/extra
keys and broken format strings across all locales before they ship.

Usage: python3 scripts/check_i18n.py [--locales fr,de,it,…]
Exit 0 = OK, 1 = problems found.
"""
import os
import re
import sys
import glob

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
BASE = os.path.join(RES, "values")
STRING_RE = re.compile(r'<string\s+name="([^"]+)"\s*>(.*?)</string>', re.DOTALL)
# Positional/simple printf specifiers and literal %% — order-insensitive multiset per key.
FMT_RE = re.compile(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]|%%')


def parse(path):
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        text = f.read()
    return {name: body for name, body in STRING_RE.findall(text)}


def fmt_multiset(s):
    return sorted(FMT_RE.findall(s))


def main():
    locales = None
    for a in sys.argv[1:]:
        if a.startswith("--locales"):
            locales = a.split("=", 1)[1].split(",") if "=" in a else sys.argv[sys.argv.index(a) + 1].split(",")
    base_files = sorted(glob.glob(os.path.join(BASE, "strings_*.xml")))
    base = {}
    for bf in base_files:
        base[os.path.basename(bf)] = parse(bf) or {}

    if locales is None:
        locales = sorted(
            os.path.basename(d)[len("values-"):]
            for d in glob.glob(os.path.join(RES, "values-*"))
            if os.path.isdir(d)
        )

    problems = 0
    for loc in locales:
        ldir = os.path.join(RES, "values-" + loc)
        for fname, bstrings in base.items():
            lstrings = parse(os.path.join(ldir, fname))
            if lstrings is None:
                print(f"[{loc}] MISSING FILE {fname}")
                problems += 1
                continue
            missing = set(bstrings) - set(lstrings)
            extra = set(lstrings) - set(bstrings)
            for k in sorted(missing):
                print(f"[{loc}] {fname}: missing key '{k}'")
                problems += 1
            for k in sorted(extra):
                print(f"[{loc}] {fname}: extra key '{k}'")
                problems += 1
            for k in sorted(set(bstrings) & set(lstrings)):
                if fmt_multiset(bstrings[k]) != fmt_multiset(lstrings[k]):
                    print(f"[{loc}] {fname}: format mismatch in '{k}': "
                          f"base={fmt_multiset(bstrings[k])} vs {fmt_multiset(lstrings[k])}")
                    problems += 1

    if problems:
        print(f"\n{problems} problem(s) found.")
        return 1
    print(f"i18n OK · {len(locales)} locale(s) · {sum(len(v) for v in base.values())} keys each")
    return 0


if __name__ == "__main__":
    sys.exit(main())
