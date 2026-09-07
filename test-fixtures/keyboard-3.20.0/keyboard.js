/*
 * Felime HTML keyboard: config-rendered rows, flick characters, long-press
 * popup, symbol recents. Native access goes through the FelimeNative bridge
 * (token-gated, design §5.3) with engine candidates, input modes and the
 * recording-only voice overlay.
 */
(() => {
    'use strict';

    const KEYBOARD_VERSION = '3.20.0';
    const MIN_NATIVE_API = 1;
    const REQUIRED_CAPABILITIES = [
        'candidate-revision-v1',
        'clipboard-v1',
        'commit-text-v1',
        'compose-control-v1',
        'cursor-repeat-v1',
        'favorites-v1',
        'ime-control-v1',
        'key-event-v1',
        'keyboard-update-status-v1',
        'text-input-v1',
        'voice-session-v1',
    ];
    // Over-long clipboard items cannot pass the native commitText limit, so
    // they render disabled in the panel (stored in full, paste blocked).
    const MAX_COMMIT_CODE_POINTS = 2000;
    // Batch 6 popup drag-away cancellation: the pick stays live
    // while the finger is within POPUP_CELL_REACH of some popup cell; once it
    // leaves every cell the layer shrinks/fades and the release commits
    // nothing, fully invisible past POPUP_GONE_RADIUS. Distance is measured to
    // the nearest cell, never to the touch origin: an edge-clamped popup
    // (right-column keys) puts legal cells 100px+ away from the pressed key.
    const POPUP_CELL_REACH = 60;
    const POPUP_GONE_RADIUS = 170;
    // Batch 6 cursor scrub: one caret step per 12px of drag at the default
    // 3x speed (SCRUB_UNIT_BASE_PX / scrubSpeed), counted from the touch
    // origin. Batch 9 exposes 1x..5x in the quick settings panel.
    const SCRUB_UNIT_BASE_PX = 36;

    const MODES = {
        'direct': { label: 'En', title: '英文 Direct', layout: 'qwerty', engine: false },
        'pinyin': { label: '拼', title: '全拼 Pinyin', layout: 'qwerty', engine: true },
        // 键位是自然码（ei→Z / ie→X / iao→C / ou→B 是自然码特征）；旧文件名
        // felime_sogou_double_pinyin 是历史误名，显示一律用「双拼」。
        'double-pinyin': { label: '双', title: '双拼', layout: 'qwerty', engine: true },
        'french': { label: 'FR', title: 'Français', layout: 'qwerty-fr', engine: true },
        'russian': { label: 'РУ', title: 'Русский', layout: 'cyrillic', engine: true },
        'japanese': { label: '日', title: '日本語 Romaji', layout: 'qwerty', engine: true },
    };

    const LAYOUTS = {
        qwerty: {
            rows: [
                'qwertyuiop',
                { keys: 'asdfghjkl', indent: true },
                { keys: 'zxcvbnm', shift: true, backspace: true },
            ],
            alts: {
                q: '1', w: '2', e: '3', r: '4', t: '5',
                y: '6', u: '7', i: '8', o: '9', p: '0',
                // 批次 21 #6: j/k carried fullwidth “ ” on the ENGLISH
                // keyboard - the half-width ~ " ' are what English expects
                // (French keeps its own accented set in qwerty-fr).
                a: '-', s: '/', d: ':', f: ';', g: '(', h: ')', j: '~', k: '"', l: "'",
                z: '@', x: '_', c: '#', v: '&', b: '?', n: '!', m: '…', '.': ',',
            },
        },
        // R-104: French uses standard QWERTY (no AZERTY); accent candidates
        // follow the design section 6.2 fixture exactly.
        'qwerty-fr': {
            rows: [
                'qwertyuiop',
                { keys: 'asdfghjkl', indent: true },
                { keys: 'zxcvbnm', shift: true, backspace: true },
            ],
            alts: {
                q: '1', w: '2', e: ['3', 'é', 'è', 'ê', 'ë'], r: '4', t: '5',
                y: ['6', 'ÿ'], u: ['7', 'ù', 'û', 'ü'], i: ['8', 'î', 'ï'], o: ['9', 'ô', 'œ'], p: '0',
                a: ['-', 'à', 'â', 'æ'], s: '/', d: ':', f: ';', g: '(', h: ')',
                j: ['~', '«'], k: ["'", '»'], l: ['"', '’'],
                z: '@', x: '_', c: ['#', 'ç'], v: '&', b: '?', n: '!', m: '.', '.': ',',
            },
        },
        cyrillic: {
            rows: [
                'йцукенгшщзхъ',
                { keys: 'фывапролджэ', indent: true },
                { keys: 'ячсмитьбю', shift: true, backspace: true },
            ],
            alts: {
                е: 'ё',
                а: '1', н: '2', р: '3', о: '4', л: '5',
                д: '6', ж: '7', э: '8', я: '9', ч: '0',
            },
        },
    };

    // G2-B03: fixed 24x24 vector icons; state changes toggle classes/colours
    // and never swap glyphs. Referenced by name from renderLetters().
    // M4: the enter key is text (换行/确定) and the globe key is replaced by
    // the Chinese/English toggle, so their glyphs were removed.
    const ICON_PATHS = {
        shift: 'M12 5l7 7h-4v6H9v-6H5z',
        caps: 'M12 3l7 7h-4v6H9v-6H5zM7 19h10v2H7z',
        backspace: 'M22 3H7c-.69 0-1.23.35-1.59.88L0 12l5.41 8.11c.36.53.9.89 1.59.89h15c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-4.59 12.59L16 17l-2.5-2.5L11 17l-1.41-1.41L12.09 13 9.59 10.5 11 9.1l2.5 2.5L16 9.1l1.41 1.41L14.91 13z',
        mic: 'M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3z M19 12a7 7 0 0 1-14 0H3a9 9 0 0 0 8 8.94V23h2v-2.06A9 9 0 0 0 21 12h-2z',
    };
    const SVG_NS = 'http://www.w3.org/2000/svg';
    const ICONS = {};
    for (const [name, pathData] of Object.entries(ICON_PATHS)) {
        const svg = document.createElementNS(SVG_NS, 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('width', '22');
        svg.setAttribute('height', '22');
        svg.setAttribute('fill', 'currentColor');
        svg.setAttribute('aria-hidden', 'true');
        const path = document.createElementNS(SVG_NS, 'path');
        path.setAttribute('d', pathData);
        svg.append(path);
        ICONS[name] = svg;
    }

    // M4: symbol rows follow the reference IME captures (English tab shows
    // the latin set with a digit first row, Chinese tab the CJK punct set).
    // Symbol categories (batch 5) live in SYMBOL_CATEGORIES below.
    // Batch 5: WeChat-style symbol categories picked from a bottom strip.
    // Every category renders as a 3x10 grid; the ninth/last cell of row 3 is
    // always the backspace key, and short lists pad with blank spacers so row
    // heights stay even (fixes the old two-row stretched "recent" layout).
    // Batch 10 (WeChat reference): the symbol layer opens on 常用 - ASCII
    // digits on row 1 (batch 14 #1 keeps digits half-width everywhere) and
    // the daily CJK symbols on rows 2-3 in Chinese modes. Row 3 ends with
    // the delete key and row 4 carries ABC | sliding categories | enter.
    const ZH_COMMON_ROWS = [
        ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'],
        ['，', '。', '、', '；', '：', '？', '！', '～', '（', '）'],
        ['“', '”', '‘', '’', '《', '》', '〈', '〉', '…'],
    ];
    const EN_COMMON_ROWS = [
        ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'],
        ['-', '/', ':', ';', '(', ')', '&', '@', '+', '='],
        ['.', ',', '?', '!', '"', "'", '*', '#', '%'],
    ];
    const SYMBOL_CATEGORIES = [
        { id: 'common', label: '常用', rows: null }, // filled per keyboard mode
        // Batch 15 (#7): the user's own table, between 常用 and 最近; hidden
        // from the strip until it has content (renderSymbolCats filters).
        { id: 'custom', label: '定制', rows: null },
        { id: 'recent', label: '最近', rows: null }, // filled from history, falls back to 常用
        {
            id: 'quote', label: '引号',
            rows: [
                ['“', '”', '‘', '’', '„', '‟', '«', '»', '‹', '›'],
                ['「', '」', '『', '』', '【', '】', '〖', '〗', '〔', '〕'],
                ['《', '》', '〈', '〉', '［', '］', '｛', '｝', '＃'],
            ],
        },
        {
            id: 'money', label: '货币',
            rows: [
                ['$', '€', '£', '¥', '₩', '₽', '₹', '₫', '฿', '¢'],
                ['¤', '₴', '₦', '₲', '₱', '﷼', '₪', '₭', '₮', '₯'],
                ['％', '＄', '＆'],
            ],
        },
        {
            id: 'math', label: '数学',
            rows: [
                ['±', '×', '÷', '≠', '≈', '≤', '≥', '∞', '√', '°'],
                ['∑', '∫', '∏', '∈', '∉', '⊂', '⊃', '∪', '∩', '∅'],
                ['′', '″', '‰', '⊕', '⊗', '⊙', '∵', '∴', '⊥'],
            ],
        },
        {
            id: 'num', label: '序号',
            rows: [
                ['①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧', '⑨', '⑩'],
                ['⑪', '⑫', '⑬', '⑭', '⑮', '⑯', '⑰', '⑱', '⑲', '⑳'],
                ['⒈', '⒉', '⒊', '⒋', '⒌', '⒍', '⒎', '⒏', '⒐'],
            ],
        },
        {
            id: 'pinyin', label: '拼音',
            rows: [
                ['ā', 'á', 'ǎ', 'à', 'ō', 'ó', 'ǒ', 'ò', 'ē', 'é'],
                ['ě', 'è', 'ī', 'í', 'ǐ', 'ì', 'ū', 'ú', 'ǔ', 'ù'],
                ['ǖ', 'ǘ', 'ǚ', 'ǜ', 'ü', 'ê', 'Ā', 'Á', 'Ǎ'],
            ],
        },
        {
            id: 'hira', label: '平假名',
            rows: [
                ['あ', 'い', 'う', 'え', 'お', 'か', 'き', 'く', 'け', 'こ'],
                ['さ', 'し', 'す', 'せ', 'そ', 'た', 'ち', 'つ', 'て', 'と'],
                ['な', 'に', 'ぬ', 'ね', 'の', 'は', 'ひ', 'ふ', 'へ'],
            ],
        },
        {
            id: 'kata', label: '片假名',
            rows: [
                ['ア', 'イ', 'ウ', 'エ', 'オ', 'カ', 'キ', 'ク', 'ケ', 'コ'],
                ['サ', 'シ', 'ス', 'セ', 'ソ', 'タ', 'チ', 'ツ', 'テ', 'ト'],
                ['ナ', 'ニ', 'ヌ', 'ネ', 'ノ', 'ハ', 'ヒ', 'フ', 'ヘ'],
            ],
        },
        {
            id: 'greek', label: '希腊',
            rows: [
                ['Α', 'Β', 'Γ', 'Δ', 'Ε', 'Ζ', 'Η', 'Θ', 'Ι', 'Κ'],
                ['Λ', 'Μ', 'Ν', 'Ξ', 'Ο', 'Π', 'Ρ', 'Σ', 'Τ', 'Υ'],
                ['Φ', 'Χ', 'Ψ', 'Ω', 'α', 'β', 'γ', 'δ', 'ε'],
            ],
        },
    ];

    // Batch 15 (#1): Chinese-mode alts carry their FINAL glyphs - mostly
    // full-width -/：；（）～“”、？！… but the ones the user pinned stay
    // HALF-WIDTH (@ . # on Z X C). Batch 16 (#3) re-pins the second row to
    // the user's exact list (a=-, s=/; the rest full-width). Row 1 keeps
    // half-width digits and the punct slot (，main / 。alt) is handled
    // separately. This replaces the batch-13 FULLWIDTH widening map: the
    // table IS the committed value, so no flick-time rewrite can misfire.
    const CN_ALTS = {
        a: '-', s: '/', d: '：', f: '；', g: '（', h: '）', j: '～',
        k: '“', l: '”',
        z: '@', x: '.', c: '#', v: '、', b: '？', n: '！', m: '…',
    };

    /* ===== Batch 16: control-key layer ===== */
    // android.view.KeyCodes the control layer may send (native side
    // whitelists the same set + A..Z).
    const CTRL_KEY_CODES = {
        Escape: 111, Tab: 61, Home: 122, End: 123,
        PageUp: 92, PageDown: 93, Del: 112,
        ArrowUp: 19, ArrowDown: 20, ArrowLeft: 21, ArrowRight: 22,
        '.': 56,
        // 批次 21 #7: the custom-key DSL and the Fn layer reach the whole
        // special-key palette - physical Backspace/Enter/Space join too.
        Enter: 66, Space: 62, Backspace: 67,
        // 批次 21 #3: the old entry was `F4: 131` - 131 is KEYCODE_F1, so
        // Alt+F4 was rejected by the native whitelist and NEVER fired.
        // KEYCODE_F4 is 134; the whole F row is here for the Fn layer.
        F1: 131, F2: 132, F3: 133, F4: 134, F5: 135, F6: 136,
        F7: 137, F8: 138, F9: 139, F10: 140, F11: 141, F12: 142,
        // 批次 20 #5: the bare LEFT modifier keys - a second tap on an armed
        // sticky modifier fires these (Win alone opens the Start menu).
        CtrlLeft: 113, AltLeft: 57, MetaLeft: 117,
    };
    // 批次 21 #1: the Fn sticky layer - twelve letter keys turn into F-keys
    // while Fn is armed (top row F1..F10, K/L take F11/F12).
    const FN_KEYS = {
        q: 'F1', w: 'F2', e: 'F3', r: 'F4', t: 'F5', y: 'F6',
        u: 'F7', i: 'F8', o: 'F9', p: 'F10', k: 'F11', l: 'F12',
    };
    const STICKY_ALONE = { Ctrl: 'CtrlLeft', Alt: 'AltLeft', Meta: 'MetaLeft' };
    // android.view.KeyEvent meta bits (SHIFT/ALT/CTRL/META).
    const CTRL_META_BITS = { Shift: 1, Alt: 2, Ctrl: 0x1000, Meta: 0x10000 };
    // The 3x3 combo grids (long-press Ctrl/Alt, tap Comb). Full key names,
    // one modifier chain per cell; ctrl+c/v and alt+f/b/. are pinned.
    const COMBO_GRIDS = {
        ctrl: [
            ['Ctrl', 'Z'], ['Ctrl', 'X'], ['Ctrl', 'C'],
            ['Ctrl', 'W'], ['Ctrl', 'V'], ['Ctrl', 'A'],
            ['Ctrl', 'U'], ['Ctrl', 'K'], ['Ctrl', 'E'],
        ],
        alt: [
            ['Alt', 'F'], ['Alt', 'B'], ['Alt', '.'],
            ['Alt', 'D'], ['Alt', 'T'], ['Alt', 'H'],
            ['Alt', 'C'], ['Alt', 'L'], ['Alt', 'N'],
        ],
        comb: [
            ['Ctrl', 'Alt', 'Del'], ['Ctrl', 'Shift', 'C'], ['Ctrl', 'Shift', 'V'],
            ['Ctrl', 'Shift', 'Esc'], ['Ctrl', 'Shift', 'T'], ['Ctrl', 'Shift', 'N'],
            ['Ctrl', 'Shift', 'W'], ['Ctrl', 'Shift', 'Tab'], ['Alt', 'F4'],
        ],
        // Batch 17: long-press the Win key - desktop shortcuts that make
        // sense on a phone-as-terminal (show desktop / lock / project).
        meta: [
            ['Meta', 'D'], ['Meta', 'L'], ['Meta', 'P'],
        ],
    };
    // Batch 16 #8: keyboard height (the letter key height) is tunable per
    // orientation; clamped so four rows always stay inside the budget.
    const KB_HEIGHT_KEY = orientation => `felime_kb_height_${orientation}`;
    const KB_ROW_MIN = 32;
    const KB_ROW_MAX = 60;

    /* ===== 批次 21 #7: custom symbol keys, defined as pasted JSON ===== */
    // Storage: {"version":1,"rows":[[ {t,tap,note} ... ] x3 ]}. tap is a
    // DSL: literal text commits as-is; [name] presses a key; [mod+name]
    // presses a combo (e.g. "[esc]ggVGD", "[ctrl+s]", "[alt+f4]").
    const CUSTOM_KEYS_STORE = 'felime_custom_keys_v2';
    const CUSTOM_LIMITS = {
        rows: 3, keys: 100, tapChars: 128, labelChars: 12, noteChars: 60,
        maxKeySteps: 16, // combo/key steps per tap (the 25/s bridge throttle)
    };
    // DSL key names -> the CTRL_KEY_CODES label sent through sendCombo.
    const CUSTOM_KEY_TOKENS = {
        esc: 'Escape', tab: 'Tab', enter: 'Enter', space: 'Space',
        bs: 'Backspace', backspace: 'Backspace', del: 'Del',
        left: 'ArrowLeft', right: 'ArrowRight', up: 'ArrowUp', down: 'ArrowDown',
        home: 'Home', end: 'End', pgup: 'PageUp', pgdn: 'PageDown', pageup: 'PageUp',
        pagedown: 'PageDown',
        f1: 'F1', f2: 'F2', f3: 'F3', f4: 'F4', f5: 'F5', f6: 'F6',
        f7: 'F7', f8: 'F8', f9: 'F9', f10: 'F10', f11: 'F11', f12: 'F12',
    };
    const CUSTOM_MOD_TOKENS = { ctrl: 'Ctrl', alt: 'Alt', shift: 'Shift', win: 'Meta', meta: 'Meta' };
    const CUSTOM_TEMPLATE = JSON.stringify({
        version: 1,
        rows: [
            [
                { t: 'Esc', tap: '[esc]', note: 'Vim / 终端 Esc' },
                { t: ':w', tap: ':w[enter]', note: 'Vim 保存' },
                { t: '整理', tap: '[esc]ggVGD', note: 'Vim 全文重新缩进' },
                { t: '保存', tap: '[ctrl+s]', note: '常见保存快捷键' },
                { t: '√', tap: '√' }, { t: '→', tap: '→' }, { t: 'F5', tap: '[f5]', note: '刷新' },
            ],
            [],
            [],
        ],
    }, null, 2);

    // Batch 6 double-pinyin parse variants: initials key -> the final keys
    // that form a real spelling with it. Derived mechanically from the
    // shipped prism's spelling set (first two key columns; scripts/verify/
    // guard_dp_finals.js re-checks the table against the prism), so zero
    // initials (a/e/o rows) and ju/qu/xu/yu's dual u/v spellings are exact.
    const DP_INITIAL_FINALS = {
        a: 'ahijklno', b: 'acdfghijklmnouxyz', c: 'abefghijkloprsuvz',
        d: 'abcefghijklmnopqrsuvwxyz', e: 'efginrz', f: 'abcfghjosuz',
        g: 'abdefghjkloprsuvwyz', h: 'abdefghjkloprsuvwyz',
        i: 'abdefghijkloprsuvwy', j: 'cdimnpqrstuvwxy',
        k: 'abdefghjkloprsuvwyz', l: 'abcdeghijklmnopqrstuvwxyz',
        m: 'abcefghijklmnoquxyz', n: 'abcdefghijklmnopqrstuvwxyz',
        o: 'abefghjkloruz', p: 'abcfghijklmnouwxyz',
        q: 'cdimnpqrstuvwxy', r: 'befghijkoprsuvw', s: 'abefghijkloprsuvz',
        t: 'abceghijklmoprsuvxyz', u: 'abdefghijklopruvwyz',
        v: 'abdefghijkloprsuvwyz', w: 'afghjlosuz', x: 'cdimnpqrstuvwxy',
        y: 'abehijklnoprstuvy', z: 'abefghijkloprsuvz',
    };

    class FelimeKeyboard {
        constructor() {
            this.token = '';
            this.ready = false;
            this.mode = 'direct';
            this.engineReady = {};
            this.shift = false;
            this.caps = false;
            this.voiceState = 'idle';
            this.editorSensitive = false;
            this.composing = false;
            this.expanded = false;
            this.lastEngineState = null;
            this.lastRawInput = '';
            this.lastChineseMode = 'pinyin';
            this.symbolCat = 'common';
            this.panelTab = 'clipboard';
            this.panelOpen = false;
            // Batch 16: control-key layer state - the toolbar swap, the
            // sticky Ctrl/Alt/Meta modifiers and the open combo grid.
            this.ctrlView = false;
            this.ctrlSuspended = false;
            this.ctrlReturnLayer = 'letters';
            // 批次 21 #1: Fn joins the sticky modifiers.
            this.sticky = { Ctrl: false, Alt: false, Meta: false, Fn: false };
            this.comboGrid = null;
            // 批次 21 #5: which key opened the combo grid (its next tap only
            // closes) and whether the native float band is touchable.
            this.comboAnchor = null;
            this._overlayOpen = false;
            // Batch 16 #8: orientation (native hello / resize fallback) and
            // the saved per-orientation total height (0 = native default).
            this.landscape = false;
            this.helloOrientation = null;
            this.safeBottom = 0;
            // 批次 21 #9: native float band above the keyboard (CSS px; 0 =
            // old behaviour - every layer stays inside the IME view).
            this.floatBand = 0;
            this.kbHeight = 0;
            this.rowHeight = 44;
            this.heightEditSaved = null;
            // Batch 15 (#7): custom-symbol editor state. customEditRow is the
            // row index while the shared strip edits a custom table row;
            // editorReturn routes closePanelEditor back to the right place.
            this.customEditRow = null;
            this.editorReturn = null;
            // Batch 13: row action menu + phrase editor state.
            this.itemMenuOpen = null;
            this.panelEditItem = null;
            this.clipboardItems = [];
            this.favoriteItems = [];
            this.popup = null;
            this.touchOrigin = null;
            this.swiping = false;
            this.voiceHold = false;
            this.spaceHoldTimer = 0;
            this.lastRevision = 0;
            this.toastTimer = null;
            // Batch 5 expanded-strip state: candidates accumulate across page
            // fetches so the area scrolls infinitely (WeChat style) instead of
            // paging. expandKey pins the accumulation to one composition.
            this.expandKey = null;
            // Batch 6 expanded area: vertical candidate grid with a
            // parse-variant column (double pinyin) and a word/single filter.
            this.expandTab = 'freq';
            this.expandRendered = 0;
            // Batch 6: a variant tap rewinds through an empty composition;
            // the auto-collapse on composition end must hold off until the
            // replay's target echo lands, then the layer reopens on the
            // chosen parse.
            this.variantReplaying = false;
            this.variantTarget = null;
            // Batch 7: the variant list is pinned to the parse the area was
            // opened with; switching variants moves the highlight and must
            // never shrink the list to the new raw's own expansions.
            this.variantAnchor = null;
            // Batch 6 cursor scrub: horizontal drag moves the
            // caret continuously, seeded where the swipe was recognised.
            // Batch 9: steps-per-pixel is tunable (1x..5x, default 3x).
            this.scrubSpeed = 3;
            // Batch 9 quick keyboard pair for the space-adjacent toggle.
            this.quickPair = ['pinyin', 'direct'];
            try {
                const speed = parseInt(localStorage.getItem('felime_scrub_speed') || '3', 10);
                if (speed >= 1 && speed <= 5) this.scrubSpeed = speed;
            } catch (_) { /* default 3x */ }
            try {
                const pair = JSON.parse(localStorage.getItem('felime_quick_pair') || 'null');
                if (Array.isArray(pair) && pair.length === 2 &&
                    MODES[pair[0]] && MODES[pair[1]]) this.quickPair = pair;
            } catch (_) { /* default 拼/En */ }
            try {
                // The height is stored per orientation; the other key (if
                // any) is picked up when the device rotates (applyHeight).
                // Values hold the TOTAL height (batch 16 #8); anything below
                // 120 is a legacy per-row height - ignored.
                const saved = parseInt(
                    localStorage.getItem(KB_HEIGHT_KEY('portrait')) || '0', 10);
                if (saved >= 120) this.kbHeight = saved;
            } catch (_) { /* native default */ }
            this.scrubBase = null;
            this.scrubSteps = 0;
            this.expandCandidates = [];
            this.expandHasNext = false;
            this.loadingMore = false;
        }

        setup() {
            this.renderMode();
            this.renderSymbols();
            document.querySelector('[data-action="letters"]').addEventListener('click', () => this.showLetters());
            this.renderSymbolCats();
            document.getElementById('setupButton').addEventListener('click', () => this.toggleSettingsPanel());
            // Batch 11 (#9): full settings opens from the toolbar button that
            // only shows while the quick panel is open.
            const fullSetup = document.getElementById('fullSetupButton');
            if (fullSetup) {
                fullSetup.addEventListener('click', () => {
                    this.closeSettingsPanel();
                    this.call(() => Native.openSetup(this.token));
                });
            }
            // Symbol layer row 4 (batch 10): the enter key lives there too.
            document.getElementById('symEnterKey').addEventListener('click', () => this.call(() => Native.enter(this.token)));
            document.getElementById('clipboardButton').addEventListener('click', () => this.openPanel('clipboard'));
            document.getElementById('favoritesButton').addEventListener('click', () => this.openPanel('favorites'));
            document.getElementById('panelClose').addEventListener('click', () => this.closePanel());
            document.getElementById('panelClear').addEventListener('click', () => {
                if (this.panelTab !== 'clipboard') return;
                this.call(() => Native.clearClipboard(this.token));
            });
            document.getElementById('panelManage').addEventListener('click', () => this.openPanelEditor(null));
            // Batch 13 (#6): the phrase editor input rides above the keyboard;
            // focus redirects native editor writes into it (see setPanelInput).
            const editorInput = document.getElementById('panelEditorInput');
            editorInput.addEventListener('focus', () => this.setPanelInput(true));
            editorInput.addEventListener('blur', () => {
                // Batch 14 review P1: picking a bar candidate mousedowns the
                // button, which blurs the input BEFORE the click - closing
                // the redirect there would land the word in the host editor.
                // While the editor flow is open the redirect stays on; the
                // explicit close paths (closePanelEditor) still clear it.
                if (!document.body.classList.contains('editing')) {
                    this.setPanelInput(false);
                }
            });
            editorInput.addEventListener('keydown', event => {
                if (event.key === 'Enter') this.savePanelEditor();
            });
            document.getElementById('panelEditorSave').addEventListener('click', () => this.savePanelEditor());
            document.getElementById('panelEditorCancel').addEventListener('click', () => this.closePanelEditor());
            // Batch 13 (#7): tapping anywhere outside an open row menu closes it.
            // Batch 16 (#5/#7): the combo grid and the mode menu get the same
            // outside-tap dismissal. 批次 21 #5: a tap on the TRIGGER key of
            // one of those layers only closes it - the trigger's own action
            // (sticky arm, mode toggle) is suppressed for that tap.
            document.getElementById('softKeyboard').addEventListener('touchstart', event => {
                const inLayer = id => event.target && event.target.closest &&
                    event.target.closest(id);
                if (this.itemMenuOpen && !inLayer('#itemMenu')) this.closeItemMenu();
                if (this.comboGrid && !inLayer('#comboPopup')) {
                    const anchor = this.comboAnchor;
                    const onAnchor = anchor && event.target.closest &&
                        event.target.closest('[data-ctrl]') === anchor;
                    this.closeComboGrid();
                    if (onAnchor) anchor._suppressClick = true;
                }
                if (document.getElementById('modeMenu').classList.contains('open') &&
                    !inLayer('#modeMenu')) {
                    const toggle = document.getElementById('modeToggle');
                    const onToggle = event.target.closest && event.target.closest('#modeToggle') === toggle;
                    this.closeModeMenu();
                    if (onToggle && toggle) toggle._suppressClick = true;
                }
            }, { capture: true, passive: true });
            document.querySelectorAll('[data-panel-tab]').forEach(button => {
                button.addEventListener('click', () => this.openPanel(button.dataset.panelTab));
                this.bindTouch(button);
            });
            document.getElementById('hide').addEventListener('click', () => this.call(() => Native.hideKeyboard(this.token)));
            document.getElementById('mic').addEventListener('click', () => this.toggleVoice());
            // Batch 16 #1: the globe opens the SYSTEM input method picker.
            document.getElementById('imeSwitchButton').addEventListener('click', () =>
                this.call(() => Native.switchInputMethod(this.token)));
            // Batch 16 #7: the control-key entry swaps the toolbar for two
            // rows of control keys (candidate bar hides, key rows compress).
            document.getElementById('ctrlTool').addEventListener('click', () =>
                this.setControlView(!this.ctrlView));
            this.bindCtrlLayer();
            // Batch 18: an explicit close affordance on the card (the
            // outside-tap dismissal stays as the second path).
            document.getElementById('comboClose').addEventListener('click', () => {
                this.closeComboGrid();
            });
            // Same pressed feedback for the floating X (批次 19 #1/#3).
            this.bindPressFeedback(document.getElementById('comboClose'));
            // ...and for the delete-confirmation card's buttons (批次 19 #8).
            this.bindPressFeedback(document.getElementById('confirmCancel'));
            this.bindPressFeedback(document.getElementById('confirmOk'));
            this.bindHeightHandle();
            // 批次 19 #8: the delete-confirmation card.
            document.getElementById('confirmCancel').addEventListener('click', () => this.closeConfirmCard());
            document.getElementById('confirmOk').addEventListener('click', () => this.deleteHighlightedCandidate());
            // Batch 16 #8: orientation also arrives over the bridge hello,
            // but the preview harness (and any missed hello) still needs the
            // viewport to win. Every resize re-derives the row height too -
            // a height drag changes the view without changing orientation.
            if (typeof window.addEventListener === 'function') {
                window.addEventListener('resize', () => {
                    // 批次 20 #6b: the IME viewport IS the keyboard - a
                    // portrait drag shrinks innerHeight below innerWidth and
                    // the old width>height test flipped the layout to the
                    // (now reverted) folded landscape. The bridge's hello
                    // orientation is authoritative once it has spoken; only
                    // the preview harness (no hello yet) falls back to the
                    // viewport ratio.
                    if (!this.helloOrientation) {
                        this.applyOrientation(window.innerWidth > window.innerHeight);
                    }
                    this.applyHeight();
                });
            }
            // Candidate compose controls (batch 4): × aborts the composition
            // and restores the toolbar; ˅ expands the candidate area over the
            // whole keyboard; inside, ˄ collapses (batch 7: the WeChat-style
            // single chevron - aborting stays with the toolbar's ×).
            document.getElementById('composeClear').addEventListener('click', () => this.clearComposing());
            document.getElementById('composeExpand').addEventListener('click', () => this.setExpanded(true));
            document.getElementById('expandCollapse').addEventListener('click', () => this.setExpanded(false));
            // Infinite horizontal strip: dragging near the right edge (or a
            // too-short strip) fetches the next candidate page and appends it.
            document.getElementById('expandGrid').addEventListener('scroll', () => this.maybeLoadMoreCandidates());
            // Batch 13 (#2): the collapsed bar shares the pool - swiping it
            // near its end pulls the next page too.
            document.getElementById('candidates').addEventListener('scroll', () => this.maybeLoadMoreCandidates());
            // Batch 6: word-frequency vs single-char filter tabs.
            document.querySelectorAll('[data-expand-tab]').forEach(button => {
                button.addEventListener('click', () => {
                    this.expandTab = button.dataset.expandTab;
                    document.querySelectorAll('[data-expand-tab]').forEach(el => (
                        el.classList.toggle('active', el === button)));
                    this.renderExpanded();
                });
            });
            this.bindTouch(document.getElementById('composeClear'));
            this.bindTouch(document.getElementById('composeExpand'));
            this.bindTouch(document.getElementById('expandCollapse'));
            document.getElementById('voiceScrim').addEventListener('click', () => {
                if (this.voiceState === 'listening' || this.voiceState === 'loading') Native.stopVoice(this.token);
            });
            this.bindTouch(document.getElementById('mic'));
            this.setupFlick(document.getElementById('softKeyboard'));
            // Batch 16 #8: a saved total height rides in at startup (native
            // restores its own copy from prefs; the bridge call keeps both
            // sides in sync, the fallback styles the view directly).
            if (this.kbHeight) this.applyKbHeight(this.kbHeight);
            this.applyHeight();
            Native.requestState();
        }

        /* ===== bridge helpers ===== */

        call(action) {
            if (!this.ready || !this.token) return;
            action(this.lastRevision);
        }

        isChineseMode() {
            return this.mode === 'pinyin' || this.mode === 'double-pinyin';
        }

        sendKey(key) {
            // Batch 18: inside the ctrl view an ARMED sticky modifier turns
            // the main keyboard's letter taps into host combos (Ctrl then w
            // sends Ctrl+W) - letting "w" fall through to the engine would
            // type into the terminal instead of firing the shortcut.
            const stickyArmed = this.ctrlView && this.sticky &&
                Object.keys(this.sticky).some(name => this.sticky[name]);
            if (stickyArmed) {
                const mods = Object.keys(this.sticky).filter(name => this.sticky[name]);
                // 批次 21 #1: an armed Fn turns the twelve mapped keys into
                // F-keys (Q -> F1 ... L -> F12); the combo clears every
                // sticky bit, Fn included.
                const fnLabel = this.sticky.Fn ? FN_KEYS[key] : null;
                if (fnLabel) {
                    this.sendCombo([...mods.filter(name => name !== 'Fn'), fnLabel]);
                    return;
                }
                // Plain modifiers + letter keeps the old combo path; Fn alone
                // leaves the tap to fall through and type the letter.
                if (/^[a-z]$/i.test(key) && mods.some(name => name !== 'Fn')) {
                    this.sendCombo([...mods.filter(name => name !== 'Fn'), key.toUpperCase()]);
                    return;
                }
            }
            // Batch 15 (#2): the punct slot's MAIN glyph is now ，and the
            // alt is 。— a tap sends ASCII ',' so the engine's punctuator
            // produces ，(the same already-verified path; sending U+FF0C
            // directly would bypass the punctuator and be dropped).
            const text = key === '.' && this.isChineseMode() ? ',' : this.applyCase(key);
            this.call(() => Native.key(text, this.token));
            if (this.shift) { this.shift = false; this.updateLabels(); }
        }

        sendText(text) {
            if (!text) return;
            this.call(() => Native.key(this.applyCase(text), this.token));
            if (this.shift) { this.shift = false; this.updateLabels(); }
        }

        /**
         * Symbol-grid insertion is literal text, never engine input: routing
         * digits through key() feeds Chinese modes, where they are consumed
         * as candidate selectors and nothing lands (user-reported bug).
         * commitText bypasses composition, like panel paste.
         */
        sendSymbol(text) {
            if (!text) return;
            // Literal insertion - no case shifting: the 拼音/希腊 categories
            // contain letters, and leftover Shift must not turn ā into Ā.
            this.call(() => Native.commitText(text, this.token));
            if (this.shift) { this.shift = false; this.updateLabels(); }
        }

        // G2-B16: Shift/Caps must also apply to accented and Cyrillic letters
        // arriving via popups and flicks, not just to [a-z0-9а-яё] key taps.
        applyCase(text) {
            if (!(this.shift || this.caps)) return text;
            if (!/^\p{L}$/u.test(text)) return text;
            if (text !== text.toLowerCase()) return text;
            return text.toUpperCase();
        }

        toggleVoice() {
            if (!this.ready) return;
            if (this.voiceState === 'listening' || this.voiceState === 'loading') Native.stopVoice(this.token);
            else Native.startVoice(this.token);
        }

        /* ===== rendering ===== */

        /** 批次 21 #9: keep the native side informed about layers that
         * overlap the float band above the keyboard - the band is not
         * touchable while closed, so any popup living there must flip the
         * native touch region (see FelimeService.onComputeInsets). */
        syncOverlay() {
            const open = this.comboGrid !== null ||
                document.getElementById('modeMenu').classList.contains('open') ||
                document.getElementById('itemMenu').classList.contains('open');
            if (this._overlayOpen === open) return;
            this._overlayOpen = open;
            if (typeof Native.setOverlayOpen === 'function') {
                this.call(() => Native.setOverlayOpen(open, this.token));
            }
        }

        renderMode() {
            const config = MODES[this.mode] || MODES.direct;
            // Batch 10: CapsLock/Shift belong to the keyboard they were set
            // on - switching keyboards must not inherit them (and Chinese
            // layouts have no shift key to undo them with).
            this.shift = false;
            this.caps = false;
            this.renderLetters(config.layout);
            this.closeModeMenu();
            this.closeSettingsPanel();
        }

        renderLetters(layoutName) {
            const layout = LAYOUTS[layoutName] || LAYOUTS.qwerty;
            // 批次 20 #6e: the folded landscape layout is REVERTED - user
            // report: the mixed bottom rows broke muscle memory and the
            // symbol layer lost its last row. Landscape now renders the same
            // four rows as portrait (the height budget grew to half the
            // screen to make room).
            const layer = document.getElementById('qwertyLayer');
            layer.replaceChildren();
            layout.rows.forEach(definition => {
                const config = typeof definition === 'string' ? { keys: definition } : definition;
                const row = this.row(config.indent);
                if (config.shift) {
                    // Batch 6: both Chinese modes carry the 分词 separator.
                    // Full pinyin: xi'an pins the split. Double pinyin: the
                    // schema's jianpin abbreviations + bare zero-initials make
                    // x'an expand into every x-syllable + an (aggregated,
                    // frequency-ranked), so the separator is useful there too
                    // (batch 5 had reverted it to Shift while n'hk was dead
                    // input). Non-Chinese modes keep Shift/Caps.
                    row.append(this.isChineseMode()
                        ? this.specialKey('sep', '分词', () => this.call(() => Native.key("'", this.token)), 'kb-wide-1_4 kb-mod sep')
                        : this.specialKey('shift', ICONS.shift, () => this.toggleShift(), 'kb-wide-1_4 kb-mod shift', 'lock'));
                }
                [...config.keys].forEach(key => row.append(this.letterKey(key)));
                if (config.backspace) row.append(this.specialKey('backspace', ICONS.backspace, () => this.call(() => Native.backspace(this.token)), 'kb-wide-1_4 kb-special', 'repeat'));
                layer.append(row);
            });
            // M4 bottom row follows the reference IME:
            // [123] [punct] [space(+mic)] [中/英] [enter]; long-press the
            // toggle for the system IME picker (the old globe slot).
            const bottom = this.row();
            bottom.append(this.specialKey('symbols', '123', () => this.showSymbols(), 'kb-wide-2_1 kb-special'));
            bottom.append(this.letterKey('.'));
            bottom.append(this.spaceKey());
            bottom.append(this.cnEnKey());
            bottom.append(this.enterKey());
            layer.append(bottom);
            this.updateLabels();
        }

        cnEnKey() {
            const button = document.createElement('button');
            button.className = 'kb-key kb-special kb-wide-1_15';
            button.dataset.role = 'cnEn';
            button.id = 'modeToggle';
            // Batch 9: tap flips the quick pair; long-press opens the full
            // mode menu (the old toolbar mode button is gone).
            button.dataset.lp = 'mode-menu';
            button.setAttribute('aria-label', '切换键盘');
            const main = document.createElement('span');
            main.className = 'cn-main';
            const sub = document.createElement('span');
            sub.className = 'cn-sub';
            button.append(main, sub);
            button.addEventListener('click', () => {
                // 批次 21 #5: the tap that closes the long-press mode menu
                // must not ALSO flip the keyboard.
                if (button._suppressClick) {
                    button._suppressClick = false;
                    return;
                }
                this.toggleChineseEnglish();
            });
            this.bindTouch(button);
            return button;
        }

        enterKey() {
            const button = document.createElement('button');
            button.className = 'kb-key kb-special kb-wide-2_25';
            button.dataset.role = 'enter';
            button.id = 'enterKey';
            button.dataset.lp = 'repeat';
            button.setAttribute('aria-label', this.composing ? '确定' : '换行');
            button.textContent = this.composing ? '确定' : '换行';
            button.addEventListener('click', () => this.call(() => Native.enter(this.token)));
            this.bindTouch(button);
            return button;
        }

        /** Batch 9 quick toggle: flips between the two modes of the user's
         * quick pair (default 拼↔En, configurable to 双↔En); a tap outside
         * the pair returns to its Chinese end. Long-press opens the full
         * mode menu - the toolbar lost its dedicated mode button. */
        toggleChineseEnglish() {
            const pair = this.quickPair;
            const target = this.mode === pair[0] ? pair[1]
                : this.mode === pair[1] ? pair[0]
                : pair[0];
            this.call(() => Native.selectMode(target, this.token));
        }

        row(indent = false) {
            const row = document.createElement('div');
            row.className = 'kb-row' + (indent ? ' kb-indent' : '');
            return row;
        }

        letterKey(key) {
            const button = document.createElement('button');
            button.className = 'kb-key kb-letter';
            button.dataset.key = key;
            button.dataset.lp = 'popup';
            button.innerHTML = '<span class="kb-alt"></span><span class="kb-main"></span>';
            button.querySelector('.kb-alt').textContent = this.altCandidates(key)[0] || '';
            button.addEventListener('click', () => this.sendKey(key));
            this.bindTouch(button);
            return button;
        }

        functionKey(label, action, classes = '', longPress = '') {
            const button = document.createElement('button');
            button.className = 'kb-key ' + classes;
            button.textContent = label;
            if (longPress) button.dataset.lp = longPress;
            button.addEventListener('click', action);
            this.bindTouch(button);
            return button;
        }

        // G2-B03: icon-bearing special key with a stable data-role for
        // structure-based assertions (text content is empty for icon keys).
        specialKey(role, icon, action, classes = '', longPress = '') {
            const button = document.createElement('button');
            button.className = 'kb-key ' + classes;
            button.dataset.role = role;
            if (typeof icon === 'string') {
                button.textContent = icon;
            } else {
                button.append(icon.cloneNode(true));
            }
            if (longPress) button.dataset.lp = longPress;
            button.addEventListener('click', action);
            this.bindTouch(button);
            return button;
        }

        spaceKey() {
            // Batch 9 (WeChat reference): the space key shows only a mic
            // glyph - the active mode's shorthand lives on the toggle key.
            // Batch 11 (#4): plain key-cap colour, not the special grey -
            // the reference keeps the space bar in the normal key style.
            const button = document.createElement('button');
            button.className = 'kb-key kb-wide-4';
            button.id = 'spaceKey';
            button.setAttribute('aria-label', '空格');
            const mic = document.createElementNS(SVG_NS, 'svg');
            mic.setAttribute('viewBox', '0 0 24 24');
            mic.setAttribute('class', 'space-mic');
            mic.setAttribute('aria-hidden', 'true');
            const path = document.createElementNS(SVG_NS, 'path');
            path.setAttribute('d', ICON_PATHS.mic);
            path.setAttribute('fill', 'currentColor');
            mic.append(path);
            button.append(mic);
            button.addEventListener('click', () => {
                // Batch 13: while composing, space must confirm the TOP
                // candidate. Native space confirms the highlight, which sits
                // on whatever page the bar/grid preloading dragged the cursor
                // to (nihao + preload -> space committed a page-3 word).
                // Choosing the pool head by id decouples it from paging.
                if (this.composing && (this.expandCandidates || []).length) {
                    this.call(revision =>
                        Native.chooseCandidate(revision, this.expandCandidates[0].id, this.token));
                    return;
                }
                this.call(() => Native.space(this.token));
            });
            this.bindSpaceHold(button);
            this.bindTouch(button, { skipClick: true });
            return button;
        }

        bindSpaceHold(button) {
            const start = event => {
                event.preventDefault();
                button.classList.add('active-touch');
                if (!this.ready || !this.token) return;
                this.spaceHoldTimer = setTimeout(() => {
                    this.voiceHold = true;
                    Native.startVoice(this.token);
                }, 350);
            };
            const finish = cancelled => {
                button.classList.remove('active-touch');
                clearTimeout(this.spaceHoldTimer);
                if (this.voiceHold) {
                    this.voiceHold = false;
                    Native.stopVoice(this.token);
                } else if (!cancelled) {
                    // touchstart preventDefault suppresses synthetic clicks,
                    // so the tap must be delivered manually.
                    button.click();
                }
            };
            button.addEventListener('touchstart', start, { passive: false });
            button.addEventListener('touchend', () => finish(false));
            button.addEventListener('touchcancel', () => finish(true));
        }

        bindTouch(button, options = {}) {
            if (button.dataset.bound) return;
            button.dataset.bound = '1';
            let holdTimer = 0;
            let repeatTimer = 0;
            let longFired = false;
            const clear = () => { clearTimeout(holdTimer); clearInterval(repeatTimer); holdTimer = repeatTimer = 0; };
            // 批次 19 #5 review: the flick layer cancels pending repeats when
            // a swipe takes over the gesture (the finger may stay on the key).
            button._cancelRepeat = clear;
            button.addEventListener('touchstart', event => {
                event.preventDefault();
                button.classList.add('active-touch');
                longFired = false;
                const touch = event.touches[0];
                this.touchOrigin = { x: touch.clientX, y: touch.clientY };
                if (button.dataset.lp === 'repeat') {
                    holdTimer = setTimeout(() => { repeatTimer = setInterval(() => button.click(), 75); }, 390);
                } else if (button.dataset.lp === 'popup' && button.dataset.key) {
                    holdTimer = setTimeout(() => { if (!this.swiping) this.openPopup(button); }, 350);
                } else if (button.dataset.lp === 'lock') {
                    holdTimer = setTimeout(() => {
                        if (!this.swiping) { longFired = true; this.lockShift(); }
                    }, 350);
                } else if (button.dataset.lp === 'mode-menu') {
                    // Long-press the toggle for the full keyboard mode list
                    // (batch 9: replaced the system IME picker there; the
                    // system picker stays in the full settings UI).
                    holdTimer = setTimeout(() => {
                        longFired = true;
                        this.toggleModeMenu();
                    }, 350);
                }
            }, { passive: false });
            // A finger that slides off the key cancels the
            // pending long-press/repeat (the press never becomes a popup or
            // auto-repeat while over some other key).
            button.addEventListener('touchmove', event => {
                const touch = event.touches[0];
                const at = document.elementFromPoint && document.elementFromPoint(touch.clientX, touch.clientY);
                if (at !== button && !(at && button.contains(at))) clear();
            }, { passive: true });
            button.addEventListener('touchend', event => {
                event.preventDefault();
                button.classList.remove('active-touch');
                clear();
                if (this.popup) this.closePopup(false);
                else if (!longFired && !this.swiping && !options.skipClick) button.click();
            }, { passive: false });
            button.addEventListener('touchcancel', () => {
                button.classList.remove('active-touch');
                clear();
                if (this.popup) this.closePopup(true);
                // Review P3: a cancelled gesture never delivers the click
                // that would consume _suppressClick (review batch21 P3) -
                // clear it or the key's NEXT tap is swallowed.
                button._suppressClick = false;
            });
        }

        setupFlick(root) {
            const threshold = 38;
            // Batch 6 scrub tuning: recognition slop; caret steps are
            // SCRUB_UNIT_PX each, counted from the touch origin.
            root.addEventListener('touchmove', event => {
                if (this.popup) {
                    event.preventDefault();
                    this.movePopup(event.touches[0]);
                    return;
                }
                // The expanded candidate strip owns horizontal drags: a swipe
                // there must scroll the strip, not move the cursor (and a
                // preventDefault here would cancel that scroll entirely).
                // Batch 12: same for the collapsed candidate BAR - swiping it
                // scrolls the strip instead of starting a flick/scrub, so the
                // extra pages stay reachable without tapping the arrows.
                if (event.target && event.target.closest &&
                    (event.target.closest('#expandLayer') ||
                     event.target.closest('#candidates'))) return;
                const touch = event.touches[0];
                if (!this.touchOrigin) return;
                const dx = touch.clientX - this.touchOrigin.x;
                const dy = touch.clientY - this.touchOrigin.y;
                const button = event.target.closest('.kb-key[data-key]');
                if (!this.swiping) {
                    // Flick/scrub recognition: the slop threshold gates the
                    // gesture start only - once swiping, the scrub below must
                    // keep tracking even when the finger crosses back over
                    // the origin (that is exactly how direction reverses).
                    if (Math.hypot(dx, dy) < threshold) return;
                    this.swiping = true;
                    // 批次 19 #5: a LEFT swipe on the backspace key aborts the
                    // whole live composition (pinyin preedit...) in one go -
                    // repeat-tapping it down letter by letter is the old way.
                    // Swipes while idle do nothing (the click stays suppressed
                    // by this.swiping, so no stray delete either). The pending
                    // hold/repeat timers die with the swipe: the finger may
                    // still be ON the key (elementFromPoint never left it) and
                    // a late repeat would eat COMMITTED text (review batch19 P3).
                    const bsKey = event.target.closest &&
                        event.target.closest('.kb-key[data-role="backspace"]');
                    if (Math.abs(dx) > Math.abs(dy) && dx < 0 && bsKey) {
                        if (bsKey._cancelRepeat) bsKey._cancelRepeat();
                        if (this.composing) this.clearComposing();
                        return;
                    }
                    if (Math.abs(dy) >= Math.abs(dx) && button && button.dataset.key) {
                        const key = button.dataset.key;
                        // Chinese-mode punct slot (batch 8): the main glyph is
                        // 。so a tap/down-flick commits it; up commits the
                        // alt ，- both via the engine punctuator (ASCII '.'
                        // / ','), so the gestures match the printed glyphs.
                        let value;
                        if (key === '.' && this.isChineseMode()) {
                            // Batch 15 (#2): main ，(tap, down) / alt 。(up);
                            // both keep flowing through the engine punctuator
                            // (Native.key) - full-width directly would be
                            // dropped unprocessed.
                            value = dy < 0 ? '.' : ',';
                        } else {
                            // Batch 15 (#1): CN_ALTS values are the final
                            // glyphs - committed as-is (commitText); the old
                            // FULLWIDTH widening map is gone.
                            value = dy < 0 ? this.altCandidates(key)[0] : key.toUpperCase();
                        }
                        if (value) {
                            // Batch 10: in Chinese modes a flicked digit/symbol
                            // or uppercase letter must LAND in the editor -
                            // Native.key() would feed the composition engine
                            // (digits become candidate selectors, uppercase
                            // becomes dead pinyin). commitText bypasses it.
                            if (this.isChineseMode() && key !== '.') {
                                this.sendSymbol(value);
                            } else {
                                this.sendText(value);
                            }
                            // Batch 12 (#7): direction-only blob, no character.
                            this.showFlick(button, dy);
                        }
                    } else if (Math.abs(dx) > Math.abs(dy) && button && !this.composing) {
                        // Scrub only starts ON a letter key: horizontal drags
                        // that begin on the panel/symbol grid scroll those
                        // layers instead of moving the caret. While a pinyin
                        // composition is live the caret belongs to the
                        // composing span - moving it just makes the next
                        // setComposingText snap it back (jumpy).
                        // Batch 14 (#7), engage: anchor one unit
                        // BEHIND the finger (soft_keyboard.js rewinds the
                        // anchor at engage), so the slop travel never counts
                        // - a light swipe starts at exactly one step instead
                        // of dumping slop/unit steps at once.
                        const unit = SCRUB_UNIT_BASE_PX / this.scrubSpeed;
                        this.scrubBase = touch.clientX - (dx > 0 ? 1 : -1) * unit;
                        this.scrubSteps = 0;
                        this.applyScrub(touch.clientX);
                    }
                } else if (this.scrubBase !== null) {
                    this.applyScrub(touch.clientX);
                }
            }, { passive: false, capture: true });
            const finish = () => {
                this.touchOrigin = null;
                this.scrubBase = null;
                this.scrubSteps = 0;
                setTimeout(() => { this.swiping = false; }, 0);
            };
            root.addEventListener('touchend', finish, { capture: true });
            root.addEventListener('touchcancel', finish, { capture: true });
        }

        /** Continuous scrub: crossing a unit boundary moves the caret by the
         * exact number of crossed steps, so fast drags jump multiple cells
         * and direction flips at the seed automatically. Unit scales with
         * the user's speed setting: 36px per step at 1x down to 7.2px at 5x
         * (3x keeps the shipped 12px feel). */
        applyScrub(clientX) {
            const unit = SCRUB_UNIT_BASE_PX / this.scrubSpeed;
            const steps = Math.trunc((clientX - this.scrubBase) / unit);
            if (steps === this.scrubSteps) return;
            const delta = steps - this.scrubSteps;
            this.scrubSteps = steps;
            // the reference parity: clamp one frame's burst (review batch14 P3) - the
            // native side rate-limits moveCursor, so a huge jump would drop
            // steps past the cap instead of landing further.
            const moves = Math.min(40, Math.abs(delta));
            for (let i = 0; i < moves; i++) {
                this.call(() => Native.moveCursor(delta > 0 ? 1 : -1, this.token));
            }
        }

        altCandidates(key) {
            // Batch 15 (#1): Chinese modes print their own symbol set.
            if (this.isChineseMode() && CN_ALTS[key]) return [CN_ALTS[key]];
            const layout = LAYOUTS[(MODES[this.mode] || MODES.direct).layout] || LAYOUTS.qwerty;
            const value = layout.alts[key];
            if (!value) return [];
            return Array.isArray(value) ? value : [value];
        }

        openPopup(button) {
            const key = button.dataset.key;
            const upper = key.toUpperCase();
            // G2-B16: letter alternates must offer their uppercase forms too
            // (e.g. Russian ё → Ё), not just the base key.
            const chars = [
                ...this.altCandidates(key).flatMap(char =>
                    /^\p{L}$/u.test(char) && char === char.toLowerCase()
                        ? [char, char.toUpperCase()] : [char]),
                upper,
                key,
            ].filter((v, i, all) => all.indexOf(v) === i);
            const popup = document.getElementById('keyPopup');
            const inner = document.getElementById('keyPopupInner');
            inner.replaceChildren();
            const cells = chars.map(char => {
                const item = document.createElement('div');
                item.className = 'kp-item';
                // Chinese mode prints full-width glyphs for the punct-slot
                // cells; the commit value stays ASCII and closePopup routes
                // it through the engine so the printed glyph is what lands.
                const glyph = this.isChineseMode() && (char === ',' || char === '.')
                    ? (char === ',' ? '，' : '。')
                    : char;
                item.textContent = glyph;
                inner.append(item);
                return { item, char, cx: 0, cy: 0 };
            });
            popup.classList.add('open');
            const rect = button.getBoundingClientRect();
            const left = Math.max(4, Math.min(innerWidth - popup.offsetWidth - 4, rect.left + rect.width / 2 - popup.offsetWidth / 2));
            popup.style.left = left + 'px';
            popup.style.top = Math.max(2, rect.top - popup.offsetHeight - 6) + 'px';
            cells.forEach(cell => {
                const r = cell.item.getBoundingClientRect();
                cell.cx = r.left + r.width / 2;
                cell.cy = r.top + r.height / 2;
            });
            const selected = cells.find(cell => cell.char === upper) || cells[0];
            selected.item.classList.add('sel');
            this.popup = { key, cells, selected, cancelled: false };
        }

        movePopup(touch) {
            if (!this.popup) return;
            // the reference parity: a finger that leaves every popup cell cancels the
            // pick - the layer shrinks/fades with distance and past
            // POPUP_GONE_RADIUS the release commits nothing. Dragging back
            // near a cell restores selection. The distance is measured to the
            // nearest cell, not the touch origin: an edge-clamped popup
            // (right-column keys) sits its legal cells over 100px away from
            // the pressed key, and those must stay valid picks.
            let selected = this.popup.cells[0];
            let best = Infinity;
            this.popup.cells.forEach(cell => {
                const d = Math.hypot(touch.clientX - cell.cx, touch.clientY - cell.cy);
                if (d < best) { best = d; selected = cell; }
            });
            const k = Math.max(0, Math.min(1,
                (best - POPUP_CELL_REACH) / (POPUP_GONE_RADIUS - POPUP_CELL_REACH)));
            const inner = document.getElementById('keyPopupInner');
            inner.style.transform = k > 0 ? `scale(${(1 - k).toFixed(3)})` : '';
            inner.style.opacity = k > 0 ? (1 - 0.9 * k).toFixed(3) : '';
            if (best > POPUP_CELL_REACH) {
                if (!this.popup.cancelled) {
                    this.popup.cancelled = true;
                    this.popup.selected = null;
                    this.popup.cells.forEach(cell => cell.item.classList.remove('sel'));
                }
                return;
            }
            this.popup.cancelled = false;
            this.popup.cells.forEach(cell => cell.item.classList.toggle('sel', cell === selected));
            this.popup.selected = selected;
        }

        closePopup(cancel) {
            const popup = this.popup;
            this.popup = null;
            const inner = document.getElementById('keyPopupInner');
            inner.style.transform = '';
            inner.style.opacity = '';
            document.getElementById('keyPopup').classList.remove('open');
            if (cancel || popup?.cancelled) return;
            // the reference parity (soft_keyboard.js closePopup): the pre-selected
            // cell is the uppercase form, so a release with no drag commits
            // that pre-selection - "original spot" only falls back to the
            // key's own character when the key-itself cell is the selected
            // one. Every live selection has a cell here, no extra fallback.
            // Batch 11 (#7): in Chinese modes the pick must LAND as typed -
            // Native.key() would feed it to the composition engine (" became
            // nothing, J/j opened a pinyin preedit). commitText bypasses it,
            // like flicks and the symbol grid.
            if (popup?.selected) {
                // Batch 15 review P1: the punct slot prints ，/。so those are
                // what a pick must land - ASCII ,/. go through the engine
                // punctuator like a tap (full-width direct would be fine here,
                // but半角 landing would NOT); other Chinese picks commit
                // literally; English always goes native key.
                const char = popup.selected.char;
                if (this.isChineseMode() && (char === ',' || char === '.')) {
                    this.sendText(char);
                } else if (this.isChineseMode()) {
                    this.sendSymbol(char);
                } else {
                    this.sendText(char);
                }
            }
        }

        /** Batch 12 (#7): the flick feedback is a direction-only blob - a
         * viscous half-ellipse that peels OFF the key along the swipe and
         * fades fast. It must NOT preview the character (the character
         * actually lands; showing it read as a duplicate). */
        showFlick(button, dy) {
            const blob = document.getElementById('flickBlob');
            if (!blob) return;
            const rect = button.getBoundingClientRect();
            const size = Math.min(38, rect.width * 0.72);
            blob.style.left = (rect.left + rect.width / 2 - size / 2) + 'px';
            blob.style.top = (rect.top + rect.height / 2 - size / 2) + 'px';
            blob.style.width = size + 'px';
            blob.style.height = size + 'px';
            const dir = dy < 0 ? -1 : 1;
            blob.classList.add('run');
            // WAAPI is assumed on real WebViews; without it the blob must not
            // stick around (the .run class would leave it painted forever).
            if (typeof blob.animate === 'function') {
                const anim = blob.animate([
                    { transform: 'scale(1.12, 0.55)', opacity: 0.5, filter: 'blur(1.5px)' },
                    { transform: `scale(1, 1) translateY(${dir * 12}px)`, opacity: 0.38, filter: 'blur(2.5px)', offset: 0.45 },
                    { transform: `scale(0.82, 1.28) translateY(${dir * 26}px)`, opacity: 0, filter: 'blur(5px)' },
                ], { duration: 250, easing: 'cubic-bezier(.2, .7, .3, 1)' });
                anim.onfinish = () => blob.classList.remove('run');
            } else {
                blob.classList.remove('run');
            }
        }

        toggleShift() {
            if (this.caps) this.caps = false;
            else this.shift = !this.shift;
            this.updateLabels();
        }

        lockShift() {
            this.caps = true;
            this.shift = false;
            this.updateLabels();
        }

        updateLabels() {
            // Reference IME: pinyin keyboards show uppercase key glyphs
            // (candidates are what actually commit), direct shows lowercase.
            const chinese = this.mode === 'pinyin' || this.mode === 'double-pinyin';
            const upper = this.shift || this.caps;
            document.querySelectorAll('[data-key]').forEach(button => {
                const key = button.dataset.key;
                const main = button.querySelector('.kb-main');
                main.textContent = (chinese || upper) ? key.toUpperCase() : key;
            });
            // Batch 15 (#2): the slot's main glyph is ，(what a tap commits
            // via the punctuator) and the alt previews the flick-up 。.
            const punct = document.querySelector('[data-key="."] .kb-alt');
            if (punct) punct.textContent = chinese ? '。' : (this.altCandidates('.')[0] || '');
            const punctMain = document.querySelector('[data-key="."] .kb-main');
            if (punctMain) punctMain.textContent = chinese ? '，' : '.';
            // Fullwidth glyphs sit in the left half of their em box: nudge
            // them right so the INK is visually centered (batch 10).
            document.querySelector('[data-key="."]')?.classList.toggle('zh-punct', chinese);
            const shift = document.querySelector('.shift');
            shift?.classList.toggle('active', this.shift);
            shift?.classList.toggle('locked', this.caps);
            // Batch 10: long-press lock shows the caps glyph (arrow + bar),
            // plain shift keeps the bare arrow (G2-B03: class/icon change,
            // never a different button).
            if (shift && shift.querySelector('svg path')) {
                shift.querySelector('svg path').setAttribute('d', this.caps ? ICON_PATHS.caps : ICON_PATHS.shift);
            }
            this.updateToggleLabels();
            this.updateEnterLabel();
            // 批次 21 #1: an armed Fn relabels the twelve F-keys last.
            this.renderFnLabels();
        }

        updateToggleLabels() {
            // Batch 9: the toggle carries mode shorthands - current big, the
            // quick-pair partner small in the lower-right corner.
            const toggle = document.getElementById('modeToggle');
            if (!toggle) return;
            const pair = this.quickPair;
            const target = this.mode === pair[0] ? pair[1]
                : this.mode === pair[1] ? pair[0]
                : pair[0];
            toggle.querySelector('.cn-main').textContent =
                (MODES[this.mode] || MODES.direct).label;
            toggle.querySelector('.cn-sub').textContent =
                (MODES[target] || MODES.direct).label;
        }

        updateEnterLabel() {
            const label = this.composing ? '确定' : '换行';
            const enter = document.getElementById('enterKey');
            if (enter) {
                enter.textContent = label;
                enter.setAttribute('aria-label', label);
            }
            // Symbol layer row 4 carries its own enter key (batch 10).
            const symEnter = document.getElementById('symEnterKey');
            if (symEnter) {
                symEnter.textContent = label;
                symEnter.setAttribute('aria-label', label);
            }
        }

        /* ===== symbol layer ===== */

        showSymbols() {
            this.symbolCat = 'common';
            this.renderSymbolCats();
            this.renderSymbols();
            document.getElementById('qwertyLayer').hidden = true;
            document.getElementById('symbolLayer').hidden = false;
        }

        showLetters() {
            document.getElementById('symbolLayer').hidden = true;
            document.getElementById('qwertyLayer').hidden = false;
        }

        /** 批次 21 #12: the IME re-showing always lands on the main view -
         * a keyboard hidden from the symbol layer must not come back
         * there. Closes every panel/layer and returns to the letters (the
         * active MODE is untouched - renderMode would also drop it). */
        resetToHome() {
            this.closePanel();
            this.closeSettingsPanel();
            this.clearEditorStrip();
            this.closeItemMenu();
            this.closeComboGrid();
            this.closeModeMenu();
            this.closeConfirmCard();
            // Review P3: a mid-drag height edit must not survive the reset -
            // cancel semantics (restore the pre-drag height), like 取消.
            if (document.body.classList.contains('height-editing')) {
                this.applyKbHeight(this.heightEditSaved);
                this.exitHeightEdit();
            }
            this.setControlView(false);
            this.setExpanded(false);
            this.showLetters();
        }

        recent() {
            try { return JSON.parse(localStorage.getItem('felime_symbol_recent') || '[]'); } catch (_) { return []; }
        }

        remember(value) {
            const values = [value, ...this.recent().filter(item => item !== value)].slice(0, 16);
            localStorage.setItem('felime_symbol_recent', JSON.stringify(values));
        }

        renderSymbolCats() {
            const strip = document.getElementById('symCats');
            strip.replaceChildren();
            SYMBOL_CATEGORIES.forEach(category => {
                // The custom tab only exists once the user saved a table.
                if (category.id === 'custom' && !this.customKeys()) return;
                const button = document.createElement('button');
                button.className = 'sym-cat' + (category.id === this.symbolCat ? ' active' : '');
                button.textContent = category.label;
                button.dataset.symCat = category.id;
                button.addEventListener('click', () => {
                    this.symbolCat = category.id;
                    document.querySelectorAll('[data-sym-cat]').forEach(el => (
                        el.classList.toggle('active', el.dataset.symCat === category.id)));
                    this.renderSymbols();
                    // The strip scrolls; keep the active category in view.
                    if (button.scrollIntoView) {
                        button.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });
                    }
                });
                strip.append(button);
            });
        }

        commonRows() {
            return this.isChineseMode() ? ZH_COMMON_ROWS : EN_COMMON_ROWS;
        }

        /** Batch 15 (#7): the user's custom symbol table - exactly 3 rows of
         * key caps (≤10/10/9; row 3's tenth cell stays the delete key).
         * Stored in localStorage; null (tab hidden) until it has content.
         * 批次 21 #7: this format is REPLACED by pasted JSON
         * (felime_custom_keys_v2); the old comma tables migrate once. */
        customRows() {
            try {
                const rows = JSON.parse(localStorage.getItem('felime_custom_rows') || 'null');
                if (Array.isArray(rows) && rows.length === 3 &&
                    rows.some(row => Array.isArray(row) && row.length)) return rows;
            } catch (_) { /* unset */ }
            return null;
        }

        /** 批次 21 #7: the pasted-JSON table: rows of {t, tap, note}. Null
         * until the user saved one; the batch-15 comma rows migrate over. */
        customKeys() {
            try {
                const parsed = JSON.parse(localStorage.getItem(CUSTOM_KEYS_STORE) || 'null');
                if (parsed && parsed.version === 1 && Array.isArray(parsed.rows) &&
                    parsed.rows.length <= CUSTOM_LIMITS.rows) {
                    return parsed.rows;
                }
            } catch (_) { /* unset */ }
            const legacy = this.customRows();
            if (legacy) {
                const rows = legacy.map(row =>
                    (row || []).map(value => ({ t: value, tap: value, note: '' })));
                try {
                    localStorage.setItem(CUSTOM_KEYS_STORE,
                        JSON.stringify({ version: 1, rows }));
                    localStorage.removeItem('felime_custom_rows');
                } catch (_) { /* keep the legacy table */ }
                return rows;
            }
            return null;
        }

        /** 批次 21 #7: tap DSL -> execution steps. Text outside [..] commits
         * literally; [name] presses a key; [mod+...+name] a combo. Returns
         * {steps} or {error} (message names the offending token). */
        parseTapDsl(tap) {
            const steps = [];
            const re = /\[([^\[\]]*)\]/g;
            let index = 0;
            let match;
            const pushText = chunk => {
                if (chunk) steps.push({ text: chunk });
            };
            while ((match = re.exec(tap))) {
                pushText(tap.slice(index, match.index));
                index = match.index + match[0].length;
                const body = match[1].trim().toLowerCase();
                if (!body) return { error: '出现空的 [] 记号' };
                const mods = [];
                let key = null;
                for (const part of body.split('+').map(p => p.trim()).filter(Boolean)) {
                    if (key === null && CUSTOM_MOD_TOKENS[part]) {
                        mods.push(CUSTOM_MOD_TOKENS[part]);
                        continue;
                    }
                    if (key === null) {
                        key = part;
                        continue;
                    }
                    return { error: `「${match[0]}」无法解析` };
                }
                if (!key) return { error: `「${match[0]}」缺少键名` };
                const comboLabel = CUSTOM_KEY_TOKENS[key] ||
                    (/^[a-z]$/.test(key) ? key.toUpperCase() : null);
                if (!comboLabel) return { error: `「${match[0]}」的键名不可用` };
                steps.push({ combo: [...mods, comboLabel] });
            }
            pushText(tap.slice(index));
            if (steps.filter(step => step.combo).length > CUSTOM_LIMITS.maxKeySteps) {
                return { error: `按键步骤超过 ${CUSTOM_LIMITS.maxKeySteps} 个` };
            }
            return { steps };
        }

        /** 批次 21 #7: validate a pasted JSON definition. Returns {rows} or
         * {error} with the FIRST problem (position + reason). */
        parseCustomKeys(text) {
            let data;
            try {
                data = JSON.parse(text);
            } catch (err) {
                return { error: 'JSON 解析失败：' + err.message };
            }
            if (!data || typeof data !== 'object' || Array.isArray(data)) {
                return { error: '顶层必须是 JSON 对象（{"version":1,"rows":[...]}）' };
            }
            if (data.version !== 1) return { error: 'version 必须是 1' };
            if (!Array.isArray(data.rows)) return { error: 'rows 必须是数组' };
            if (data.rows.length > CUSTOM_LIMITS.rows) {
                return { error: `最多 ${CUSTOM_LIMITS.rows} 行（收到 ${data.rows.length} 行）` };
            }
            const rows = [];
            let total = 0;
            for (let r = 0; r < data.rows.length; r++) {
                const row = data.rows[r];
                if (!Array.isArray(row)) return { error: `第 ${r + 1} 行必须是数组` };
                const keys = [];
                for (let c = 0; c < row.length; c++) {
                    const cell = row[c];
                    const at = `第 ${r + 1} 行第 ${c + 1} 个键`;
                    if (!cell || typeof cell !== 'object' || Array.isArray(cell)) {
                        return { error: `${at} 必须是对象（{t, tap, note}）` };
                    }
                    const label = typeof cell.t === 'string' ? cell.t.trim() : '';
                    if (!label) return { error: `${at} 缺少 t（键面）` };
                    if ([...label].length > CUSTOM_LIMITS.labelChars) {
                        return { error: `「${label}」的 t 超过 ${CUSTOM_LIMITS.labelChars} 字` };
                    }
                    const note = cell.note == null ? '' : String(cell.note);
                    if ([...note].length > CUSTOM_LIMITS.noteChars) {
                        return { error: `「${label}」的 note 超过 ${CUSTOM_LIMITS.noteChars} 字` };
                    }
                    const tap = typeof cell.tap === 'string' ? cell.tap : '';
                    if (!tap) return { error: `${at}（「${label}」）缺少 tap（单击行为）` };
                    if ([...tap].length > CUSTOM_LIMITS.tapChars) {
                        return { error: `「${label}」的 tap 超过 ${CUSTOM_LIMITS.tapChars} 字符` };
                    }
                    const parsed = this.parseTapDsl(tap);
                    if (parsed.error) return { error: `「${label}」的 tap ${parsed.error}` };
                    if (++total > CUSTOM_LIMITS.keys) {
                        return { error: `键总数超过 ${CUSTOM_LIMITS.keys}` };
                    }
                    keys.push({ t: label, tap, note });
                }
                rows.push(keys);
            }
            if (!rows.some(row => row.length)) return { error: '至少要定义一个键' };
            return { rows };
        }

        /** 批次 21 #7: fire one custom key - text chunks commit literally,
         * key/combo steps go through sendCombo (same channel as the ctrl
         * layer). Steps were validated when the table was saved; a table
         * edited out-of-band re-validates defensively. */
        runCustomCell(cell) {
            const parsed = this.parseTapDsl(cell.tap);
            if (parsed.error) {
                this.showToast(`按键无效：${parsed.error}`);
                return;
            }
            for (const step of parsed.steps) {
                if (step.text) this.sendSymbol(step.text);
                else this.sendCombo(step.combo);
            }
        }

        symbolCategoryValues() {
            if (this.symbolCat === 'common') {
                return this.commonRows().flat();
            }
            if (this.symbolCat === 'recent') {
                const values = this.recent();
                // Fill the remainder from the common set so the grid always
                // shows full, evenly spaced rows (batch 5 style fix).
                for (const row of this.commonRows()) {
                    for (const value of row) {
                        if (values.length >= 29) break;
                        if (!values.includes(value)) values.push(value);
                    }
                }
                return values;
            }
            const category = SYMBOL_CATEGORIES.find(c => c.id === this.symbolCat) || SYMBOL_CATEGORIES[0];
            // Pad every row to the full 10 cells: flattening short rows first
            // shifted the grid left and split pairs like ( ) across rows
            // (review batch5 P2).
            const values = [];
            category.rows.forEach(row => {
                const padded = [...row];
                while (padded.length < 10) padded.push('');
                values.push(...padded);
            });
            return values;
        }

        renderSymbols() {
            const grid = document.getElementById('symGrid');
            grid.replaceChildren();
            // 批次 21 #7: the custom table renders as three independent
            // horizontally scrollable strips (unlimited keys per row, drag
            // to see the overflow) with a fixed backspace column on the
            // right - the old "row 3 cell 10 is delete" deal belonged to
            // the even 10-column grid.
            if (this.symbolCat === 'custom') {
                const wrap = document.createElement('div');
                wrap.className = 'sym-custom';
                const rowsBox = document.createElement('div');
                rowsBox.className = 'sym-custom-rows';
                const rows = this.customKeys() || [[], [], []];
                rows.forEach(row => {
                    const strip = document.createElement('div');
                    strip.className = 'kb-row sym-custom-row';
                    (row || []).forEach(cell => {
                        const button = document.createElement('button');
                        button.className = 'kb-key sym-custom-key';
                        button.textContent = cell.t;
                        button.addEventListener('click', () => this.runCustomCell(cell));
                        if (cell.note) {
                            this.bindItemLongPress(button, () => this.showToast(cell.note));
                        }
                        strip.append(button);
                    });
                    rowsBox.append(strip);
                });
                const bsCol = document.createElement('div');
                bsCol.className = 'sym-custom-bs';
                bsCol.append(this.specialKey('backspace', ICONS.backspace,
                    () => this.call(() => Native.backspace(this.token)),
                    'kb-special', 'repeat'));
                wrap.append(rowsBox, bsCol);
                grid.append(wrap);
                return;
            }
            const values = this.symbolCategoryValues();
            while (values.length < 29) values.push('');
            for (let r = 0; r < 3; r++) {
                const row = this.row();
                const slice = values.slice(r * 10, r * 10 + 10);
                if (r === 2) slice.length = 9; // last cell of row 3 is backspace
                slice.forEach(value => {
                    if (value === '') {
                        const blank = document.createElement('span');
                        blank.className = 'sym-blank';
                        row.append(blank);
                        return;
                    }
                    const button = this.functionKey(value, () => {
                        this.sendSymbol(value);
                        this.remember(value);
                        if (this.symbolCat === 'recent') this.renderSymbols();
                    }, value.length > 1 ? 'sym-multi' : 'sym-single');
                    row.append(button);
                });
                if (r === 2) {
                    row.append(this.specialKey('backspace', ICONS.backspace,
                        () => this.call(() => Native.backspace(this.token)),
                        'kb-special', 'repeat'));
                }
                grid.append(row);
            }
        }

        /* ===== control-key layer (batch 16 #7) ===== */

        /** Swap the TOOLBAR for the two control-key rows (the bar's 40px
         * slot, batch 18: the rows never exceed the slot so the keyboard
         * body keeps its height). The ctrl view is a SWITCH: composing or
         * a panel only SUSPENDS it (see suspendCtrlView), the user turns
         * it off with the X. Any layer that owns the bar blocks entering. */
        setControlView(on) {
            // Entering is blocked while composing (the composing toolbar
            // swap owns the bar); leaving is always allowed.
            if (on && this.composing) return;
            if (on && this.panelOpen) return; // panel owns the toolbar
            // The quick settings panel owns the key area too (batch 16).
            if (on && document.getElementById('settingsPanel').classList.contains('open')) return;
            // Batch 17 review P3: the editor strip (custom-symbol editing)
            // owns the bar too - it would fight the ctrl rows for the slot.
            if (on && document.body.classList.contains('editing')) return;
            this.ctrlView = on;
            this.ctrlSuspended = false;
            document.body.classList.toggle('ctrl-view', on);
            document.getElementById('ctrlLayer').hidden = !on;
            document.getElementById('candidateBar').hidden = on;
            if (!on) {
                this.closeComboGrid();
                this.sticky = { Ctrl: false, Alt: false, Meta: false, Fn: false };
            }
            this.renderCtrlSticky();
        }

        /** Batch 18 #1: the ctrl view is a switch, not a one-shot. When a
         * composition (or a panel) borrows the toolbar, the display hands
         * the bar back but the switch STAYS ON; maybeResumeCtrlView brings
         * the rows back once the borrower leaves. */
        suspendCtrlView() {
            if (!this.ctrlView || this.ctrlSuspended) return;
            this.ctrlSuspended = true;
            this.closeComboGrid();
            this.sticky = { Ctrl: false, Alt: false, Meta: false, Fn: false };
            this.renderCtrlSticky();
            document.body.classList.remove('ctrl-view');
            document.getElementById('ctrlLayer').hidden = true;
            document.getElementById('candidateBar').hidden = false;
        }

        maybeResumeCtrlView() {
            if (!this.ctrlView || !this.ctrlSuspended) return;
            if (this.composing || this.panelOpen) return;
            if (document.body.classList.contains('editing')) return;
            if (document.getElementById('settingsPanel').classList.contains('open')) return;
            this.ctrlSuspended = false;
            document.body.classList.add('ctrl-view');
            document.getElementById('ctrlLayer').hidden = false;
            document.getElementById('candidateBar').hidden = true;
        }

        /** 批次 19 #3: pressed feedback for buttons that keep their native
         * click path (no bindTouch) - the class goes on directly; :active
         * alone is unreliable on touch. */
        bindPressFeedback(el) {
            el.addEventListener('touchstart', () => el.classList.add('active-touch'), { passive: true });
            el.addEventListener('touchend', () => el.classList.remove('active-touch'));
            el.addEventListener('touchcancel', () => el.classList.remove('active-touch'));
        }

        bindCtrlLayer() {
            document.querySelectorAll('#ctrlLayer [data-ctrl]').forEach(button => {
                this.bindPressFeedback(button);
                button.addEventListener('click', () => {
                    // A long-press that opened the combo grid is followed by
                    // a synthetic click - it must not ALSO flip the sticky
                    // modifier (review batch16 P2).
                    if (button._suppressClick) {
                        button._suppressClick = false;
                        return;
                    }
                    this.handleCtrlKey(button.dataset.ctrl);
                });
                // Ctrl/Alt/Meta long-press opens their combo grids; the Fn
                // key (批次 21 #2) long-presses into the former Comb grid -
                // its tap is the sticky toggle. Plain keys just fire.
                if (button.classList.contains('ctrl-mod') ||
                    button.classList.contains('ctrl-combo')) {
                    button.addEventListener('touchstart', () => {
                        button._comboTimer = setTimeout(() => {
                            button._suppressClick = true;
                            this.openComboGrid(
                                button.dataset.ctrl === 'sticky-fn'
                                    ? 'comb'
                                    : button.dataset.ctrl.replace('sticky-', ''),
                                button,
                            );
                        }, 350);
                    }, { passive: true });
                    button.addEventListener('touchend', () => {
                        clearTimeout(button._comboTimer);
                    });
                    button.addEventListener('touchcancel', () => {
                        clearTimeout(button._comboTimer);
                        // Same residue rule: a cancelled long-press never
                        // delivers the click that clears _suppressClick.
                        button._suppressClick = false;
                    });
                }
            });
            document.getElementById('comboPopup').addEventListener('click', event => {
                if (event.target.id === 'comboPopup') this.closeComboGrid();
            });
        }

        /** Sticky modifiers: Ctrl/Alt/Meta/Fn arm the NEXT key into a combo
         * (reference terminal-keyboard behaviour); they light up and clear
         * after the combo lands. Fn additionally relabels the letter rows
         * (renderFnLabels). */
        renderCtrlSticky() {
            document.querySelectorAll('#ctrlLayer .ctrl-mod').forEach(button => {
                const mod = button.dataset.ctrl.replace('sticky-', '');
                const meta = mod === 'ctrl' ? 'Ctrl'
                    : mod === 'alt' ? 'Alt'
                    : mod === 'fn' ? 'Fn' : 'Meta';
                button.classList.toggle('active', this.sticky[meta]);
            });
            this.renderFnLabels();
        }

        /** 批次 21 #1: while Fn is armed the twelve mapped keys print their
         * F-number as the main glyph (the letter drops to the small alt
         * slot). The OFF branch restores the base glyphs itself - every
         * disarm path (sendCombo, second-tap disarm, collapse/suspend) only
         * calls renderCtrlSticky, and review P1-1 showed an early return
         * left F1..F12 printed while taps already typed letters. The alt
         * slot must go back to the mode's alt hint, not the bare letter. */
        renderFnLabels() {
            const on = this.ctrlView && this.sticky.Fn;
            const chinese = this.mode === 'pinyin' || this.mode === 'double-pinyin';
            const upper = this.shift || this.caps;
            document.querySelectorAll('#qwertyLayer [data-key]').forEach(button => {
                const key = button.dataset.key;
                if (!FN_KEYS[key]) return; // updateLabels owns every other state
                const main = button.querySelector('.kb-main');
                const alt = button.querySelector('.kb-alt');
                if (!main || !alt) return;
                if (on) {
                    main.textContent = FN_KEYS[key];
                    alt.textContent = (chinese || upper) ? key.toUpperCase() : key;
                } else {
                    main.textContent = (chinese || upper) ? key.toUpperCase() : key;
                    alt.textContent = this.altCandidates(key)[0] || '';
                }
            });
        }

        handleCtrlKey(action) {
            if (action === 'collapse') {
                this.setControlView(false);
                return;
            }
            if (action.startsWith('sticky-')) {
                const meta = action === 'sticky-ctrl' ? 'Ctrl'
                    : action === 'sticky-alt' ? 'Alt'
                    : action === 'sticky-fn' ? 'Fn' : 'Meta';
                if (this.sticky[meta]) {
                    if (meta === 'Fn') {
                        // Fn has no bare F-key; a second tap just disarms.
                        this.sticky.Fn = false;
                        this.renderCtrlSticky();
                        return;
                    }
                    // 批次 20 #5: tapping the ARMED modifier again fires the
                    // bare key (Win alone opens the Windows menu, Alt alone
                    // the menu bar) - a no-op disarm read as "broken".
                    this.sendCombo([STICKY_ALONE[meta]]);
                    return;
                }
                this.sticky[meta] = true;
                this.renderCtrlSticky();
                return;
            }
            // A plain control key: fires with the armed modifiers at once.
            const mods = Object.keys(this.sticky).filter(key => this.sticky[key]);
            this.sendCombo([...mods, action]);
        }

        /** Send one host key event: [modifiers..., key] -> keycode + meta
         * bits over Native.keyEvent (whitelisted on the native side).
         * 批次 21 #3/#4: EVERY modifier combo rides the PHYSICAL channel
         * (modifier key down -> key down/up -> modifier up). The RDP round
         * proved the single-event form leaves the remote Alt held down -
         * the Alt+Tab switcher never commits (the client synthesizes
         * Alt-down from the meta bit but no matching Alt-up), the same
         * failure class as the original Win report. Ctrl/Shift join them
         * for one uniform sequence that mirrors a physical left-hand press. */
        sendCombo(parts) {
            const label = parts[parts.length - 1];
            const keyCode = this.keyCodeFor(label);
            if (!keyCode) return;
            let meta = 0;
            parts.slice(0, -1).forEach(mod => { meta |= CTRL_META_BITS[mod] || 0; });
            const physical = meta !== 0 && typeof Native.keyEventPhysical === 'function';
            this.call(() => physical
                ? Native.keyEventPhysical(keyCode, meta, this.token)
                : Native.keyEvent(keyCode, meta, this.token));
            this.sticky = { Ctrl: false, Alt: false, Meta: false, Fn: false };
            this.renderCtrlSticky();
        }

        keyCodeFor(label) {
            if (CTRL_KEY_CODES[label] !== undefined) return CTRL_KEY_CODES[label];
            if (/^[A-Z]$/.test(label)) return 29 + label.charCodeAt(0) - 65; // KEYCODE_A..
            return 0;
        }

        /** The 3x3 combo grid floats above the control layer; cells carry
         * the full key names stacked per line (demo round 3). 批次 22
         * #0.3: placement hugs the trigger's top edge across the WHOLE IME
         * window (band included), shrinking its cells if the headroom is
         * short - the trigger key itself is never covered and the card
         * never straddles the key rows half-off (design §0 总原则).
         * 批次 21 #5: tapping the anchor again only closes the card. */
        openComboGrid(grid, anchor) {
            const popup = document.getElementById('comboPopup');
            const inner = document.getElementById('comboPopupInner');
            inner.replaceChildren();
            (COMBO_GRIDS[grid] || []).forEach(combo => {
                const cell = document.createElement('button');
                cell.className = 'combo-cell';
                combo.forEach((part, index) => {
                    const line = document.createElement('span');
                    // Modifier names ride as small muted text; the last item
                    // is the key itself and gets the big face.
                    line.className = index === combo.length - 1 ? 'combo-main' : 'combo-mod';
                    line.textContent = part;
                    cell.append(line);
                });
                cell.addEventListener('click', () => {
                    this.closeComboGrid();
                    this.sendCombo(combo);
                });
                inner.append(cell);
            });
            this.comboGrid = grid;
            this.comboAnchor = anchor || null;
            popup.classList.add('open');
            // 批次 22 #0.3: placement is ANCHOR-driven across the whole IME
            // window (the band above the keyboard is window, too - 批次 21
            // #9 got the space right but pinned the card to the keyboard's
            // top EDGE instead of the trigger, so it drifted off its key;
            // and when the band ran short in landscape the old floor let it
            // hang halfway over the key rows). Rule: hug the trigger's top
            // edge with the full-size card; if the space above the trigger
            // cannot hold it, shrink the cells (58 → 40px floor, still
            // tappable) before ever covering a key. The ✕ badge overhangs
            // 14px top/right (review batch19 P2) - the clamps reserve that.
            const rect = anchor.getBoundingClientRect();
            // Review batch22 P1-1: headroom must reserve the ✕ badge's 14px
            // overhang AND the 6px gap the hug branch adds on top, or the
            // badge clips at the window edge on the tight fits.
            const availUp = rect.top - 20;
            // Full-size first; shrink only when the space above the trigger
            // cannot hold the card (then re-measure before positioning).
            // Review batch22 P1-1: measure at the 58px design size FIRST -
            // closeComboGrid leaves the inline --combo-cell behind, and
            // sizing the shrink decision off the stale small cell made the
            // shrink branch flip to side-placement on every second open.
            popup.style.removeProperty('--combo-cell');
            if (popup.offsetHeight > availUp) {
                // Card height = 3*cell + 2*gap + padding + borders = 3*cell
                // + 26 (review batch22 P1-1: the +24 constant ignored the
                // 2px borders and left every third fit 2px short).
                popup.style.setProperty('--combo-cell',
                    Math.max(40, Math.floor((availUp - 26) / 3)) + 'px');
            }
            const cardW = popup.offsetWidth;
            const cardH = popup.offsetHeight;
            const left = Math.max(16, Math.min(innerWidth - cardW - 16,
                rect.left + rect.width / 2 - cardW / 2));
            if (cardH <= availUp) {
                // Hug the trigger's top edge (the usual case: portrait has
                // the whole band above the keyboard to grow into).
                popup.style.left = left + 'px';
                popup.style.top = (rect.top - cardH - 6) + 'px';
            } else {
                // Even the 40px floor does not fit above the trigger (short
                // landscape ctrl rows): slide BESIDE the trigger, vertically
                // centred on its row - the trigger key itself stays visible
                // and tappable (tap it = close, 批次 21 #5).
                const cy = rect.top + rect.height / 2;
                popup.style.top =
                    Math.max(14, Math.min(cy - cardH / 2, innerHeight - cardH - 4)) + 'px';
                popup.style.left = (rect.right + 8 + cardW <= innerWidth - 16
                    ? rect.right + 8
                    : Math.max(16, rect.left - 8 - cardW)) + 'px';
            }
            this.syncOverlay();
        }

        closeComboGrid() {
            this.comboGrid = null;
            this.comboAnchor = null;
            const popup = document.getElementById('comboPopup');
            popup.classList.remove('open');
            this.syncOverlay();
        }

        /* ===== orientation & keyboard height (batch 16 #8) ===== */

        applyOrientation(landscape) {
            if (this.landscape === landscape) return;
            this.landscape = landscape;
            document.body.classList.toggle('landscape', landscape);
            // Stored heights are per orientation; pick the right one, then
            // re-render the letter rows (the layout folds in landscape).
            this.kbHeight = this.storedKbHeight();
            this.renderMode();
            this.applyHeight();
        }

        /** Saved TOTAL keyboard height (CSS px) for the current orientation;
         * 0 = native default (272). Legacy keys held a per-row height (<100)
         * - ignored so an old value cannot clamp the new total. */
        storedKbHeight() {
            try {
                const saved = parseInt(
                    localStorage.getItem(KB_HEIGHT_KEY(this.landscape ? 'landscape' : 'portrait')) || '0', 10);
                if (saved >= 120) return saved;
            } catch (_) { /* unset */ }
            return 0;
        }

        /** Row height derives from the ACTUAL view height (the native side
         * owns the total; we only keep the rows inside it). Keys scale WITH
         * the dragged height - a taller keyboard grows the keys (capped),
         * a shorter one shrinks them (demo feedback: never stretch the row
         * gaps). chrome = top pad + bar + gaps + bottom pad, including the
         * inter-row margins (portrait 14+2+40+10+15+3*5 = 96). */
        applyHeight() {
            const view = document.getElementById('softKeyboard');
            const available = (view && view.clientHeight) || window.innerHeight;
            // Batch 18 #2: the ctrl rows live INSIDE the bar slot again, so
            // the keyboard budget is orientation-only (no ctrl branch). The
            // height-edit card (batch 18 #3) owns its own 36px slice: while
            // it shows, the bar is pushed down and the chrome grows to keep
            // the rows inside the view.
            // 批次 21 #10: the landscape bar grew to clear the preedit line
            // (margin-top 14 + 40 bar vs the old 2 + 26 that made the pinyin
            // overlap the candidates): chrome = 4 pad + 14 + 40 + 4 + 4 pad
            // + 4x3 row margins = 78 (was 52 - 批次 20 #6e composition).
            let chrome = this.landscape ? 78 : 96;
            const rows = 4;
            if (document.body.classList.contains('height-editing')) chrome += 36;
            const safe = (this.landscape && this.safeBottom) || 0;
            if (safe) chrome += safe;
            const fit = Math.floor((available - chrome) / rows);
            const rowHeight = Math.max(KB_ROW_MIN, Math.min(fit, KB_ROW_MAX));
            const root = document.documentElement;
            if (root && root.style && typeof root.style.setProperty === 'function') {
                root.style.setProperty('--kb-row-h', rowHeight + 'px');
                // 批次 20 #6d: the landscape keyboard stops above the gesture
                // strip; the background colour fills the inset (theme-safe).
                root.style.setProperty('--safe-bottom', safe + 'px');
            }
        }

        /** Push a new TOTAL height to the native side. Old bridges (and the
         * preview harness without the native method) fall back to styling
         * the view directly so the gesture still demonstrates. */
        applyKbHeight(total) {
            this.kbHeight = total;
            const view = document.getElementById('softKeyboard');
            if (typeof Native.setKeyboardHeight === 'function') {
                this.call(() => Native.setKeyboardHeight(Math.round(total), this.token));
            } else if (view) {
                view.style.height = Math.round(total) + 'px';
            }
            this.applyHeight();
        }

        /** Batch 16 #8: drag the keyboard's top edge to resize the WHOLE
         * view (keys and fonts scale with it); Save keeps the height for
         * the CURRENT orientation, Cancel restores. */
        enterHeightEdit() {
            const view = document.getElementById('softKeyboard');
            this.heightEditSaved = (view && view.clientHeight) || 272;
            this.closeSettingsPanel();
            // Batch 18 #1: borrow the bar slot for the duration (resume on
            // exit) so the ctrl rows never fight the card for the slot.
            this.suspendCtrlView();
            const handle = document.getElementById('heightHandle');
            handle.hidden = false;
            document.body.classList.add('height-editing');
            this.showHeightValue();
            this.applyHeight();
        }

        exitHeightEdit() {
            document.getElementById('heightHandle').hidden = true;
            document.getElementById('heightHint').classList.remove('dragging');
            document.body.classList.remove('height-editing');
            this.applyHeight();
            this.maybeResumeCtrlView();
        }

        showHeightValue() {
            const view = document.getElementById('softKeyboard');
            const total = (view && view.clientHeight) || this.kbHeight || 272;
            document.getElementById('heightValue').textContent =
                `${Math.round(total)}px（拖动调整）`;
            // Batch 18 #3: the card is pinned to the keyboard's top edge in
            // CSS (absolute, top: 3px) - it moves with the edge by itself.
            // No fixed positioning: on device the old top-24px hint landed
            // ON the drag strip and the first key row (the IME cannot paint
            // above its own window, so "float above" must mean "top edge").
        }

        bindHeightHandle() {
            const handle = document.getElementById('heightHandle');
            let startY = 0;
            let startTotal = 272;
            let dragging = false;
            handle.addEventListener('touchstart', event => {
                // The hint's buttons must keep their taps: only the strip
                // itself starts a drag (preventDefault would eat clicks).
                if (event.target && event.target.closest &&
                    event.target.closest('#heightHint')) return;
                event.preventDefault();
                const view = document.getElementById('softKeyboard');
                startY = event.touches[0].clientY;
                startTotal = (view && view.clientHeight) || 272;
                dragging = true;
                document.getElementById('heightHint').classList.add('dragging');
            }, { passive: false });
            // 批次 20 #6a: coalesce the per-move rebuilds into one per frame
            // - a full view relayout per touched pixel strobed the keyboard.
            let pendingPx = null;
            let rafId = 0;
            const flush = () => {
                rafId = 0;
                if (pendingPx === null) return;
                const px = pendingPx;
                pendingPx = null;
                this.applyKbHeight(px);
                this.showHeightValue();
            };
            const raf = typeof window !== 'undefined' && window.requestAnimationFrame
                ? window.requestAnimationFrame.bind(window)
                : fn => setTimeout(fn, 16);
            const schedule = next => {
                pendingPx = next;
                if (!rafId) rafId = raf(flush);
            };
            handle.addEventListener('touchmove', event => {
                // A touch that started on the hint (its buttons overlap the
                // strip) never records an origin - dragging here would move
                // the keyboard with a STALE start value (demo bug: the view
                // snapped to the minimum). No origin, no drag.
                if (!dragging) return;
                event.preventDefault();
                // NO JS-side max clamp: on device the WebView's innerHeight
                // IS the keyboard view itself, so any local ceiling would
                // cap the drag below its own minimum (review batch16 P1).
                // The native side owns the real-screen clamp.
                const min = this.landscape ? 170 : 210;
                const next = Math.max(min, startTotal + (startY - event.touches[0].clientY));
                schedule(next);
            }, { passive: false });
            const stopDrag = () => {
                dragging = false;
                if (rafId) { raf(flush); }
                document.getElementById('heightHint').classList.remove('dragging');
            };
            handle.addEventListener('touchend', stopDrag);
            handle.addEventListener('touchcancel', stopDrag);
            document.getElementById('heightCancel').addEventListener('click', () => {
                this.applyKbHeight(this.heightEditSaved);
                this.exitHeightEdit();
                this.toggleSettingsPanel();
            });
            document.getElementById('heightSave').addEventListener('click', () => {
                const view = document.getElementById('softKeyboard');
                const total = Math.round((view && view.clientHeight) || this.heightEditSaved);
                try {
                    localStorage.setItem(
                        KB_HEIGHT_KEY(this.landscape ? 'landscape' : 'portrait'), String(total));
                } catch (_) {}
                this.showToast('键盘高度已保存');
                this.exitHeightEdit();
                this.toggleSettingsPanel();
            });
        }

        /* ===== mode menu ===== */

        toggleModeMenu() {
            const menu = document.getElementById('modeMenu');
            if (menu.classList.contains('open')) { this.closeModeMenu(); return; }
            this.closeSettingsPanel();
            menu.replaceChildren();
            this.modeOrder().forEach(name => {
                const config = MODES[name];
                const button = document.createElement('button');
                const ready = !config.engine || this.engineReady[name] !== false;
                const current = name === this.mode;
                button.className = current ? 'current' : (ready ? '' : 'preparing');
                // Batch 16 (#6): compact rows - the shorthand leads, the full
                // title follows (left aligned, no trailing blank).
                button.innerHTML = `<span class="prep">${ready ? (MODES[name] || MODES.direct).label : '…'}</span><span>${config.title}</span>`;
                if (ready && !current) {
                    button.addEventListener('click', () => {
                        this.closeModeMenu();
                        this.call(() => Native.selectMode(name, this.token));
                    });
                }
                menu.append(button);
            });
            // M4: 键盘设置 moved out of the menu to the toolbar setupButton;
            // batch 9 moved the theme row into that settings panel too.
            menu.scrollTop = 0; // scroll state must not leak between opens
            menu.classList.add('open');
            // 批次 22 #0.4: ANCHOR-driven placement. 批次 21 #9 had the
            // right idea (use the app-area band above the keyboard so all
            // rows fit) but pinned the menu to the keyboard's top EDGE -
            // far from its trigger and drifting over whatever the app
            // showed there. Now the menu hugs the mode toggle's top edge
            // (right edges aligned); only when the space above the toggle
            // cannot hold it does it fall back to window-top + scroll
            // (short landscape band) - it never lands on the key rows and
            // never detaches from its trigger.
            menu.style.maxHeight = 'none'; // measure the natural height first
            const toggle = document.getElementById('modeToggle').getBoundingClientRect();
            menu.style.left = 'auto';
            menu.style.right = Math.max(4, innerWidth - toggle.right) + 'px';
            menu.style.bottom = 'auto';
            const availUp = toggle.top - 14;
            if (menu.offsetHeight <= availUp) {
                menu.style.maxHeight = '';
                menu.style.top = (toggle.top - menu.offsetHeight - 6) + 'px';
            } else {
                menu.style.maxHeight = availUp + 'px';
                menu.style.top = '14px';
            }
            this.syncOverlay();
        }

        closeModeMenu() {
            document.getElementById('modeMenu').classList.remove('open');
            this.syncOverlay();
        }

        /** Automation hook (batch 9): wipe the editor through the IME's own
         * deletion cascade - host-injected keyevents are unreliable while
         * the WebView is focused. */
        clearEditorBridge() {
            if (!this.ready || !this.token) return 'not-ready';
            this.call(() => Native.clearComposing(this.token));
            for (let i = 0; i < 160; i++) {
                this.call(() => Native.backspace(this.token));
            }
            return 'ok';
        }

        /* ===== quick settings panel (batch 9) ===== */

        toggleSettingsPanel(page = null) {
            const panel = document.getElementById('settingsPanel');
            if (panel.classList.contains('open')) { this.closeSettingsPanel(); return; }
            this.closeModeMenu();
            // Batch 16: the control view owns the key area too - it never
            // coexists with the settings panel. Batch 18 #1: borrow, don't
            // switch off (closing the panel restores the rows).
            if (this.ctrlView) this.suspendCtrlView();
            // Batch 15 review P2: opening the quick panel over the editor
            // strip must tear the strip down too, or the input rides on
            // without a keyboard (and keeps the native redirect armed).
            this.clearEditorStrip();
            // Batch 11: the panel reopens on its home page (or the requested
            // sub-page - the custom-row editor returns to 定制键盘).
            this.settingsPage = page;
            this.renderSettingsPanel();
            panel.classList.add('open');
            panel.hidden = false;
            // Batch 14 (#6b): the panel REPLACES the key area (no overlay) -
            // remember which key layer to restore on close.
            this.settingsReturnLayer =
                document.getElementById('symbolLayer').hidden ? 'letters' : 'symbols';
            document.getElementById('symbolLayer').hidden = true;
            document.getElementById('qwertyLayer').hidden = true;
            // Batch 11 (#9): full settings lives in the toolbar, next to the
            // gear, and only while the quick panel is open.
            const full = document.getElementById('fullSetupButton');
            if (full) full.hidden = false;
        }

        closeSettingsPanel() {
            const panel = document.getElementById('settingsPanel');
            if (!panel) return;
            if (!panel.classList.contains('open')) return;
            panel.classList.remove('open');
            panel.hidden = true;
            this.settingsPage = null;
            this.hideSettingsPageBar();
            // Batch 14 (#6b): hand the key layer back unconditionally - the
            // panel replaces whichever layer was visible when it opened.
            // Review P1: the old "editor/panel own their layers" branch
            // stranded an empty key area after a settings round-trip inside
            // the phrase editor (the editor coexists with the qwerty layer
            // since batch 14 #4).
            const toSymbols = this.settingsReturnLayer === 'symbols';
            document.getElementById('symbolLayer').hidden = !toSymbols;
            document.getElementById('qwertyLayer').hidden = toSymbols;
            // Batch 18 #1: the panel borrowed the bar from the ctrl view -
            // bring the rows back if the switch is still on.
            this.maybeResumeCtrlView();
            const full = document.getElementById('fullSetupButton');
            if (full) full.hidden = true;
        }

        /** Batch 11 (#1): quick settings grew sub-pages - complex features
         * (quick-switch pairs, the double-pinyin key map, phrase management)
         * get their own page with a back row instead of stacking inline
         * blocks that overflowed the screen. */
        renderSettingsPanel(page = this.settingsPage) {
            const panel = document.getElementById('settingsPanel');
            panel.replaceChildren();
            if (page) {
                // Batch 15: the sub-page header rides the TOOLBAR (left:
                // back + title, right: close) instead of its own row.
                this.showSettingsPageBar({ pair: '输入法快捷切换', menu: '长按菜单',
                    schema: '双拼键位 - 自然码', custom: '定制键盘' }[page] || '');
            } else {
                this.hideSettingsPageBar();
            }
            if (page === 'pair') this.renderPairEditor(panel);
            else if (page === 'menu') this.renderMenuEditor(panel);
            else if (page === 'schema') this.renderSchemaPage(panel);
            else if (page === 'custom') this.renderCustomPage(panel);
            else this.renderSettingsHome(panel);
        }

        /** Batch 15: sub-page chrome lives in the candidate bar - a ‹ back
         * button and the page title on the left, a close × on the right,
         * same .tool pill styling as the rest of the toolbar; every regular
         * tool hides while a sub-page is up (body.settings-page). */
        showSettingsPageBar(title) {
            const bar = document.getElementById('settingsPageBar');
            bar.replaceChildren();
            const back = document.createElement('button');
            back.className = 'tool';
            back.textContent = '‹';
            back.setAttribute('aria-label', '返回设置首页');
            back.addEventListener('click', () => {
                this.settingsPage = null;
                this.renderSettingsPanel();
            });
            const label = document.createElement('span');
            label.className = 'page-title';
            label.textContent = title;
            const close = document.createElement('button');
            close.className = 'tool';
            close.textContent = '×';
            close.setAttribute('aria-label', '收起设置');
            close.addEventListener('click', () => this.closeSettingsPanel());
            bar.append(back, label, close);
            bar.hidden = false;
            document.body.classList.add('settings-page');
        }

        hideSettingsPageBar() {
            const bar = document.getElementById('settingsPageBar');
            if (bar) bar.hidden = true;
            document.body.classList.remove('settings-page');
        }

        renderSettingsHome(panel) {
            const addRow = label => {
                const row = document.createElement('div');
                row.className = 'set-row';
                const name = document.createElement('span');
                name.className = 'set-label';
                name.textContent = label;
                row.append(name);
                panel.append(row);
                return row;
            };
            const addOptions = (row, entries, current, onPick) => {
                const opts = document.createElement('div');
                opts.className = 'set-opts';
                entries.forEach(([value, text]) => {
                    const opt = document.createElement('button');
                    opt.className = 'set-opt' + (value === current ? ' active' : '');
                    opt.textContent = text;
                    if (onPick) {
                        opt.addEventListener('click', () => {
                            onPick(value);
                            this.renderSettingsPanel();
                        });
                    } else {
                        opt.disabled = true;
                    }
                    opts.append(opt);
                });
                row.append(opts);
            };
            const addNav = (label, value, page) => {
                const row = addRow(label);
                const nav = document.createElement('button');
                nav.className = 'set-opt set-nav';
                nav.textContent = `${value} ›`;
                nav.addEventListener('click', () => {
                    this.settingsPage = page;
                    this.renderSettingsPanel();
                });
                row.append(nav);
            };

            const themeRow = addRow('色彩模式');
            const theme = (() => {
                try { return localStorage.getItem('felime_theme') || 'auto'; } catch (_) { return 'auto'; }
            })();
            addOptions(themeRow, [
                ['auto', '跟随系统'], ['light', '浅色'], ['dark', '深色'],
            ], theme, value => {
                try { localStorage.setItem('felime_theme', value); } catch (_) {}
                applyTheme();
            });

            const speedRow = addRow('滑动跟手');
            addOptions(speedRow, [
                [1, '1x'], [2, '2x'], [3, '3x'], [4, '4x'], [5, '5x'],
            ], this.scrubSpeed, value => {
                this.scrubSpeed = value;
                try { localStorage.setItem('felime_scrub_speed', String(value)); } catch (_) {}
            });

            // Batch 10: only 自然码 exists; batch 11 moved its key map to a
            // sub-page rendered as an actual key layout (#6).
            addNav('双拼键位', '自然码', 'schema');
            // Batch 10: quick switch supports ANY two keyboards. Batch 15:
            // the long-press menu list is a SEPARATE setting - not everyone
            // wants fr/ru/ja and both Chinese modes listed there.
            addNav('快捷切换', this.quickPair.map(m => MODES[m].label).join(' / '), 'pair');
            addNav('长按菜单', `${this.menuModes().length} 个键盘`, 'menu');
            // 批次 21 #7: the custom table is pasted JSON now.
            addNav('定制键盘', this.customKeys() ? '已定制' : '未定制', 'custom');
            // Batch 16 (#8): drag the keyboard's top edge to resize; the
            // height saves per orientation (a nav to an editor, not a page).
            const heightRow = addRow('键盘高度');
            const heightNav = document.createElement('button');
            heightNav.className = 'set-opt set-nav';
            heightNav.textContent = '调节 ›';
            heightNav.addEventListener('click', () => this.enterHeightEdit());
            heightRow.append(heightNav);
            // Batch 14 (#5): no favorites entry here - the panel toolbar icon
            // is the entry, and quick settings reads cleaner with three rows.
        }

        /** 自然码键位图（批 12 #6，批 15 重排，批 16 #4/#6 再调）：说明统一
         * 在示意图上方；每行独立居中（不再用 shift/⌫ 占位格凑宽度）；
         * 双韵母键内上下两行；V 的前两个短 candidate 并排一行（ui ü）。 */
        renderSchemaPage(panel) {
            const map = document.createElement('div');
            map.id = 'schemaMap';
            const notes = document.createElement('div');
            notes.className = 'map-notes';
            const initials = document.createElement('div');
            initials.className = 'map-line';
            initials.textContent = '声母：zh=V ch=I sh=U，其余与拼音相同';
            const zero = document.createElement('div');
            zero.className = 'map-line';
            // 自然码零声母：直接打全拼（引擎 algebra 同时派生了 首字母+韵母键
            // 的 aa/al/aj 变体与搜狗式 o+韵母键，这里只宣传自然码主打法）。
            zero.textContent =
                '零声母（a/e 开头）：直接打全拼，如 啊=aa、爱=ai、安=an、恩=en、二=er';
            notes.append(initials, zero);
            map.append(notes);
            // [key, final1, final2|null, initial|null] - two finals stack
            // inside the key (batch 15). Batch 16 (#6): V's two short finals
            // share one line ("ui ü") so all rows stay one key tall.
            const rows = [
                [['q', 'iu'], ['w', 'ua', 'ia'], ['e', 'e'], ['r', 'uan', 'er'],
                 ['t', 'ue', 've'], ['y', 'ing', 'uai'], ['u', 'u', null, 'sh'],
                 ['i', 'i', null, 'ch'], ['o', 'o', 'uo'], ['p', 'un']],
                [['a', 'a'], ['s', 'ong', 'iong'], ['d', 'iang', 'uang'],
                 ['f', 'en'], ['g', 'eng'], ['h', 'ang'], ['j', 'an'],
                 ['k', 'ao'], ['l', 'ai']],
                [['z', 'ei'], ['x', 'ie'], ['c', 'iao'], ['v', 'ui ü', null, 'zh'],
                 ['b', 'ou'], ['n', 'in'], ['m', 'ian']],
            ];
            rows.forEach(cells => {
                const row = document.createElement('div');
                row.className = 'kmap-row';
                cells.forEach(([key, f1, f2, initial]) => {
                    const cell = document.createElement('div');
                    cell.className = 'kmap-key';
                    const cap = document.createElement('b');
                    cap.textContent = key.toUpperCase();
                    cell.append(cap);
                    const fin = document.createElement('span');
                    fin.textContent = f1;
                    cell.append(fin);
                    if (f2) {
                        const fin2 = document.createElement('span');
                        fin2.textContent = f2;
                        cell.append(fin2);
                    }
                    if (initial) {
                        const ini = document.createElement('i');
                        ini.textContent = initial;
                        cell.append(ini);
                    }
                    row.append(cell);
                });
                map.append(row);
            });
            panel.append(map);
        }

        /** 批次 21 #7: the custom table is PASTED JSON now - one editor for
         * the whole table (validation errors are shown, never swallowed),
         * plus a template button for a quick start. */
        renderCustomPage(panel) {
            const box = document.createElement('div');
            box.className = 'custom-editor';
            const hint = document.createElement('div');
            hint.className = 'pair-hint';
            hint.textContent =
                '粘贴 JSON 定义符号键盘（最多 3 行，每行键数不限）：t=键面，' +
                'tap=单击行为（文本 / [esc] 单键 / [ctrl+s] 组合，可混排，如 [esc]ggVGD），' +
                'note=长按说明。超宽的行可以左右拖动查看。';
            box.append(hint);
            const status = document.createElement('div');
            status.className = 'set-row';
            const label = document.createElement('span');
            label.className = 'set-label';
            label.textContent = '当前状态';
            const preview = document.createElement('span');
            preview.className = 'custom-preview';
            const rows = this.customKeys();
            preview.textContent = rows
                ? `已定制 ${rows.reduce((sum, row) => sum + (row || []).length, 0)} 个键`
                : '未定制';
            status.append(label, preview);
            const actions = document.createElement('div');
            actions.className = 'custom-actions';
            const edit = document.createElement('button');
            edit.className = 'set-opt set-nav';
            edit.textContent = '粘贴 JSON ›';
            edit.setAttribute('aria-label', '粘贴 JSON 定制键盘');
            edit.addEventListener('click', () => this.openCustomJsonEditor());
            const template = document.createElement('button');
            template.className = 'set-opt set-nav';
            template.textContent = '插入模板 ›';
            template.setAttribute('aria-label', '插入定制模板');
            template.addEventListener('click', () => this.openCustomJsonEditor(CUSTOM_TEMPLATE));
            actions.append(edit, template);
            box.append(status, actions);
            panel.append(box);
        }

        /** 批次 21 #7: edit the whole custom table as JSON in the shared
         * editor strip (textarea; system paste works there). */
        openCustomJsonEditor(prefill = null) {
            this.editorReturn = 'custom';
            this.customEditRow = null;
            const current = this.customKeys();
            const rows = current || [[], [], []];
            const editor = document.getElementById('panelEditor');
            const input = document.getElementById('panelEditorInput');
            const area = document.getElementById('panelEditorArea');
            input.hidden = true;
            area.hidden = false;
            area.value = prefill != null ? prefill
                : JSON.stringify({ version: 1, rows }, null, 2);
            area.placeholder = '粘贴定制 JSON';
            this.editorMode = 'custom-json';
            this.closeSettingsPanel();
            document.getElementById('symbolLayer').hidden = true;
            document.getElementById('qwertyLayer').hidden = false;
            document.body.classList.add('editing');
            editor.hidden = false;
            this.setPanelInput(true);
            area.focus();
        }

        /** 批次 21 #7: validate + persist the pasted JSON. Errors keep the
         * editor open and name the first problem - nothing is truncated
         * silently. */
        saveCustomJson(text) {
            const parsed = this.parseCustomKeys(text);
            if (parsed.error) {
                this.showToast(parsed.error);
                return;
            }
            try {
                localStorage.setItem(CUSTOM_KEYS_STORE,
                    JSON.stringify({ version: 1, rows: parsed.rows }));
            } catch (_) {
                this.showToast('保存失败：本地存储不可用');
                return;
            }
            this.editorMode = null;
            // The symbol strip's 定制 tab exists only once the table has
            // content - refresh it wherever we are (showSymbols re-runs this
            // anyway before the layer is next shown).
            this.renderSymbolCats();
            this.closePanelEditor();
            this.showToast(`已保存 ${parsed.rows.reduce((sum, row) => sum + row.length, 0)} 个键`);
        }

        /** Quick-switch sub-page (batch 15): pick EXACTLY the two keyboards
         * the toggle key flips between - tick first, then the name. The
         * long-press list is a separate setting (renderMenuEditor). */
        renderPairEditor(panel) {
            const box = document.createElement('div');
            box.id = 'pairEditor';
            const hint = document.createElement('div');
            hint.className = 'pair-hint';
            hint.textContent =
                '勾选两项作为切换键的快捷切换对（点已勾选项无效果，点未勾选项会替换最早勾选的一项）';
            box.append(hint);
            this.orderedModeNames().forEach(name => {
                const row = document.createElement('div');
                row.className = 'pair-row';
                row.dataset.mode = name;
                const tick = document.createElement('button');
                const on = this.quickPair.includes(name);
                tick.className = 'pair-tick' + (on ? ' on' : '');
                tick.textContent = on ? '✓' : '';
                tick.setAttribute('aria-label', `快捷切换 ${MODES[name].title}`);
                const label = document.createElement('span');
                label.className = 'pair-name';
                label.textContent = MODES[name].title;
                tick.addEventListener('click', () => {
                    // Exactly two stay ticked: the pair must never drop to
                    // one (the toggle shorthand would lie), so an un-tick is
                    // a no-op and a new tick replaces the oldest member.
                    if (this.quickPair.includes(name)) return;
                    this.quickPair.push(name);
                    if (this.quickPair.length > 2) this.quickPair.shift();
                    try { localStorage.setItem('felime_quick_pair', JSON.stringify(this.quickPair)); } catch (_) {}
                    this.updateToggleLabels();
                    box.querySelectorAll('.pair-row').forEach(el => {
                        const active = this.quickPair.includes(el.dataset.mode);
                        const t = el.querySelector('.pair-tick');
                        t.classList.toggle('on', active);
                        t.textContent = active ? '✓' : '';
                    });
                });
                row.append(tick, label);
                box.append(row);
            });
            panel.append(box);
        }

        /** Long-press menu sub-page (batch 15): tick WHICH keyboards appear
         * in the toggle's long-press menu (default: all; at least one stays),
         * and drag to reorder that menu. Tick first, drag handle last. */
        renderMenuEditor(panel) {
            const box = document.createElement('div');
            box.id = 'menuEditor';
            const hint = document.createElement('div');
            hint.className = 'pair-hint';
            hint.textContent = '勾选长按切换键时列出的键盘 · 拖动排序（至少保留一个）';
            box.append(hint);
            const enabled = this.menuModes();
            this.orderedModeNames().forEach(name => {
                const row = document.createElement('div');
                row.className = 'pair-row';
                row.dataset.mode = name;
                const tick = document.createElement('button');
                const on = enabled.includes(name);
                tick.className = 'pair-tick' + (on ? ' on' : '');
                tick.textContent = on ? '✓' : '';
                tick.setAttribute('aria-label', `长按菜单显示 ${MODES[name].title}`);
                const label = document.createElement('span');
                label.className = 'pair-name';
                label.textContent = MODES[name].title;
                const handle = document.createElement('span');
                handle.className = 'pair-drag';
                handle.textContent = '≡';
                handle.setAttribute('aria-label', '拖动排序');
                tick.addEventListener('click', () => {
                    // At least one keyboard stays listed: dropping the last
                    // tick is ignored (an empty menu would brick the picker).
                    const current = this.menuModes();
                    if (current.includes(name) && current.length <= 1) return;
                    const next = current.includes(name)
                        ? current.filter(m => m !== name)
                        : [...current, name];
                    try {
                        localStorage.setItem('felime_menu_modes', JSON.stringify(next));
                    } catch (_) {}
                    tick.classList.toggle('on', next.includes(name));
                    tick.textContent = next.includes(name) ? '✓' : '';
                });
                row.append(tick, label, handle);
                box.append(row);
                this.bindListDrag(row, box, '.pair-row', 'mode', order => {
                    try { localStorage.setItem('felime_mode_order', JSON.stringify(order)); } catch (_) {}
                });
            });
            panel.append(box);
        }

        /** Minimal in-flow touch drag (batch 10 pairs, batch 11 phrases):
         * while the finger holds a row handle the row swaps with whatever
         * sibling it crosses; onDrop receives the resulting row-id order. */
        bindListDrag(row, box, rowSelector, idAttr, onDrop) {
            const handle = row.querySelector('.pair-drag');
            handle.addEventListener('touchstart', event => {
                event.preventDefault();
                event.stopPropagation();
                row.classList.add('dragging');
                const move = ev => {
                    ev.preventDefault();
                    const y = ev.touches[0].clientY;
                    for (const other of box.querySelectorAll(rowSelector)) {
                        if (other === row) continue;
                        const r = other.getBoundingClientRect();
                        if (y >= r.top && y <= r.bottom) {
                            if (y > r.top + r.height / 2 && row.nextElementSibling !== other) {
                                box.insertBefore(row, other.nextElementSibling);
                            } else if (y <= r.top + r.height / 2 && row.previousElementSibling !== other) {
                                box.insertBefore(row, other);
                            }
                            break;
                        }
                    }
                };
                const up = () => {
                    row.classList.remove('dragging');
                    handle.removeEventListener('touchmove', move);
                    handle.removeEventListener('touchend', up);
                    handle.removeEventListener('touchcancel', up);
                    onDrop([...box.querySelectorAll(rowSelector)].map(el => el.dataset[idAttr]));
                };
                handle.addEventListener('touchmove', move, { passive: false });
                handle.addEventListener('touchend', up);
                handle.addEventListener('touchcancel', up);
            }, { passive: false });
        }


        /** Saved drag order (batch 10) applied to the long-press menu. */
        modeOrder() {
            const ordered = this.orderedModeNames();
            // Batch 15: the long-press menu shows ONLY the keyboards the user
            // enabled (default: all) - not everyone wants fr/ru/ja there.
            // The quick toggle always reaches the pair regardless.
            let menu = null;
            try { menu = JSON.parse(localStorage.getItem('felime_menu_modes') || 'null'); } catch (_) {}
            if (Array.isArray(menu)) {
                const filtered = ordered.filter(name => menu.includes(name));
                if (filtered.length) return filtered;
            }
            return ordered;
        }

        /** Every known keyboard in the saved drag order (unfiltered). */
        orderedModeNames() {
            let saved = null;
            try { saved = JSON.parse(localStorage.getItem('felime_mode_order') || 'null'); } catch (_) {}
            const names = Object.keys(MODES);
            if (Array.isArray(saved)) {
                const clean = saved.filter(n => MODES[n]);
                names.forEach(n => { if (!clean.includes(n)) clean.push(n); });
                return clean;
            }
            return names;
        }

        /** Batch 15: which keyboards the long-press menu lists. Null = all. */
        menuModes() {
            let menu = null;
            try { menu = JSON.parse(localStorage.getItem('felime_menu_modes') || 'null'); } catch (_) {}
            const filtered = Array.isArray(menu) && menu.length
                ? this.orderedModeNames().filter(name => menu.includes(name))
                : null;
            // Unknown ids only would resolve to nothing - fall back to all.
            return filtered && filtered.length
                ? filtered
                : this.orderedModeNames();
        }

        /* ===== candidates ===== */

        clearComposing() {
            this.call(() => Native.clearComposing(this.token));
            // Optimistic local restore: the engine event roundtrip also clears
            // composing, but the toolbar must not lag a roundtrip behind the
            // tap. If the bridge rejects (stale token), the next engine event
            // repaints the true state anyway.
            this.composing = false;
            this.updateComposing({ composing: false }, '');
            if (this.expanded) this.setExpanded(false);
        }

        setExpanded(expanded) {
            this.expanded = expanded;
            document.body.classList.toggle('expanded', expanded);
            document.getElementById('expandLayer').hidden = !expanded;
            if (expanded) {
                // Pin the accumulation to the current composition; picking ˅
                // again after a collapse keeps the already-fetched candidates.
                const key = this.lastRawInput || '';
                if (key !== this.expandKey) {
                    this.expandKey = key;
                    this.expandCandidates = [];
                    // Batch 11 (#5): a NEW composition must not inherit the
                    // previous parse's variant list - the anchor pin exists
                    // for in-place variant switches (finishVariantReplay),
                    // not across compositions. xi'j opened after an x'an
                    // session would otherwise show x'an's sixteen variants.
                    this.variantAnchor = null;
                }
                this.loadingMore = false;
                this.accumulateCandidates(this.lastEngineState || {});
                // Batch 13: onEngineState now owns expandKey even while the
                // layer is closed, so a key-diff here no longer detects the
                // first open - repaint the grid (and the shared bar) on
                // every open from the pool we already hold.
                this.renderExpanded();
                this.renderCandidates(this.lastEngineState || {});
                // A strip shorter than the viewport can never be scrolled, so
                // preload the next page right away (until it overflows).
                this.maybeLoadMoreCandidates();
            } else {
                // Collapse keeps the fetched pages; a NEW composition clears
                // them (onEngineState resets expandKey) and so does clearing
                // the composition entirely.
                document.getElementById('expandPreedit').textContent = '';
            }
        }

        /** Merge one engine state's candidates into the strip (dedupe by id). */
        accumulateCandidates(state) {
            const known = new Set(this.expandCandidates.map(candidate => candidate.id));
            (state.candidates || []).forEach(candidate => {
                if (!known.has(candidate.id)) this.expandCandidates.push(candidate);
            });
            this.expandHasNext = !!state.hasNextPage;
            this.loadingMore = false;
        }

        /** Fresh composition: the filter tab returns to 词频 (word freq). */
        resetExpandTab() {
            this.expandTab = 'freq';
            document.querySelectorAll('[data-expand-tab]').forEach(el => (
                el.classList.toggle('active', el.dataset.expandTab === 'freq')));
        }

        renderExpanded() {
            const strip = document.getElementById('expandGrid');
            document.getElementById('expandPreedit').textContent = this.lastRawInput || '';
            strip.replaceChildren();
            this.expandRendered = 0;
            this.renderVariants();
            this.appendExpandedCandidates();
        }

        /** Batch 6 parse variants (double pinyin): every way to read the raw
         * keys as exact syllables or first-key abbreviations. The first entry
         * is the raw input itself; a single-key first segment expands into
         * every syllable that starts with it (xi'an, xy'an, xr'an ...), and
         * longer inputs offer syllable-boundary prefixes (vf, vf'x ...) whose
         * trailing segment the engine completes via its abbreviations. */
        expandVariantsFor(rawInput) {
            // The engine echo interleaves display-only spaces at segment
            // boundaries ('x an''); the variant space speaks pure key codes.
            const raw = (rawInput || '').replace(/ /g, '');
            if (!raw || this.mode !== 'double-pinyin') return [];
            const variants = [];
            const seen = new Set([raw]);
            const push = keys => {
                if (!seen.has(keys)) { seen.add(keys); variants.push(keys); }
            };
            const segments = raw.split("'");
            const first = segments[0];
            if (first.length === 1 && segments.length > 1) {
                const finals = DP_INITIAL_FINALS[first[0]] || '';
                for (const final of finals) {
                    const keys = first + final + "'" + segments.slice(1).join("'");
                    // Only exact double-key syllables - the expansion exists
                    // to pin one exact parse, not to re-abbreviate.
                    push(keys);
                }
            }
            // Batch 11 (#5): every segment a complete 2-key syllable means the
            // user TYPED the full parse (xi'an) - it pins itself and the
            // column must not offer prefix re-reads (xi). The expansion only
            // disambiguates single-key abbreviations (x'an, vf'x).
            if (segments.some(seg => seg.length === 1)) {
                const joined = raw.replace(/'/g, '');
                for (let cut = 2; cut <= joined.length - 2; cut += 2) {
                    push(joined.slice(0, cut));
                }
            }
            return variants;
        }

        renderVariants() {
            const column = document.getElementById('expandVariants');
            column.replaceChildren();
            const raw = (this.lastRawInput || '').replace(/ /g, '');
            // Pin the list to the parse the area was opened with: switching
            // to xc'an moves the highlight but keeps x'an/xd'an/xi'an/...
            // listed (a list rebuilt from the new raw would shrink to its
            // own two entries).
            if (!this.variantAnchor) this.variantAnchor = raw;
            const anchor = this.variantAnchor;
            const variants = this.expandVariantsFor(anchor);
            column.hidden = variants.length === 0;
            const makeButton = keys => {
                const button = document.createElement('button');
                button.className = 'expand-variant' + (keys === raw ? ' current' : '');
                button.textContent = keys;
                button.addEventListener('click', () => this.switchToVariant(keys));
                column.append(button);
            };
            if (variants.length) {
                makeButton(anchor);
                variants.forEach(makeButton);
            }
        }

        /** Switch to a parse variant IN PLACE: the variant highlights at once
         * and the right-hand grid swaps to that parse's candidates. The
         * rewind+retype happens inside one native call, so there is no
         * visible delete-and-retype and the layer never collapses. The grid
         * keeps the previous parse's candidates until the target echo lands
         * (variantReplaying suppresses intermediate re-renders). */
        switchToVariant(keys) {
            if (this.variantReplaying) return;
            this.variantReplaying = true;
            this.variantTarget = keys;
            // Optimistic highlight; the list itself stays put. The grid dims
            // and refuses taps while its ids still belong to the previous
            // parse (batch 9: taps during the swap used to be lost).
            document.querySelectorAll('#expandVariants .expand-variant').forEach(el => {
                el.classList.toggle('current', el.textContent === keys);
            });
            document.getElementById('expandGrid').classList.add('reloading');
            document.getElementById('expandPreedit').textContent = keys;
            if (typeof Native.setComposition === 'function') {
                this.call(() => Native.setComposition(keys, this.token));
            } else {
                // Older native bridge: fall back to per-key replay.
                const previous = (this.lastRawInput || '').replace(/ /g, '');
                const replay = [];
                for (let i = 0; i < previous.length; i++) replay.push('<backspace>');
                for (const ch of keys) replay.push(ch);
                const step = () => {
                    if (!replay.length) return;
                    const next = replay.shift();
                    if (next === '<backspace>') {
                        this.call(() => Native.backspace(this.token));
                    } else {
                        this.call(() => Native.key(next, this.token));
                    }
                    setTimeout(step, 45);
                };
                step();
            }
            // Safety valve: the guard normally lifts on the echo carrying the
            // target composition. If that echo never arrives (bridge failure)
            // the layer must not stay frozen forever.
            setTimeout(() => {
                if (this.variantReplaying) this.finishVariantReplay();
            }, 1500);
        }

        /** Lift the replay guard once the target composition's echo has
         * landed (safety valve: 1500ms without it), then refresh the grid
         * on the chosen parse. The refresh must NOT go through the
         * expandKey reset - that path clears the variant anchor and would
         * shrink the list to the new parse's own expansions. */
        finishVariantReplay() {
            this.variantReplaying = false;
            this.variantTarget = null;
            document.getElementById('expandGrid').classList.remove('reloading');
            if (!this.expanded) {
                this.setExpanded(true);
                return;
            }
            const state = this.lastEngineState || {};
            const rawEcho = state.rawInput || state.composing || '';
            // onEngineEvent order: the target-echo detection above runs before
            // updateComposing, so lastRawInput still holds the old parse here.
            if (rawEcho) this.lastRawInput = rawEcho;
            this.expandKey = rawEcho;
            this.expandCandidates = [];
            this.accumulateCandidates(state);
            this.renderExpanded();
            // Batch 13: the bar shares the pool - the safety-valve path
            // (timeout without the target echo) must not strand it on the
            // previous parse's candidates.
            this.renderCandidates(state);
        }

        /** Incremental strip append (P1-2): replacing the whole strip would
         * collapse scrollWidth and clamp scrollLeft back to 0 on every page
         * fetch - the endless drag would snap to the left each time. The
         * batch-6 filter tab re-renders fully (renderExpanded); appends stay
         * incremental for the same tab. */
        appendExpandedCandidates() {
            const strip = document.getElementById('expandGrid');
            const visible = this.expandCandidates.filter(candidate =>
                this.expandTab !== 'single' || [...candidate.text].length === 1);
            visible.forEach((candidate, index) => {
                if (index < this.expandRendered) return;
                const button = document.createElement('button');
                button.className = index === 0 ? 'expand-candidate first' : 'expand-candidate';
                button.textContent = candidate.text;
                // Native clicks only: bindTouch preventDefaults touchstart,
                // which cancels the strip's pan (review batch5 P1).
                let longPressed = false;
                button.addEventListener('click', () => {
                    if (longPressed) { longPressed = false; return; }
                    this.call(revision => Native.chooseCandidate(revision, candidate.id, this.token));
                });
                this.bindCandidateLongPress(button, candidate, () => { longPressed = true; });
                // Same mousedown guard as the bar (review batch14 P1): the
                // expanded grid is reachable while the phrase editor is open.
                button.addEventListener('mousedown', event => event.preventDefault());
                strip.append(button);
                this.expandRendered += 1;
            });
            // Empty state lives here so a reset->append sequence can never
            // strand a stale placeholder next to freshly added candidates.
            if (!visible.length) {
                if (!strip.querySelector('.expand-empty')) {
                    const empty = document.createElement('div');
                    empty.className = 'expand-empty';
                    empty.textContent = this.expandTab === 'single' ? '暂无单字' : '暂无候选';
                    strip.append(empty);
                }
            } else {
                const empty = strip.querySelector('.expand-empty');
                if (empty) empty.remove();
            }
        }

        /** Fetch the next page when the visible candidate surface is scrolled
         * near its end (or too short to scroll at all). Batch 13: the bar
         * (horizontal) joins the expanded grid (vertical) - whichever is on
         * screen drives the shared native cursor. */
        maybeLoadMoreCandidates() {
            if (!this.expandHasNext || this.loadingMore) return;
            const strip = document.getElementById('expandGrid');
            const bar = document.getElementById('candidates');
            let nearEnd = false;
            let tooShort = false;
            if (this.expanded) {
                if (!strip.children.length) return;
                const top = strip.scrollTop || 0;
                const height = strip.clientHeight || 0;
                const total = strip.scrollHeight || 0;
                nearEnd = top + height >= total - 120;
                tooShort = total <= height;
            } else {
                const left = bar.scrollLeft || 0;
                const width = bar.clientWidth || 0;
                const total = bar.scrollWidth || 0;
                if (!total) return;
                nearEnd = left + width >= total - 120;
                tooShort = total <= width;
            }
            if (!nearEnd && !tooShort) return;
            this.loadingMore = true;
            this.call(revision => Native.pageNext(revision, this.token));
            // A rejected fetch (stale token/stamp) emits no engine event and
            // would leave loadingMore stuck true - rearm after a beat.
            setTimeout(() => { this.loadingMore = false; }, 900);
        }

        renderCandidates(state) {
            // Variant replay bursts intermediate events: freeze the bar like
            // the grid (the replay's target echo repaints it).
            if (this.variantReplaying) return;
            const bar = document.getElementById('candidates');
            // Full repaints would clamp scrollLeft back to 0 mid-drag - the
            // exact bar-side version of the grid bug appendExpandedCandidates
            // exists for. Hold and restore across the rebuild.
            const held = bar.scrollLeft || 0;
            bar.replaceChildren();
            // Batch 13: the bar renders the WHOLE accumulated pool (same pool
            // the expanded grid scrolls) - native paging must not cap it at
            // one page, and swiping the bar reveals the rest. The first pool
            // entry keeps the highlighted pill.
            (this.expandCandidates || []).forEach((candidate, index) => {
                const button = document.createElement('button');
                button.className = index === 0 ? 'candidate first' : 'candidate';
                button.textContent = candidate.text;
                let longPressed = false;
                button.addEventListener('click', () => {
                    // 批次 19 #8: a long-press opens the delete menu; the
                    // release would otherwise also fire the pick (same guard
                    // as the favorites rows).
                    if (longPressed) { longPressed = false; return; }
                    this.call(revision => Native.chooseCandidate(revision, candidate.id, this.token));
                });
                this.bindCandidateLongPress(button, candidate, () => { longPressed = true; });
                // Batch 12: native clicks only - bindTouch preventDefaults the
                // touchstart, which is exactly what cancels the bar's native
                // horizontal pan (the strip must stay swipeable).
                // Review batch14 P1: a mousedown's default focus move would
                // blur the phrase editor input mid-pick (the redirect then
                // lands the word in the host editor); suppressing it keeps
                // the tap a pure click without touching the pan.
                button.addEventListener('mousedown', event => event.preventDefault());
                bar.append(button);
            });
            // Batch 15 (#6): the ‹ › pager buttons are gone - the bar shows
            // the whole accumulated pool and swiping past the end auto-fetches
            // the next page (maybeLoadMoreCandidates).
            bar.scrollLeft = held;
            // A pool shorter than the bar can never be scrolled, so keep
            // pulling pages until the strip overflows (endless drag ready).
            if (!this.expanded) this.maybeLoadMoreCandidates();
        }

        /** M4 composing chrome: preedit line, toolbar swap, enter label. */
        updateComposing(state, rawInput) {
            // Variant replay fires a burst of intermediate engine events
            // (rewind passes through the empty composition); the UI must
            // stay frozen on the target parse until the replay settles.
            if (this.variantReplaying) return;
            const wasComposing = this.composing;
            this.composing = !!state.composing;
            if (this.composing && rawInput !== undefined) this.lastRawInput = rawInput;
            document.body.classList.toggle('composing', this.composing);
            const preedit = document.getElementById('preeditLine');
            preedit.textContent = this.composing ? this.lastRawInput : '';
            const recording = this.voiceState !== 'idle';
            // Composing hides the setup/mode/clipboard tools but never the mic
            // while a voice session is active (the stop entry must survive).
            document.getElementById('setupButton').hidden = this.composing;
            document.getElementById('clipboardButton').hidden = this.composing;
            document.getElementById('favoritesButton').hidden = this.composing;
            // Batch 16: the new control/IME tools follow the same rule.
            // Batch 18 #1: a composition started mid-control-view only
            // SUSPENDS the rows (switch stays on) - picking a candidate
            // brings them back via maybeResumeCtrlView below.
            document.getElementById('ctrlTool').hidden = this.composing;
            document.getElementById('imeSwitchButton').hidden = this.composing;
            if (this.composing && this.ctrlView) this.suspendCtrlView();
            else if (!this.composing) this.maybeResumeCtrlView();
            // Batch 11: keep the quick panel open while the user is typing
            // INTO it (phrase manager input) - the candidate bar sits above
            // the panel (top 44px) so the two coexist; anywhere else a
            // composition closes the panel as before.
            if (this.composing && !this.settingsInputFocus) this.closeSettingsPanel();
            // Compose controls exist only while there is something to clear.
            // A live voice session hides them too: the × must not clear the
            // ASR partial that shares the editor span (review batch4 P2).
            const voiceBusy = recording;
            document.getElementById('composeClear').hidden = !this.composing || voiceBusy;
            document.getElementById('composeExpand').hidden = !this.composing || voiceBusy;
            if (!this.composing && this.expanded && !this.variantReplaying) this.setExpanded(false);
            const mic = document.getElementById('mic');
            mic.hidden = this.composing && !recording;
            // Batch 5: while composing the right side carries exactly two
            // buttons (× and ˅). The keyboard-dismiss chevron looks identical
            // to the expand arrow - hide it until the composition ends.
            document.getElementById('hide').hidden = this.composing;
            // Collapse overlays only on the idle→composing transition, so a
            // stream of unrelated native events cannot close an open menu.
            // Batch 12 (#6): typing INTO a panel input (phrase add/edit) must
            // not close the panel under the user's fingers.
            if (this.composing && !wasComposing && !this.settingsInputFocus) {
                if (this.panelOpen) this.closePanel();
                this.closeModeMenu();
            }
            this.updateEnterLabel();
        }

        /* ===== clipboard / favorites panel ===== */

        /** Tear the shared editor strip down completely: hide it, drop the
         * editing key-height override, release the native redirect and clear
         * every routing flag (batch 15 review P2 - leaving any of these
         * dangling strands the UI in half-torn-down states). */
        clearEditorStrip() {
            const editor = document.getElementById('panelEditor');
            if (!editor.hidden) {
                editor.hidden = true;
                document.body.classList.remove('editing');
                if (this.settingsInputFocus) this.setPanelInput(false);
                this.panelEditItem = null;
            }
            this.customEditRow = null;
            this.editorReturn = null;
            this.editorMode = null;
        }

        openPanel(tab) {
            if (!this.ready) return;
            this.panelTab = tab === 'favorites' ? 'favorites' : 'clipboard';
            this.panelOpen = true;
            // Remember the layer to restore on close (panel can open from the
            // symbol layer too).
            this.panelReturnLayer = document.getElementById('symbolLayer').hidden ? 'letters' : 'symbols';
            this.closeModeMenu();
            // Batch 16: the control view never coexists with the panel.
            // Batch 18 #1: borrow, don't switch off - closing the panel
            // brings the rows back.
            if (this.ctrlView) this.suspendCtrlView();
            // Batch 11 review P2: the quick settings panel (z-index 30) would
            // sit above the panel layer and its gear is hidden with the
            // toolbar - close it or the user gets trapped.
            this.closeSettingsPanel();
            // Batch 13: leaving the editor (cancel path) or a tab switch must
            // tear the editor strip down before the list shows.
            // Batch 14 review P2 + batch 15 review P2: one teardown for
            // every flag and layer the strip owns.
            this.clearEditorStrip();
            this.closeItemMenu();
            // Batch 8: the panel REPLACES the toolbar row instead of adding
            // another line to the keyboard - its own head carries the tabs.
            document.getElementById('candidateBar').hidden = true;
            document.getElementById('qwertyLayer').hidden = true;
            document.getElementById('symbolLayer').hidden = true;
            document.getElementById('panelLayer').hidden = false;
            document.querySelectorAll('[data-panel-tab]').forEach(button => {
                button.classList.toggle('active', button.dataset.panelTab === this.panelTab);
            });
            document.getElementById('panelClear').hidden = this.panelTab !== 'clipboard';
            document.getElementById('panelManage').hidden = this.panelTab !== 'favorites';
            this.renderPanel();
            if (this.panelTab === 'clipboard') Native.getClipboard(this.token);
            else Native.getFavorites(this.token);
        }

        closePanel() {
            if (this.settingsInputFocus) this.setPanelInput(false);
            this.panelOpen = false;
            document.getElementById('panelLayer').hidden = true;
            document.getElementById('candidateBar').hidden = false;
            const toSymbols = this.panelReturnLayer === 'symbols';
            document.getElementById('symbolLayer').hidden = !toSymbols;
            document.getElementById('qwertyLayer').hidden = toSymbols;
            // Batch 18 #1: the panel only borrowed the bar from the ctrl
            // view - hand the rows back if the switch is still on.
            this.maybeResumeCtrlView();
        }

        renderPanel() {
            const list = document.getElementById('panelList');
            const empty = document.getElementById('panelEmpty');
            list.replaceChildren();
            const items = this.panelTab === 'clipboard' ? this.clipboardItems : this.favoriteItems;
            empty.hidden = items.length > 0;
            if (!items.length) {
                empty.textContent = this.panelTab === 'clipboard'
                    ? '剪贴板已开启，复制的内容将在这里显示'
                    : '暂无常用语，点右上角「＋添加」';
                return;
            }
            items.forEach(item => {
                const row = document.createElement(this.panelTab === 'favorites' ? 'div' : 'button');
                row.className = 'panel-item';
                row.dataset.itemId = item.id;
                const tooLong = [...item.text].length > MAX_COMMIT_CODE_POINTS;
                if (tooLong) row.classList.add('disabled');

                const commit = () => {
                    if (tooLong) return;
                    this.call(() => Native.commitText(item.text, this.token));
                    this.closePanel();
                };

                if (this.panelTab === 'clipboard') {
                    const preview = document.createElement('span');
                    preview.className = 'panel-text';
                    // Two-line clamp in CSS; the hard cut only marks over-long rows
                    // and uses code-point slicing so surrogate pairs stay intact.
                    preview.textContent = tooLong
                        ? Array.from(item.text).slice(0, 400).join('') + '…（内容过长）'
                        : item.text;
                    const remove = document.createElement('span');
                    remove.className = 'panel-remove';
                    remove.textContent = '×';
                    remove.setAttribute('aria-label', '删除');
                    remove.addEventListener('click', event => {
                        event.stopPropagation();
                        this.call(() => Native.removeClipboard(item.id, this.token));
                    });
                    row.append(preview, remove);
                    // Native clicks only: bindTouch's preventDefault would kill
                    // panel scrolling AND bubble a second row click on remove taps
                    // (review B2/M4).
                    row.addEventListener('click', commit);
                    list.append(row);
                    return;
                }

                // Batch 13 (#7): the row stays compact - drag handle, text,
                // and a ⋯ trigger. Pin/edit/delete live in the long-press
                // menu (⋯ tap = long press).
                const handle = document.createElement('span');
                handle.className = 'pair-drag';
                handle.textContent = '≡';
                const preview = document.createElement('span');
                preview.className = 'panel-text';
                preview.textContent = item.text;
                let longPressed = false;
                preview.addEventListener('click', () => {
                    if (longPressed) { longPressed = false; return; }
                    commit();
                });
                this.bindItemLongPress(preview, () => { longPressed = true; });
                const more = document.createElement('button');
                more.className = 'panel-more';
                more.textContent = '⋯';
                more.setAttribute('aria-label', '更多操作');
                more.addEventListener('click', () => this.openItemMenu(item, more));
                row.append(handle, preview, more);
                this.bindListDrag(row, list, '.panel-item', 'itemId', order => {
                    order.forEach((id, index) => {
                        if ((this.favoriteItems || [])[index]?.id !== id) {
                            this.call(() => Native.favoritesMove(id, index, this.token));
                        }
                    });
                });
                list.append(row);
            });
        }

        /** Batch 12 (#6): focus tracking for panel inputs, shared with the
         * native redirect - while active, editor writes come back through
         * onPanelCommit/onPanelDelete instead of the host editor. */
        setPanelInput(active) {
            this.settingsInputFocus = active;
            this.call(() => Native.panelInput(active, this.token));
        }

        /** The panel input the native redirect may write into. Strictly the
         * focused one: falling back to a hidden field would misfile text. */
        panelInputField() {
            const el = document.activeElement;
            if (el && el.classList && el.classList.contains('phrase-input')) return el;
            return null;
        }

        insertIntoPanelInput(text) {
            const field = this.panelInputField();
            if (!field) return;
            const start = field.selectionStart ?? field.value.length;
            const end = field.selectionEnd ?? start;
            field.value = field.value.slice(0, start) + text + field.value.slice(end);
            const caret = start + text.length;
            try { field.setSelectionRange(caret, caret); } catch (_) { /* fake DOM */ }
        }

        deleteFromPanelInput(count) {
            const field = this.panelInputField();
            if (!field) return;
            for (let i = 0; i < count; i++) {
                const start = field.selectionStart ?? field.value.length;
                const end = field.selectionEnd ?? start;
                if (start === end) {
                    if (start === 0) return;
                    field.value = field.value.slice(0, start - 1) + field.value.slice(end);
                    try { field.setSelectionRange(start - 1, start - 1); } catch (_) {}
                } else {
                    field.value = field.value.slice(0, start) + field.value.slice(end);
                    try { field.setSelectionRange(start, start); } catch (_) {}
                }
            }
        }

        onPanelCommit(payload) {
            const text = String((payload && payload.text) || '');
            if (text) this.insertIntoPanelInput(text);
        }

        onPanelDelete(payload) {
            this.deleteFromPanelInput(Number((payload && payload.count) || 1));
        }

        /** Batch 13 (#6), reworked batch 14 (#4): the editor strip sits above
         * the candidate bar (still selectable mid-edit) with the regular
         * keyboard below; body.editing compresses the key height to fit.
         * A panel input inside the scrolling list is unusable (it covers the
         * very keyboard that must type into it). */
        openPanelEditor(item) {
            this.panelEditItem = item || null;
            this.customEditRow = null;
            this.editorReturn = null;
            this.closeItemMenu();
            const editor = document.getElementById('panelEditor');
            const input = document.getElementById('panelEditorInput');
            const area = document.getElementById('panelEditorArea');
            // 批次 21 #7: the favorites flow uses the single-line input; the
            // textarea belongs to the custom-JSON editor.
            input.hidden = false;
            area.hidden = true;
            this.editorMode = null;
            input.value = item ? item.text : '';
            input.placeholder = item ? '编辑常用内容（最多 200 字）' : '输入常用内容（最多 200 字）';
            document.getElementById('panelLayer').hidden = true;
            // Batch 14 (#4): the bar STAYS (openPanel hid it) - picking a
            // candidate mid-edit is the whole point; the strip rides ABOVE it.
            document.getElementById('candidateBar').hidden = false;
            document.getElementById('symbolLayer').hidden = true;
            document.getElementById('qwertyLayer').hidden = false;
            document.body.classList.add('editing');
            editor.hidden = false;
            this.setPanelInput(true);
            input.focus();
        }

        closePanelEditor() {
            const editor = document.getElementById('panelEditor');
            const input = document.getElementById('panelEditorInput');
            const area = document.getElementById('panelEditorArea');
            input.value = '';
            input.hidden = false;
            area.value = '';
            area.hidden = true;
            this.editorMode = null;
            editor.hidden = true;
            document.body.classList.remove('editing');
            if (this.settingsInputFocus) this.setPanelInput(false);
            // Batch 15 (#7): custom-row edits return to their settings page
            // instead of the favorites panel.
            if (this.editorReturn === 'custom') {
                this.editorReturn = null;
                this.customEditRow = null;
                this.toggleSettingsPanel('custom');
                return;
            }
            this.openPanel('favorites');
        }

        savePanelEditor() {
            // 批次 21 #7: the textarea form edits the custom-keys JSON.
            if (this.editorMode === 'custom-json') {
                this.saveCustomJson(document.getElementById('panelEditorArea').value);
                return;
            }
            const input = document.getElementById('panelEditorInput');
            const text = input.value.trim();
            if (!text) return;
            if (this.panelEditItem) {
                const id = this.panelEditItem.id;
                if (text !== this.panelEditItem.text) {
                    this.call(() => Native.favoritesUpdate(id, text, this.token));
                }
            } else {
                this.call(() => Native.favoritesAdd(text, this.token));
            }
            this.panelEditItem = null;
            this.closePanelEditor();
        }

        /** Batch 13 (#7): pin/edit/delete ride a long-press menu (⋯ tap opens
         * the same one); rows only carry the drag handle, text and ⋯. */
        openItemMenu(item, anchor) {
            const menu = document.getElementById('itemMenu');
            this.closeItemMenu();
            this.itemMenuOpen = item.id;
            const build = (label, cls, action) => {
                const button = document.createElement('button');
                if (cls) button.className = cls;
                button.textContent = label;
                button.addEventListener('click', () => {
                    this.closeItemMenu();
                    action();
                });
                menu.append(button);
            };
            build('置顶', '', () =>
                this.call(() => Native.favoritesMove(item.id, 0, this.token)));
            build('编辑', '', () => this.openPanelEditor(item));
            build('删除', 'danger', () =>
                this.call(() => Native.removeFavorite(item.id, this.token)));
            menu.classList.add('open');
            // Clamp above the anchor row (rows sit in a scrollable list).
            const rect = anchor.getBoundingClientRect();
            menu.style.left = Math.max(4, Math.min(innerWidth - menu.offsetWidth - 4,
                rect.right - menu.offsetWidth)) + 'px';
            menu.style.top = Math.max(2, rect.top - menu.offsetHeight - 6) + 'px';
            this.syncOverlay();
        }

        closeItemMenu() {
            this.itemMenuOpen = null;
            const menu = document.getElementById('itemMenu');
            menu.classList.remove('open');
            menu.replaceChildren();
            this.syncOverlay();
        }

        /* ===== 批次 19 #8: 长按候选删除自造词 ===== */

        /** Long-press a candidate (bar or expanded grid). The candidates keep
         * native clicks (bindTouch would kill the bar's pan), so this is the
         * passive bindItemLongPress plus the click-suppress flag callback.
         * Only Chinese modes have a librime user lexicon to delete from. */
        bindCandidateLongPress(button, candidate, onLongPress) {
            if (!this.isChineseMode()) return;
            this.bindItemLongPress(button, () => {
                onLongPress();
                this.openCandidateMenu(candidate, button);
            });
        }

        /** 批次 20 #1: EVERY candidate is deletable now - the engine seeks to
         * the candidate's page and walks the librime highlight (selector's
         * Down = next candidate) onto it before Shift+Delete. Old APKs have
         * neither bridge method - no menu at all; 0.14.3 natives only know
         * the head-only variant, so off-head falls back to it there. */
        openCandidateMenu(candidate, anchor) {
            if (this.composing === false) return;
            const hasAny = typeof Native.deleteCandidate === 'function';
            const hasHeadOnly = typeof Native.deleteHighlightedCandidate === 'function';
            if (!hasAny && !hasHeadOnly) return;
            const head = (this.expandCandidates || [])[0];
            // With only the 0.14.3 method the non-head menu still opens -
            // it carries the disabled upgrade hint instead of a silent no-op.
            const deletable = hasAny ||
                (hasHeadOnly && !!head && head.id === candidate.id);
            const menu = document.getElementById('itemMenu');
            this.closeItemMenu();
            this.itemMenuOpen = 'candidate';
            const build = (label, cls, action) => {
                const button = document.createElement('button');
                if (cls) button.className = cls;
                button.textContent = label;
                button.addEventListener('click', () => {
                    this.closeItemMenu();
                    action();
                });
                menu.append(button);
            };
            if (deletable) {
                build('删除自造词', 'danger', () => this.confirmDeleteCandidate(candidate));
            } else {
                // Old native (0.14.3): only the head is deletable there.
                const hint = document.createElement('button');
                hint.disabled = true;
                hint.textContent = '该候选需升级 APK 后删除';
                menu.append(hint);
            }
            menu.classList.add('open');
            // The bar sits at the TOP of the keyboard: the menu must open
            // DOWNWARD (favorites rows open upward).
            menu.style.left = '0';
            menu.style.top = '0';
            const rect = anchor.getBoundingClientRect();
            const left = Math.max(4, Math.min(innerWidth - menu.offsetWidth - 4, rect.left));
            menu.style.left = left + 'px';
            menu.style.top = Math.min(innerHeight - menu.offsetHeight - 2, rect.bottom + 6) + 'px';
            this.syncOverlay();
        }

        confirmDeleteCandidate(candidate) {
            this.deleteTarget = candidate;
            document.getElementById('confirmText').textContent =
                `从自选词词库删除「${candidate.text}」？（固定词库的词删不掉）`;
            document.getElementById('confirmCard').hidden = false;
        }

        closeConfirmCard() {
            this.deleteTarget = null;
            document.getElementById('confirmCard').hidden = true;
        }

        deleteHighlightedCandidate() {
            const candidate = this.deleteTarget;
            this.closeConfirmCard();
            if (!candidate) return;
            // 批次 20 #1: prefer the any-candidate channel; 0.14.3 natives
            // only know the head-only variant (the head is the only thing
            // that was deletable there).
            const head = (this.expandCandidates || [])[0];
            const isHead = head && head.id === candidate.id;
            if (typeof Native.deleteCandidate === 'function') {
                this.pendingDelete = candidate;
                this.call(revision => Native.deleteCandidate(revision, candidate.id, this.token));
            } else if (isHead && typeof Native.deleteHighlightedCandidate === 'function') {
                this.pendingDelete = candidate;
                this.call(() => Native.deleteHighlightedCandidate(this.token));
            } else {
                return;
            }
            // The engine refreshes the candidates WITHOUT changing the
            // preedit, so the accumulated pool must be rebuilt on the echo
            // (accumulateCandidates only appends - the deleted word would
            // stay on the bar forever).
            setTimeout(() => { this.pendingDelete = null; }, 1500);
        }

        /** Long-press on a panel row text (no preventDefault: the list must
         * keep scrolling); a drag past a few px cancels the timer. */
        bindItemLongPress(el, onLongPress) {
            let timer = 0;
            let startX = 0;
            let startY = 0;
            el.addEventListener('touchstart', event => {
                const touch = event.touches[0];
                startX = touch.clientX;
                startY = touch.clientY;
                timer = setTimeout(() => {
                    timer = 0;
                    onLongPress();
                }, 380);
            }, { passive: true });
            el.addEventListener('touchmove', event => {
                if (!timer) return;
                const touch = event.touches[0];
                if (Math.hypot(touch.clientX - startX, touch.clientY - startY) > 12) {
                    clearTimeout(timer);
                    timer = 0;
                }
            }, { passive: true });
            const clear = () => {
                if (timer) clearTimeout(timer);
                timer = 0;
            };
            el.addEventListener('touchend', clear, { passive: true });
            el.addEventListener('touchcancel', clear, { passive: true });
        }

        onClipboard(payload) {
            this.clipboardItems = (payload.items || []).map(item => ({
                id: String(item.id), text: String(item.text), time: Number(item.time) || 0,
            }));
            if (this.panelOpen && this.panelTab === 'clipboard') this.renderPanel();
        }

        onFavorites(payload) {
            this.favoriteItems = (payload.items || []).map(item => ({
                id: String(item.id), text: String(item.text), time: Number(item.time) || 0,
            }));
            if (this.panelOpen && this.panelTab === 'favorites') this.renderPanel();
        }

        /* ===== native callbacks ===== */

        onBridgeHello(payload) {
            if (payload.nativeApiVersion < MIN_NATIVE_API) return;
            const provided = payload.capabilities || [];
            if (!REQUIRED_CAPABILITIES.every(cap => provided.includes(cap))) return;
            this.token = payload.pageGenerationToken;
            this.engineReady = payload.engineDataReady || {};
            // 批次 20 #6d: bottom gesture-nav inset (CSS px) - landscape
            // pads the keyboard above it (see applyHeight).
            this.safeBottom = Number(payload.safeBottom) || 0;
            // 批次 21 #9: the native float band above the keyboard - the
            // room every popup may float into (0 keeps everything inside
            // the IME view, e.g. the preview harness).
            this.floatBand = Number(payload.floatBand) || 0;
            {
                const root = document.documentElement;
                if (root && root.style && typeof root.style.setProperty === 'function') {
                    root.style.setProperty('--band', this.floatBand + 'px');
                }
            }
            // Batch 16 #8: native orientation wins over the resize heuristic.
            if (payload.orientation) {
                this.helloOrientation = payload.orientation;
                this.applyOrientation(payload.orientation === 'landscape');
            }
            // 批次 21 #9: the band shrinks the keyboard INSIDE an unchanged
            // viewport - no resize event fires and applyOrientation
            // early-returns on a same-orientation hello, so the row budget
            // must be recomputed here (a stale 60px budget overflowed the
            // rows out of the shorter view and broke every coordinate-based
            // device gesture).
            this.applyHeight();
            if (payload.theme === 'dark' || payload.theme === 'light') {
                systemTheme = payload.theme;
                systemThemeKnown = true;
                try { localStorage.setItem('felime_system_theme', payload.theme); } catch (_) {}
                applyTheme();
            }
            try {
                const stored = localStorage.getItem('felime_last_chinese_mode');
                if (stored === 'pinyin' || stored === 'double-pinyin') this.lastChineseMode = stored;
            } catch (_) {}
            const nextMode = MODES[payload.mode] ? payload.mode : 'direct';
            const modeChanged = nextMode !== this.mode;
            this.mode = nextMode;
            this.ready = true;
            if (modeChanged) this.renderMode();
            Native.keyboardReady(KEYBOARD_VERSION, MIN_NATIVE_API, JSON.stringify(REQUIRED_CAPABILITIES), this.token);
        }

        onEngineState(payload) {
            this.lastRevision = payload.revision || 0;
            this.lastEngineState = payload;
            // Replay completes when the echo carrying the target parse
            // arrives; the intermediate echoes (including the empty
            // composition) keep the auto-collapse suppressed until then.
            if (this.variantReplaying && payload.composing) {
                const raw = (payload.rawInput || payload.composing || '').replace(/ /g, '');
                if (this.variantTarget && raw === this.variantTarget) this.finishVariantReplay();
            }
            if (payload.mode && MODES[payload.mode] && payload.mode !== this.mode) {
                this.mode = payload.mode;
                this.renderMode();
            }
            this.updateComposing(payload, payload.rawInput || payload.composing || '');
            // Batch 13: ONE accumulated pool feeds both the candidate bar and
            // the expanded grid. Maintaining it before renderCandidates (and
            // regardless of expansion) is what lets the bar show every
            // candidate and keep its head after the grid collapses - the old
            // per-page bar is what stranded it on a low-frequency page.
            if (payload.composing) {
                const key = payload.rawInput || payload.composing || '';
                // Rewind bursts can emit a composing event with an EMPTY raw;
                // only a real (non-empty) new input resets the accumulation.
                if (key && key !== this.expandKey) {
                    this.expandKey = key;
                    this.expandCandidates = [];
                    this.resetExpandTab();
                    this.variantAnchor = null;
                    if (this.expanded) this.renderExpanded();
                }
                // 批次 19 #8: the echo after a delete keeps the preedit, so
                // the pool must be rebuilt by hand - accumulateCandidates only
                // appends. The deleted word vanishing from the fresh pool is
                // also the only honest success signal (librime deletes
                // silently; fixed-dictionary words are no-ops).
                if (this.pendingDelete) {
                    const gone = this.pendingDelete;
                    this.pendingDelete = null;
                    this.expandCandidates = [];
                    this.accumulateCandidates(payload);
                    // Review batch19 P1: the expanded grid renders
                    // incrementally (expandRendered watermark) - without a
                    // full re-render the deleted word's button would survive
                    // right under a "deleted" toast.
                    if (this.expanded) this.renderExpanded();
                    const stillThere = (this.expandCandidates || []).some(c => c.text === gone.text);
                    this.showToast(stillThere
                        ? `「${gone.text}」来自固定词库，无法删除`
                        : `已从自选词词库删除「${gone.text}」`);
                } else {
                    this.accumulateCandidates(payload);
                }
            } else if (!this.variantReplaying) {
                this.expandCandidates = [];
                this.expandKey = null;
                this.pendingDelete = null;
                if (!document.getElementById('confirmCard').hidden) this.closeConfirmCard();
            }
            this.renderCandidates(payload);
            // Intermediate replay events must not clear/rebuild the grid;
            // the target echo lifts the guard above and flows through.
            if (this.expanded && !this.variantReplaying) {
                if (payload.composing) {
                    // Incremental: a full replace would clamp scrollLeft to 0
                    // mid-drag (review batch5 P1).
                    this.appendExpandedCandidates();
                    this.maybeLoadMoreCandidates();
                } else {
                    this.setExpanded(false);
                }
            }
        }

        onNativeState(payload) {
            this.voiceState = payload.state || 'idle';
            const overlay = document.getElementById('voiceOverlay');
            const recording = ['listening', 'loading', 'stopping'].includes(this.voiceState);
            overlay.classList.toggle('open', recording);
            document.getElementById('voiceStatus').textContent =
                this.voiceState === 'listening' ? '正在聆听…'
                : this.voiceState === 'loading' ? '启动识别…'
                : this.voiceState === 'stopping' ? '结束识别…'
                : '';
            if (payload.message && this.voiceState === 'error') {
                document.getElementById('voiceStatus').textContent = payload.message;
            }
            document.getElementById('partialText').textContent = payload.partial || '';
            document.querySelector('#levelBar i').style.transform = `scaleX(${Math.max(0, Math.min(1, payload.level || 0))})`;
            const mic = document.getElementById('mic');
            // G2-B03: the mic is a fixed SVG icon; only classes/colours change.
            mic.className = 'tool' + (this.voiceState === 'idle' ? '' : ' ' + this.voiceState);
            const space = document.querySelector('#spaceKey');
            space?.classList.toggle('voice', this.voiceState !== 'idle');
            this.updateMicDisabled();
            // Recompute composing chrome: a voice session may start/stop while
            // composing, which changes whether the mic tool may stay hidden.
            this.updateComposing({ composing: this.composing });
            if (this.voiceState === 'error' && payload.message) this.showToast(payload.message);
        }

        onEditorInfo(payload) {
            this.editorSensitive = !!payload.sensitive;
            this.updateMicDisabled();
        }

        /** H4: mic disabled is the OR of editor sensitivity and stop-in-progress. */
        updateMicDisabled() {
            const mic = document.getElementById('mic');
            mic.disabled = this.editorSensitive || this.voiceState === 'stopping';
        }

        showToast(message) {
            const toast = document.getElementById('toast');
            toast.textContent = message;
            toast.classList.add('open');
            clearTimeout(this.toastTimer);
            this.toastTimer = setTimeout(() => toast.classList.remove('open'), 2600);
        }
    }

    const Native = window.FelimeNative || {
        keyboardReady: () => {},
        key: value => console.log('key', value),
        setComposition: keys => console.log('setComposition', keys),
        space: () => console.log('space'),
        backspace: () => console.log('backspace'),
        enter: () => console.log('enter'),
        moveCursor: delta => console.log('moveCursor', delta),
        keyEvent: (keyCode, metaState) => console.log('keyEvent', keyCode, metaState),
        chooseCandidate: (revision, id) => console.log('choose', revision, id),
        deleteHighlightedCandidate: () => console.log('deleteHighlightedCandidate'),
        deleteCandidate: (revision, id) => console.log('deleteCandidate', revision, id),
        pageNext: () => {},
        pagePrevious: () => {},
        selectMode: mode => console.log('mode', mode),
        startVoice: () => {},
        stopVoice: () => {},
        switchInputMethod: () => {},
        hideKeyboard: () => {},
        openSetup: () => {},
        reloadKeyboard: () => {},
        requestState: () => {},
        commitText: text => console.log('commitText', text),
        getClipboard: () => {},
        removeClipboard: id => console.log('removeClipboard', id),
        clearClipboard: () => {},
        getFavorites: () => {},
        removeFavorite: id => console.log('removeFavorite', id),
    };

    const THEMES = ['auto', 'light', 'dark'];
    const THEME_LABELS = { auto: '跟随系统', light: '浅色', dark: '深色' };
    // Last system theme seen over the bridge, persisted so the first paint of
    // a rebuilt WebView already matches the system (the CSS prefers-color-
    // scheme fallback stays active until the bridge has spoken once - review
    // F1: never paint a guessed theme over it).
    let systemTheme = 'light';
    let systemThemeKnown = false;
    try {
        const saved = localStorage.getItem('felime_system_theme');
        if (saved === 'dark' || saved === 'light') {
            systemTheme = saved;
            systemThemeKnown = true;
        }
    } catch (_) { /* storage unavailable */ }
    function applyTheme() {
        let theme = 'auto';
        try {
            theme = localStorage.getItem('felime_theme') || 'auto';
        } catch (_) { /* stay auto */ }
        const root = document.documentElement;
        if (theme !== 'auto') {
            root.className = `theme-${theme}`;
            return;
        }
        // auto follows the native system theme (WebView builds differ in
        // whether prefers-color-scheme ever flips). Until the bridge told us
        // once, leave the class unset so the CSS media-query fallback paints.
        root.className = systemThemeKnown ? `theme-${systemTheme}` : '';
    }
    function cycleTheme() {
        try {
            const current = localStorage.getItem('felime_theme') || 'auto';
            const next = THEMES[(THEMES.indexOf(current) + 1) % THEMES.length];
            localStorage.setItem('felime_theme', next);
            applyTheme();
            return next;
        } catch (_) {
            return 'auto';
        }
    }
    applyTheme();

    const keyboard = new FelimeKeyboard();
    // Batch 9: keyboard buttons must never take TAB/arrow focus. A focused
    // key makes the WebView eat host-injected keyevents (adb `input keyevent`)
    // as spatial navigation + clicks - observed as a '.' per clear attempt
    // and KEYCODE_0 landing as '2'. Unfocusable buttons let those events fall
    // through to the host editor.
    const defocusButtons = () => {
        document.querySelectorAll('button:not([tabindex])').forEach(button => {
            button.tabIndex = -1;
        });
    };
    if (typeof MutationObserver === 'function') {
        new MutationObserver(defocusButtons).observe(document.body, {
            childList: true,
            subtree: true,
        });
    }
    defocusButtons();
    keyboard.cycleTheme = cycleTheme;
    keyboard.themeLabel = () => {
        try {
            const theme = localStorage.getItem('felime_theme') || 'auto';
            return THEME_LABELS[theme];
        } catch (_) {
            return THEME_LABELS.auto;
        }
    };
    window.Felime = {
        onBridgeHello: payload => keyboard.onBridgeHello(payload),
        onEngineState: payload => keyboard.onEngineState(payload),
        onNativeState: payload => keyboard.onNativeState(payload),
        onEditorInfo: payload => keyboard.onEditorInfo(payload),
        onClipboard: payload => keyboard.onClipboard(payload),
        onFavorites: payload => keyboard.onFavorites(payload),
        onPanelCommit: payload => keyboard.onPanelCommit(payload),
        onPanelDelete: payload => keyboard.onPanelDelete(payload),
        // Debug/automation hooks: the mode menu and settings panel render
        // lazily, so DOM-only openers would show an empty container.
        toggleModeMenu: () => keyboard.toggleModeMenu(),
        closeModeMenu: () => keyboard.closeModeMenu(),
        toggleSettingsPanel: () => keyboard.toggleSettingsPanel(),
        closeSettingsPanel: () => keyboard.closeSettingsPanel(),
        toggleControlView: () => keyboard.setControlView(!keyboard.ctrlView),
        clearEditor: () => keyboard.clearEditorBridge(),
        // 批次 21 #12: the native re-show path lands the keyboard on its
        // main view.
        resetToHome: () => keyboard.resetToHome(),
        // Batch 20 suite hook: drives the height bridge without synthesizing
        // a drag (the drag gesture itself is covered by batch 16).
        applyKbHeight: total => keyboard.applyKbHeight(total),
        // Device-suite hook: driving the newer bridge methods (height/key
        // events) from automation needs the live page token.
        get token() { return keyboard.token; },
    };
    keyboard.setup();
})();
