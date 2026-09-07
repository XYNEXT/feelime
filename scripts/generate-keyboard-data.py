#!/usr/bin/env python3
"""Generate the displayed key map from the shipped Rime schema.

Use --check in verification; default writes the generated section in keyboard.js.
No dictionary download or native build is needed.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / 'app/src/main/assets/engine-data/rime/ziranma_double_pinyin.schema.yaml'
KEYBOARD = ROOT / 'app/src/main/assets/keyboard/keyboard.js'
FINALS = 'iu ua ia e uan er ue ve ing uai u i o uo un a ong iong iang uang en eng ang an ao ai ei ie iao ui v ou in ian'.split()


def spell(raw, rules):
    states = [raw]
    for rule in rules:
        parts = rule.split('/')
        kind, pattern = parts[:2]
        replacement = parts[2] if len(parts) > 3 else ''
        if kind == 'abbrev':
            continue
        if kind == 'xlit':
            states = [s.translate(str.maketrans(pattern, replacement)) for s in states]
            continue
        replacement = re.sub(r'\$(\d+)', r'\\g<\1>', replacement)
        if kind == 'erase':
            states = [s for s in states if not re.search(pattern, s)]
        elif kind == 'derive':
            states += [re.sub(pattern, replacement, s) for s in list(states) if re.search(pattern, s)]
        elif kind == 'xform':
            states = [re.sub(pattern, replacement, s) for s in states]
        else:
            raise ValueError('Unsupported Rime algebra rule: ' + kind)
        states = list(dict.fromkeys(states))
    return states


def generate():
    schema = SCHEMA.read_text()
    algebra = schema.split('speller:', 1)[1].split('  alphabet:', 1)[0]
    rules = re.findall(r'^\s+- "([^"]+)"', algebra, re.M)
    mapping = {key: [] for key in 'qwertyuiopasdfghjklzxcvbnm'}
    for final in FINALS:
        raw = final if final == 'er' else 'b' + final
        encodings = [s for s in spell(raw, rules) if len(s) == 2]
        if not encodings:
            raise ValueError('No full spelling for final ' + final)
        targets = {s[-1] for s in encodings}
        if len(targets) != 1:
            raise ValueError(f'Ambiguous final {final}: {targets}')
        mapping[targets.pop()].append(final)
    initials = {}
    for initial in ['zh', 'ch', 'sh']:
        encodings = spell(initial + 'a', rules)
        initials[encodings[0][0]] = initial
    rows = []
    for letters in ['qwertyuiop', 'asdfghjkl', 'zxcvbnm']:
        row = []
        for key in letters:
            finals = mapping[key]
            if key == 'v' and finals == ['ui', 'v']:
                finals = ['ui ü']
            if not finals or len(finals) > 2:
                raise ValueError(f'Unsupported display cell {key}: {finals}')
            row.append([key, finals[0], finals[1] if len(finals) > 1 else None, initials.get(key)])
        rows.append(row)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    rows = generate()
    digest = hashlib.sha256(SCHEMA.read_bytes()).hexdigest()
    generated = '    // BEGIN GENERATED SCHEMA_MAP\n    // schema-sha256: ' + digest + '\n'
    generated += '    const SCHEMA_MAP_ROWS = ' + json.dumps(rows, ensure_ascii=False, separators=(',', ':')) + ';\n'
    generated += '    // END GENERATED SCHEMA_MAP'
    source = KEYBOARD.read_text()
    pattern = r'    // BEGIN GENERATED SCHEMA_MAP[\s\S]*?    // END GENERATED SCHEMA_MAP'
    if not re.search(pattern, source):
        raise SystemExit('Generated section missing in keyboard.js')
    expected = re.sub(pattern, lambda _: generated, source)
    if args.check:
        if source != expected:
            raise SystemExit('Key map differs from schema. Run scripts/generate-keyboard-data.py')
        print('Displayed key map matches the shipped schema.')
    else:
        KEYBOARD.write_text(expected)


if __name__ == '__main__':
    main()
