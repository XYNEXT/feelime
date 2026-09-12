#!/usr/bin/env python3
"""Build the Chinese association (联想) bigram table for the keyboard.

Input: LCCC conversation corpus (jsonl.gz). Each line is a JSON array of
utterances; the text arrives PRE-SEGMENTED (space-separated words, standalone
punctuation tokens), so no tokenizer runs here — a punctuation token simply
breaks the word chain.

Output: engine-data/assoc/zh_bigram.tsv — PLAIN TSV lines
`prev<TAB>next<TAB>count` (prev groups ordered by prev frequency, followers
by count desc then word asc; fully deterministic). NOT gzipped: aapt2
decompresses and strips .gz assets at package time, so the runtime would
never find the file under its .gz name. The APK entry compresses anyway.

Usage:
  python3 scripts/build-association-data.py \
      --corpus ~/tmp/fv-assoc/lccc_base_train.jsonl.gz
"""
import argparse
import collections
import gzip
import json
import pathlib
import re
import sys

# 联想表只收这些长度的纯中文词（单字虚词与 2-4 字词是联想主体）。
WORD = re.compile(r"^[一-鿿]{1,4}$")
# 前词门槛：总后继次数少于它的前词不稳，直接丢。
MIN_PREV_TOTAL = 12
# 每个前词最多保留的后继数。
TOP_K = 8
# 后继单对最低次数。
MIN_PAIR_COUNT = 3


def word_chain(utterance):
    """CJK words of one utterance; punctuation/latin tokens break the chain."""
    chain = []
    for token in utterance.split():
        if WORD.match(token):
            chain.append(token)
        else:
            yield chain
            chain = []
    yield chain


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", required=True, help="LCCC jsonl.gz")
    parser.add_argument(
        "--out", default="app/src/main/assets/engine-data/assoc/zh_bigram.tsv")
    args = parser.parse_args()

    pairs = collections.Counter()
    prev_totals = collections.Counter()
    lines = 0
    with gzip.open(args.corpus, "rt", encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            lines += 1
            try:
                utterances = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(utterances, list):
                continue
            for utterance in utterances:
                if not isinstance(utterance, str):
                    continue
                for chain in word_chain(utterance):
                    for first, second in zip(chain, chain[1:]):
                        if first == second:
                            continue
                        pairs[(first, second)] += 1
                        prev_totals[first] += 1
            if lines % 500000 == 0:
                print(f"  {lines} lines, {len(pairs)} pair types", flush=True)

    kept = collections.defaultdict(list)
    for (prev, nxt), count in pairs.items():
        if count < MIN_PAIR_COUNT or prev_totals[prev] < MIN_PREV_TOTAL:
            continue
        kept[prev].append((nxt, count))
    rows = []
    for prev in sorted(kept, key=lambda w: (-prev_totals[w], w)):
        followers = sorted(kept[prev], key=lambda nc: (-nc[1], nc[0]))[:TOP_K]
        for nxt, count in followers:
            rows.append(f"{prev}\t{nxt}\t{count}")

    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    payload = ("\n".join(rows) + "\n").encode("utf-8")
    out.write_bytes(payload)

    import hashlib

    print(f"corpus lines: {lines}")
    print(f"prev words: {len(kept)}, rows: {len(rows)}")
    print(f"raw {len(payload)} bytes -> gz {out.stat().st_size} bytes")
    print(f"sha256 {hashlib.sha256(out.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    sys.exit(main())
