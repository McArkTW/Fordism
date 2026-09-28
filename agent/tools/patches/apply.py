"""Apply one exact-text replacement to an installed tool's source, or fail the image build.

usage: python3 apply.py <patch.json>
patch.json: {"file": "<path>", "old": "<text>", "new": "<text>", "why": "<one line>"}

The text must occur exactly once. A tool update that moves or rewrites it fails the build here, rather
than shipping an image where the fix silently no longer applies.
"""
import json
import sys

patch = json.load(open(sys.argv[1]))
path = patch["file"]
source = open(path, encoding="utf-8").read()
count = source.count(patch["old"])
if count != 1:
    sys.exit(f"patch {sys.argv[1]}: expected the target text once in {path}, found {count} — the tool changed; re-check the fix")
open(path, "w", encoding="utf-8").write(source.replace(patch["old"], patch["new"]))
print(f"patched {path}: {patch['why']}")
