#!/usr/bin/env python3
"""Device gates (natural-code keymap / native setup pages / candidate swipe).

#1 key map matches the engine (live syllable probes + R=er label),
#2 native setup two-level pages, #3 single-column mode menu, #4 expanded
candidates size to content (no ellipsis at 3+ chars), #5 alt "," optical
centering class, #6 favorites panel self-management (add/edit/delete),
#7 flick feedback is a direction blob, never a character preview."""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import device_verify as d

RESULTS = []


def record(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail else ""), flush=True)


def ev(expr):
    return d.devtools_eval(expr)


def tap_text(label, wait=1.0):
    """Tap a SetupActivity node by its exact text (menu rows carry titles)."""
    import re
    xml = d.ui_dump()
    for node in re.finditer(r"<node [^>]*/>", xml):
        blob = node.group(0)
        if f'text="{label}"' not in blob:
            continue
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', blob)
        if not bounds:
            continue
        g = [int(x) for x in bounds.groups()]
        d.tap((g[0] + g[2]) / 2, (g[1] + g[3]) / 2, wait)
        return True
    return False


def dump_texts():
    import re
    import html
    xml = d.ui_dump()
    found = set()
    for node in re.finditer(r"<node [^>]*/>", xml):
        m = re.search(r' text="([^"]*)"', node.group(0))
        if m and m.group(1):
            found.add(html.unescape(m.group(1)))
    return found


def has_text(texts, *labels):
    return any(label in text for text in texts for label in labels)


def has_none(texts, *labels):
    return not has_text(texts, *labels)


