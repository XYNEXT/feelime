#!/usr/bin/env python3
"""Generate offline phrase initials from the pinned, already licensed Rime data.

Usage: generate-phrase-initials.py ARCHIVE (from fetch-native-engine-inputs.sh)
       generate-phrase-initials.py --check
"""
import hashlib
import json
import re
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE_SHA = '876c7ba559794f476abf7195a255aea29000cee281e6f5ec664928dce018bd90'
OUT = ROOT / 'app/src/main/assets/phrase-initials.tsv'
META = ROOT / 'third_party/phrase-initials.json'
CONVERSION = ROOT / 'app/src/main/assets/engine-data/rime/opencc/TSCharacters.txt'


def sha(data):
    return hashlib.sha256(data).hexdigest()


if sys.argv[1:] == ['--check']:
    meta = json.loads(META.read_text())
    assert sha(OUT.read_bytes()) == meta['outputSha256'], 'Phrase initials hash mismatch'
    assert sha(CONVERSION.read_bytes()) == meta['simplificationSha256'], 'Simplification data changed'
    print('Phrase initials match the recorded source derivation.')
    raise SystemExit(0)

if len(sys.argv) != 2:
    raise SystemExit(__doc__)
archive = Path(sys.argv[1])
assert sha(archive.read_bytes()) == ARCHIVE_SHA, 'Wrong source archive'
with tarfile.open(archive) as source:
    entry = next(f for f in source.getmembers() if f.name.endswith('/luna_pinyin.dict.yaml'))
    dictionary = source.extractfile(entry).read().decode('utf8')
weights = {}
initials = {}
for line in dictionary.splitlines():
    fields = line.split('\t')
    if len(fields) < 2 or len(fields[0]) != 1 or not re.fullmatch('[a-z]+', fields[1]):
        continue
    ch, spelling = fields[:2]
    if not ('\u3400' <= ch <= '\u9fff' or ord(ch) >= 0x20000):
        continue
    try:
        weight = float(fields[2].rstrip('%')) if len(fields) > 2 else 0
    except ValueError:
        weight = 0
    if ch not in weights or weight > weights[ch]:
        weights[ch], initials[ch] = weight, spelling[0]
# Traditional and simplified characters share the selected dictionary reading.
for line in CONVERSION.read_text().splitlines():
    fields = line.split('\t')
    if len(fields) == 2 and fields[0] in initials:
        for simplified in fields[1].split():
            if len(simplified) == 1 and simplified not in initials:
                initials[simplified] = initials[fields[0]]
data = ''.join(f'{ch}\t{initials[ch]}\n' for ch in sorted(initials)).encode()
OUT.write_bytes(data)
META.write_text(json.dumps({
    'purpose': 'Offline default shortcut for saved phrases; user can edit ambiguous readings.',
    'source': 'rime-luna-pinyin@56b934b099dfbeab842320f13aa8b461a6ab3e42',
    'archiveSha256': ARCHIVE_SHA,
    'simplificationSource': 'engine-data/rime/opencc/TSCharacters.txt (see third_party/manifest.json)',
    'simplificationSha256': sha(CONVERSION.read_bytes()),
    'license': 'third_party/licenses/rime-data/luna-pinyin-LGPL-3.0.txt',
    'generator': 'scripts/generate-phrase-initials.py',
    'output': 'app/src/main/assets/phrase-initials.tsv',
    'outputSha256': sha(data),
    'characters': len(initials),
}, indent=2) + '\n')
print(f'Generated {len(initials)} character initials, {len(data)} bytes.')
