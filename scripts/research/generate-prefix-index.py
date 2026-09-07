#!/usr/bin/env python3
"""Create Feelime's deterministic, read-only Hunspell prefix index."""

import pathlib
import sys


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: generate-prefix-index.py INPUT.dic OUTPUT.prefix.txt")
    source = pathlib.Path(sys.argv[1])
    output = pathlib.Path(sys.argv[2])
    lines = source.read_text(encoding="utf-8-sig").splitlines()
    if not lines or not lines[0].split()[0].isdigit():
        raise SystemExit(f"invalid Hunspell dictionary header: {source}")
    # Sort by (lowercase, natural): the runtime loader validates and binary-
    # searches with Kotlin's Unicode lowercase (not casefold).  The two orders
    # differ for MICRO SIGN U+00B5 vs GREEK SMALL MU U+03BC, so casefold here
    # would ship an index the device rejects as unsorted.
    words = sorted({line.split("/", 1)[0] for line in lines[1:] if line}, key=lambda s: (s.lower(), s))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("FEELIME_PREFIX_V1\n" + "\n".join(words) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
