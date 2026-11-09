#!/usr/bin/env python3
"""Every place the app behaves differently in a debug build must be approved in scripts/debug-gates.txt.

Why (2026-11-09): group wallets were switched on only in debug builds while the server lacked them, and stayed that way;
the first Play release hid a real feature. A user-facing feature must never depend on the build type. A new debug-only
check fails CI until someone adds it to the approved list with the reason it is a debug tool, not a feature.

Usage: python3 scripts/check_debug_gates.py  (exit 1 on an unapproved or stale entry)
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APPROVED = ROOT / "scripts" / "debug-gates.txt"
PATTERN = r"debugBuild|BuildConfig\.DEBUG|isDebuggable|FLAG_DEBUGGABLE|isDebugBinary"
PATHS = ["shared/src", "androidApp/src/main", "circlewallet/src/main", "iosApp"]


def found():
    out = subprocess.run(
        ["git", "grep", "-n", "-E", PATTERN, "--", *PATHS],
        cwd=ROOT, capture_output=True, text=True,
    ).stdout
    hits = set()
    for line in out.splitlines():
        path, _, rest = line.split(":", 2)
        if "/src/" in path and ("Test/" in path or "/test/" in path):
            continue  # tests may build flags however they like
        code = rest.strip()
        if code.startswith(("//", "*", "/*")):
            continue
        hits.add((path, code))
    return hits


def approved():
    entries = {}
    for n, raw in enumerate(APPROVED.read_text().splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        parts = [p.strip() for p in raw.split(" | ")]
        if len(parts) != 3 or not parts[2] or parts[2] in ("None", "<why it's a debug tool>"):
            sys.exit(f"{APPROVED.name}:{n}: expected 'path | code | why it is a debug tool, not a feature'")
        entries[(parts[0], parts[1])] = parts[2]
    return entries


def main():
    hits, ok = found(), approved()
    new = sorted(hits - ok.keys())
    gone = sorted(ok.keys() - hits)
    for path, code in new:
        print(f"::error file={path}::Debug-only behaviour not approved: {code}")
        print(f"  If this is a debug tool, add to scripts/debug-gates.txt:\n  {path} | {code} | <why it's a debug tool>")
        print("  If it gates something users should see, it must not depend on the build type.\n")
    for path, code in gone:
        print(f"::error::Stale entry in scripts/debug-gates.txt (line no longer in the code): {path} | {code}")
    if new or gone:
        sys.exit(1)
    print(f"{len(hits)} debug-only checks, all approved.")


if __name__ == "__main__":
    main()
