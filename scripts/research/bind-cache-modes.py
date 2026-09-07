#!/usr/bin/env python3
"""Add authenticated POSIX modes to an existing Mozc cache manifest."""

import json
import os
import pathlib
import stat
import sys


if len(sys.argv) != 4:
    raise SystemExit("usage: bind-cache-modes.py CACHE INPUT_MANIFEST OUTPUT_MANIFEST")
root = pathlib.Path(sys.argv[1]).resolve()
source = pathlib.Path(sys.argv[2])
output = pathlib.Path(sys.argv[3])
document = json.loads(source.read_text())
for item in document["entries"]:
    path = root / item["path"]
    if item["type"] == "file":
        if not path.is_file() or path.is_symlink():
            raise SystemExit(f"missing regular cache file: {item['path']}")
        item["mode"] = format(stat.S_IMODE(path.stat().st_mode), "04o")
    elif item["type"] == "symlink":
        if not path.is_symlink() or os.readlink(path) != item["target"]:
            raise SystemExit(f"cache symlink mismatch: {item['path']}")
    else:
        raise SystemExit(f"unexpected cache entry type: {item['type']}")
document["schemaVersion"] = 3
document["modeBinding"] = "Every regular-file POSIX mode is bound as four-digit octal and verified before offline replay."
output.write_text(json.dumps(document, indent=2) + "\n")