def main():
    d.prepare()
    kb = d.fresh_kb(refocus=True)
    if not kb:
        raise SystemExit('keyboard geometry unavailable')
    d.reset_shift(kb)
    d.devtools_click_mode("双拼")
    time.sleep(1.2)
    kb = d.fresh_kb(refocus=False) or kb

    # ---- #1 key map vs engine: probe syllables ON the natural-code map ----
    # Each entry: key sequence -> expected glyph(s) in the candidate bar.
    probes = [
        ("ud", "双霜"),   # sh+uang
        ("go", "国过果"),  # g+uo
        ("yn", "因音银引"),  # y+in
        ("hk", "好"),     # h+ao
        ("xm", "先现线"),   # x+ian
        ("or", "二儿而"),   # er via the R key (also er/or/… spellings)
        ("sr", "算酸"),    # s+uan
        ("xd", "想向"),    # x+iang
        ("aa", "啊"),     # zero-initial a = a+a (natural code)
        ("ai", "爱艾"),    # zero-initial ai = ai (natural code, user-reported)
        ("an", "安按"),    # zero-initial an = an
        ("en", "恩"),     # zero-initial en = en
    ]
    for keys, expected in probes:
        d.clear_field(kb)
        for ch in keys:
            d.press(kb, ch, 0.15)
        time.sleep(0.8)
        cands = d.devtools_candidates()
        ok = any(ch in c for c in cands for ch in expected)
        record(f"{keys} yields a map-consistent syllable", ok,
               f"cands={cands[:6]}")
    d.clear_field(kb)

    # ---- #1b the rendered key map labels R with er (was uan-only) ----
    ev("window.Feelime.toggleSettingsPanel && window.Feelime.toggleSettingsPanel()")
    time.sleep(0.5)
    ev("(() => { const row = [...document.querySelectorAll('#settingsPanel .set-row')]"
       ".find(r => ['双拼键位', 'Pinyin key map'].includes(r.querySelector('.set-label')?.textContent.trim()));"
       " row?.querySelector('.set-nav')?.click(); return 1; })()")
    time.sleep(0.5)
    # R's two finals stack as separate spans; both notes live in
    # .map-notes ABOVE the grid.
    r_label = ev("(() => { const cell = [...document.querySelectorAll('#schemaMap .kmap-key')]"
                 ".find(c => c.querySelector('b')?.textContent === 'R');"
                 " return cell ? [...cell.querySelectorAll('span')].map(s => s.textContent).join('/') : ''; })()") or ""
    hint = ev("[...document.querySelectorAll('#schemaMap .map-line')].map(e => e.textContent).join(' ')") or ""
    notes_first = ev("document.querySelector('#schemaMap').children[0]?.className") or ""
    record("key map labels R=uan+er, notes above the grid",
           "uan" in r_label and "er" in r_label
           and "爱=ai" in hint and "啊=aa" in hint and "先按" not in hint
           and notes_first == "map-notes",
           f"R={r_label!r} hint={hint!r} first={notes_first!r}")
    ev("window.Feelime.closeSettingsPanel && window.Feelime.closeSettingsPanel()")
    time.sleep(0.3)

    # ---- #5 zh-punct class drives BOTH the 。main and ，alt optical shift ----
    zh_class = ev("!!document.querySelector('[data-key=\".\"]').classList.contains('zh-punct')")
    record("punct key carries zh-punct (main+alt optical centering)",
           zh_class is True, f"zh-punct={zh_class}")

    # ---- #3 the mode menu is a single column ----
    ev("window.Feelime.toggleModeMenu && window.Feelime.toggleModeMenu()")
    time.sleep(0.4)
    cols = ev("getComputedStyle(document.getElementById('modeMenu'))"
              ".gridTemplateColumns.split(' ').length")
    items = ev("document.getElementById('modeMenu').children.length") or 0
    record("mode menu is single-column with all 6 modes",
           cols == 1 and items == 6, f"cols={cols} items={items}")
    ev("window.Feelime.closeModeMenu && window.Feelime.closeModeMenu()")
    time.sleep(0.2)

    # ---- #4a the collapsed candidate bar swipes horizontally ----
    d.clear_field(kb)
    for ch in "jisuanji":
        d.press(kb, ch, 0.12)
    time.sleep(0.8)
    # In-page synthetic TouchEvents are untrusted and can never drive the
    # browser's native pan (scrollLeft stays 0 no matter what), so the gate
    # asserts the STRUCTURAL conditions for a native pan instead: no touch
    # listener preventDefaults the strip's events, and the CSS actually
    # scrolls horizontally. Trusted-finger swiping is verified on a real device.
    bar = ev("(() => { const b = document.getElementById('candidates');"
             " const cs = getComputedStyle(b);"
             " const cell = b.querySelector('.candidate');"
             " const mk = kind => new TouchEvent(kind, {cancelable: true, bubbles: true,"
             "   touches: kind === 'touchend' ? [] : [new Touch({identifier: 3,"
             "     target: cell || b, clientX: b.getBoundingClientRect().left + 20,"
             "     clientY: b.getBoundingClientRect().top + 10})], changedTouches: []});"
             " const s = (cell || b).dispatchEvent(mk('touchstart'));"
             " const m = b.dispatchEvent(mk('touchmove'));"
             " return { panCss: cs.overflowX === 'auto' && !/none/.test(cs.touchAction),"
             "          startKept: !s.defaultPrevented, moveKept: !m.defaultPrevented,"
             "          room: b.scrollWidth - b.clientWidth }; })()") or {}
    ev("document.activeElement && document.activeElement.blur && document.activeElement.blur(); 1")
    ok4a = (bar.get("panCss") is True and bar.get("startKept") is True
            and bar.get("moveKept") is True)
    record("candidate bar keeps native pan (no touch hijack)", ok4a,
           f"bar={bar}")
    d.clear_field(kb)

    # ---- #4 expanded candidates size to content (3-char word readable) ----
    d.devtools_click_mode("全拼 Pinyin")
    time.sleep(1.0)
    kb = d.fresh_kb(refocus=False) or kb
    d.clear_field(kb)
    for ch in "jisuanji":
        d.press(kb, ch, 0.12)
    time.sleep(0.8)
    ev("document.getElementById('composeExpand')?.click()")
    time.sleep(0.8)
    grid = ev("(() => { const g = document.getElementById('expandGrid');"
              " const cs = getComputedStyle(g);"
              " const rows = [...g.querySelectorAll('.expand-candidate')].map(el => {"
              "  const r = el.getBoundingClientRect();"
              "  return { t: el.textContent, w: Math.round(r.width),"
              "           clipped: el.scrollWidth > el.clientWidth + 1 }; });"
              " return { wrap: cs.flexWrap, rows }; })()") or {}
    rows = grid.get("rows") or []
    three = next((r for r in rows if len(r.get("t", "")) >= 3), None)
    ok4 = (grid.get("wrap") == "wrap" and rows and
           all(not r["clipped"] for r in rows) and
           (three is None or three["w"] >= 3 * 14))
    record("expanded grid wraps and never ellipsises candidates", ok4,
           f"wrap={grid.get('wrap')} n={len(rows)} three={three}")
    ev("document.getElementById('expandCollapse')?.click()")
    time.sleep(0.3)
    d.clear_field(kb)

    # ---- #6 moved to device_candidate_pool_verify.py: the phrase editor strip and
    # row action menu replaced the in-panel management UI ----
    # ---- #7 flick feedback is a direction blob, not a character preview ----
    # English mode: the up-flick alt must land half-width ('3'); Chinese-mode
    # flicks are full-width since .
    d.devtools_click_mode("英文 Direct")
    time.sleep(1.0)
    kb = d.fresh_kb(refocus=False) or kb
    d.clear_field(kb)
    ev("(() => { window.__blobRuns = 0; const b = document.getElementById('flickBlob');"
       " const orig = b.animate.bind(b);"
       " b.animate = (...a) => { window.__blobRuns++; return orig(...a); }; return 1; })()")
    ex, ey = kb['e']
    pts = []
    for i in range(14):
        pts.append([round((ex - d._DT_OFFSET[0]) / d._DT_SCALE),
                    round((ey - 160 * i / 13 - d._DT_OFFSET[1]) / d._DT_SCALE)])
    d.synth_gesture(pts, step_ms=12)
    time.sleep(0.4)
    runs = ev("window.__blobRuns || 0") or 0
    blob_text = ev("document.getElementById('flickBlob').textContent") or ""
    text = d.field_text_retry()
    record("flick animates the blob and never previews a character",
           runs >= 1 and blob_text == "" and text == "3",
           f"runs={runs} blob={blob_text!r} field={text!r}")
    d.clear_field(kb)

    # ---- #2 setup surface: two-level pages (design §6.2) ----
    # Sections render per page now; display:none drops the hidden groups
    # from the a11y tree, so each page is dumped AFTER switching to it via
    # the page's own DevTools target (same channel the earlier check). 剪贴板/常用语
    # 管理只在键盘面板（R5）；常用语添加仍在键盘编辑卡（A-1）。
    ev("document.getElementById('hide').click()")
    time.sleep(0.8)
    d.shell("am start -n com.feelime.ime/.SetupActivity --ez com.feelime.ime.extra.SHOW_DEBUG_FIXTURES true")
    time.sleep(1.8)
    sev = lambda expr: d.devtools_eval_target("settings/index.html", expr)
    # Cold WebView: poll for the page instead of trusting the fixed sleep.
    for _ in range(8):
        if sev("!!window.FeelimeSettings && !!window.FeelimeSettings.showPage"):
            break
        time.sleep(1.0)

    def page_texts(name):
        sev(f"window.FeelimeSettings.showPage('{name}')")
        time.sleep(0.8)
        return dump_texts()

    # Home: hero + IME status + group entries - nothing deeper.
    texts = page_texts("home")
    entries = (
        ("输入法状态", "Input method status"),
        ("键盘与输入", "Keyboard & input"),
        ("语音识别", "Voice recognition"),
        ("键盘热更新", "Keyboard updates"),
        ("关于", "About"),
        ("输入测试", "Input test"),
    )
    deep = (
        ("当前方案：自然码", "Current scheme: Ziranma"),
        ("插入模板", "Insert template"),
        ("更新源地址", "Update source (metainfo.json)"),
        ("第三方许可与组件说明", "Third-party licenses & components"),
        ("去掉句尾句号", "Remove final periods"),
        ("断点续传", "resume and per-file verification"),
    )
    menu_ok = all(has_text(texts, *labels) for labels in entries)
    home_only = all(has_none(texts, *labels) for labels in deep)
    fav_gone = has_none(texts, "添加常用语", "Add phrase")
    record("settings home shows hero + IME status + entries only",
           menu_ok and home_only and fav_gone,
           f"missing={[labels for labels in entries if not has_text(texts, *labels)]}"
           f" leaked={[labels for labels in deep if has_text(texts, *labels)]}"
           f" fav_gone={fav_gone}")

    texts = page_texts("input")
    record("键盘与输入 page groups 双拼 + 定制 (自然码 copy)",
           has_text(texts, "双拼方案", "Double-pinyin scheme")
           and has_text(texts, "当前方案：自然码", "Current scheme: Ziranma")
           and has_text(texts, "插入模板", "Insert template")
           and has_none(texts, "离线中英混合语音输入法", "Offline Chinese-English voice input"),
           f"hit={[t[:24] for t in texts if '自然码' in t or 'Ziranma' in t][:1]}")

    texts = page_texts("voice")
    record("语音识别 page merges voice models + asr settings",
           has_text(texts, "语音模型", "voice models")
           and has_text(texts, "断点续传", "resume and per-file verification")
           and has_text(texts, "去掉句尾句号", "Remove final periods")
           and has_text(texts, "保存识别设置", "Save recognition options"),
           "sample=" + str(sorted(t[:18] for t in texts
                                   if "模型" in t or "model" in t.casefold())[:3]))

    texts = page_texts("update")
    record("键盘热更新 page exposes source / check / restore",
           has_text(texts, "更新源地址", "Update source (metainfo.json)")
           and has_text(texts, "检查更新", "Check for updates")
           and has_text(texts, "恢复内置", "Restore built-in"),
           "sample=" + str(sorted(t[:18] for t in texts
                                  if "更新" in t or "内置" in t
                                  or "update" in t.casefold()
                                  or "built-in" in t.casefold())[:4]))

    texts = page_texts("about")
    record("关于 page shows version info + copy + notices",
           has_text(texts, "版本信息", "Version information")
           and has_text(texts, "复制版本信息", "Copy version info")
           and has_text(texts, "第三方许可与组件说明", "Third-party licenses & components"),
           "sample=" + str(sorted(t[:18] for t in texts
                                  if "版本" in t or "复制" in t
                                  or "version" in t.casefold()
                                  or "copy" in t.casefold())[:4]))

    failed = [name for name, ok, _ in RESULTS if not ok]
    print(f"\n== keymap device suite: "
          f"{len(RESULTS) - len(failed)} passed, {len(failed)} failed ==")
    if failed:
        print("failures: " + " | ".join(failed))
        sys.exit(1)


if __name__ == "__main__":
    main()
