#!/usr/bin/env python3
"""Generate the displayed key map and the variant table for every shipped
double-pinyin scheme (ziranma / flypy / sogou).

- Key map: derived from each schema's speller algebra (same rules the deployer
  compiles into the prism), so the rendered chart cannot drift from the engine.
- Variant table: parsed from the shipped prism.txt spelling set, the exact
  source scripts/verify/guard_dp_finals.js checks against.

Use --check in verification; default writes the generated section in keyboard.js.
No dictionary download or native build is needed.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = {
    'ziranma': 'ziranma_double_pinyin',
    'flypy': 'double_pinyin_flypy',
    'sogou': 'double_pinyin_sogou',
    'ziguang': 'double_pinyin_ziguang',
}
RIME_DIR = ROOT / 'app/src/main/assets/engine-data/rime'
KEYBOARD = ROOT / 'app/src/main/assets/keyboard/keyboard.js'
# The key map chart lives in the settings app (APK asset - the hot-updatable
# keyboard package whitelist only serves index/keyboard.js/css/VERSION).
SETTINGS_DATA = ROOT / 'app/src/main/assets/settings/dp-data.js'
FINALS = 'iu ua ia e uan er ue ve ing uai u i o uo un a ong iong iang uang en eng ang an ao ai ei ie iao ui v ou in ian'.split()
# ';' rides the WIDE key (shift slot, left of Z on the keyboard) and carries
# a final only in sogou - first cell of the last row keeps the chart honest
# about where the key actually is.
KEY_ROWS = ['qwertyuiop', 'asdfghjkl', ';zxcvbnm']


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


def load_rules(schema_path):
    """Quoted rules under speller:algebra, regardless of key order inside the
    speller block (sogou puts alphabet before algebra)."""
    lines = Path(schema_path).read_text().splitlines()
    start = next(i for i, l in enumerate(lines) if l.startswith('speller:'))
    block = []
    for line in lines[start + 1:]:
        if line and not line[0].isspace():
            break
        block.append(line)
    text = '\n'.join(block)
    algebra = text.split('algebra:', 1)[1]
    return re.findall(r'- "([^"]+)"', algebra)


def keymap_rows(rules):
    letters = ''.join(KEY_ROWS)
    mapping = {key: [] for key in letters}
    for final in FINALS:
        raw = final if final == 'er' else 'b' + final
        encodings = [s for s in spell(raw, rules) if len(s) == 2]
        if not encodings:
            raise ValueError('No full spelling for final ' + final)
        targets = {s[-1] for s in encodings}
        if len(targets) != 1:
            raise ValueError(f'Ambiguous final {final}: {targets}')
        target = targets.pop()
        if target not in mapping:
            continue
        mapping[target].append(final)
    initials = {}
    for initial in ['zh', 'ch', 'sh']:
        encodings = spell(initial + 'a', rules)
        initials[encodings[0][0]] = initial
    rows = []
    for keys in KEY_ROWS:
        row = []
        for key in keys:
            finals = mapping[key]
            display = ['ü' if f == 'v' else f for f in finals]
            if key == 'v' and finals == ['ui', 'v']:
                display = ['ui ü']
            # ziguang's N carries ue/ve/ui (ue and ve are spell variants of
            # üe) - fold the trio into one honest cell, same as the v case.
            if key == 'n' and sorted(finals) == ['ue', 'ui', 've']:
                display = ['ui üe']
                finals = ['ui üe']
            if not finals:
                continue  # ';' carries a final only in sogou; row length varies.
            if len(finals) > 2:
                raise ValueError(f'Unsupported display cell {key}: {finals}')
            row.append([key, display[0], display[1] if len(display) > 1 else None,
                        initials.get(key)])
        rows.append(row)
    return rows


def variant_table(prism_id):
    """first key -> sorted second keys, over every 2-key spelling in the
    shipped prism (same derivation as guard_dp_finals.js)."""
    table = {}
    for line in (RIME_DIR / f'{prism_id}.prism.txt').read_text().splitlines():
        spelling = line.split('\t')[0]
        if len(spelling) == 2:
            table.setdefault(spelling[0], set()).add(spelling[1])
    return {k: ''.join(sorted(v)) for k, v in sorted(table.items())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    maps = {}
    tables = {}
    digests = []
    for scheme, prism_id in SCHEMAS.items():
        rules = load_rules(RIME_DIR / f'{prism_id}.schema.yaml')
        maps[scheme] = keymap_rows(rules)
        tables[scheme] = variant_table(prism_id)
        digests.append(f'{scheme}={hashlib.sha256((RIME_DIR / (prism_id + ".schema.yaml")).read_bytes()).hexdigest()[:16]}')
    generated = ('    // BEGIN GENERATED SCHEMA_MAP\n    // schema-sha256: '
                 + ' '.join(digests) + '\n')
    generated += ('    const DP_INITIAL_FINALS = '
                  + json.dumps(tables, ensure_ascii=False, separators=(',', ':')) + ';\n')
    generated += '    // END GENERATED SCHEMA_MAP'
    settings_data = (
        '// Generated by scripts/generate-keyboard-data.py from the shipped\n'
        '// double-pinyin schemas (schema-sha256: ' + ' '.join(digests) + ').\n'
        '// Displayed key map per scheme; consumed by the settings page only.\n'
        'window.FeelimeDp = '
        + json.dumps({'schemes': list(SCHEMAS), 'maps': maps},
                     ensure_ascii=False, separators=(',', ':')) + ';\n')
    source = KEYBOARD.read_text()
    pattern = r'    // BEGIN GENERATED SCHEMA_MAP[\s\S]*?    // END GENERATED SCHEMA_MAP'
    if not re.search(pattern, source):
        raise SystemExit('Generated section missing in keyboard.js')
    expected = re.sub(pattern, lambda _: generated, source)
    if args.check:
        bad = source != expected
        # A missing file must fail the gate too (a dropped or never-committed
        # dp-data.js would otherwise blank the settings key map silently).
        if not SETTINGS_DATA.exists() or SETTINGS_DATA.read_text() != settings_data:
            bad = True
        if bad:
            raise SystemExit('Key map differs from schema. Run scripts/generate-keyboard-data.py')
        print('Displayed key map matches the shipped schemas.')
    else:
        KEYBOARD.write_text(expected)
        SETTINGS_DATA.write_text(settings_data)


if __name__ == '__main__':
    main()
