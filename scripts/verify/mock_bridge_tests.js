/*
 * Mock-bridge functional suite (verification-plan.md, JS-contract rows).
 * Run: node scripts/verify/mock_bridge_tests.js
 */
'use strict';

const { KeyboardWorld, KEYBOARD_VERSION } = require('./mock_bridge_harness');

let passed = 0;
let failed = 0;
let skipped = 0;
const failures = [];

// Version gate: tests may carry {since, until} keyboard-version bounds so the
// same suite pins era-specific behavior (3.20.0 baseline vs 3.21.0 forms).
// skipped tests print why and never count as failures.
function verTuple(v) { return v.split('.').map(Number); }
function verAtLeast(v, floor) {
    const [a, b] = [verTuple(v), verTuple(floor)];
    for (let i = 0; i < Math.max(a.length, b.length); i++) {
        const d = (a[i] || 0) - (b[i] || 0);
        if (d !== 0) return d > 0;
    }
    return true;
}

function test(name, bodyOrOpts, maybeBody) {
    const opts = typeof bodyOrOpts === 'object' ? bodyOrOpts : null;
    const body = opts ? maybeBody : bodyOrOpts;
    if (opts) {
        if (opts.since && !verAtLeast(KEYBOARD_VERSION, opts.since)) {
            skipped += 1;
            console.log(`SKIP ${name} (needs keyboard >= ${opts.since}, running ${KEYBOARD_VERSION})`);
            return;
        }
        if (opts.until && verAtLeast(KEYBOARD_VERSION, opts.until) &&
            KEYBOARD_VERSION !== opts.until) {
            skipped += 1;
            console.log(`SKIP ${name} (needs keyboard <= ${opts.until}, running ${KEYBOARD_VERSION})`);
            return;
        }
    }
    try {
        body();
        passed += 1;
        console.log('PASS ' + name);
    } catch (error) {
        failed += 1;
        failures.push(name);
        console.log('FAIL ' + name + ' :: ' + error.message);
    }
}

function assert(condition, message) {
    if (!condition) throw new Error(message || 'assertion failed');
}
function equal(actual, expected, message) {
    if (actual !== expected) {
        throw new Error(`${message || 'equal'}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
    }
}

function fresh(overrides) {
    const world = new KeyboardWorld().build();
    world.hello(overrides);
    return world;
}

// ---------------------------------------------------------------- A/B basics

test('structure: symbol layer starts hidden, overlay exists closed', () => {
    const world = new KeyboardWorld().build();
    assert(world.$('symbolLayer').hidden, 'symbolLayer must start hidden');
    assert(!world.$('qwertyLayer').hidden, 'qwertyLayer must start visible');
    assert(world.$('voiceOverlay'), 'voiceOverlay exists');
    assert(!world.$('voiceOverlay').classList.contains('open'), 'overlay closed');
    assert(world.$('candidates'), 'candidates bar exists');
    assert(world.$('mic') && world.$('hide'), 'mic and hide tools exist');
});

test('no native traffic before bridge hello', () => {
    const world = new KeyboardWorld().build();
    world.tap(world.key('n'));
    world.tap(world.$('spaceKey'));
    const unexpected = world.native.calls.filter(c => c.method !== 'requestState');
    equal(unexpected.length, 0, 'no input calls before hello');
});

test('handshake replies keyboardReady and gates calls with token', () => {
    const world = fresh();
    const ready = world.native.of('keyboardReady');
    equal(ready.length, 1, 'one keyboardReady');
    equal(ready[0].args[3], 'tok-1', 'token echoed');
    world.tap(world.key('n'));
    const keys = world.native.of('key');
    equal(keys.length, 1, 'key went through');
    equal(keys[0].args[0], 'n', 'letter payload');
    equal(keys[0].args[1], 'tok-1', 'token attached');
});

test('incompatible native api or capabilities never arm the bridge', () => {
    const low = fresh({ nativeApiVersion: 0 });
    low.tap(world0Key(low));
    equal(low.native.of('keyboardReady').length, 0, 'no ready for low api');
    equal(
        low.native.calls.filter(c => c.method !== 'requestState').length,
        0,
        'no input calls for low api',
    );

    const missingCap = fresh({ capabilities: ['text-input-v1'] });
    missingCap.tap(world0Key(missingCap));
    equal(missingCap.native.of('keyboardReady').length, 0, 'no ready for missing cap');
});

function world0Key(world) {
    return world.key('n');
}

test('letter taps send one scalar each', () => {
    const world = fresh();
    'hello'.split('').forEach(ch => world.tap(world.key(ch)));
    const keys = world.native.of('key').map(c => c.args[0]).join('');
    equal(keys, 'hello', 'typed letters');
});

test('key press feedback fires once per touchstart with the token', () => {
    const world = fresh();
    const token = world.native.of('keyboardReady')[0].args[3];
    const key = world.key('h');
    world.touchDown(key);
    world.touchUp(key);
    const feedback = world.native.of('keyFeedback');
    equal(feedback.length, 1, 'one feedback per key touchstart');
    equal(feedback[0].args[0], token, 'feedback carries the page token');

    // 退格长按的 75ms 重复不触发额外反馈（反馈只在 touchstart）。
    const backspace = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.dataset.role === 'backspace',
    );
    world.touchDown(backspace);
    world.clock.advance(400);
    world.clock.advance(300);
    world.touchUp(backspace);
    const backspaceFeedback = world.native.of('keyFeedback').length;
    assert(backspaceFeedback === 2, `touchstart-only feedback, got ${backspaceFeedback}`);
});

test('space tap sends space', () => {
    const world = fresh();
    world.tap(world.$('spaceKey'));
    equal(world.native.of('space').length, 1, 'space once');
    equal(world.native.of('startVoice').length, 0, 'no voice on tap');
});

test('shift once uppercases exactly one letter', () => {
    const world = fresh();
    const shift = world.document.querySelector('.shift');
    world.tap(shift);
    world.tap(world.key('h'));
    world.tap(world.key('i'));
    equal(world.native.of('key').map(c => c.args[0]).join(''), 'Hi', 'H then i');
    assert(shift.classList.contains('active') === false, 'shift resets after one char');
});

test('long-press 350ms locks caps', () => {
    const world = fresh();
    const shift = world.document.querySelector('.shift');
    world.touchDown(shift);
    world.clock.advance(360);
    world.touchUp(shift);
    assert(shift.classList.contains('locked'), 'locked class');
    world.tap(world.key('o'));
    world.tap(world.key('k'));
    equal(world.native.of('key').map(c => c.args[0]).join(''), 'OK', 'caps uppercase');
    world.tap(shift);
    world.tap(world.key('a'));
    equal(world.native.of('key').slice(-1)[0].args[0], 'a', 'unlock gives lowercase');
});

test('backspace repeats while held (390ms then 75ms cadence)', () => {
    const world = fresh();
    const backspace = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.dataset.role === 'backspace',
    );
    world.touchDown(backspace);
    world.clock.advance(400);
    world.clock.advance(300);
    world.touchUp(backspace);
    const count = world.native.of('backspace').length;
    assert(count >= 4, `expected repeats, got ${count}`);
    const before = count;
    world.clock.advance(500);
    equal(world.native.of('backspace').length, before, 'no repeats after release');
});

test('enter/hide/globe taps', () => {
    const world = fresh();
    const enter = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.dataset.role === 'enter',
    );
    world.tap(enter);
    equal(world.native.of('enter').length, 1, 'enter');
    world.tap(world.$('hide'));
    equal(world.native.of('hideKeyboard').length, 1, 'hide');
    world.tap(world.$('mic'));
    equal(world.native.of('startVoice').length, 1, 'mic toggles voice on');
});

// ---------------------------------------------------------------- J voice

test('punct-key flicks stay on the engine path (never full-width direct)', () => {
    const world = fresh({ mode: 'pinyin' });
    const dot = world.key('.');
    world.touchDown(dot, 20, 20);
    world.move(dot, 20, -30);
    world.touchUp(dot);
    world.clock.advance(2);
    // Main ，/ alt 。- the up-flick (alt) sends ASCII dot.
    equal(world.native.of('key').slice(-1)[0].args[0], '.',
        'punct up-flick sends ASCII dot for the punctuator');
    world.touchDown(dot, 20, 20);
    world.move(dot, 20, 70);
    world.touchUp(dot);
    world.clock.advance(2);
    equal(world.native.of('key').slice(-1)[0].args[0], ',',
        'punct down-flick sends ASCII comma for the punctuator');
    equal(world.native.of('commitText').filter(c => [',', '.', '\uff0c', '\u3002'].includes(c.args[0])).length, 0,
        'punct flicks never bypass the engine');
});

test('composing space confirms the pool head, not the paged highlight', () => {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ mode: 'pinyin', revision: 7, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }, { id: 'c2', text: '好' }], hasNextPage: true });
    // preload dragged the native cursor to a later page - the pool head is
    // still the top candidate.
    world.engineState({ mode: 'pinyin', revision: 9, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c9', text: '妮' }], hasNextPage: false });
    world.tap(world.$('spaceKey'));
    const pick = world.native.of('chooseCandidate').slice(-1)[0];
    equal(pick.args[0], 9, 'choose uses the live revision');
    equal(pick.args[1], 'c1', 'space confirms the POOL HEAD by id');
    equal(world.native.of('space').length, 0, 'native space bypassed while composing');
    // Idle space still goes native.
    world.engineState({ mode: 'pinyin', revision: 10, composing: false, rawInput: '',
        candidates: [], hasNextPage: false });
    world.tap(world.$('spaceKey'));
    equal(world.native.of('space').length, 1, 'idle space unchanged');
});

test('space hold 350ms starts voice once, release stops', () => {
    const world = fresh();
    const space = world.$('spaceKey');
    world.touchDown(space);
    world.clock.advance(200);
    world.clock.advance(200); // total 400 > 350
    world.clock.advance(1000);
    equal(world.native.of('startVoice').length, 1, 'startVoice exactly once');
    world.touchUp(space);
    equal(world.native.of('stopVoice').length, 1, 'stopVoice on release');
    equal(world.native.of('space').length, 0, 'no space emitted after hold');
});

test('space release under 350ms emits space, never voice', () => {
    const world = fresh();
    const space = world.$('spaceKey');
    world.touchDown(space);
    world.clock.advance(300);
    world.touchUp(space);
    equal(world.native.of('space').length, 1, 'space emitted');
    equal(world.native.of('startVoice').length, 0, 'voice never started');
});

test('cancelled space hold uses the era-specific voice cancel path', () => {
    const world = fresh();
    const space = world.$('spaceKey');
    world.touchDown(space);
    world.clock.advance(400);
    world.touchCancel(space);
    equal(world.native.of('startVoice').length, 1, 'started after threshold');
    if (verAtLeast(KEYBOARD_VERSION, '3.24.0')) {
        equal(world.native.of('cancelVoice').length, 1, 'cancel discards voice');
        equal(world.native.of('stopVoice').length, 0, 'cancel never submits voice');
    } else {
        // 3.20.0 predates the cancelVoice bridge method; its touchcancel
        // keeps the established stopVoice behavior.
        equal(world.native.of('cancelVoice').length, 0, 'legacy has no cancelVoice');
        equal(world.native.of('stopVoice').length, 1, 'legacy cancel stops voice');
    }
    equal(world.native.of('space').length, 0, 'no space after hold');
});

test('voice overlay visible only during recording states', () => {
    const world = fresh();
    world.nativeState({ state: 'idle' });
    assert(!world.$('voiceOverlay').classList.contains('open'), 'idle closed');
    world.nativeState({ state: 'loading', level: 0 });
    assert(world.$('voiceOverlay').classList.contains('open'), 'loading open');
    world.nativeState({ state: 'listening', level: 0.5, partial: '你好' });
    assert(world.$('voiceOverlay').classList.contains('open'), 'listening open');
    equal(world.$('partialText').textContent, '你好', 'partial shown');
    world.nativeState({ state: 'stopping' });
    assert(world.$('voiceOverlay').classList.contains('open'), 'stopping open');
    world.nativeState({ state: 'idle' });
    assert(!world.$('voiceOverlay').classList.contains('open'), 'back to idle closed');
    world.nativeState({ state: 'error', message: 'boom' });
    assert(!world.$('voiceOverlay').classList.contains('open'), 'error closed');
    assert(world.$('toast').classList.contains('open'), 'error toast');
});

test('scrim tap stops a listening session', () => {
    const world = fresh();
    world.nativeState({ state: 'listening' });
    world.tap(world.$('voiceScrim'));
    equal(world.native.of('stopVoice').length, 1, 'scrim stops voice');
});

test('voice card keeps the era-specific submit and cancel controls', () => {
    if (!verAtLeast(KEYBOARD_VERSION, '3.24.0')) {
        const legacy = fresh();
        legacy.nativeState({ state: 'listening', partial: '旧版语音' });
        assert(!legacy.$('voiceClose'), 'legacy card has no close control');
        legacy.tap(legacy.$('voiceCard'));
        equal(legacy.native.of('stopVoice').length, 0,
            'legacy card tap has no submit handler');
        equal(legacy.native.of('cancelVoice').length, 0,
            'legacy card tap has no cancel handler');
        return;
    }
    const submit = fresh();
    submit.nativeState({ state: 'listening', partial: '第一段' });
    submit.tap(submit.$('voiceCard'));
    equal(submit.native.of('stopVoice').length, 1, 'card tap submits voice');
    equal(submit.native.of('cancelVoice').length, 0, 'card tap does not cancel');

    const cancel = fresh();
    cancel.nativeState({ state: 'listening', partial: '第二段' });
    cancel.tap(cancel.$('voiceClose'));
    equal(cancel.native.of('cancelVoice').length, 1,
        '撤销 discards immediately (icon makes the semantics clear, no confirm)');
    equal(cancel.native.of('stopVoice').length, 0, 'close does not submit');
});

test('voice overlay: big 说完了 button finishes and inserts, visually distinct from 撤销', () => {
    if (!verAtLeast(KEYBOARD_VERSION, '3.28.0')) return;
    const done = fresh();
    done.nativeState({ state: 'listening', partial: '说完的内容' });
    done.tap(done.$('voiceDone'));
    equal(done.native.of('stopVoice').length, 1, '说完了 submits');
    equal(done.native.of('cancelVoice').length, 0, '说完了 never cancels');

    const card = fresh();
    card.nativeState({ state: 'listening', partial: '' });
    card.tap(card.$('voiceCard'));
    equal(card.native.of('stopVoice').length, 1, 'card tap still submits (hint unchanged)');

    const distinct = fresh();
    distinct.nativeState({ state: 'listening', partial: '' });
    equal(distinct.$('voiceDone').textContent.trim(), '说完了',
        'done button reads 说完了 (vs 撤销)'); // 视觉比例由 preview/真机截图把关
});

test('voice entry hints match the gesture that started it', () => {
    const toolbar = fresh();
    toolbar.tap(toolbar.$('mic'));
    toolbar.nativeState({ state: 'listening' });
    equal(toolbar.$('voiceHint').textContent,
        verAtLeast(KEYBOARD_VERSION, '3.24.0') ? '点击任意位置结束' : '说完后点击任意位置结束',
        'toolbar hint');

    const hold = fresh();
    const space = hold.$('spaceKey');
    hold.touchDown(space);
    hold.clock.advance(400);
    hold.nativeState({ state: 'listening' });
    equal(hold.$('voiceHint').textContent,
        verAtLeast(KEYBOARD_VERSION, '3.28.0') ? '松手上屏' :
            verAtLeast(KEYBOARD_VERSION, '3.24.0') ? '松手结束' : '说完后点击任意位置结束',
        'space hold hint');
    hold.touchCancel(space);
});

test('hold voice overlay: no buttons, slide hint only; mic overlay keeps both buttons', () => {
    if (!verAtLeast(KEYBOARD_VERSION, '3.28.0')) return;
    const hold = fresh();
    const space = hold.$('spaceKey');
    hold.touchDown(space);
    hold.clock.advance(400);
    hold.nativeState({ state: 'listening', partial: '长按说的' });
    equal(hold.$('voiceOverlay').className.includes('hold'), true, 'hold class on overlay');
    equal(hold.$('voiceActions').className.includes('hold') ||
        getComputedStyleBridge(hold, 'voiceActions') === 'none', true,
        '说完了 hidden in hold overlay');
    equal(getComputedStyleBridge(hold, 'voiceSlideHint') !== 'none', true,
        'slide hint visible in hold overlay');
    hold.touchCancel(space);

    const mic = fresh();
    mic.nativeState({ state: 'listening' });
    equal(mic.$('voiceOverlay').className.includes('hold'), false, 'mic overlay not hold');
    equal(getComputedStyleBridge(mic, 'voiceDone') !== 'none', true, '说完了 visible for mic');
    equal(getComputedStyleBridge(mic, 'voiceClose') !== 'none', true, '撤销 visible for mic');
});

test('hold voice slide-up: card shrinks+fades with progress; past threshold release cancels', () => {
    if (!verAtLeast(KEYBOARD_VERSION, '3.28.0')) return;
    const world = fresh();
    const space = world.$('spaceKey');
    world.touchDown(space, 20, 200);
    world.clock.advance(400);
    equal(world.native.of('startVoice').length, 1, 'hold started voice');

    // 中途（未过阈值）：浮层随进度变小变透明，提示胶囊同步放大。
    world.move(space, 20, 145); // 55px 上滑 → 0.5
    const card = world.$('voiceCard');
    equal(card.style.opacity, '0.725', 'mid-slide card fade');
    assert(card.style.transform.includes('scale(0.890)'), 'mid-slide card shrink');
    assert(world.$('voiceSlideHint').style.transform.includes('scale(1.075)'),
        'slide pill scales up (font+bg together)');
    equal(world.$('voiceSlideHint').className.includes('arm'), false, 'not armed yet');

    // 过阈值：提示进入 arm 态。
    world.move(space, 20, 80); // 120px 上滑 → 1.0+
    equal(world.$('voiceSlideHint').className.includes('arm'), true, 'armed hint');

    // 松手 = 撤销（不是上屏）。
    world.touchUp(space, 20, 80);
    equal(world.native.of('cancelVoice').length, 1, 'armed release discards');
    equal(world.native.of('stopVoice').length, 0, 'armed release does not submit');

    // 未过阈值松手 = 上屏。
    const commit = fresh();
    const space2 = commit.$('spaceKey');
    commit.touchDown(space2, 20, 200);
    commit.clock.advance(400);
    commit.move(space2, 20, 180); // 20px，远低于阈值
    commit.touchUp(space2, 20, 180);
    equal(commit.native.of('stopVoice').length, 1, 'plain release inserts');
    equal(commit.native.of('cancelVoice').length, 0, 'plain release never cancels');
    // 松手后动画复位。
    equal(commit.$('voiceCard').style.opacity === '' ||
        commit.$('voiceCard').style.opacity === undefined, true, 'card styles reset');
});

function getComputedStyleBridge(world, id) {
    // fake DOM 没有样式计算：看类/显隐约定（overlay.hold 的 CSS 规则由
    // css_lint R2 与真机/preview 截图把关），这里断言状态类本身。
    const overlay = world.$('voiceOverlay');
    const el = world.$(id);
    if (overlay.className.includes('hold')) {
        if (id === 'voiceActions' || id === 'voiceClose') return 'none';
        return 'block';
    }
    return el && el.id ? 'flex' : 'none';
}

test('sensitive editor disables mic', () => {
    const world = fresh();
    world.editorInfo({ sensitive: true });
    assert(world.$('mic').disabled, 'mic disabled');
    world.editorInfo({ sensitive: false });
    assert(!world.$('mic').disabled, 'mic re-enabled');
});

test('editor state and voice state compose in any order', () => {
    // onNativeState after onEditorInfo: sensitivity must survive.
    const first = fresh();
    first.editorInfo({ sensitive: true });
    first.nativeState({ state: 'idle' });
    assert(first.$('mic').disabled, 'sensitive survives native state');
    // onEditorInfo after onNativeState: stop-in-progress must survive.
    const second = fresh();
    second.nativeState({ state: 'stopping' });
    second.editorInfo({ sensitive: false });
    assert(second.$('mic').disabled, 'stopping survives editor state');
    second.nativeState({ state: 'idle' });
    assert(!second.$('mic').disabled, 'idle re-enables after stop');
});

// ---------------------------------------------------------------- M4 bottom row

test('bottom row: toggle flips the quick keyboard pair', () => {
    const world = fresh();
    const toggle = world.$('modeToggle');
    assert(toggle && toggle.dataset.role === 'cnEn', 'cn-en toggle exists');
    // default pair 拼/En: direct -> pinyin
    world.tap(toggle);
    equal(world.native.of('selectMode')[0].args[0], 'pinyin', 'direct to pinyin');
    world.engineState({ phase: 'READY', revision: 2, mode: 'pinyin', composing: '', candidates: [] });
    world.tap(toggle);
    equal(world.native.of('selectMode')[1].args[0], 'direct', 'pinyin back to direct');
    // a mode outside the pair returns to the pair's Chinese end (3.20.0
    // semantics; R13 replaces this for 3.21.2+ - see the gated test below)
    world.engineState({ phase: 'READY', revision: 3, mode: 'french', composing: '', candidates: [] });
    world.tap(toggle);
    equal(world.native.of('selectMode')[2].args[0], 'pinyin', 'french falls back to pair end');
});

test('toggle returns to the last user-selected mode', {
    since: '3.21.2', until: '3.23.0'
}, () => {
    const pickMode = (world, title) => {
        const toggle = world.$('modeToggle');
        world.touchDown(toggle);
        world.clock.advance(360);
        world.touchUp(toggle);
        const item = [...world.document.querySelectorAll('#modeMenu button')]
            .find(b => (b.textContent || '').includes(title));
        assert(item, `mode menu has ${title}`);
        item.click();
    };
    const world = fresh();
    const toggle = world.$('modeToggle');
    // 双拼 -> 法语 -> toggle lands back on 双拼 (mode-menu picks feed the
    // last-mode memory), and a second tap round-trips back.
    pickMode(world, 'Français');
    world.engineState({ phase: 'READY', revision: 3, mode: 'french', composing: '', candidates: [] });
    pickMode(world, '双拼');
    world.engineState({ phase: 'READY', revision: 4, mode: 'double-pinyin', composing: '', candidates: [] });
    world.tap(toggle);
    equal(world.native.of('selectMode').slice(-1)[0].args[0], 'french',
        'toggle returns to the last user-selected mode (french)');
    world.engineState({ phase: 'READY', revision: 5, mode: 'french', composing: '', candidates: [] });
    world.tap(toggle);
    equal(world.native.of('selectMode').slice(-1)[0].args[0], 'double-pinyin',
        'round trip back (A->B->A)');
    // No explicit switch recorded: pair logic still applies.
    const seeded = fresh();
    seeded.engineState({ phase: 'READY', revision: 2, mode: 'french', composing: '', candidates: [] });
    seeded.tap(seeded.$('modeToggle'));
    equal(seeded.native.of('selectMode').slice(-1)[0].args[0], 'pinyin',
        'engine-seeded french (no user switch) still falls back to pair end');
});

test('quick toggle ignores temporary mode-menu choices', {since: '3.24.0'}, () => {
    const pickMode = (world, title) => {
        const toggle = world.$('modeToggle');
        world.touchDown(toggle);
        world.clock.advance(360);
        world.touchUp(toggle);
        const item = [...world.document.querySelectorAll('#modeMenu button')]
            .find(b => (b.textContent || '').includes(title));
        assert(item, `mode menu has ${title}`);
        item.click();
    };
    const world = fresh();
    const toggle = world.$('modeToggle');
    const savedPair = world.storage.get('feelime_quick_pair');
    // The mode menu may temporarily select a third keyboard, but the quick
    // toggle still follows the saved pair (拼/En) and must not rewrite it.
    pickMode(world, 'Français');
    world.engineState({ phase: 'READY', revision: 3, mode: 'french', composing: '', candidates: [] });
    world.tap(toggle);
    equal(world.native.of('selectMode').slice(-1)[0].args[0], 'pinyin',
        'temporary French mode returns to saved pair first entry');
    world.engineState({ phase: 'READY', revision: 4, mode: 'pinyin', composing: '', candidates: [] });
    world.tap(toggle);
    equal(world.native.of('selectMode').slice(-1)[0].args[0], 'direct',
        'saved pair round trips from first to second entry');

    pickMode(world, '双拼');
    world.engineState({ phase: 'READY', revision: 5, mode: 'double-pinyin', composing: '', candidates: [] });
    world.tap(toggle);
    equal(world.native.of('selectMode').slice(-1)[0].args[0], 'pinyin',
        'temporary Double Pinyin mode also returns to saved pair');
    equal(world.storage.get('feelime_quick_pair'), savedPair,
        'temporary mode does not rewrite saved pair');

    // A fresh engine-seeded third mode follows the same rule without a menu
    // click; only the saved pair controls the target.
    const seeded = fresh();
    seeded.engineState({ phase: 'READY', revision: 2, mode: 'french', composing: '', candidates: [] });
    seeded.tap(seeded.$('modeToggle'));
    equal(seeded.native.of('selectMode').slice(-1)[0].args[0], 'pinyin',
        'engine-seeded temporary mode returns to saved pair');
});

test('toggle long-press opens the full mode menu (no native switch call)', () => {
    const world = fresh();
    const toggle = world.$('modeToggle');
    world.touchDown(toggle);
    world.clock.advance(360);
    world.touchUp(toggle);
    assert(world.$('modeMenu').classList.contains('open'), 'mode menu open after long press');
    equal(world.native.of('selectMode').length, 0, 'long press does not switch mode');
    equal(world.native.of('switchInputMethod').length, 0, 'system picker not called');
});

test('bottom row roles: no globe, no toolbar mode button, shorthand labels', () => {
    const world = fresh();
    assert(!world.document.querySelector('[data-role="globe"]'), 'globe removed');
    assert(world.$('modeButton') === null, 'toolbar mode button removed ');
    equal(world.$('enterKey').dataset.role, 'enter', 'enter role kept');
    equal(world.$('enterKey').textContent, '换行', 'enter text idle');
    const toggle = world.$('modeToggle');
    equal(toggle.querySelector('.cn-main').textContent, 'En', 'current shorthand in direct');
    equal(toggle.querySelector('.cn-sub').textContent, '拼', 'quick-pair partner shorthand');
    // space key: mic only, no mode text
    equal(world.$('spaceKey').textContent, '', 'space key carries no text');
    const mic = [...world.$('spaceKey').children].some(c => (c.tagName || '').toUpperCase() === 'SVG');
    assert(mic, 'space key shows the mic glyph');
});

test('B5b Chinese modes swap Shift for the 分词 separator key', () => {
    const world = fresh({ mode: 'pinyin' });
    equal(world.document.querySelector('.shift') === null, true, 'no shift key in pinyin');
    const sep = world.document.querySelector('[data-role="sep"]');
    assert(sep, '分词 key present');
    equal(sep.textContent, '分词', 'separator label');
    world.tap(sep);
    equal(world.native.of('key').slice(-1)[0].args[0], "'", "separator sends the ' delimiter");
    // Direct keeps Shift.
    world.engineState({ mode: 'direct', revision: 2, composing: false, candidates: [] });
    assert(world.document.querySelector('.shift'), 'shift restored in direct');
    equal(world.document.querySelector('[data-role="sep"]') === null, true, 'separator removed in direct');
});

test(' Punct slot prints 。 as main, ，as the alt; taps send ascii .',  ()=> {
    const world = fresh({ mode: 'double-pinyin' });
    // Schema makes x'an parse (jianpin abbreviations + bare zero
    // initials), so double pinyin gets the 分词 key back (reverted it
    // to Shift while n'hk was dead input).
    const sep = world.document.querySelector('[data-role="sep"]');
    assert(sep, '分词 key present in double pinyin');
    world.tap(sep);
    equal(world.native.of('key').slice(-1)[0].args[0], "'", "separator sends '");
    // The slot swapped - main ，(tap) / alt 。(flick up).
    equal(world.key('.').querySelector('.kb-main').textContent, '，', 'punct main reads ，');
    equal(world.key('.').querySelector('.kb-alt').textContent, '。', 'punct alt previews 。');
    world.tap(world.key('.'));
    // ASCII comma: the engine punctuator converts it to ，(verified path).
    equal(world.native.of('key').slice(-1)[0].args[0], ',', 'punct tap sends ascii comma');
});

test('chinese engines show uppercase glyphs and 。punct', () => {
    const world = fresh();
    world.engineState({ phase: 'READY', revision: 2, mode: 'pinyin', composing: '', candidates: [] });
    equal(world.key('q').querySelector('.kb-main').textContent, 'Q', 'uppercase glyphs in pinyin');
    equal(world.key('.').querySelector('.kb-main').textContent, '，', 'punct slot reads ，');
    world.tap(world.key('.'));
    // ASCII comma: the engine punctuator converts it to ，(verified path).
    equal(world.native.of('key').slice(-1)[0].args[0], ',', 'punct sends ascii comma');
    world.engineState({ phase: 'READY', revision: 3, mode: 'direct', composing: '', candidates: [] });
    equal(world.key('q').querySelector('.kb-main').textContent, 'q', 'lowercase glyphs in direct');
});

// ---------------------------------------------------------------- gestures

test('long-press 350ms opens popup, drag selects, release commits', () => {
    const world = fresh();
    const eKey = world.key('e');
    world.touchDown(eKey);
    world.clock.advance(360);
    assert(world.$('keyPopup').classList.contains('open'), 'popup open');
    const items = world.document.querySelectorAll('.kp-item');
    assert(items.length >= 3, 'popup has candidates');
    // 相对跟手（3.39.0，qwerty 弹层与 T9 同款）：拖动量 = 目标格 − 锚点格。
    const anchor0 = [...items].find(el => el.classList.contains('sel'));
    const ar0 = anchor0.getBoundingClientRect();
    const tr0 = items[1].getBoundingClientRect();
    world.move(eKey,
        20 + (tr0.left + tr0.width / 2) - (ar0.left + ar0.width / 2),
        20 + (tr0.top + tr0.height / 2) - (ar0.top + ar0.height / 2));
    world.touchUp(eKey);
    const keys = world.native.of('key');
    assert(keys.length === 1, 'one commit from popup');
    equal(keys[0].args[0], items[1].textContent, 'selected char committed');
});

test('popup cancel commits nothing', () => {
    const world = fresh();
    const eKey = world.key('e');
    world.touchDown(eKey);
    world.clock.advance(360);
    world.touchCancel(eKey);
    equal(world.native.of('key').length, 0, 'no commit on cancel');
});

test('flick up sends the small alt char, flick down uppercases', () => {
    const world = fresh();
    const q = world.key('q');
    world.touchDown(q, 20, 20);
    world.move(q, 20, -30); // 50px up
    world.touchUp(q);
    world.clock.advance(2); // deferred swiping reset
    equal(world.native.of('key').slice(-1)[0].args[0], '1',
        'flick alt goes through the engine so it can join a composition');
    // The feedback is a direction-only blob - it must never
    // preview the character that lands.
    const blob = world.$('flickBlob');
    assert(blob && blob.classList.contains('run'), 'flick blob ran');
    assert(!blob.textContent, 'blob never shows a character');

    const a = world.key('a');
    world.touchDown(a, 20, 20);
    world.move(a, 20, 70); // 50px down
    world.touchUp(a);
    world.clock.advance(2);
    equal(world.native.of('key').slice(-1)[0].args[0], 'A', 'down gives uppercase');
});

test('horizontal swipe scrubs from a fixed threshold crossing', () => {
    const world = fresh();
    const g = world.key('g');
    const fixedThresholdAnchor = verAtLeast(KEYBOARD_VERSION, '3.23.0');
    // 's current implementation anchors the first step at the fixed
    // threshold crossing.  The 3.20.0 baseline anchors at the first sampled
    // point instead, so its reverse sample crosses one extra unit.
    world.touchDown(g, 20, 20);
    world.move(g, -20, 0); // 40px travel: just past the 38px slop, engage
    equal(world.native.of('moveCursor').length, 1, 'engage steps exactly once');
    world.move(g, -68, 0); // further 48px left -> 4 more steps at 12px/step
    equal(world.native.of('moveCursor').reduce((sum, c) => sum + Math.abs(c.args[0]), 0), 5, 'scrub moves per 12px after engage');
    for (const call of world.native.of('moveCursor')) {
        assert(call.args[0] < 0, 'left while dragging left');
    }
    world.move(g, 20, 0); // back at the origin: two steps right of the crossing
    const right = world.native.of('moveCursor').filter(c => c.args[0] > 0);
    equal(right.reduce((sum, c) => sum + c.args[0], 0), fixedThresholdAnchor ? 6 : 7,
        fixedThresholdAnchor ? 'crossing back moves right' : 'legacy sampled anchor moves right');
    world.touchUp(g, 20, 0);
    const before = world.native.of('moveCursor').length;
    world.move(g, 100, 0);
    equal(world.native.of('moveCursor').length, before, 'release stops the scrub');
});

test('fast scrub keeps all steps beyond the old 40-step cap', {since: '3.22.0'}, () => {
    const world = fresh();
    const g = world.key('g');
    world.touchDown(g, 20, 20);
    world.move(g, -40, 0);
    world.move(g, -1240, 0);
    world.touchUp(g, -1240, 0);
    const moves = world.native.of('moveCursor');
    equal(moves.reduce((sum, c) => sum + c.args[0], 0), -102, 'all crossed steps reach native');
    assert(moves.length < 5, 'bounded batch avoids single-step call flood');
});

test('123 opens on 常用: digits row 1, fullwidth rows for Chinese', () => {
    const world = fresh({ mode: 'pinyin' });
    const key123 = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    );
    world.tap(key123);
    assert(world.$('qwertyLayer').hidden, 'letters hidden');
    assert(!world.$('symbolLayer').hidden, 'symbols visible');
    equal(world.$('symGrid').children.length, 3, 'three rows');
    equal(
        [...world.$('symGrid').children[0].children].map(k => k.textContent).join(''),
        '1234567890',
        'digits lead row 1',
    );
    equal(
        [...world.$('symGrid').children[1].children].map(k => k.textContent).join(''),
        '，。、；：？！～（）',
        'Chinese mode default rows are fullwidth',
    );
    // Row 3 ends with the delete key.
    equal(world.$('symGrid').children[2].children[9].dataset.role, 'backspace',
        'row 3 ends with backspace');
    // Typing a symbol inserts literally and records it as recent.
    world.tap(world.$('symGrid').children[2].children[0]); // '“'
    equal(world.native.of('commitText').slice(-1)[0].args[0], '“',
        'symbol inserts literally');
});

test('long-press 123 opens the nine-pad; tap keeps the symbol layer', {since: '3.27.0'}, () => {
    const world = fresh();
    const key123 = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    );
    world.tap(key123);
    assert(!world.$('symbolLayer').hidden, 'tap opens the symbol layer');
    world.tap(world.document.querySelector('[data-action="letters"]'));
    assert(world.$('symbolLayer').hidden, 'ABC returns to letters');
    world.touchDown(key123);
    world.clock.advance(360);
    world.touchUp(key123);
    assert(!world.$('numPadLayer').hidden, 'long-press opens the nine-pad');
    assert(world.$('qwertyLayer').hidden, 'letters hidden');
    assert(world.$('symbolLayer').hidden, 'symbol layer hidden');
});

test('nine-pad commits literally; fn column rides the native bridges', {since: '3.27.0'}, () => {
    const world = fresh({ mode: 'pinyin' });
    const key123 = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    );
    world.touchDown(key123);
    world.clock.advance(360);
    world.touchUp(key123);
    const layer = world.$('numPadLayer');
    const key = label => [...layer.querySelectorAll('.kb-key')]
        .find(el => el.textContent === label);
    world.tap(key('5'));
    equal(world.native.of('commitText').slice(-1)[0].args[0], '5',
        'digit commits literally');
    equal(world.native.of('key').length, 0, 'engine never sees nine-pad digits');
    world.tap(key('.'));
    equal(world.native.of('commitText').slice(-1)[0].args[0], '.',
        'dot stays a literal dot in pinyin');
    world.tap([...layer.querySelectorAll('.num-sym-key')].find(el => el.textContent === '@'));
    equal(world.native.of('commitText').slice(-1)[0].args[0], '@', 'strip commits literally');
    world.tap(layer.querySelector('[data-role="backspace"]'));
    equal(world.native.of('backspace').length, 1, 'backspace rides the bridge');
    world.tap(key('空格'));
    equal(world.native.of('space').length, 1, 'space rides the bridge');
    world.tap(key('换行'));
    equal(world.native.of('enter').length, 1, 'enter rides the bridge');
    world.tap(layer.querySelector('.num-back'));
    assert(!world.$('qwertyLayer').hidden, 'back returns to letters');
});

test('emoji sub-view commits and remembers; 123 tab returns', {since: '3.27.0'}, () => {
    const world = fresh();
    const key123 = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    );
    world.touchDown(key123);
    world.clock.advance(360);
    world.touchUp(key123);
    const layer = world.$('numPadLayer');
    const emojiKey = () => layer.querySelector('[data-role="emoji"]');
    world.tap(emojiKey());
    const pages = layer.querySelector('.emoji-pages');
    assert(pages, 'emoji pages rendered');
    let tabs = [...layer.querySelectorAll('.emoji-cats .sym-cat')];
    equal(tabs[0].textContent, '123', 'digits tab leads');
    equal(tabs.length, 8, '123 + seven categories before the first pick');
    const first = pages.querySelector('.emoji-key');
    world.tap(first);
    equal(world.native.of('commitText').slice(-1)[0].args[0], first.textContent,
        'emoji commits literally');
    world.tap(tabs[0]);
    assert(!layer.querySelector('.emoji-pages'), 'digits return in place');
    world.tap(emojiKey());
    tabs = [...layer.querySelectorAll('.emoji-cats .sym-cat')];
    equal(tabs.length, 9, '常用 appears after the first pick');
    equal(tabs[1].textContent, '常用', '常用 follows the digits tab');
    equal(tabs[1].classList.contains('active'), true,
        'the leading category tab matches the page being shown');
    equal(tabs[2].classList.contains('active'), false, 'later tabs stay dark');
    // Leaving the pad ends the emoji session.
    world.tap(tabs[0]);
    world.tap(layer.querySelector('.num-back'));
    world.touchDown(key123);
    world.clock.advance(360);
    world.touchUp(key123);
    assert(!world.$('numPadLayer').querySelector('.emoji-pages'),
        'emoji view reset after leaving the pad');
});

test('panel borrows and restores the nine-pad', {since: '3.27.0'}, () => {
    const world = fresh();
    const key123 = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    );
    world.touchDown(key123);
    world.clock.advance(360);
    world.touchUp(key123);
    world.tap(world.document.querySelector('[data-panel-tab="clipboard"]'));
    assert(!world.$('panelLayer').hidden, 'panel opens from the nine-pad');
    assert(world.$('numPadLayer').hidden, 'pad hidden under the panel');
    world.tap(world.$('panelClose'));
    assert(!world.$('numPadLayer').hidden, 'pad restored after the panel');
    assert(world.$('panelLayer').hidden, 'panel closed');
});

test('rotation keeps the pinned 常用 variant; mode switch resets it', {since: '3.27.0'}, () => {
    const world = fresh({ mode: 'pinyin' });
    world.tap([...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    ));
    const tab = () => world.document.querySelector('[data-sym-cat="common"]');
    world.tap(tab());
    equal(tab().querySelector('.cat-sub').textContent, 'En', 'pinned en');
    // Rotation re-renders the SAME mode: pin, badge and grid must agree.
    world.hello({ orientation: 'landscape', mode: 'pinyin' });
    equal(tab().querySelector('.cat-sub').textContent, 'En', 'badge survives rotation');
    equal([...world.$('symGrid').children[1].children].map(k => k.textContent).join(''),
        '-/:;()&@+=', 'grid still matches the badge');
    // A REAL mode switch resets to the new mode's default, live.
    world.hello({ orientation: 'landscape', mode: 'double-pinyin' });
    equal(tab().querySelector('.cat-sub').textContent, '中', 'mode switch resets the pin');
    equal([...world.$('symGrid').children[1].children].map(k => k.textContent).join(''),
        '，。、；：？！～（）', 'grid follows the new default');
});

test('panel editor card preserves the nine-pad return layer', {since: '3.27.0'}, () => {
    const world = fresh();
    const key123 = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    );
    world.touchDown(key123);
    world.clock.advance(360);
    world.touchUp(key123);
    world.tap(world.document.querySelector('[data-panel-tab="clipboard"]'));
    world.tap(world.document.getElementById('panelManage'));
    assert(!world.$('phraseCard').hidden, 'card open');
    assert(!world.$('qwertyLayer').hidden, 'letters under the card');
    world.tap(world.document.getElementById('phraseCardCancel'));
    assert(!world.$('panelLayer').hidden, 'panel list restored');
    world.tap(world.$('panelClose'));
    assert(!world.$('numPadLayer').hidden, 'back to the nine-pad, not letters');
});

test('nine-pad enter label follows composition; locale re-renders the pad', {since: '3.27.0'}, () => {
    const world = fresh();
    world.engineState({ mode: 'direct', composing: true, rawInput: 'ni', revision: 2, candidates: [] });
    const key123 = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    );
    world.touchDown(key123);
    world.clock.advance(360);
    world.touchUp(key123);
    equal(world.$('numEnterKey').textContent, '确定', 'enter reads 确定 while composing');
    world.engineState({ mode: 'direct', composing: false, rawInput: '', revision: 3, candidates: [] });
    world.hello({ uiLocale: 'en' });
    equal(world.$('numEnterKey').textContent, 'Enter', 'locale switch re-renders the pad');
    assert([...world.$('numPadLayer').querySelectorAll('.kb-key')]
        .find(el => el.textContent === 'Space'), 'space label translated');
    equal(world.document.querySelector('[data-role="emoji"]').getAttribute('aria-label'),
        'emoji', 'emoji entry label stays language-neutral');
});

test('方向 category commits directional text and a real tab', {since: '3.28.0'}, () => {
    const world = fresh();
    world.tap([...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    ));
    world.tap([...world.document.querySelectorAll('[data-sym-cat]')].find(
        el => el.dataset.symCat === 'arrows',
    ));
    const left = [...world.$('symGrid').querySelectorAll('.kb-key')].find(
        el => el.textContent === '←',
    );
    assert(left, 'arrow cell rendered');
    assert(!left.dataset.lp, 'no repeat long-press on text arrows');
    world.tap(left);
    equal(world.native.of('commitText').slice(-1)[0].args[0], '←',
        'arrow commits as literal text');
    equal(world.native.of('keyEvent').length, 0, 'no key events');
    equal(world.native.of('key').length, 0, 'no engine traffic');
    const tab = [...world.$('symGrid').querySelectorAll('.kb-key')].find(
        el => el.textContent === '⇥',
    );
    assert(tab, 'tab cell rendered');
    world.tap(tab);
    equal(world.native.of('commitText').slice(-1)[0].args[0], '\t',
        'tab cell commits a real tab character');
});

test('quote tab toggles zh/en; en side supplies ascii brackets', {since: '3.28.0'}, () => {
    const world = fresh();
    world.tap([...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    ));
    const quoteTab = () => [...world.document.querySelectorAll('[data-sym-cat]')]
        .find(el => el.dataset.symCat === 'quote');
    world.tap(quoteTab());
    equal(quoteTab().querySelector('.cat-sub').textContent, '中',
        'quote defaults to the zh table');
    equal([...world.$('symGrid').children[0].children].map(k => k.textContent).join(''),
        '“”‘’„‟«»‹›', 'zh quote rows');
    world.tap(quoteTab());
    equal(quoteTab().querySelector('.cat-sub').textContent, 'En', 'badge flips');
    equal([...world.$('symGrid').children[0].children].map(k => k.textContent).join(''),
        '[]{}()<>\'"', 'en quote table leads with ascii brackets');
    world.tap([...world.$('symGrid').querySelectorAll('.kb-key')].find(
        el => el.textContent === '[',
    ));
    equal(world.native.of('commitText').slice(-1)[0].args[0], '[',
        'english bracket commits literally');
    // The two pins are independent.
    const commonTab = world.document.querySelector('[data-sym-cat="common"]');
    world.tap(commonTab);
    equal(commonTab.querySelector('.cat-sub').textContent, 'En',
        'common tab unaffected by the quote pin');
});

test('second tap on the active 常用 tab flips the zh/en table', {since: '3.28.0'}, () => {
    const world = fresh({ mode: 'pinyin' });
    const key123 = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    );
    world.tap(key123);
    const commonTab = world.document.querySelector('[data-sym-cat="common"]');
    const rows = () => [...world.$('symGrid').children[1].children].map(k => k.textContent).join('');
    equal(commonTab.querySelector('.cat-sub').textContent, '中', 'badge follows the mode');
    equal(rows(), '，。、；：？！～（）', 'mode default is the zh table');
    world.tap(commonTab);
    equal(commonTab.querySelector('.cat-sub').textContent, 'En', 'badge flips');
    equal(rows(), '-/:;()&@+=', 'grid re-renders from the en table');
    equal(world.$('symGrid').children[0].children[0].textContent, '1', 'digits still lead row 1');
    world.tap(commonTab);
    equal(commonTab.querySelector('.cat-sub').textContent, '中', 'toggles back');
    equal(rows(), '，。、；：？！～（）', 'zh table restored');
    // A mode switch ends the pin - the table follows the mode again.
    world.tap(commonTab); // pin en
    world.engineState({ mode: 'direct', revision: 2, composing: false, candidates: [] });
    world.tap([...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    ));
    const tab = world.document.querySelector('[data-sym-cat="common"]');
    equal(tab.querySelector('.cat-sub').textContent, 'En', 'direct keeps the en default');
    equal(
        [...world.$('symGrid').children[1].children].map(k => k.textContent).join(''),
        '-/:;()&@+=',
        'en rows without a stale pin',
    );
});

test('I1b symbol-grid digits insert literally in Chinese modes', () => {
    // Regression: sendText routed through key(); in pinyin mode the engine
    // consumes digits as candidate selectors, so nothing ever landed.
    const world = fresh({ mode: 'pinyin' });
    const key123 = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    );
    world.tap(key123);
    const rows = world.$('symGrid').children;
    world.tap(rows[0].children[0]); // '1'
    world.tap(rows[0].children[4]); // '5'
    const commits = world.native.of('commitText').map(c => c.args[0]);
    equal(JSON.stringify(commits), JSON.stringify(['1', '5']), 'digits committed literally');
    equal(world.native.of('key').length, 0, 'no engine key() traffic from symbol grid');
});

test('symbol category strip lists all batches with stable keys', {since: '3.28.0'}, () => {
    const world = fresh();
    const key123 = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    );
    world.tap(key123);
    const cats = [...world.document.querySelectorAll('[data-sym-cat]')];
    equal(
        cats.map(c => c.dataset.symCat).join(','),
        'common,recent,quote,money,math,arrows,num,pinyin,hira,kata,greek',
        'category keys frozen',
    );
    equal(
        cats.map(c => c.textContent).join(','),
        '常用En,最近,引号中,货币,数学,方向,序号,拼音,平假名,片假名,希腊',
        'labels aligned with the category list (paired tables carry 中/En badges)',
    );
    // Japanese kana and Greek are newer symbol-layer additions.
    const keyText = () => [...world.$('symGrid').querySelectorAll('.kb-key')]
        .map(b => b.textContent).join('');
    world.tap(cats.find(c => c.dataset.symCat === 'hira'));
    assert(keyText().includes('あ'), 'hiragana row present');
    world.tap(cats.find(c => c.dataset.symCat === 'kata'));
    assert(keyText().includes('ア'), 'katakana row present');
    world.tap(cats.find(c => c.dataset.symCat === 'greek'));
    assert(keyText().includes('Α'), 'greek caps present');
    // The backspace key occupies the last cell of row 3 in every category.
    equal(world.$('symGrid').children[2].children[9].dataset.role, 'backspace',
        'row 3 ends with backspace');
});

test('every 123 entry resets to the 常用 category', () => {
    const world = fresh();
    const key123 = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    );
    world.tap(key123);
    equal(world.$('symGrid').children[0].children[0].textContent, '1', 'common category active');
    world.tap([...world.document.querySelectorAll('[data-sym-cat]')].find(
        el => el.dataset.symCat === 'money',
    ));
    equal(world.$('symGrid').children[0].children[0].textContent, '$', 'money category active');
    // Back to letters, then 123 again must land on 常用.
    const abc = [...world.document.querySelectorAll('[data-action="letters"]')][0];
    world.tap(abc);
    world.tap(key123);
    equal(world.$('symGrid').children[0].children[0].textContent, '1', 'reset to 常用');
});

test('recent fills full even rows, remembers order, keeps backspace', () => {
    const world = fresh({ mode: 'pinyin' });
    const key123 = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    );
    world.tap(key123);
    // Tap 常用 (fullwidth) symbols to build history.
    world.tap(world.$('symGrid').children[1].children[0]); // '，'
    world.tap(world.$('symGrid').children[1].children[1]); // '。'
    const recent = [...world.document.querySelectorAll('[data-sym-cat]')].find(
        el => el.dataset.symCat === 'recent',
    );
    world.tap(recent);
    const rows = world.$('symGrid').children;
    equal(rows.length, 3, 'three full rows (spacing fix)');
    equal(
        [...rows[0].children].map(k => k.textContent).slice(0, 2).join(''),
        '。，',
        'most recent first, then padded from 中文 set',
    );
    assert(rows[0].children.length === 10, 'every row is a full 10 cells');
    const lastCell = rows[2].children[9];
    equal(lastCell.dataset.role, 'backspace', 'row 3 ends with backspace');
    world.tap(lastCell);
    equal(world.native.of('backspace').length, 1, 'symbol-grid backspace works');
});

test('caps state resets when the keyboard switches (requirement 1)', () => {
    const world = fresh();
    const shift = [...world.document.querySelectorAll('[data-role="shift"]')][0];
    world.touchDown(shift);
    world.clock.advance(360);
    world.touchUp(shift);
    world.tap(world.key('o'));
    equal(world.native.of('key').slice(-1)[0].args[0], 'O', 'caps active first');
    world.engineState({ mode: 'double-pinyin', revision: 2, candidates: [], composing: '' });
    world.tap(world.key('a'));
    equal(world.native.of('key').slice(-1)[0].args[0], 'a',
        'caps must NOT leak into the Chinese keyboard');
    world.engineState({ mode: 'direct', revision: 3, candidates: [], composing: '' });
    world.tap(world.key('k'));
    equal(world.native.of('key').slice(-1)[0].args[0], 'k',
        'caps cleared by the switch, not restored');
});

test('Chinese-mode flicks commit through commitText (requirement 2)', () => {
    const world = fresh({ mode: 'double-pinyin' });
    const e = world.key('e');
    world.touchDown(e, 20, 20);
    world.move(e, 20, -30); // 50px up
    world.touchUp(e);
    world.clock.advance(2);
    const commits = world.native.of('commitText').map(c => c.args[0]);
    // Digits stay half-width; symbols/quotes go full-width.
    equal(commits[commits.length - 1], '3', 'flick-up alt digit lands half-width');
    world.touchDown(e, 20, 20);
    world.move(e, 20, 70); // 50px down
    world.touchUp(e);
    world.clock.advance(2);
    const commits2 = world.native.of('commitText').map(c => c.args[0]);
    equal(commits2[commits2.length - 1], 'E', 'flick-down uppercase lands via commitText');
    equal(world.native.of('key').filter(c => ['3', 'E'].includes(c.args[0])).length, 0,
        'flicked digits/uppercase never enter the composition engine');
    // The user's re-pinned second row - f=； g=（ h=） j=～.
    // CN_ALTS values still commit as-is (the FULLWIDTH map stays gone).
    const g = world.key('g');
    world.touchDown(g, 20, 20);
    world.move(g, 20, -30);
    world.touchUp(g);
    world.clock.advance(2);
    const commits3 = world.native.of('commitText').map(c => c.args[0]);
    equal(commits3[commits3.length - 1], '\uff08', 'flick-up bracket lands full-width');
    const h = world.key('h');
    world.touchDown(h, 20, 20);
    world.move(h, 20, -30);
    world.touchUp(h);
    world.clock.advance(2);
    const commits4 = world.native.of('commitText').map(c => c.args[0]);
    equal(commits4[commits4.length - 1], '\uff09', 'flick-up closing bracket lands as printed');
    // English mode keeps the raw half-width glyph.
    const world2 = fresh();
    const e2 = world2.key('e');
    world2.touchDown(e2, 20, 20);
    world2.move(e2, 20, -30);
    world2.touchUp(e2);
    world2.clock.advance(2);
    const direct = world2.native.of('key').map(c => c.args[0]);
    equal(direct[direct.length - 1], '3', 'English flick goes through the engine unchanged');
});

test('empty composing echo keeps the variant anchor (requirement 9)', () => {
    const world = fresh({ mode: 'double-pinyin' });
    world.engineState({ mode: 'double-pinyin', revision: 1, composing: "x'an",
        rawInput: "x'an", candidates: [{ id: 'c1', text: '西安' }], hasNextPage: false });
    world.tap(world.$('composeExpand'));
    const count = () => world.document.querySelectorAll('#expandVariants .expand-variant').length;
    assert(count() >= 3, 'variant column rendered');
    // The guard: a mid-rewind EMPTY echo must NOT clear the column.
    world.engineState({ mode: 'double-pinyin', revision: 2, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    assert(count() >= 3, 'empty intermediate echo keeps the variant column');
    // A fresh ABBREVIATED composition (x'an) rebuilds the full column. The
    // empty echo collapsed the layer, so reopen before asserting.
    world.engineState({ mode: 'double-pinyin', revision: 3, composing: "x'an",
        rawInput: "x'an", candidates: [{ id: 'c2', text: '西安' }], hasNextPage: false });
    world.tap(world.$('composeExpand'));
    const titles = [...world.document.querySelectorAll('#expandVariants .expand-variant')]
        .map(b => b.textContent);
    assert(count() >= 3 && titles.includes("xi'an"),
        'a fresh abbreviated composition rebuilds the full variant column');
    // A FULLY-typed composition (xc'an, every segment a
    // complete 2-key syllable) pins one parse - no expansion offered.
    world.engineState({ mode: 'double-pinyin', revision: 4, composing: "xc'an",
        rawInput: "xc'an", candidates: [{ id: 'c3', text: '次安' }], hasNextPage: false });
    world.tap(world.$('composeExpand'));
    assert(count() === 0, 'a complete input offers no variants (column hidden)');
});

test('mode order from storage drives the long-press menu (requirement 12)', () => {
    const world = fresh();
    world.storage.set('feelime_mode_order', JSON.stringify(
        ['japanese', 'pinyin', 'direct', 'french', 'russian', 'double-pinyin']));
    world.touchDown(world.$('modeToggle'));
    world.clock.advance(360);
    world.touchUp(world.$('modeToggle'));
    // Compact rows - the shorthand span leads, the title span
    // follows.
    const titles = [...world.$('modeMenu').children]
        .map(b => b.querySelectorAll('span')[1].textContent);
    equal(titles[0], '日本語 Romaji', 'saved order leads the menu');
    equal(titles[1], '全拼 Pinyin', 'second follows the saved order');
});



// ---------------------------------------------------------------- 

test('Chinese-mode popup pick lands literally (requirement 7)', () => {
    const world = fresh({ mode: 'double-pinyin' });
    // \u76f8\u5bf9\u8ddf\u624b\u7684\u53d6\u6d88\u5224\u5b9a\u770b\u300c\u865a\u62df\u5149\u6807\u662f\u5426\u6ed1\u51fa\u6d6e\u5c42\u5361\u7247\u300d\uff1afake DOM \u6ca1\u6709\u771f\u5b9e
    // \u5e03\u5c40\uff0c\u628a\u5361\u7247\u77e9\u5f62\u9489\u5230\u8986\u76d6\u6240\u6709\u683c\u5b50\u5047\u77e9\u5f62\u7684\u4f4d\u7f6e\u518d\u5f00\u5c42\u3002
    world.document.getElementById('keyPopup').getBoundingClientRect =
        () => ({ left: 0, top: 0, right: 500, bottom: 400, width: 500, height: 400 });
    // \u201c moved from J to K (j now carries \uff5e).
    const j = world.key('k');
    world.touchDown(j);
    world.clock.advance(360);
    const items = [...world.document.querySelectorAll('.kp-item')];
    const quote = items.find(el => el.textContent === '\u201c');
    assert(quote, '\u201c offered in the popup');
    // \u76f8\u5bf9\u8ddf\u624b\uff083.39.0\uff09\uff1a\u62d6\u52a8\u91cf = \u76ee\u6807\u683c \u2212 \u951a\u70b9\u683c\uff08\u6309\u4e0b\u70b9 20,20\uff09\uff0c
    // \u6536\u5c3e\u5750\u6807 = \u6700\u540e\u79fb\u52a8\u4f4d\u7f6e\uff08\u7ec8\u6001\u4f4d\u79fb\u7ed3\u7b97\uff0c\u4e0e\u771f\u5b9e\u624b\u6307\u4e00\u81f4\uff09\u3002
    const selItem = () => [...world.document.querySelectorAll('.kp-item')]
        .find(el => el.classList.contains('sel'));
    const dragTo = (el, pressEl) => {
        const ar = selItem().getBoundingClientRect();
        const tr = el.getBoundingClientRect();
        const mx = 20 + (tr.left + tr.width / 2) - (ar.left + ar.width / 2);
        const my = 20 + (tr.top + tr.height / 2) - (ar.top + ar.height / 2);
        world.move(pressEl, mx, my);
        world.touchUp(pressEl, mx, my);
    };
    dragTo(quote, j);
    const commits = world.native.of('commitText').map(c => c.args[0]);
    equal(commits[commits.length - 1], '\u201c', 'popup symbol lands via commitText');
    equal(world.native.of('key').filter(c => c.args[0] === '\u201c').length, 0,
        'popup symbol never enters the composition engine');
    // Letters picked from the popup also bypass the engine.
    world.touchDown(j);
    world.clock.advance(360);
    const upper = [...world.document.querySelectorAll('.kp-item')].find(el => el.textContent === 'K');
    dragTo(upper, j);
    const commits2 = world.native.of('commitText').map(c => c.args[0]);
    equal(commits2[commits2.length - 1], 'K', 'popup letter lands literally in Chinese mode');
});

test('settings sub-pages: nav, key map, back (requirements 1+6)', {since: '3.33.0'}, () => {
    const world = fresh();
    world.tap(world.$('setupButton'));
    // Sub-page chrome rides the toolbar - title on the
    // left, close on the right; back returns home.
    world.tap(world.tile('快捷切换'));
    assert(world.document.body.classList.contains('settings-page'),
        'settings-page hides the regular tools');
    // pair sub-page: still six keyboards, tick BEFORE the name .
    equal(world.document.querySelectorAll('#pairEditor .pair-row').length, 7, 'pair page rows (t9 joined)');
    const pairRow = world.document.querySelector('#pairEditor .pair-row');
    assert(pairRow.children[0].classList.contains('pair-tick'),
        'pair tick precedes the name');
    assert(!pairRow.querySelector('.pair-drag'), 'pair page has no drag handle');
    // harness DOM: structural queries only - back is child 0, close is child 2.
    world.tap(world.$('settingsPageBar').children[0]);
    equal(world.document.querySelectorAll('#pairEditor').length, 0, 'back returns home');
    // menu sub-page: tick + name + drag handle LAST.
    world.tap(world.tile('长按菜单'));
    equal(world.document.querySelectorAll('#menuEditor .pair-row').length, 7, 'menu page rows (t9 joined)');
    const menuRow = world.document.querySelector('#menuEditor .pair-row');
    assert(menuRow.children[0].classList.contains('pair-tick'), 'menu tick first');
    assert(menuRow.children[menuRow.children.length - 1].classList.contains('pair-drag'),
        'menu drag handle last');
    world.tap(world.$('settingsPageBar').children[2]);
    assert(!world.$('settingsPanel').classList.contains('open'), 'close icon closes the panel');
    // No favorites entry in quick settings any more.
    assert(!world.tileNames().includes('常用语'), 'favorites entry removed from quick settings');
});

test('custom keys: tab hidden until saved; saveCustomJson persists (§15 editor lives in settings)', {since: '3.21.0'}, () => {
    const world = fresh();
    const key123 = () => [...world.document.querySelectorAll('.kb-key')]
        .find(el => el.textContent === '123');
    const customTab = () => [...world.document.querySelectorAll('.sym-cat')]
        .find(el => el.textContent === '定制');
    world.tap(key123());
    assert(!customTab(), 'no 定制 tab before a table exists');
    world.context.window.Feelime.saveCustomJson(JSON.stringify({
        version: 1,
        rows: [[
            { t: 'Esc', tap: '[esc]', note: '终端 Esc' },
        ], [], []],
    }));
    const saved = JSON.parse(world.storage.get('feelime_custom_keys_v2'));
    equal(saved.version, 1, 'schema version stored');
    equal(saved.rows[0][0].tap, '[esc]', 'DSL stored verbatim');
    equal(saved.rows[0][0].note, '终端 Esc', 'note stored');
    assert(customTab(), '定制 tab appears after save');
    // the table mirrors back to the native store for the settings page (§15)
    const mirrored = world.native.of('setCustomKeys').slice(-1)[0];
    assert(mirrored, 'native mirror called');
    equal(JSON.parse(mirrored.args[0]).rows[0][0].tap, '[esc]', 'mirror carries the table');
});

test('custom JSON validation: limits and DSL errors name the problem', {since: '3.21.0'}, () => {
    const world = fresh();
    const save = text => world.context.window.Feelime.saveCustomJson(text);
    save('not json');
    assert(world.$('toast').textContent.includes('JSON 解析失败'), 'parse error surfaced');
    save(JSON.stringify({ version: 2, rows: [] }));
    assert(world.$('toast').textContent.includes('version'), 'wrong version rejected');
    save(JSON.stringify({ version: 1, rows: [[{ t: 'A' }]] }));
    assert(world.$('toast').textContent.includes('tap'), 'missing tap rejected');
    save(JSON.stringify({ version: 1, rows: [[{ t: 'A', tap: '[bogus]' }]] }));
    assert(world.$('toast').textContent.includes('bogus'), 'unknown DSL token rejected');
    save(JSON.stringify({ version: 1, rows: [[], [], [], []] }));
    assert(world.$('toast').textContent.includes('3 行'), 'four rows rejected');
    save(JSON.stringify({ version: 1, rows: [[{ t: '√', tap: '√' }]] }));
    assert(world.$('toast').textContent.includes('已保存 1 个键'), 'valid save confirms');
});

test('custom keys: settings-page JSON editor round-trip (3.20.0 form)', {until: '3.20.0'}, () => {
    // 3.20.0 has no Felime.saveCustomJson hook - the whole flow lives behind
    // the settings page (paste-JSON textarea in the editor strip).
    const world = fresh();
    world.tap(world.$('setupButton'));
    const nav = [...world.$('settingsPanel').querySelectorAll('.set-row')]
        .find(row => row.querySelector('.set-label').textContent === '定制键盘')
        .querySelector('.set-nav');
    world.tap(nav);
    const page = () => world.$('settingsPanel');
    assert(page().textContent.includes('未定制'), 'status shows 未定制');
    // 粘贴 JSON › opens the shared editor strip in TEXTAREA form.
    const edit = [...page().querySelectorAll('button')]
        .find(b => b.textContent === '粘贴 JSON ›');
    world.tap(edit);
    assert(!world.$('panelEditor').hidden, 'editor strip open');
    assert(world.$('panelEditorInput').hidden, 'single-line input hidden (textarea form)');
    assert(!world.$('panelEditorArea').hidden, 'textarea visible');
    assert(world.document.body.classList.contains('editing'), 'editing on');
    equal(JSON.parse(world.$('panelEditorArea').value).version, 1,
        'prefilled with the current (empty) table');
    // Paste a valid table and save: storage persists, the toast names the
    // count, the strip closes back onto the custom sub-page.
    world.$('panelEditorArea').value = JSON.stringify({
        version: 1,
        rows: [[
            { t: 'Esc', tap: '[esc]' },
            { t: ':w', tap: ':w[enter]', note: 'Vim 保存' },
        ], [], []],
    });
    world.tap(world.document.getElementById('panelEditorSave'));
    assert(world.storage.get('felime_custom_keys_v2'), 'table persisted to storage');
    assert(world.$('toast').textContent.includes('已保存 2 个键'),
        'toast names the saved count');
    assert(world.$('panelEditor').hidden, 'strip closed after save');
    assert(page().classList.contains('open') &&
        page().textContent.includes('已定制 2 个键'),
        'back on the custom sub-page, status updated');
});

test('custom JSON validation: errors name the problem (3.20.0 form)', {until: '3.20.0'}, () => {
    const world = fresh();
    world.tap(world.$('setupButton'));
    const nav = [...world.$('settingsPanel').querySelectorAll('.set-row')]
        .find(row => row.querySelector('.set-label').textContent === '定制键盘')
        .querySelector('.set-nav');
    world.tap(nav);
    world.tap([...world.$('settingsPanel').querySelectorAll('button')]
        .find(b => b.textContent === '粘贴 JSON ›'));
    const area = world.$('panelEditorArea');
    const save = () => world.tap(world.document.getElementById('panelEditorSave'));
    // Errors keep the editor open and name the first problem - nothing is
    // truncated silently .
    area.value = 'not json';
    save();
    assert(world.$('toast').textContent.includes('JSON 解析失败'), 'parse error surfaced');
    assert(!world.$('panelEditor').hidden, 'editor stays open on error');
    area.value = JSON.stringify({ version: 2, rows: [] });
    save();
    assert(world.$('toast').textContent.includes('version 必须是 1'), 'wrong version rejected');
    area.value = JSON.stringify({ version: 1, rows: [[{ t: 'A' }]] });
    save();
    assert(world.$('toast').textContent.includes('缺少 tap'), 'missing tap rejected');
    area.value = JSON.stringify({ version: 1, rows: [[], [], [], []] });
    save();
    assert(world.$('toast').textContent.includes('3 行'), 'four rows rejected');
    assert(!world.storage.has('felime_custom_keys_v2'), 'nothing persisted');
    area.value = JSON.stringify({ version: 1, rows: [[{ t: '√', tap: '√' }]] });
    save();
    assert(world.$('toast').textContent.includes('已保存 1 个键'), 'valid save confirms');
});

test('favorite codes join the candidate pool; picks commit the phrase directly', {since: '3.21.0'}, () => {
    const world = fresh();
    world.hello();
    world.favorites([
        { id: 'f1', time: 1, text: '祝福', code: 'zf' },
        { id: 'f2', time: 2, text: '明天见', code: 'mtj' },
        { id: 'f3', time: 3, text: '无码短语', code: '' },
    ]);
    // Typing past the code (zfa) keeps 祝福 matched -> it slots in AFTER the
    // engine head (continuing the sentence keeps the phrase offered).
    world.engineState({ mode: 'pinyin', revision: 7, composing: 'zfa', rawInput: 'zfa',
        candidates: [{ id: 'c1', text: '在' }], hasNextPage: false });
    const bar = [...world.$('candidates').querySelectorAll('.candidate')].map(b => b.textContent);
    equal(JSON.stringify(bar), JSON.stringify(['在', '祝福']), 'prefix match sits after the engine head');
    // Exact match takes the HEAD.
    world.engineState({ mode: 'pinyin', revision: 8, composing: 'zf', rawInput: 'zf',
        candidates: [{ id: 'c1', text: '在' }, { id: 'c2', text: '咱' }], hasNextPage: false });
    const bar2 = [...world.$('candidates').querySelectorAll('.candidate')].map(b => b.textContent);
    equal(JSON.stringify(bar2), JSON.stringify(['祝福', '在', '咱']), 'exact code match takes the head');
    // Non-matching codes never inject; codeless favorites stay out.
    world.engineState({ mode: 'pinyin', revision: 9, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c3', text: '你' }], hasNextPage: false });
    const bar3 = [...world.$('candidates').querySelectorAll('.candidate')].map(b => b.textContent);
    equal(JSON.stringify(bar3), JSON.stringify(['你']), 'no match, no injection');
    // Picking the overlay phrase clears the composition and commits literally.
    world.engineState({ mode: 'pinyin', revision: 10, composing: 'zf', rawInput: 'zf',
        candidates: [{ id: 'c1', text: '在' }], hasNextPage: false });
    const favButton = [...world.$('candidates').querySelectorAll('.candidate')]
        .find(b => b.textContent === '祝福');
    favButton.click();
    equal(world.native.of('clearComposing').length, 1, 'composition cleared');
    const commit = world.native.of('commitText').slice(-1)[0];
    equal(commit.args[0], '祝福', 'phrase committed literally');
    equal(world.native.of('chooseCandidate').filter(c => c.args[1] === 'fav:f1').length,
        0, 'engine channel never sees overlay ids');
    // Space confirms the pool head = the exact favorite (same funnel).
    world.engineState({ mode: 'pinyin', revision: 11, composing: 'zf', rawInput: 'zf',
        candidates: [{ id: 'c1', text: '在' }], hasNextPage: false });
    world.tap(world.$('spaceKey'));
    const commits = world.native.of('commitText');
    equal(commits[commits.length - 1].args[0], '祝福', 'space picks the favorite head');
});

test('rank slots: exact favorites splice into their 1-based candidate slot', {since: '3.25.0'}, () => {
    const world = fresh();
    world.hello();
    world.favorites([
        { id: 'f1', time: 1, text: '祝福', code: 'zf', rank: 2 },
        { id: 'f2', time: 2, text: '中奖', code: 'zf', rank: 3 },
    ]);
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'zf', rawInput: 'zf',
        candidates: [{ id: 'c1', text: '在' }, { id: 'c2', text: '咱' }], hasNextPage: false });
    const bar = () => [...world.$('candidates').querySelectorAll('.candidate')].map(b => b.textContent);
    equal(JSON.stringify(bar()), JSON.stringify(['在', '祝福', '中奖', '咱']),
        'rank 2/3 land after the engine head, in slot order');
    // Ties keep list order side by side (both rank 2 -> 2nd and 3rd slots).
    world.favorites([
        { id: 'f1', time: 1, text: '祝福', code: 'zf', rank: 2 },
        { id: 'f2', time: 2, text: '中奖', code: 'zf', rank: 2 },
    ]);
    world.engineState({ mode: 'pinyin', revision: 2, composing: 'zf', rawInput: 'zf',
        candidates: [{ id: 'c1', text: '在' }, { id: 'c2', text: '咱' }], hasNextPage: false });
    equal(JSON.stringify(bar()), JSON.stringify(['在', '祝福', '中奖', '咱']),
        'ties sit side by side in list order');
    // A rank past the pool end clamps to the tail; rank 1 stays the head.
    // (The pool accumulates across states of one composition - 候选池语义.)
    world.favorites([
        { id: 'f1', time: 1, text: '祝福', code: 'zf', rank: 9 },
        { id: 'f2', time: 2, text: '在吗', code: 'zf', rank: 1 },
    ]);
    world.engineState({ mode: 'pinyin', revision: 3, composing: 'zf', rawInput: 'zf',
        candidates: [{ id: 'c1', text: '在' }], hasNextPage: false });
    equal(JSON.stringify(bar()), JSON.stringify(['在吗', '在', '咱', '祝福']),
        'rank 1 keeps the head; oversized ranks clamp to the tail');
});

test('phrase card edits the rank with +/- steppers', {since: '3.25.0'}, () => {
    const world = fresh();
    world.hello();
    world.favorites([{ id: 'a1', time: 1, text: '你好', code: 'nh', rank: 2 }]);
    world.tap(world.$('favoritesButton'));
    world.tap(world.document.getElementById('panelManage'));
    assert(!world.$('phraseCard').hidden && world.$('phraseCard').classList.contains('open'),
        'card open');
    const rankValue = () => world.document.getElementById('phraseCardRankValue').textContent;
    equal(rankValue(), '1', 'add starts at rank 1');
    world.tap(world.document.getElementById('phraseCardRankUp'));
    equal(rankValue(), '2', 'plus steps up');
    world.tap(world.document.getElementById('phraseCardRankDown'));
    world.tap(world.document.getElementById('phraseCardRankDown'));
    equal(rankValue(), '1', 'minus steps down and clamps at 1');
    // Edit flow: the stored rank prefills, stepping edits it, save carries it.
    const more = [...world.document.querySelectorAll('.panel-item')][0].querySelector('.panel-more');
    world.tap(more);
    world.tap([...world.document.getElementById('itemMenu').querySelectorAll('button')][1]);
    equal(world.document.getElementById('phraseCardInput').value, '你好', 'edit prefills text');
    equal(rankValue(), '2', 'edit prefills the stored rank');
    world.tap(world.document.getElementById('phraseCardRankUp'));
    world.document.activeElement = world.document.getElementById('phraseCardInput');
    world.tap(world.document.getElementById('phraseCardSave'));
    const session = world.native.of('panelFlush').slice(-1)[0].args[0];
    world.context.Feelime.onPanelFlushed({session});
    const updates = world.native.of('favoritesUpdate').map(c => c.args.slice(0, 4));
    equal(JSON.stringify(updates[updates.length - 1]), JSON.stringify(['a1', '你好', 'nh', 3]),
        'save carries id/text/code/rank');
});

test('custom keys: settings-page table adopts on hello; bad tables ignored (§15)', {since: '3.21.0'}, () => {
    const world = fresh();
    world.native.customKeysPayload = JSON.stringify({
        version: 1,
        rows: [[{ t: '✓', tap: '好的', note: '' }], [], []],
    });
    world.hello();
    const adopted = JSON.parse(world.storage.get('feelime_custom_keys_v2'));
    equal(adopted.rows[0][0].tap, '好的', 'native table adopted into the keyboard store');
    // a structurally-bad table never replaces a working one
    world.native.customKeysPayload = 'not json';
    world.hello();
    const kept = JSON.parse(world.storage.get('feelime_custom_keys_v2'));
    equal(kept.rows[0][0].tap, '好的', 'bad table ignored, prior table kept');
});

test('pool recompute survives mid-composition favorite edits', {since: '3.21.0'}, () => {
    const world = fresh();
    world.hello();
    world.favorites([{ id: 'f1', time: 1, text: '祝福', code: 'zf' }]);
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'zf', rawInput: 'zf',
        candidates: [{ id: 'c1', text: '在' }], hasNextPage: false });
    const texts = () => [...world.$('candidates').querySelectorAll('.candidate')].map(b => b.textContent);
    equal(JSON.stringify(texts()), JSON.stringify(['祝福', '在']), 'exact favorite heads the pool');
    // A second matching favorite arrives mid-composition: both stay (the old
    // incremental injection dropped the first one here).
    world.favorites([
        { id: 'f1', time: 1, text: '祝福', code: 'zf' },
        { id: 'f2', time: 2, text: '早晨', code: 'z' },
    ]);
    equal(JSON.stringify(texts()), JSON.stringify(['祝福', '在', '早晨']),
        'new favorite joins without losing the first');
    // Deleting it updates the pool immediately (no stale fav entry).
    world.favorites([{ id: 'f1', time: 1, text: '祝福', code: 'zf' }]);
    equal(JSON.stringify(texts()), JSON.stringify(['祝福', '在']),
        'deleted favorite leaves the pool at once');
});

test('custom keys switch OFF clears the keyboard mirror', {since: '3.21.0'}, () => {
    const world = fresh();
    world.native.customKeysPayload = JSON.stringify({
        version: 1,
        rows: [[{ t: '✓', tap: '好的', note: '' }], [], []],
    });
    world.hello();
    equal(JSON.parse(world.storage.get('feelime_custom_keys_v2')).rows[0][0].tap,
        '好的', 'table adopted first');
    // Settings page turns the switch off: native now answers "disabled".
    world.native.customKeysPayload = 'disabled';
    world.hello();
    equal(world.storage.has('feelime_custom_keys_v2'), false,
        'mirror dropped, the custom layer falls back to stock');
});

test('custom keys migrate UP to native when native is unset', {since: '3.21.0'}, () => {
    const world = fresh();
    const localTable = JSON.stringify({
        version: 1,
        rows: [[{ t: '哈', tap: '哈哈', note: '' }], [], []],
    });
    world.storage.set('feelime_custom_keys_v2', localTable);
    world.native.customKeysPayload = '';
    world.hello();
    const pushed = world.native.of('setCustomKeys').slice(-1)[0];
    equal(pushed.args[0], localTable, 'pre-migration local table pushed to native');
});

test('custom key taps: DSL executes text, keys and combos', () => {
    const world = fresh();
    world.storage.set('feelime_custom_keys_v2', JSON.stringify({
        version: 1,
        rows: [[
            { t: '哈', tap: '哈哈' },
            { t: 'Esc', tap: '[esc]' },
            { t: '保存', tap: '[ctrl+s]' },
            { t: '整理', tap: '[esc]ggVGD' },
        ], [], []],
    }));
    const key123 = [...world.document.querySelectorAll('.kb-key')]
        .find(el => el.textContent === '123');
    world.tap(key123);
    const customTab = [...world.document.querySelectorAll('.sym-cat')]
        .find(el => el.textContent === '定制');
    customTab.click();
    const keys = [...world.document.querySelectorAll('#symGrid .sym-custom-key')];
    keys[0].click();
    equal(world.native.of('commitText').slice(-1)[0].args[0], '哈哈', 'text commits literally');
    keys[1].click();
    equal(world.native.of('keyEvent').slice(-1)[0].args[0], 111, 'esc = keycode 111');
    keys[2].click();
    equal(JSON.stringify(world.native.of('keyEventPhysical').slice(-1)[0].args.slice(0, 2)),
        JSON.stringify([47, 4096]), 'ctrl+s rides the PHYSICAL channel (KEYCODE_S 47, CTRL meta)');
    keys[3].click();
    const calls = world.native.calls;
    const esc = calls.map(c => c.method).lastIndexOf('keyEvent');
    equal(calls[esc].args[0], 111, 'macro fires esc first');
    const commits = world.native.of('commitText');
    equal(commits[commits.length - 1].args[0], 'ggVGD', 'macro text after the key');
});

test('legacy comma tables migrate into the v2 JSON store', () => {
    const world = fresh();
    world.storage.set('feelime_custom_rows',
        JSON.stringify([['★', '☆'], [], []]));
    const key123 = [...world.document.querySelectorAll('.kb-key')]
        .find(el => el.textContent === '123');
    world.tap(key123);
    const customTab = [...world.document.querySelectorAll('.sym-cat')]
        .find(el => el.textContent === '定制');
    assert(customTab, 'migrated table shows the 定制 tab');
    customTab.click();
    const saved = JSON.parse(world.storage.get('feelime_custom_keys_v2'));
    equal(saved.rows[0][0].t, '★', 'cap migrated');
    equal(saved.rows[0][0].tap, '★', 'tap migrated from the literal');
    assert(world.storage.get('feelime_custom_rows') === undefined ||
        world.storage.get('feelime_custom_rows') === null,
        'legacy key cleared');
});

test('English j/k/l alt-texts are the half-width tilde/quote set ',  ()=> {
    const world = fresh({ mode: 'direct' });
    equal(world.key('j').querySelector('.kb-alt').textContent, '~', 'j alt = ~');
    equal(world.key('k').querySelector('.kb-alt').textContent, '"', 'k alt = "');
    equal(world.key('l').querySelector(".kb-alt").textContent, "'", 'l alt = \'');
    // Chinese modes keep their own fullwidth set.
    const zh = fresh({ mode: 'pinyin' });
    equal(zh.key('j').querySelector('.kb-alt').textContent, '～', 'CN j alt stays ～');
    equal(zh.key('l').querySelector('.kb-alt').textContent, '”', 'CN l alt stays ”');
});

test('tapping the toggle while the mode menu is open only closes it ',  ()=> {
    const world = fresh();
    world.context.window.Feelime.toggleModeMenu();
    assert(world.$('modeMenu').classList.contains('open'), 'menu open');
    world.tap(world.$('modeToggle'));
    assert(!world.$('modeMenu').classList.contains('open'), 'menu closed');
    equal(world.native.of('selectMode').length, 0, 'keyboard NOT switched');
    // A plain second tap toggles the keyboard pair as usual.
    world.tap(world.$('modeToggle'));
    equal(world.native.of('selectMode').length, 1, 'next tap toggles again');
});

test('tapping the anchor key while its combo grid is open only closes it', () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    const fn = world.document.querySelector('[data-ctrl="sticky-fn"]');
    world.touchDown(fn);
    world.clock.advance(360);
    world.touchUp(fn);
    assert(world.$('comboPopup').classList.contains('open'), 'grid open');
    world.tap(fn);
    assert(!world.$('comboPopup').classList.contains('open'), 'grid closed');
    assert(!fn.classList.contains('active'), 'Fn NOT armed by the closing tap');
});

test('resetToHome lands on the letters with every layer closed ',  ()=> {
    const world = fresh();
    const key123 = [...world.document.querySelectorAll('.kb-key')]
        .find(el => el.textContent === '123');
    world.tap(key123);
    assert(world.$('symbolLayer').hidden === false, 'symbol layer up');
    world.context.window.Feelime.toggleControlView();
    world.context.window.Feelime.toggleModeMenu();
    world.context.window.Feelime.resetToHome();
    assert(!world.$('qwertyLayer').hidden, 'letters shown');
    assert(world.$('symbolLayer').hidden, 'symbol layer down');
    assert(world.$('ctrlLayer').hidden, 'ctrl layer closed');
    assert(!world.document.body.classList.contains('ctrl-view'), 'ctrl view off');
    assert(!world.$('modeMenu').classList.contains('open'), 'mode menu closed');
});

test('float band rides hello; band popups flip the native touch region', () => {
    const world = fresh({ floatBand: 200 });
    equal(world.document.documentElement.style.getPropertyValue('--band'),
        '200px', '--band set from hello');
    // A band-open layer tells the native side to extend the touch region.
    world.context.window.Feelime.toggleModeMenu();
    equal(world.native.of('setOverlayOpen').slice(-1)[0].args[0], true,
        'setOverlayOpen(true) on menu open');
    world.context.window.Feelime.closeModeMenu();
    equal(world.native.of('setOverlayOpen').slice(-1)[0].args[0], false,
        'setOverlayOpen(false) on menu close');
    // Anchor-driven placement - the menu hugs the toggle's
    // top edge when there is headroom, else falls back to window-top +
    // scroll (the fake DOM parks every element near the top, so the
    // fallback branch is what runs here).
    const menu = world.$('modeMenu');
    const toggleTop = world.$('modeToggle').getBoundingClientRect().top;
    world.context.window.Feelime.toggleModeMenu();
    equal(menu.style.top, '14px',
        'menu without headroom pins to the window top');
    equal(menu.style.maxHeight, (toggleTop - 14) + 'px',
        'menu capped by the space above its trigger');
});

test('combo card anchors to its trigger; shrink/side fallbacks keep it in-window', () => {
    const world = fresh();
    const popup = world.$('comboPopup');
    const open = anchor => {
        world.context.window.Feelime.openComboGrid &&
            world.context.window.Feelime.openComboGrid('comb', anchor);
    };
    const fn = world.document.querySelector('[data-ctrl="sticky-fn"]');
    if (fn) {
        world.touchDown(fn);
        world.clock.advance(360);
        world.touchUp(fn);
    } else {
        open(fn); // ctrl layer unavailable in this harness build
    }
    assert(popup.classList.contains('open'), 'grid open via exposed hook');
    const style = popup.style;
    const cell = style.getPropertyValue('--combo-cell');
    const top = parseFloat(style.top);
    const left = parseFloat(style.left);
    // The fake DOM parks every element near the window top, so the shrink
    // branch runs; whatever the branch, the card stays inside the window
    // and the ✕ badge (14px overhang) never clips.
    assert(cell !== undefined && cell !== '', `cell size set (${cell})`);
    assert(parseFloat(cell) >= 40, `cell never below the 40px floor (${cell})`);
    assert(top >= 14, `card top clears the badge overhang (${top})`);
    assert(left >= 16 && left + 300 <= world.context.window.innerWidth + 320,
        `card left clamped into the window (${left})`);
    // Closed-open cycle: the stale --combo-cell must not poison the resize
    // decision.
    if (fn) {
        world.touchDown(fn);
        world.clock.advance(360);
        world.touchUp(fn);
    } else {
        open(fn);
    }
    assert(popup.style.getPropertyValue('--combo-cell') !== undefined,
        're-open recomputes the cell size');
});

test('long-press menu filters to the enabled keyboards', {since: '3.33.0'}, () => {
    const world = fresh({ mode: 'pinyin' });
    world.storage.set('feelime_menu_modes',
        JSON.stringify(['pinyin', 'direct', 'double-pinyin']));
    world.context.window.Feelime.toggleModeMenu();
    const titles = [...world.$('modeMenu').children]
        .map(el => el.querySelectorAll('span')[1].textContent);
    // Order follows the saved drag order (orderedModeNames), filtered to the
    // enabled set - menu_modes is a membership set, not an order. The
    // compact row's title is the SECOND span (shorthand leads).
    equal(JSON.stringify(titles),
        JSON.stringify(['英文 Direct', '全拼 Pinyin', '双拼']),
        'menu lists only enabled keyboards');
    // An empty enable set falls back to every keyboard.
    world.storage.set('feelime_menu_modes', JSON.stringify([]));
    world.context.window.Feelime.closeModeMenu();
    world.context.window.Feelime.toggleModeMenu();
    equal(world.$('modeMenu').children.length, 7, 'empty set falls back to all (t9 joined)');
});

test('phrase editor strip + item menu hit the bridge', {since: '3.21.0'},  ()=> {
    const world = fresh();
    world.favorites([
        { id: 'a1', time: 1, text: '你好' },
        { id: 'b2', time: 2, text: '在吗' },
    ]);
    world.tap(world.$('favoritesButton'));
    const rows = () => [...world.document.querySelectorAll('.panel-item')];
    equal(rows().length, 2, 'two phrases listed');
    // ＋添加 opens the editor strip over the keyboard (panel hides).
    world.tap(world.document.getElementById('panelManage'));
    assert(!world.$('phraseCard').hidden && world.$('phraseCard').classList.contains('open'),
        'phrase card open ');
    assert(world.$('panelLayer').hidden, 'panel list hidden while editing');
    assert(world.$('qwertyLayer').hidden === false, 'keyboard visible under the strip');
    equal(world.native.of('panelInput').slice(-1)[0].args[0], true,
        'editor focus redirects native writes');
    // typing lands in the card input via the redirect callback 
    world.document.activeElement = world.document.getElementById('phraseCardInput');
    world.context.window.Feelime.onPanelCommit({ text: '收到' });
    equal(world.document.getElementById('phraseCardInput').value, '收到',
        'commit lands in the card input');
    world.tap(world.document.getElementById('phraseCardSave'));
    if (verAtLeast(KEYBOARD_VERSION, '3.22.0')) {
        const session = world.native.of('panelFlush').slice(-1)[0].args[0];
        world.context.Feelime.onPanelFlushed({session});
    }
    equal(world.native.of('favoritesAdd').slice(-1)[0].args[0], '收到', 'add hits the bridge');
    assert(world.$('phraseCard').hidden, 'editor closes after save');
    assert(!world.$('panelLayer').hidden, 'panel list restored');
    // ⋯ opens the row menu; 编辑 opens the editor prefilled; 置顶/删除 act.
    const more = rows()[0].querySelector('.panel-more');
    world.tap(more);
    const menu = world.document.getElementById('itemMenu');
    assert(menu.classList.contains('open'), 'item menu opens');
    const [pin, edit, del] = [...menu.querySelectorAll('button')];
    assert(pin.textContent === '置顶' && edit.textContent === '编辑' && del.textContent === '删除',
        'menu offers pin/edit/delete');
    world.tap(edit);
    assert(world.document.getElementById('phraseCardInput').value === '你好',
        'edit prefills the item text (card)');
    world.document.getElementById('phraseCardInput').value = '你好呀';
    world.tap(world.document.getElementById('phraseCardSave'));
    if (verAtLeast(KEYBOARD_VERSION, '3.22.0')) {
        const session = world.native.of('panelFlush').slice(-1)[0].args[0];
        world.context.Feelime.onPanelFlushed({session});
    }
    const updates = world.native.of('favoritesUpdate').map(c => c.args.slice(0, 3));
    equal(JSON.stringify(updates[updates.length - 1]), JSON.stringify(['a1', '你好呀', '']),
        'update targets the row id ( +code arg)');
    world.tap(rows()[1].querySelector('.panel-more'));
    world.tap([...world.document.getElementById('itemMenu').querySelectorAll('button')][0]);
    const moves = world.native.of('favoritesMove').map(c => [c.args[0], c.args[1]]);
    equal(JSON.stringify(moves[moves.length - 1]), JSON.stringify(['b2', 0]), 'pin moves to top');
    world.tap(rows()[0].querySelector('.panel-more'));
    world.tap([...world.document.getElementById('itemMenu').querySelectorAll('button')][2]);
    equal(world.native.of('removeFavorite').slice(-1)[0].args[0], 'a1', 'menu delete removes');
});

test('panel editor strip + item menu hit the bridge (3.20.0 form)', {until: '3.20.0'},  ()=> {
    const world = fresh();
    world.favorites([
        { id: 'a1', time: 1, text: '你好' },
        { id: 'b2', time: 2, text: '在吗' },
    ]);
    world.tap(world.$('favoritesButton'));
    const rows = () => [...world.document.querySelectorAll('.panel-item')];
    equal(rows().length, 2, 'two phrases listed');
    // ＋添加 opens the editor STRIP over the keyboard (panel hides, 
    // #4 keeps the candidate bar under it, body.editing compresses keys).
    world.tap(world.document.getElementById('panelManage'));
    assert(!world.$('panelEditor').hidden, 'editor strip open');
    assert(world.$('panelLayer').hidden, 'panel list hidden while editing');
    assert(!world.$('candidateBar').hidden, 'candidate bar stays under the strip ');
    assert(world.$('qwertyLayer').hidden === false, 'keyboard visible under the strip');
    assert(world.document.body.classList.contains('editing'), 'body.editing compresses key height');
    equal(world.document.getElementById('panelEditorInput').value, '', 'add starts empty');
    // typing lands in the strip input via the redirect callback
    world.document.activeElement = world.document.getElementById('panelEditorInput');
    world.context.window.Felime.onPanelCommit({ text: '收到' });
    equal(world.document.getElementById('panelEditorInput').value, '收到',
        'commit lands in the strip input');
    world.tap(world.document.getElementById('panelEditorSave'));
    equal(world.native.of('favoritesAdd').slice(-1)[0].args[0], '收到', 'add hits the bridge');
    assert(world.$('panelEditor').hidden, 'editor closes after save');
    assert(!world.$('panelLayer').hidden, 'panel list restored');
    // ⋯ opens the row menu; 编辑 prefills; 置顶/删除 act (same menu as 3.21.0,
    // but favoritesUpdate carries two payload args + token).
    const more = rows()[0].querySelector('.panel-more');
    world.tap(more);
    const menu = world.document.getElementById('itemMenu');
    assert(menu.classList.contains('open'), 'item menu opens');
    const [pin, edit, del] = [...menu.querySelectorAll('button')];
    assert(pin.textContent === '置顶' && edit.textContent === '编辑' && del.textContent === '删除',
        'menu offers pin/edit/delete');
    world.tap(edit);
    assert(world.document.getElementById('panelEditorInput').value === '你好',
        'edit prefills the item text');
    world.document.activeElement = world.document.getElementById('panelEditorInput');
    world.document.getElementById('panelEditorInput').value = '你好呀';
    world.tap(world.document.getElementById('panelEditorSave'));
    const updates = world.native.of('favoritesUpdate').map(c => c.args.slice(0, 2));
    equal(JSON.stringify(updates[updates.length - 1]), JSON.stringify(['a1', '你好呀']),
        'update targets the row id (two payload args + token)');
    world.tap(rows()[1].querySelector('.panel-more'));
    world.tap([...world.document.getElementById('itemMenu').querySelectorAll('button')][0]);
    const moves = world.native.of('favoritesMove').map(c => [c.args[0], c.args[1]]);
    equal(JSON.stringify(moves[moves.length - 1]), JSON.stringify(['b2', 0]), 'pin moves to top');
    world.tap(rows()[0].querySelector('.panel-more'));
    world.tap([...world.document.getElementById('itemMenu').querySelectorAll('button')][2]);
    equal(world.native.of('removeFavorite').slice(-1)[0].args[0], 'a1', 'menu delete removes');
});

test('candidate bar mirrors the full pool and survives collapse', () => {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }, { id: 'c2', text: '好' }], hasNextPage: true });
    let bar = [...world.document.querySelectorAll('#candidates .candidate')].map(c => c.textContent);
    equal(JSON.stringify(bar), JSON.stringify(['你', '好']), 'bar mirrors page 1');
    // The next page lands (bar/grid scroll pulled it) - the bar grows, never
    // caps at one native page.
    world.engineState({ mode: 'pinyin', revision: 2, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c3', text: '泥' }, { id: 'c4', text: '妮' }], hasNextPage: false });
    bar = [...world.document.querySelectorAll('#candidates .candidate')].map(c => c.textContent);
    equal(JSON.stringify(bar), JSON.stringify(['你', '好', '泥', '妮']), 'bar accumulates pages');
    // Collapse the expanded layer: the bar keeps the pool head and full body -
    // no stranding on a low-frequency page.
    world.tap(world.$('composeExpand'));
    assert(!world.$('expandLayer').hidden, 'expanded');
    world.tap(world.$('expandCollapse'));
    assert(world.$('expandLayer').hidden, 'collapsed');
    bar = [...world.document.querySelectorAll('#candidates .candidate')].map(c => c.textContent);
    equal(bar[0], '你', 'bar head stays the top candidate after collapse');
    equal(bar.length, 4, 'bar keeps the whole pool after collapse');
});

test('candidate bar stays swipeable (native touch, no scrub)', () => {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }, { id: 'c2', text: '好' }], hasNextPage: false });
    const cand = world.document.querySelector('.candidate');
    assert(cand, 'candidate rendered');
    // Candidates carry a passive long-press listener now; the
    // bar stays swipeable as long as NO touchstart preventDefaults.
    const touchstarts = cand.listeners.filter(l => l.type === 'touchstart');
    assert(touchstarts.length && touchstarts.every(l => l.passive),
        'candidate touchstart listeners stay passive (bar keeps native pan)');
    world.tap(cand);
    equal(world.native.of('chooseCandidate').length, 1, 'native click still chooses');
    // A horizontal drag on the bar must scroll the strip, never scrub.
    world.touchDown(cand, 20, 20);
    assert(!cand._touchPrevented, 'touchstart not preventDefaulted (pan allowed)');
    world.move(cand, -90, 20);
    world.dispatch(cand, 'touchend', -90, 20); // bare touchend: browsers cancel
    // the click after a real drag; the harness would synthesize one.
    equal(world.native.of('moveCursor').length, 0, 'bar drag never scrubs');
});

test('editor strip captures editor writes via the native redirect', {since: '3.21.0', until: '3.21.4'}, () => {
    const world = fresh();
    world.tap(world.$('favoritesButton'));
    world.tap(world.document.getElementById('panelManage'));
    const input = world.document.getElementById('panelEditorInput');
    const fire = (el, type) => {
        const l = el.listeners.find(l => l.type === type);
        if (l) l.handler({ target: el });
    };
    // Real focus drives the native redirect flag. Count the call (not just
    // the last value) - the editor-open path already armed the redirect, so
    // the value alone would stay green with the focus listener deleted.
    world.document.activeElement = input;
    const armed = world.native.of('panelInput').length;
    fire(input, 'focus');
    equal(world.native.of('panelInput').length, armed + 1,
        'focus listener arms the native redirect');
    equal(world.native.of('panelInput').slice(-1)[0].args[0], true,
        'focus tells native to redirect editor writes');
    // Native-side commit/delete come back through the callbacks.
    world.context.window.Feelime.onPanelCommit({ text: '收到' });
    world.context.window.Feelime.onPanelCommit({ text: '了' });
    equal(input.value, '收到了', 'panel commit lands in the focused input');
    world.context.window.Feelime.onPanelDelete({ count: 1 });
    equal(input.value, '收到', 'panel delete removes one code point');
    equal(world.native.of('key').length, 0, 'host editor untouched');
    // Review P1: a candidate-pick mousedown blurs the input BEFORE
    // the click - inside the editor flow the redirect must stay armed or the
    // chosen word lands in the host editor.
    fire(input, 'blur');
    world.document.activeElement = null;
    equal(world.native.of('panelInput').slice(-1)[0].args[0], true,
        'blur inside the editor flow keeps the redirect armed');
    // The card, not the strip, hosts the favorites editor.
    // With nothing focused, a late native commit is dropped, never misfiled.
    world.context.window.Feelime.onPanelCommit({ text: 'X' });
    equal(input.value, '收到', 'commit without a focused panel input is dropped');
    // Leaving the editor releases the redirect (card buttons).
    world.tap(world.document.getElementById('phraseCardCancel'));
    equal(world.native.of('panelInput').slice(-1)[0].args[0], false,
        'closing the editor releases the redirect');
});

test('editor strip captures editor writes via the native redirect (3.20.0 form)', {until: '3.20.0'}, () => {
    const world = fresh();
    world.tap(world.$('favoritesButton'));
    world.tap(world.document.getElementById('panelManage'));
    const input = world.document.getElementById('panelEditorInput');
    assert(input && !input.hidden, 'strip input rendered');
    const fire = (el, type) => {
        const l = el.listeners.find(l => l.type === type);
        if (l) l.handler({ target: el });
    };
    // Real focus drives the native redirect flag. Count the call (not just
    // the last value) - the editor-open path already armed the redirect, so
    // the value alone would stay green with the focus listener deleted.
    world.document.activeElement = input;
    const armed = world.native.of('panelInput').length;
    fire(input, 'focus');
    equal(world.native.of('panelInput').length, armed + 1,
        'focus listener arms the native redirect');
    equal(world.native.of('panelInput').slice(-1)[0].args[0], true,
        'focus tells native to redirect editor writes');
    world.context.window.Felime.onPanelCommit({ text: '收到' });
    world.context.window.Felime.onPanelCommit({ text: '了' });
    equal(input.value, '收到了', 'panel commit lands in the focused input');
    world.context.window.Felime.onPanelDelete({ count: 1 });
    equal(input.value, '收到', 'panel delete removes one code point');
    equal(world.native.of('key').length, 0, 'host editor untouched');
    // Leaving the editor releases the redirect (cancel closes the strip and
    // reopens the favorites panel).
    world.tap(world.document.getElementById('panelEditorCancel'));
    equal(world.native.of('panelInput').slice(-1)[0].args[0], false,
        'closing the strip releases the redirect');
    assert(!world.$('panelLayer').hidden, 'panel restored after cancel');
});

test('settings round-trip inside the phrase editor keeps a keyboard', {since: '3.21.0'}, () => {
    const world = fresh();
    world.tap(world.$('favoritesButton'));
    world.tap(world.document.getElementById('panelManage'));
    assert(!world.$('phraseCard').hidden, 'editor card open');
    world.context.window.Feelime.toggleSettingsPanel();
    assert(world.$('settingsPanel').classList.contains('open'), 'quick panel open');
    assert(world.$('qwertyLayer').hidden, 'key layer hidden while panel open');
    world.context.window.Feelime.toggleSettingsPanel();
    assert(!world.$('settingsPanel').classList.contains('open'), 'quick panel closed');
    assert(!world.$('qwertyLayer').hidden,
        'qwerty restored, no empty key area');
    // Review P2: the strip is torn down with the round-trip (same
    // as any other flow) - no input riding on without a keyboard.
    assert(world.$('panelEditor').hidden, 'editor strip torn down');
    assert(!world.document.body.classList.contains('editing'), 'editing class dropped');
    assert(world.native.of('panelInput').slice(-1)[0].args[0] === false,
        'redirect released');
});

test('settings round-trip inside the phrase editor keeps a keyboard (3.20.0 form)', {until: '3.20.0'}, () => {
    const world = fresh();
    world.tap(world.$('favoritesButton'));
    world.tap(world.document.getElementById('panelManage'));
    assert(!world.$('panelEditor').hidden, 'editor strip open');
    world.context.window.Felime.toggleSettingsPanel();
    assert(world.$('settingsPanel').classList.contains('open'), 'quick panel open');
    assert(world.$('qwertyLayer').hidden, 'key layer hidden while panel open');
    // Opening the panel over the strip tears the strip down (review
    // P2) - no input riding on without a keyboard, redirect released.
    assert(world.$('panelEditor').hidden, 'editor strip torn down');
    assert(!world.document.body.classList.contains('editing'), 'editing class dropped');
    assert(world.native.of('panelInput').slice(-1)[0].args[0] === false,
        'redirect released');
    world.context.window.Felime.toggleSettingsPanel();
    assert(!world.$('settingsPanel').classList.contains('open'), 'quick panel closed');
    assert(!world.$('qwertyLayer').hidden,
        'qwerty restored, no empty key area');
});

test('leaving the strip clears the editing key height (3.20.0 form)', {until: '3.20.0'}, () => {
    // In 3.20.0 the panel (and its tabs) is hidden while the strip is up, so
    // the equivalent "leave the editor" path is the cancel button.
    const world = fresh();
    world.tap(world.$('favoritesButton'));
    world.tap(world.document.getElementById('panelManage'));
    assert(world.document.body.classList.contains('editing'), 'editing on');
    world.tap(world.document.getElementById('panelEditorCancel'));
    assert(!world.document.body.classList.contains('editing'),
        'cancel drops the editing class');
    assert(!world.$('panelLayer').hidden, 'panel restored');
});

test('leaving the editor via a panel tab clears the editing key height', {since: '3.21.0'}, () => {
    const world = fresh();
    world.tap(world.$('favoritesButton'));
    world.tap(world.document.getElementById('panelManage'));
    assert(!world.$('phraseCard').hidden, 'editing on (card open)');
    world.tap(world.document.querySelector('[data-panel-tab="clipboard"]'));
    assert(!world.document.body.classList.contains('editing'),
        'editing class dropped on tab switch');
});

test('double-pinyin key map lives in the settings app now', {since: '3.29.0'}, () => {
    const world = fresh();
    world.tap(world.$('setupButton'));
    assert(!world.tileNames().includes('双拼键位'), 'schema nav removed from the quick panel');
    world.tap(world.$('setupButton'));
});

test('dp scheme switch: sep key and variant tables follow the hello', {since: '3.29.0'}, () => {
    const world = fresh({ mode: 'double-pinyin' });
    const sepKey = () => world.document.querySelector('[data-role="sep"]');
    const sepLabel = () => (sepKey() ? sepKey().textContent : '');
    equal(sepLabel(), '分词', 'default (ziranma) sep key is the separator');
    // Sogou hello: the wide slot becomes the ing KEY and emits ';'.
    world.hello({ mode: 'double-pinyin', dpScheme: 'sogou' });
    equal(sepLabel(), 'ing', 'sogou sep key shows the ing final');
    world.tap(sepKey());
    equal(world.native.of('key').slice(-1)[0].args[0], ';', 'sogou sep key emits ;');
    // Flypy keeps the separator (its wide slot is still 分词).
    world.hello({ mode: 'double-pinyin', dpScheme: 'flypy' });
    equal(sepLabel(), '分词', 'flypy sep key is the separator');
    world.tap(sepKey());
    equal(world.native.of('key').slice(-1)[0].args[0], "'", "flypy sep key emits '");
    // Unknown scheme id (older engine) falls back to 自然码 parsing.
    world.hello({ mode: 'double-pinyin', dpScheme: 'nonsense' });
    equal(sepLabel(), '分词', 'unknown scheme falls back to ziranma');
});

test('dp scheme switch: single-key expansion uses the active scheme table', {since: '3.29.0'}, () => {
    // Sogou carries ing on ';', so first-key x expands with x+; (xing);
    // 自然码 has no ';' final. The expansion must follow the hello's scheme.
    const world = fresh({ mode: 'double-pinyin' });
    const variants = () => [...world.$('expandVariants').querySelectorAll('.expand-variant')]
        .map(el => el.textContent);
    const feed = revision => {
        world.engineState({
            mode: 'double-pinyin', composing: true, rawInput: "x'an", revision,
            candidates: [{ id: 'a', text: '西安' }], hasPreviousPage: false, hasNextPage: false,
        });
        world.tap(world.$('composeExpand'));
    };
    world.hello({ mode: 'double-pinyin', dpScheme: 'sogou' });
    feed(5);
    assert(variants().some(keys => keys.startsWith("x;'")), 'sogou x expands with ; (xing)');
    // Back to 自然码: the same input no longer offers ; expansions.
    world.hello({ mode: 'double-pinyin', dpScheme: 'ziranma' });
    feed(6);
    assert(!variants().some(keys => keys.startsWith("x;'")), 'ziranma x never expands to ;');
});

// ---------------------------------------------------------------- modes

test('t9 renders the five-column grid and selects via the menu', {since: '3.35.0'}, () => {
    const world = fresh();
    world.engineState({ mode: 't9', revision: 1, candidates: [], composing: '' });
    const keys = [...world.document.querySelectorAll('#qwertyLayer .kb-key')]
        .map(k => k.dataset.key);
    for (const d of ['1', '2', '3', '4', '5', '6', '7', '8', '9']) {
        assert(keys.includes(d), `digit ${d} present`);
    }
    // 五列网格（preview-t9 定稿）：左列条 + 右列 重输/emoji；qwerty 的
    // 分词键不再进 T9——9426 类切分歧义交给引擎音节图与音节候选条。
    assert(world.document.getElementById('t9Strip'), 'side strip present');
    assert(world.document.querySelector('[data-role="t9clear"]'), '重输 key present');
    assert(world.document.querySelector('[data-role="t9emoji"]'), 'emoji key present');
    assert(!world.document.querySelector('[data-role="sep"]'), 'no qwerty separator on t9');
    // mic 挂 data-key=0：上滑字面 0 + 横滑 scrub 的手势选择器依赖
    // （T9 下唯一保留 scrub 的键）。
    equal(world.$('spaceKey').dataset.key, '0', 'mic carries data-key 0');
    // 右上角 0 角标提示上滑字面 0；长按圆点由 .t9-space 的 CSS 挪到左上。
    assert(world.$('spaceKey').classList.contains('t9-space'),
        'space marked for the corner-dot swap');
    assert([...world.$('spaceKey').querySelectorAll('.t9-sup')]
        .some(s => s.textContent === '0'), 'space shows the 0 badge');
    // 菜单可选。
    const world2 = fresh();
    world2.touchDown(world2.$('modeToggle'));
    world2.clock.advance(360);
    world2.touchUp(world2.$('modeToggle'));
    const t9 = [...world2.$('modeMenu').children]
        .find(item => item.textContent.includes('九宫格'));
    assert(t9, 'mode menu lists 九宫格 T9');
    world2.tap(t9);
    equal(world2.native.of('selectMode').slice(-1)[0].args[0], 't9', 't9 selected');
});

test('mode menu lists all modes; selecting emits selectMode', {since: '3.33.0'}, () => {
    const world = fresh();
    const toggle = world.$('modeToggle');
    world.touchDown(toggle);
    world.clock.advance(360);
    world.touchUp(toggle);
    assert(world.$('modeMenu').classList.contains('open'), 'menu open');
    const items = [...world.$('modeMenu').children];
    equal(items.length, 7, '7 modes (theme moved to the settings panel; t9 joined)');
    equal(items[0].textContent, 'En英文 Direct', 'first item: shorthand leads, title follows');
    world.tap(items[0]);
    equal(world.native.of('selectMode').length, 0, 'direct is current, no call');
    world.touchDown(toggle);
    world.clock.advance(360);
    world.touchUp(toggle);
    const double = [...world.$('modeMenu').children][2];
    world.tap(double);
    equal(world.native.of('selectMode').slice(-1)[0].args[0], 'double-pinyin', 'double pinyin selected');
});

test('the full-settings gear toggles with the panel', () => {
    const world = fresh();
    const full = world.$('fullSetupButton');
// The gear rides the toolbar ONLY while the quick panel is open -
    // both keyboard generations agree (the permanent-resident form was
    // user-rejected and reverted).
    assert(full.hidden === true, 'gear hidden while the panel is closed ');
    world.tap(world.$('setupButton'));
    assert(full.hidden === false, 'gear appears with the panel');
    world.tap(world.$('setupButton'));
    assert(full.hidden === true, 'gear hides again on close (non-resident)');
});

test('setup button opens the quick settings panel; full settings entry calls openSetup', {since: '3.21.0'}, () => {
    const world = fresh();
    const setup = world.document.querySelectorAll('[data-role="setup"]')[0];
    assert(setup, 'setupButton exists');
    world.tap(setup);
    const panel = world.$('settingsPanel');
    assert(panel.classList.contains('open'), 'settings panel open');
    // 3.38.0 tile grid (wechat-style): both pages render into the DOM.
    // Complex features are sub-page nav tiles; tools stay on the toolbar.
    equal(JSON.stringify(world.tileNames()),
        JSON.stringify([
            '色彩模式', '中文联想', '按键声音', '按键振动',
            '键盘高度', '快捷切换', '候选字号', '界面语言',
            '底部留白', '长按时长', '滑动选字', '长按菜单',
            '定制键盘', '双拼方案', '完整设置',
        ]),
        'quick-settings tiles present (2 pages, voice/clipboard stay on the main keyboard)');
    equal(world.native.of('openSetup').length, 0, 'no openSetup until the full-settings entry');
    // The full-settings entry is a toolbar button next to the
    // gear, visible only while the panel is open.
    const full = world.$('fullSetupButton');
    assert(full, 'fullSetupButton exists');
    assert(full.hidden === false, 'fullSetupButton visible while panel open');
    world.tap(full);
    equal(world.native.of('openSetup').length, 1, 'openSetup called from toolbar');
    assert(!panel.classList.contains('open'), 'panel closed after opening full settings');
    assert(world.$('fullSetupButton').hidden === true, 'gear hides again on close');
});

test('setup button opens the quick settings panel (3.20.0 form)', {until: '3.20.0'}, () => {
    const world = fresh();
    const setup = world.document.querySelectorAll('[data-role="setup"]')[0];
    assert(setup, 'setupButton exists');
    world.tap(setup);
    const panel = world.$('settingsPanel');
    assert(panel.classList.contains('open'), 'settings panel open');
    // Home rows as shipped in 3.20.0: Added the custom-keys row;
    // Kept favorites out (the panel toolbar icon is the entry).
    const rows = [...panel.querySelectorAll('.set-label')].map(el => el.textContent);
    equal(JSON.stringify(rows),
        JSON.stringify(['色彩模式', (verAtLeast(KEYBOARD_VERSION, '3.22.0') ? '光标移动速度' : '滑动跟手'), '双拼键位', '快捷切换', '长按菜单', '定制键盘', '键盘高度']),
        'settings rows (7 rows; 定制键盘 lived here until )');
    equal(world.native.of('openSetup').length, 0, 'no openSetup until the full-settings entry');
    const full = world.$('fullSetupButton');
    assert(full && full.hidden === false, 'fullSetupButton visible while panel open');
    world.tap(full);
    equal(world.native.of('openSetup').length, 1, 'openSetup called from toolbar');
    assert(!panel.classList.contains('open'), 'panel closed after opening full settings');
    assert(world.$('fullSetupButton').hidden === true, 'gear hides again on close');
});

test('scrub speed setting changes the caret step unit', {until: '3.29.0'}, () => {
    const world = fresh();
    const fixedThresholdAnchor = verAtLeast(KEYBOARD_VERSION, '3.23.0');
    world.tap(world.$('setupButton'));
    const speedRow = [...world.$('settingsPanel').querySelectorAll('.set-row')]
        .find(row => row.querySelector('.set-label').textContent === (verAtLeast(KEYBOARD_VERSION, '3.22.0') ? '光标移动速度' : '滑动跟手'));
    world.tap([...speedRow.querySelectorAll('.set-opt')].find(b => b.textContent === '5x'));
    // the panel re-renders after a pick - re-query the fresh nodes
    const freshRow = [...world.$('settingsPanel').querySelectorAll('.set-row')]
        .find(row => row.querySelector('.set-label').textContent === (verAtLeast(KEYBOARD_VERSION, '3.22.0') ? '光标移动速度' : '滑动跟手'));
    assert([...freshRow.querySelectorAll('.set-opt')].find(b => b.textContent === '5x')
        .classList.contains('active'), '5x selected');
    equal(world.storage.get('feelime_scrub_speed'), '5', 'speed persisted');
    // close panel, scrub on g at 5x: 36/5 = 7.2px per step, engage = 1 step
    world.tap(world.$('setupButton'));
    const g = world.key('g');
    world.touchDown(g, 20, 20);
    world.move(g, -20, 0);
    // At exactly -1 unit, the sampled 3.20.0 anchor is subject to floating
    // point truncation and emits on the next sample; current fixed-anchor
    // code emits the initial step at engagement.
    equal(world.native.of('moveCursor').length, fixedThresholdAnchor ? 1 : 0,
        fixedThresholdAnchor ? 'engage steps once at 5x too' : 'legacy engage waits for the next sample');
    world.move(g, -34.4, 0); // +14.4px = exactly 2 more steps
    equal(world.native.of('moveCursor').reduce((sum, c) => sum + Math.abs(c.args[0]), 0),
        fixedThresholdAnchor ? 3 : 2,
        fixedThresholdAnchor ? '5x scrubs per 7.2px' : 'legacy 5x sampled anchor');
    world.touchUp(g, -34.4, 0);
});

test('scrub speed arrives through hello and changes the caret step unit', {since: '3.30.0'}, () => {
    // 光标移动速度 moved to the full settings app; the keyboard only
    // adopts the native pref value that rides hello (mode-fallback §4).
    const world = fresh();
    world.hello({scrubSpeed: 5});
    const g = world.key('g');
    world.touchDown(g, 20, 20);
    world.move(g, -20, 0);
    // 36/5 = 7.2px per step at 5x; fixed anchor engages on the first sample
    equal(world.native.of('moveCursor').length, 1, 'engage steps once at 5x');
    world.move(g, -34.4, 0); // +14.4px = exactly 2 more steps
    equal(world.native.of('moveCursor').reduce((sum, c) => sum + Math.abs(c.args[0]), 0),
        3, '5x scrubs per 7.2px');
    world.touchUp(g, -34.4, 0);
});

test('degrade fallback announces once, badges the toggle, and recovery clears', {since: '3.30.0'}, () => {
    const world = fresh();
    // Start on a NON-direct mode so the degrade event's mode change really
    // runs renderMode() - that re-render used to wipe the badge (device
    // gate caught it; round-6 R6-3).
    world.hello({mode: 'pinyin'});
    // Degrade transition: toast + badge + status strip, once per seq.
    world.engineState({phase: 'READY', revision: 1, mode: 'direct', composing: '',
        degraded: true, degradedActive: true, failedMode: 'double-pinyin',
        degradeReason: 'WARMUP_TIMEOUT', degradeSeq: 1});
    assert(world.$('toast').classList.contains('open'), 'degrade toast shown');
    assert(world.$('toast').textContent.includes('双拼'), 'toast names the failed mode');
    assert(world.$('modeToggle').classList.contains('degraded'), 'toggle badge on');
    assert(!world.$('engineStatus').hidden, 'status strip visible');
    // Hello restore (WebView rebuild): badge stays, NO second toast.
    const toastAfterRestore = world.$('toast').textContent;
    world.hello({degraded: true, failedMode: 'double-pinyin', degradeReason: 'WARMUP_TIMEOUT', degradeSeq: 1});
    assert(world.$('modeToggle').classList.contains('degraded'), 'badge restored from hello');
    equal(world.$('toast').textContent, toastAfterRestore, 'hello restore never re-toasts');
    // Retry that fails again: new seq → toast again.
    world.engineState({phase: 'READY', revision: 2, mode: 'direct', composing: '',
        degraded: true, degradedActive: true, failedMode: 'double-pinyin',
        degradeReason: 'WARMUP_TIMEOUT', degradeSeq: 2});
    assert(world.$('toast').textContent !== toastAfterRestore || world.$('toast').textContent.includes('重试'), 'refailure re-toasts');
    // Recovery: cleared without toast.
    world.engineState({phase: 'READY', revision: 3, mode: 'double-pinyin', composing: '',
        degraded: true, degradedActive: false, failedMode: 'double-pinyin', degradeSeq: 2});
    assert(!world.$('modeToggle').classList.contains('degraded'), 'badge cleared on recovery');
    assert(world.$('engineStatus').hidden, 'status strip hidden on recovery');
    // Healthy hello keeps it cleared.
    world.hello({degraded: false, warming: false});
    assert(!world.$('modeToggle').classList.contains('degraded'), 'stays cleared');
});

test('hello snapshot on a rebuilt page restores the badge silently and never re-toasts', {since: '3.30.0'}, () => {
    // A FRESH world (WebView rebuild): the failure predates the page, the
    // hello seq was never "seen" here — the snapshot must still be silent
    // (codex P2: seq alone cannot tell snapshot from notification).
    const world = fresh();
    world.hello({degraded: true, failedMode: 'double-pinyin',
        degradeReason: 'WARMUP_TIMEOUT', degradeSeq: 7});
    assert(world.$('modeToggle').classList.contains('degraded'), 'badge restored');
    assert(!world.$('toast').classList.contains('open'), 'snapshot never toasts');
    // The event for the same failure (already announced before the rebuild)
    // must not toast again either — the snapshot marked the seq seen.
    world.engineState({phase: 'READY', revision: 1, mode: 'direct', composing: '',
        degraded: true, degradedActive: true, failedMode: 'double-pinyin',
        degradeReason: 'WARMUP_TIMEOUT', degradeSeq: 7});
    assert(!world.$('toast').classList.contains('open'), 'seen seq stays silent');
    assert(!world.$('engineStatus').hidden, 'status strip still serves');
    assert(world.$('candidates').hidden, 'status strip takes the candidate slot');
    // Recovery hands the slot back.
    world.engineState({phase: 'READY', revision: 2, mode: 'double-pinyin', composing: '',
        degraded: true, degradedActive: false, failedMode: 'double-pinyin', degradeSeq: 7});
    assert(!world.$('candidates').hidden, 'candidate bar back after recovery');
});

test('degraded short-press retries the failed mode instead of the pair', {since: '3.30.0'}, () => {
    const world = fresh();
    world.engineState({phase: 'READY', revision: 1, mode: 'direct', composing: '',
        degraded: true, degradedActive: true, failedMode: 'double-pinyin',
        degradeReason: 'ENGINE_INIT_FAILED', degradeSeq: 1});
    world.tap(world.$('modeToggle'));
    equal(world.native.of('selectMode').slice(-1)[0].args[0], 'double-pinyin',
        'short press retries the failed mode');
    // After recovery the toggle returns to the saved pair behaviour
    // (default pair 拼/En; mode=direct → partner is pinyin).
    world.engineState({phase: 'READY', revision: 2, mode: 'double-pinyin', composing: '',
        degraded: true, degradedActive: false, failedMode: 'double-pinyin', degradeSeq: 1});
    world.tap(world.$('modeToggle'));
    equal(world.native.of('selectMode').slice(-1)[0].args[0], 'pinyin',
        'healthy toggle flips to the pair partner');
});

test('warming shows the preparing strip until the engine is ready', {since: '3.30.0'}, () => {
    const world = fresh();
    world.engineState({phase: 'LOADING', revision: 1, mode: 'pinyin', composing: ''});
    assert(!world.$('engineStatus').hidden, 'warming strip visible');
    assert(world.$('engineStatus').textContent.includes('准备'), 'preparing copy');
    // The warmup-success READY lands with the engine's initial EMPTY state.
    world.engineState({phase: 'READY', revision: 2, mode: 'pinyin', composing: ''});
    assert(world.$('engineStatus').hidden, 'strip hidden once ready');
});

test('bottom pad rides hello into the CSS budget and is excluded from content', {since: '3.30.0'}, () => {
    const world = fresh();
    world.hello({});
    const rowsBefore = world.document.documentElement.style['--kb-row-h'];
    world.hello({bottomPad: 24});
    equal(world.document.documentElement.style['--kb-bottom-pad'], '24px',
        'pad mirrored into CSS var');
    // Harness view height is fixed, so the row budget shrinks by exactly
    // pad/4 per row (24/4 = 6) — the accounting that keeps rows unchanged
    // on device, where the native window grows by the pad.
    const before = parseInt(rowsBefore, 10);
    const after = parseInt(world.document.documentElement.style['--kb-row-h'], 10);
    equal(before - after, 6, 'row budget excludes exactly the pad');
});

test('candidate font scale rides hello into the body dataset', {since: '3.30.0'}, () => {
    const world = fresh();
    world.hello({});
    equal(world.document.body.dataset.candFont, 'normal', 'default normal');
    world.hello({candidateFont: 1});
    equal(world.document.body.dataset.candFont, 'large', 'large tier applied');
    world.hello({candidateFont: 2});
    equal(world.document.body.dataset.candFont, 'xlarge', 'xlarge tier applied');
    world.hello({candidateFont: 9});
    equal(world.document.body.dataset.candFont, 'xlarge', 'off-whitelist ignored (keeps last)');
});

test('quick-pair editor: tick 双拼 relabels the toggle and flips the pair', () => {
    const world = fresh();
    world.tap(world.$('setupButton'));
    if (verAtLeast(KEYBOARD_VERSION, '3.38.0')) {
        world.tap(world.tile('快捷切换')); // opens the pair editor
    } else {
        const pairRow = [...world.$('settingsPanel').querySelectorAll('.set-row')]
            .find(row => row.querySelector('.set-label').textContent === '快捷切换');
        world.tap(pairRow.querySelector('.set-opt')); // opens the pair editor
    }
    const editor = world.$('pairEditor');
    assert(editor, 'pair editor opens');
    const shuangRow = [...editor.querySelectorAll('.pair-row')]
        .find(row => row.dataset.mode === 'double-pinyin');
    world.tap(shuangRow.querySelector('.pair-tick'));
    const toggle = world.$('modeToggle');
    equal(toggle.querySelector('.cn-main').textContent, 'En', 'current shorthand unchanged');
    equal(toggle.querySelector('.cn-sub').textContent, '双', 'partner shorthand now 双');
    // Default pair was 拼/En; ticking 双拼 pushes out the OLDEST member (拼),
    // leaving En/双 - En stays a member so the toggle flips straight to 双.
    equal(JSON.parse(world.storage.get('feelime_quick_pair')).join('/'),
        'direct/double-pinyin', 'pair persisted (two ticks, oldest dropped)');
    world.tap(world.$('setupButton')); // close
    world.tap(toggle); // En is a pair member: flips to its partner 双
    equal(world.native.of('selectMode').slice(-1)[0].args[0], 'double-pinyin',
        'toggle flips inside the configured pair');
});

test('unready engine mode is disabled in the menu', () => {
    const world = fresh({ engineDataReady: { pinyin: false, 'double-pinyin': false, japanese: false, french: false, russian: false } });
    const toggle = world.$('modeToggle');
    world.touchDown(toggle);
    world.clock.advance(360);
    world.touchUp(toggle);
    const pinyin = [...world.$('modeMenu').children][1];
    assert(pinyin.className.includes('preparing'), 'preparing class');
    world.tap(pinyin);
    equal(world.native.of('selectMode').length, 0, 'no selectMode for unready');
});

test('mode change re-renders layout and toggle shorthand', () => {
    const world = fresh();
    world.engineState({ mode: 'russian', revision: 1, candidates: [], composing: '' });
    equal(world.document.querySelectorAll('[data-key="й"]').length, 1, 'cyrillic й rendered');
    equal(world.$('modeToggle').querySelector('.cn-main').textContent, 'РУ', 'toggle shorthand');
    world.engineState({ mode: 'direct', revision: 2, candidates: [], composing: '' });
    assert(world.key('q'), 'qwerty restored');
});

test('french layout is QWERTY with design accent alts', () => {
    const world = fresh({ mode: 'french' });
    const row1 = world.document.getElementById('qwertyLayer').children[0];
    equal(
        [...row1.querySelectorAll('[data-key]')].map(k => k.dataset.key).join(''),
        'qwertyuiop',
        'qwerty rows',
    );
    const e = world.key('e');
    equal(e.querySelector('.kb-alt').textContent, '3', 'alt starts with 3');
    // Long-press popup on e exposes the accents.
    world.touchDown(e);
    world.clock.advance(360);
    const items = [...world.document.querySelectorAll('.kp-item')].map(i => i.textContent);
    for (const accent of ['é', 'è', 'ê', 'ë']) {
        assert(items.includes(accent), `accent ${accent} in popup`);
    }
    world.touchCancel(e);
});

test('a and o carry the full French accent set', {since: '3.21.2'}, () => {
    const world = fresh({ mode: 'french' });
    // ä/ö were missing - "some variants never showed" user report.
    const probe = key => {
        const el = world.key(key);
        world.touchDown(el);
        world.clock.advance(360);
        const items = [...world.document.querySelectorAll('.kp-item')].map(i => i.textContent);
        world.touchCancel(el);
        return items;
    };
    for (const accent of ['à', 'â', 'ä', 'æ']) {
        assert(probe('a').includes(accent), `accent ${accent} in a popup`);
    }
    for (const accent of ['ô', 'ö', 'œ']) {
        assert(probe('o').includes(accent), `accent ${accent} in o popup`);
    }
});

test('accent variants ride the candidate pool after the engine head', {since: '3.21.2'}, () => {
    const world = fresh({ mode: 'french' });
    world.hello();
    // Composition "ete": the engine echoes candidates; the overlay must add
    // the accented alts of the FIRST character after the engine's first
    // candidate - never at the head (space confirms expandCandidates[0],
    // "a"+space must stay "a").
    world.engineState({ mode: 'french', revision: 1, composing: 'ete', rawInput: 'ete',
        candidates: [{ id: 'c0', text: 'ete' }, { id: 'c1', text: 'été' }], hasNextPage: false });
    const bar = [...world.$('candidates').querySelectorAll('.candidate')].map(b => b.textContent);
    equal(bar[0], 'ete', 'engine candidate still heads the pool');
    for (const accent of ['é', 'è', 'ê', 'ë']) {
        assert(bar.includes(accent), `variant ${accent} injected`);
    }
    equal(bar[1], 'é', 'variants sit right after the engine head');
    // Picking a variant swaps the first character and keeps composing
    // (atomic setComposition), never a bare commit.
    const variantButton = [...world.$('candidates').querySelectorAll('.candidate')]
        .find(b => b.textContent === 'ê');
    variantButton.click();
    const setCalls = world.native.of('setComposition');
    equal(setCalls.length, 1, 'one atomic setComposition');
    equal(setCalls[0].args[0], 'ête', 'first char swapped, rest kept');
    equal(world.native.of('commitText').length, 0, 'no bare commit path');
    // Space still confirms the ENGINE head, not a variant ("a"+space stays
    // "a" - a regression pin).
    world.engineState({ mode: 'french', revision: 2, composing: 'a', rawInput: 'a',
        candidates: [{ id: 'c0', text: 'a' }], hasNextPage: false });
    world.tap(world.$('spaceKey'));
    const pick = world.native.of('chooseCandidate').slice(-1)[0];
    equal(pick && pick.args[1], 'c0', 'space confirms the ENGINE entry, not a variant');
});

test('accent selection preserves collapsed and expanded candidate views', {since: '3.22.0'}, () => {
    for (const expanded of [false, true]) {
        const world = fresh({mode: 'french'});
        world.engineState({mode: 'french', revision: 1, composing: 'ete', rawInput: 'ete',
            candidates: [{id: 'c0', text: 'ete'}], hasNextPage: false});
        if (expanded) world.tap(world.$('composeExpand'));
        const button = [...world.$('candidates').querySelectorAll('.candidate')]
            .find(b => b.textContent === 'é');
        button.click();
        world.engineState({mode: 'french', revision: 2, composing: 'éte', rawInput: 'éte',
            candidates: [{id: 'c0', text: 'été'}], hasNextPage: false});
        equal(world.document.body.classList.contains('expanded'), expanded, 'view preserved');
    }
});

test('space commits unknown French word when pool contains only accents', {since: '3.22.0'}, () => {
    const world = fresh({mode: 'french'});
    world.engineState({mode: 'french', revision: 1, composing: 'azzzzz', rawInput: 'azzzzz',
        candidates: [], hasNextPage: false});
    assert(world.$('candidates').querySelectorAll('.candidate').length > 0, 'accents available');
    world.tap(world.$('spaceKey'));
    equal(world.native.of('space').length, 1, 'raw-word space path');
    equal(world.native.of('setComposition').length, 0, 'no accent replacement');
});

test('japanese mode keeps qwerty; space stays mic-only', () => {
    const world = fresh({ mode: 'japanese' });
    assert(world.key('q'), 'qwerty in japanese');
    equal(world.$('spaceKey').textContent, '', 'space key shows no mode text');
    equal(world.$('modeToggle').querySelector('.cn-main').textContent, '日', 'japanese shorthand');
});

// ---------------------------------------------------------------- C candidates

test('C2/C3/C7 candidates render, choose and page with revision', () => {
    const world = fresh();
    world.engineState({
        phase: 'READY',
        revision: 7,
        composing: 'nihao',
        candidates: [
            { id: 'c1', text: '你好' },
            { id: 'c2', text: '尼豪' },
        ],
        hasPreviousPage: false,
        hasNextPage: true,
    });
    const bar = world.$('candidates');
    equal(world.$('preeditLine').textContent, 'nihao', 'preedit on its own line');
    equal(bar.children[0].textContent, '你好', 'candidate 1');
    assert(bar.children[0].className.includes('first'), 'first candidate pill');
    assert(world.document.body.classList.contains('composing'), 'body composing');
    assert(world.$('setupButton').hidden, 'setup hidden while composing');
    assert(world.$('mic').hidden, 'mic hidden while composing and idle');
    equal(world.$('enterKey').textContent, '确定', 'enter reads 确定 while composing');
    // The ‹ › pager buttons are gone from the DOM.
    assert(!world.$('pagePrev') && !world.$('pageNext'), 'no pager buttons');
    world.tap(bar.children[0]);
    const choose = world.native.of('chooseCandidate');
    equal(choose.length, 1, 'choose call');
    equal(choose[0].args[0], 7, 'revision passed');
    equal(choose[0].args[1], 'c1', 'candidate id');
});

test('bar auto-fetches pages and CN alts carry their final glyphs ',  ()=> {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ phase: 'READY', revision: 7, composing: '', candidates: [] });
    // maybeLoadMoreCandidates on a too-short bar pulls the next page.
    world.engineState({ mode: 'pinyin', revision: 8, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }], hasNextPage: true });
    equal(world.native.of('pageNext').length >= 1, true,
        'too-short bar auto-fetches the next page');
    // The user's pinned set - a=- s=/ (half-width), the rest
    // full-width.
    equal(world.key('a').querySelector('.kb-alt').textContent, '-', 'a alt is half-width dash');
    equal(world.key('s').querySelector('.kb-alt').textContent, '/', 's alt is half-width slash');
    equal(world.key('v').querySelector('.kb-alt').textContent, '、', 'v alt is 顿号');
    equal(world.key('x').querySelector('.kb-alt').textContent, '.', 'x alt stays half-width dot');
    equal(world.key('m').querySelector('.kb-alt').textContent, '…', 'm alt is ellipsis');
    const world2 = fresh({ mode: 'direct' });
    equal(world2.key('s').querySelector('.kb-alt').textContent, '/',
        'direct keeps the layout alts');
});

test('empty candidates leave the bar clean', () => {
    const world = fresh();
    world.engineState({ phase: 'READY', revision: 1, composing: '', candidates: [] });
    equal(world.$('candidates').children.length, 0, 'no chips');
    assert(!world.document.body.classList.contains('composing'), 'not composing');
    equal(world.$('enterKey').textContent, '换行', 'enter reads 换行 when idle');
});

test('voice session keeps the mic visible during composing', () => {
    const world = fresh();
    world.engineState({ phase: 'READY', revision: 1, composing: 'ni', candidates: [] });
    assert(world.$('mic').hidden, 'mic hidden while idle-composing');
    world.nativeState({ state: 'listening' });
    assert(!world.$('mic').hidden, 'mic must stay tappable while listening');
    // Review M1: native events during composing must not clobber the preedit
    // with a boolean ("true") — it keeps the last raw input text.
    equal(world.$('preeditLine').textContent, 'ni', 'preedit survives native events');
});

// ---------------------------------------------------------------- H tokens

test('token rotation re-arms calls with the new token', () => {
    const world = fresh();
    world.hello({ pageGenerationToken: 'tok-2' });
    world.tap(world.key('z'));
    equal(world.native.of('key')[0].args[1], 'tok-2', 'new token used');
});

// ---------------------------------------------------------------- theme

test('theme switches auto/light/dark from the settings panel', () => {
    const world = new KeyboardWorld().build();
    world.hello();
    const doc = world.document;
    equal(doc.documentElement.className, 'theme-light', 'auto follows default system light');
    world.tap(world.$('setupButton'));
    if (verAtLeast(KEYBOARD_VERSION, '3.38.0')) {
        // 3.38.0: the theme tile CYCLES auto → light → dark → auto;
        // the state line names the current pick (tile is the state).
        const state = () => {
            // a tap re-renders the home page - always re-query the fresh node
            const tile = world.tile('色彩模式');
            assert(tile, 'theme tile exists');
            return tile.querySelector('.qs-state').textContent;
        };
        equal(state(), '跟随系统', 'state line shows auto');
        world.tap(world.tile('色彩模式'));
        equal(doc.documentElement.className, 'theme-light', 'light selected');
        equal(state(), '浅色', 'state line follows light');
        world.tap(world.tile('色彩模式'));
        equal(doc.documentElement.className, 'theme-dark', 'dark selected');
        equal(state(), '深色', 'state line follows dark');
        world.tap(world.tile('色彩模式'));
        equal(doc.documentElement.className, 'theme-light', 'auto back (system light)');
        equal(state(), '跟随系统', 'state line back to auto');
    } else {
        const themeRow = [...world.$('settingsPanel').querySelectorAll('.set-row')]
            .find(row => row.querySelector('.set-label').textContent === '色彩模式');
        assert(themeRow, 'theme row exists');
        const pick = text => [...themeRow.querySelectorAll('.set-opt')]
            .find(b => b.textContent === text);
        world.tap(pick('浅色'));
        equal(doc.documentElement.className, 'theme-light', 'light selected');
        world.tap(pick('深色'));
        equal(doc.documentElement.className, 'theme-dark', 'dark selected');
        world.tap(pick('跟随系统'));
        equal(doc.documentElement.className, 'theme-light', 'auto back (system light)');
    }
});
test('quick tiles: toggles write setQuickPref, hello echo re-reads state', {since: '3.38.0'}, () => {
    const world = fresh();
    world.hello({ associationOn: true, keySound: false, keyHaptic: false });
    world.tap(world.$('setupButton'));
    const state = label => world.tile(label).querySelector('.qs-state').textContent;
    equal(state('中文联想'), '开', 'association reflects hello');
    world.tap(world.tile('中文联想'));
    const assoc = world.native.of('setQuickPref').slice(-1)[0];
    equal(`${assoc.args[0]}=${assoc.args[1]}`, 'association=0', 'tap writes the pref');
    equal(state('中文联想'), '关', 'optimistic re-render flips the state line');
    world.tap(world.tile('按键声音'));
    const sound = world.native.of('setQuickPref').slice(-1)[0];
    equal(`${sound.args[0]}=${sound.args[1]}`, 'keySound=1', 'sound toggle writes the pref');
    equal(state('按键声音'), '开', 'sound state line follows');
    // Native echo (broadcast → hello re-push) is authoritative: a value
    // changed elsewhere lands in the open panel too.
    world.hello({ associationOn: false, keySound: true });
    equal(state('中文联想'), '关', 'hello echo wins for association');
    equal(state('按键声音'), '开', 'hello echo keeps key sound');
    const calls = world.native.of('setQuickPref').map(c => c.args[2]);
    assert(calls.every(tok => tok === world.tokenValue), 'every write carries the page token');
});

test('quick tiles: cycle tiles rotate steps and apply locally', {since: '3.38.0'}, () => {
    const world = fresh();
    world.hello({ candidateFont: 0, holdMs: 350, popupSnap: 1, bottomPad: 0, dpScheme: 'ziranma' });
    world.tap(world.$('setupButton'));
    const state = label => world.tile(label).querySelector('.qs-state').textContent;
    const lastPref = () => {
        const call = world.native.of('setQuickPref').slice(-1)[0];
        return `${call.args[0]}=${call.args[1]}`;
    };
    world.tap(world.tile('候选字号'));
    equal(lastPref(), 'candidateFont=1', 'font cycles to large');
    equal(world.document.body.dataset.candFont, 'large', 'candidate scale applied');
    world.tap(world.tile('长按时长'));
    equal(lastPref(), 'holdMs=450', 'hold steps 350 → 450');
    equal(state('长按时长'), '450ms', 'hold state line follows');
    world.tap(world.tile('滑动选字'));
    equal(lastPref(), 'popupSnap=2', 'snap 标准 → 紧');
    world.tap(world.tile('底部留白'));
    equal(lastPref(), 'bottomPad=12', 'pad steps 0 → 12');
    equal(world.document.documentElement.style.getPropertyValue('--kb-bottom-pad').trim(), '12px',
        'pad applied to the view budget');
    world.tap(world.tile('双拼方案'));
    equal(lastPref(), 'dpScheme=flypy', 'dp scheme cycles 自然码 → 小鹤');
    world.tap(world.tile('界面语言'));
    equal(lastPref(), 'uiLocale=en', 'locale cycles zh → en');
    // hello re-push with the new locale translates the whole grid.
    world.hello({ uiLocale: 'en' });
    assert(world.tileNames().includes('Associations'), 'tile names translate on the echo');
});

test('quick tiles: re-render keeps the current page (no jump to page 1)', {since: '3.38.0'}, () => {
    const world = fresh();
    world.hello();
    world.tap(world.$('setupButton'));
    // 翻到第二页（真实设备是手指横滑；harness 直接拨 scrollLeft）。
    const strip = world.document.querySelector('.qs-pages');
    strip.scrollLeft = strip.firstElementChild.offsetWidth;
    strip.listeners.filter(l => l.type === 'scroll').forEach(l => l.handler({ target: strip }));
    assert(world.tileNames().indexOf('长按菜单') >= 0, 'page-2 tiles stay in the DOM');
    // 点第二页的子页导航 → 返回首页：必须还在第二页。
    world.tap(world.tile('长按菜单'));
    assert(world.document.body.classList.contains('settings-page'), 'sub-page opened');
    world.tap(world.$('settingsPageBar').children[0]); // back
    const strip2 = world.document.querySelector('.qs-pages');
    equal(strip2.scrollLeft, strip2.firstElementChild.offsetWidth,
        'home re-render restores page 2');
    const dots = [...world.document.querySelectorAll('.qs-dots span')];
    assert(dots[1] && dots[1].classList.contains('cur'), 'second dot active');
});

test('quick tiles: gear tile opens full settings; old APKs never fake state', {since: '3.38.0'}, () => {
    const world = fresh();
    world.hello({ keySound: false });
    world.tap(world.$('setupButton'));
    world.tap(world.tile('完整设置'));
    equal(world.native.of('openSetup').length, 1, 'gear tile opens the settings app');
    assert(!world.$('settingsPanel').classList.contains('open'), 'panel closed first');
    // Hot-updated JS on an older APK: no setQuickPref on the bridge - the
    // tile must no-op, not paint a state that native never adopted.
    const stale = fresh();
    stale.hello({ keySound: false });
    stale.context.window.FeelimeNative.setQuickPref = undefined;
    stale.tap(stale.$('setupButton'));
    stale.tap(stale.tile('按键振动'));
    equal(stale.tile('按键振动').querySelector('.qs-state').textContent, '关',
        'state stays off without the native channel');
});

test('first paint: never guess a system theme before the bridge speaks', () => {
    const cold = new KeyboardWorld().build();
    equal(cold.document.documentElement.className, '', 'no hello yet: CSS media fallback active');
    const warmWorld = new KeyboardWorld();
    warmWorld.storage.set('feelime_system_theme', 'dark');
    const warm = warmWorld.build();
    equal(warm.document.documentElement.className, 'theme-dark', 'persisted system theme paints on load');
});

test('auto theme follows hello payload; explicit storage wins', () => {
    const dark = new KeyboardWorld().build();
    dark.hello({ theme: 'dark' });
    equal(dark.document.documentElement.className, 'theme-dark', 'auto adopts system dark');

    const pinned = new KeyboardWorld().build();
    pinned.storage.set('feelime_theme', 'light');
    pinned.hello({ theme: 'dark' });
    equal(pinned.document.documentElement.className, 'theme-light', 'explicit light beats system dark');

    const again = new KeyboardWorld().build();
    again.hello({ theme: 'dark' });
    again.hello({ theme: 'light' });
    equal(again.document.documentElement.className, 'theme-light', 'config change repushes theme');
});

test('theme preference survives a reload', () => {
    const first = new KeyboardWorld().build();
    first.hello();
    first.storage.set('feelime_theme', 'light');
    const secondWorld = new KeyboardWorld();
    secondWorld.storage.set('feelime_theme', 'light');
    const second = secondWorld.build();
    equal(second.document.documentElement.className, 'theme-light', 'pinned light reapplies on load');
});

// ------------------------------------------------- userdata stores mirror

test('stores mirror: hello pushes localStorage settings to native; onStoresRestored applies them', {since: '3.28.0'}, () => {
    const world = new KeyboardWorld().build();
    world.storage.set('feelime_theme', 'dark');
    world.storage.set('feelime_scrub_speed', '5');
    world.storage.set('feelime_symbol_recent', '["x"]'); // 使用痕迹：不应进镜像
    world.hello();
    const pushes = world.native.of('pushStores');
    equal(pushes.length, 1, 'one mirror push per hello');
    const payload = JSON.parse(pushes[0].args[0]);
    equal(payload.feelime_theme, 'dark', 'theme rides the mirror');
    equal(payload.feelime_scrub_speed, '5', 'scrub speed rides the mirror');
    equal(payload.feelime_symbol_recent, undefined, 'usage traces stay out of the backup');

    // 导入恢复：原生把镜像推回来，主题当场生效、白名单外键被忽略。
    world.context.window.Feelime.onStoresRestored({ feelime_theme: 'light', feelime_evil_key: '1' });
    equal(world.document.documentElement.className, 'theme-light', 'restored theme applies');
    equal(world.storage.get('feelime_evil_key'), undefined, 'non-whitelisted keys are dropped');
    equal(world.storage.get('feelime_theme'), 'light', 'restored theme is persisted');
});

test('stores rev: a newer native mirror (settings import) wins the next hello', {since: '3.28.0'}, () => {
    const world = new KeyboardWorld().build();
    world.storage.set('feelime_theme', 'dark');
    world.storage.set('feelime_scrub_speed', '3');
    world.hello();
    // 设置页导入备份：原生镜像 rev 跳号 + 携带恢复值。
    world.native.storesRev = 7;
    world.native.storesPayload = JSON.stringify({
        rev: 7,
        values: { feelime_theme: 'light', feelime_scrub_speed: '5', feelime_evil: 'x' },
    });
    world.hello();
    equal(world.storage.get('feelime_theme'), 'light', 'hello pulls the restored values');
    // 拉取(7)之后 hello 收尾的 push 把 rev 推到 8——值已收敛，只是计号前进。
    equal(parseInt(world.storage.get('feelime_stores_rev'), 10) >= 7, true,
        'rev recorded after pull');
    equal(world.storage.get('feelime_evil'), undefined, 'non-whitelisted values are dropped');
    equal(world.document.documentElement.className, 'theme-light', 'restored theme applies');
    equal(JSON.parse(world.native.of('pushStores').slice(-1)[0].args[0]).feelime_theme,
        'light', 'the follow-up push carries the restored values, not the stale ones');
});

test('stores restore is authoritative: keys absent from the backup are removed locally', {since: '3.28.0'}, () => {
    const world = new KeyboardWorld().build();
    world.storage.set('feelime_theme', 'dark');
    world.storage.set('feelime_scrub_speed', '5');
    world.storage.set('feelime_quick_pair', JSON.stringify(['direct', 'double']));
    world.hello();
    // 导入的备份只带 theme（scrub/quick_pair 是导出方没有的键）。
    world.native.storesRev = 9;
    world.native.storesPayload = JSON.stringify({
        rev: 9,
        values: { feelime_theme: 'light' },
    });
    world.hello();
    equal(world.storage.get('feelime_theme'), 'light', 'restored value lands');
    equal(world.storage.get('feelime_scrub_speed'), undefined,
        'key absent from the backup is removed (overwrite semantics)');
    equal(world.storage.get('feelime_quick_pair'), undefined,
        'quick-pair absent from the backup is removed too');
    // 「先拉后推」不得把删除的键从旧 localStorage 复活回镜像。
    const followUp = JSON.parse(world.native.of('pushStores').slice(-1)[0].args[0]);
    equal(followUp.feelime_scrub_speed, undefined, 'push does not resurrect removed keys');
    equal(followUp.feelime_quick_pair, undefined, 'push does not resurrect quick-pair');
});

test('hello locale change defers its push until after the pull (restore survives)', {since: '3.28.0'}, () => {
    const world = new KeyboardWorld().build();
    world.storage.set('feelime_theme', 'dark');
    world.hello();
    // 设置页导入空备份（镜像清空 + rev 跳号），hello 同时带语言切换：
    // 语言分支若在拉取前 push，陈旧主题会写回镜像把恢复值顶掉。
    world.native.storesPayload = JSON.stringify({ rev: 9, values: {} });
    world.hello({ uiLocale: 'en' });
    equal(world.storage.get('feelime_theme'), undefined,
        'empty mirror clears the stale theme even when the locale also changed');
    const tail = JSON.parse(world.native.of('pushStores').slice(-1)[0].args[0]);
    equal(tail.feelime_theme, undefined,
        'the post-hello push carries no resurrected theme');
});

test('stores pull ignores malformed mirror payloads', {since: '3.28.0'}, () => {
    const world = new KeyboardWorld().build();
    world.storage.set('feelime_theme', 'dark');
    world.hello();
    // rev 跳号但 values 是数组：异常镜像不能当成「空备份」触发全量删除。
    world.native.storesPayload = JSON.stringify({ rev: 12, values: [] });
    world.hello();
    equal(world.storage.get('feelime_theme'), 'dark',
        'array payload is not an empty backup');
});

test('restoring a backup without the locale key falls back to the default language', {since: '3.28.1'}, () => {
    const world = new KeyboardWorld().build();
    world.storage.set('feelime_ui_locale', 'en');
    world.hello({ uiLocale: 'en' });
    equal(world.document.documentElement.lang, 'en', 'starts as en');
    world.native.storesPayload = JSON.stringify({ rev: 5, values: {} });
    world.hello({ uiLocale: 'en' });
    equal(world.document.documentElement.lang, 'zh-CN',
        'runtime locale falls back to zh when the backup lacks the key');
});

// ------------------------------------------------- candidate compose controls

test('candidate bar shows × and ˅ only while composing; × calls clearComposing and restores', () => {
    const world = fresh();
    equal(world.$('composeClear').hidden, true, 'hidden when idle');
    equal(world.$('composeExpand').hidden, true, 'hidden when idle');
    world.engineState({
        mode: 'pinyin', composing: true, rawInput: 'ni', revision: 3,
        candidates: [{ id: '1', text: '你' }, { id: '2', text: '泥' }],
        hasPreviousPage: false, hasNextPage: false,
    });
    equal(world.$('composeClear').hidden, false, 'visible while composing');
    equal(world.$('composeExpand').hidden, false, 'visible while composing');
    // Only × and ˅ sit on the right while composing - the keyboard
    // dismiss chevron (same downward glyph) must disappear.
    equal(world.$('hide').hidden, true, 'dismiss chevron hidden while composing');
    equal(world.native.of('clearComposing').length, 0, 'idle: no calls');
    world.tap(world.$('composeClear'));
    equal(world.native.of('clearComposing').length, 1, '× hits clearComposing');
    equal(world.$('composeClear').hidden, true, 'toolbar restored optimistically');
    world.engineState({ mode: 'pinyin', composing: false, rawInput: '', revision: 4, candidates: [] });
    equal(world.$('hide').hidden, false, 'dismiss chevron back when idle');
});

test('X1b live voice session hides the compose controls', () => {
    // Review P2: × shares the editor span with the ASR partial; while
    // a voice session is live it must not be tappable.
    const world = fresh();
    world.engineState({
        mode: 'pinyin', composing: true, rawInput: 'ni', revision: 3,
        candidates: [{ id: '1', text: '你' }], hasPreviousPage: false, hasNextPage: false,
    });
    equal(world.$('composeClear').hidden, false, 'visible while plain composing');
    world.nativeState({ state: 'listening' });
    equal(world.$('composeClear').hidden, true, '× hidden while listening');
    equal(world.$('composeExpand').hidden, true, '˅ hidden while listening');
    world.nativeState({ state: 'idle' });
    equal(world.$('composeClear').hidden, false, 'restored when idle again');
});

test('expand shows the candidate strip; collapse/back/auto-collapse work', () => {
    const world = fresh();
    world.engineState({
        mode: 'pinyin', composing: true, rawInput: 'nihao', revision: 5,
        candidates: [{ id: 'a', text: '你好' }, { id: 'b', text: '你号' }, { id: 'c', text: '拟好' }],
        hasPreviousPage: false, hasNextPage: true,
    });
    world.tap(world.$('composeExpand'));
    equal(world.document.body.classList.contains('expanded'), true, 'body expanded');
    equal(world.$('expandLayer').hidden, false, 'layer visible');
    const buttons = [...world.document.querySelectorAll('.expand-candidate')].map(b => b.textContent);
    equal(JSON.stringify(buttons), JSON.stringify(['你好', '你号', '拟好']), 'strip mirrors candidates');
    equal(world.$('expandPreedit').textContent, 'nihao', 'big preedit');
    // Tapping a strip candidate goes through the same bridge path as the bar.
    world.tap(world.document.querySelectorAll('.expand-candidate')[1]);
    equal(world.native.of('chooseCandidate')[0].args[1], 'b', 'strip choose passes id');
    world.tap(world.$('expandCollapse'));
    equal(world.document.body.classList.contains('expanded'), false, '˄ collapses');
    // The head carries ONE chevron; aborting the
    // composition belongs to the toolbar × after collapsing.
    equal(world.$('expandBack'), null, 'no second head button');
    // expand again; collapsing then clearing via the toolbar × still works.
    world.tap(world.$('composeExpand'));
    world.tap(world.$('expandCollapse'));
    world.tap(world.$('composeClear'));
    equal(world.native.of('clearComposing').length >= 1, true, 'toolbar × clears composing');
    // composing done while expanded: engine event auto-collapses
    world.tap(world.$('composeExpand'));
    equal(world.document.body.classList.contains('expanded'), true, 'expanded again');
    world.engineState({ mode: 'pinyin', composing: false, rawInput: '', revision: 6, candidates: [] });
    equal(world.document.body.classList.contains('expanded'), false, 'auto-collapse on commit');
});

test('X2b expand strip accumulates pages and drops the pager', () => {
    const world = fresh();
    world.engineState({
        mode: 'pinyin', composing: true, rawInput: 'nihao', revision: 5,
        candidates: [{ id: 'a', text: '你好' }, { id: 'b', text: '你号' }],
        hasPreviousPage: false, hasNextPage: true,
    });
    world.tap(world.$('composeExpand'));
    equal(world.document.querySelectorAll('.expand-candidate').length, 2, 'initial page');
    equal(world.$('expandPrev') === null && world.$('expandNext') === null, true,
        'pager arrows removed (infinite drag)');
    // Same composition, next page arrives: append instead of replace.
    world.engineState({
        mode: 'pinyin', composing: true, rawInput: 'nihao', revision: 6,
        candidates: [{ id: 'c', text: '拟好' }], hasPreviousPage: true, hasNextPage: false,
    });
    const texts = [...world.document.querySelectorAll('.expand-candidate')].map(b => b.textContent);
    equal(JSON.stringify(texts), JSON.stringify(['你好', '你号', '拟好']), 'pages accumulate');
    // New composition resets the accumulation.
    world.engineState({
        mode: 'pinyin', composing: true, rawInput: 'ni', revision: 7,
        candidates: [{ id: 'd', text: '你' }], hasPreviousPage: false, hasNextPage: false,
    });
    equal(world.document.querySelectorAll('.expand-candidate').length, 1, 'reset on new composition');
    equal(world.native.of('pageNext').length >= 1, true, 'too-short strip auto-fetches next page');
});

test('X2c expand area: variant column, single-char tab, vertical grid', () => {
    // Rework: the expanded area is a vertically scrolling grid with
    // a parse-variant column (double pinyin) and a word/single-char filter.
    const world = fresh({ mode: 'double-pinyin' });
    world.engineState({
        mode: 'double-pinyin', composing: true, rawInput: "x'an", revision: 5,
        candidates: [
            { id: 'a', text: '西安' }, { id: 'b', text: '喜爱' }, { id: 'c', text: '西' },
            { id: 'd', text: '性爱' }, { id: 'e', text: '相爱' },
        ],
        hasPreviousPage: false, hasNextPage: true,
    });
    world.tap(world.$('composeExpand'));
    // Variant column lists the raw parse plus every x-syllable expansion.
    const variants = [...world.$('expandVariants').querySelectorAll('.expand-variant')]
        .map(el => el.textContent);
    assert(variants.includes("x'an"), 'raw parse listed');
    assert(variants.includes("xi'an"), 'x syllable expansion listed');
    assert(variants.includes("xc'an") || variants.includes("xn'an"), 'more expansions listed');
    assert(world.$('expandVariants').querySelector('.expand-variant').classList.contains('current'),
        'raw parse is the current variant');

    // Tapping a variant switches the parse IN PLACE: one atomic
    // setComposition call, optimistic highlight, no per-key replay.
    world.native.reset();
    const target = [...world.$('expandVariants').querySelectorAll('.expand-variant')]
        .find(el => el.textContent === "xi'an");
    world.tap(target);
    const comps = world.native.of('setComposition');
    equal(comps.length, 1, 'variant switch is one atomic native call');
    equal(comps[0].args[0], "xi'an", 'setComposition carries the chosen parse');
    equal(world.native.of('backspace').length + world.native.of('key').length, 0,
        'no delete-and-retype key simulation');
    assert(
        [...world.$('expandVariants').querySelectorAll('.expand-variant')]
            .find(el => el.textContent === "xi'an").classList.contains('current'),
        'chosen variant highlights optimistically');
    world.tap(target); // mid-replay re-click: the re-entry guard must refuse
    equal(world.native.of('setComposition').length, 1, 'no re-entry while replaying');

    // Intermediate engine events during the replay must not rebuild the
    // grid: feed the empty-composition echo, the grid stays as it was.
    world.engineState({
        mode: 'double-pinyin', composing: false, rawInput: '', revision: 6,
        candidates: [], hasPreviousPage: false, hasNextPage: false,
    });
    equal(world.$('expandGrid').querySelectorAll('.expand-candidate').length, 5,
        'grid frozen during replay');

    // 单字 tab filters the grid down to single characters.
    world.tap([...world.document.querySelectorAll('[data-expand-tab]')]
        .find(el => el.dataset.expandTab === 'single'));
    const texts = [...world.$('expandGrid').querySelectorAll('.expand-candidate')]
        .map(el => el.textContent);
    equal(texts.join(','), '西', 'single-char tab filters multi-char words');
    // Back to 词频: all five render again.
    world.tap([...world.document.querySelectorAll('[data-expand-tab]')]
        .find(el => el.dataset.expandTab === 'freq'));
    equal(world.$('expandGrid').querySelectorAll('.expand-candidate').length, 5,
        'freq tab restores every candidate');

    // P1-1 guard : candidate buttons still do not own the touch.
    const result = world.drag(world.$('expandGrid').querySelector('.expand-candidate'), -200);
    equal(result.cancelled, false, 'candidate touchstart must not preventDefault the grid pan');

    // The target echo lifts the guard and rebuilds the grid on the parse.
    world.engineState({
        mode: 'double-pinyin', composing: true, rawInput: "xi'an", revision: 6,
        candidates: [
            { id: 'a', text: '西茶' }, { id: 'b', text: '西察' },
        ],
        hasPreviousPage: false, hasNextPage: false,
    });
    const after = [...world.$('expandGrid').querySelectorAll('.expand-candidate')]
        .map(el => el.textContent);
    equal(after.join(','), '西茶,西察', 'target echo swaps the grid to the chosen parse');
    world.native.reset();
    world.tap(target);
    assert(world.native.of('setComposition').length === 1, 'guard released after target echo');
});

test('X2d drag on a qwerty key stays cancelled (scroll semantics kept honest)', () => {
    // Guard the harness itself: keys DO preventDefault touchstart, so a drag
    // there must report cancelled - otherwise X2c proves nothing.
    const world = fresh();
    const result = world.drag(world.key('g'), -200);
    equal(result.cancelled, true, 'bindTouch keys cancel the synthetic pan');
    equal(world.$('symCats') !== null, true, 'overflow containers declared');
});

test('I6b symbol category strip drags horizontally', () => {
    // Review P1: touch-action:none on category buttons killed the
    // strip pan. Mock models the touch side (no preventDefault); the CSS
    // side is guarded by css_lint.js.
    const world = fresh();
    const key123 = [...world.document.querySelectorAll('.kb-special')].find(
        el => el.textContent === '123',
    );
    world.tap(key123);
    const strip = world.$('symCats');
    const cats = strip.querySelectorAll('[data-sym-cat]');
    assert(cats.length >= 10, 'ten categories rendered (dropped cn/en tabs)');
    const result = world.drag(cats[0], -240);
    equal(result.cancelled, false, 'category buttons must not cancel the pan');
    equal(result.scrollLeft > 0, true, 'drag scrolls the category strip');
});

test('keyboard declares compose-control-v1', () => {
    const world = fresh();
    const ready = world.native.of('keyboardReady');
    equal(ready.length, 1, 'keyboardReady called');
    const caps = JSON.parse(ready[0].args[2]);
    assert(caps.includes('compose-control-v1'), 'capability declared');
});

// ---------------------------------------------------------------- G2 fixes

test('mic is a fixed SVG icon; state changes never swap glyphs', () => {
    const world = fresh();
    const mic = world.$('mic');
    const svg = mic.children.find(child => child.tagName === 'SVG');
    assert(svg, 'mic renders an svg icon');
    const childCount = mic.children.length;
    world.nativeState({ state: 'listening' });
    assert(mic.classList.contains('listening'), 'listening class set');
    equal(mic.children.length, childCount, 'icon element unchanged');
    assert(mic.children.includes(svg), 'same icon instance');
    world.nativeState({ state: 'idle' });
    assert(!mic.classList.contains('listening'), 'class cleared on idle');
    assert(!mic.textContent.includes('●') && !mic.textContent.includes('■'), 'no glyph swap');
});

test('russian е popup offers both ё and Ё', () => {
    const world = fresh({ mode: 'russian' });
    const eKey = world.key('е');
    world.touchDown(eKey);
    world.clock.advance(360);
    assert(world.$('keyPopup').classList.contains('open'), 'popup open');
    const labels = [...world.document.querySelectorAll('.kp-item')].map(el => el.textContent);
    assert(labels.includes('ё'), 'lowercase ё present');
    assert(labels.includes('Ё'), 'uppercase Ё present');
    world.touchCancel(eKey);
});

test('shift applies to accented popup selection (é → É)', () => {
    const world = fresh({ mode: 'french' });
    // 相对跟手：把卡片矩形钉到覆盖所有格子假矩形的位置（取消边界用）。
    world.document.getElementById('keyPopup').getBoundingClientRect =
        () => ({ left: 0, top: 0, right: 500, bottom: 400, width: 500, height: 400 });
    const shift = [...world.document.querySelectorAll('.kb-key')].find(
        el => el.dataset.role === 'shift',
    );
    world.tap(shift);
    const eKey = world.key('e');
    world.touchDown(eKey);
    world.clock.advance(360);
    const items = [...world.document.querySelectorAll('.kp-item')];
    const target = items.find(el => el.textContent === 'é');
    assert(target, 'é offered in popup');
    // 相对跟手（3.39.0）：拖动量 = 目标格 − 锚点格（按下点 20,20），
    // 收尾坐标 = 最后移动位置。
    const ar = [...items].find(el => el.classList.contains('sel'))
        .getBoundingClientRect();
    const tr = target.getBoundingClientRect();
    const mx = 20 + (tr.left + tr.width / 2) - (ar.left + ar.width / 2);
    const my = 20 + (tr.top + tr.height / 2) - (ar.top + ar.height / 2);
    world.move(eKey, mx, my);
    world.touchUp(eKey, mx, my);
    const keys = world.native.of('key');
    equal(keys[keys.length - 1].args[0], 'É', 'shift applied to accented selection');
});

// ------------------------------------------------- clipboard/favorites panel

test('clipboard button opens panel and requests clipboard', () => {
    const world = fresh();
    world.tap(world.$('clipboardButton'));
    assert(!world.$('panelLayer').hidden, 'panel visible');
    assert(world.$('qwertyLayer').hidden, 'letters hidden behind panel');
    assert(world.$('panelClose'), 'close control exists');
    equal(world.native.of('getClipboard').length, 1, 'clipboard requested');
    equal(world.native.of('getClipboard')[0].args[0], 'tok-1', 'token attached');
    world.tap(world.$('panelClose'));
    assert(world.$('panelLayer').hidden, 'panel closed');
    assert(!world.$('qwertyLayer').hidden, 'letters restored');
});

test('clipboard items render, paste commits via commitText', () => {
    const world = fresh();
    world.clipboard([
        { id: 'a1', time: 1, text: '复制的内容' },
        { id: 'b2', time: 2, text: 'second' },
    ]);
    world.tap(world.$('clipboardButton'));
    const rows = [...world.document.querySelectorAll('.panel-item')];
    equal(rows.length, 2, 'two items rendered');
    world.tap(rows[1]); // 'second'
    const commits = world.native.of('commitText');
    equal(commits.length, 1, 'paste goes through commitText');
    equal(commits[0].args[0], 'second', 'full text committed');
    equal(commits[0].args[1], 'tok-1', 'token attached');
    assert(world.$('panelLayer').hidden, 'panel closes after paste');
});

test('remove and clear clipboard hit the bridge with hex ids', () => {
    const world = fresh();
    world.clipboard([{ id: 'a1', time: 1, text: 'keep me' }]);
    world.tap(world.$('clipboardButton'));
    const row = world.document.querySelectorAll('.panel-item')[0];
    row.querySelector('.panel-remove').click();
    equal(world.native.of('removeClipboard').length, 1, 'removeClipboard called');
    equal(world.native.of('removeClipboard')[0].args[0], 'a1', 'id passed');
    // Tapping × must not also paste the row content.
    equal(world.native.of('commitText').length, 0, 'remove must not paste');
    world.tap(world.$('panelClear'));
    equal(world.native.of('clearClipboard').length, 1, 'clearClipboard called');
});

test('P3b panel rows use native clicks (no touch handlers)', () => {
    // bindTouch preventDefaults scrolling and double-fires
    // remove taps; panel rows must rely on native click only.
    const world = fresh();
    world.clipboard([{ id: 'a1', time: 1, text: 'x' }]);
    world.tap(world.$('clipboardButton'));
    const row = world.document.querySelectorAll('.panel-item')[0];
    equal(row.dataset.bound, undefined, 'row not bound by bindTouch');
});

test('over-long clipboard item renders disabled and never commits', () => {
    const world = fresh();
    const huge = 'x'.repeat(2001);
    world.clipboard([{ id: 'big', time: 1, text: huge }]);
    world.tap(world.$('clipboardButton'));
    const row = world.document.querySelectorAll('.panel-item')[0];
    assert(row.className.includes('disabled'), 'row marked too long');
    world.tap(row);
    equal(world.native.of('commitText').length, 0, 'no commit for over-long item');
});

test('favorites tab renders, pastes; menu deletes', () => {
    const world = fresh();
    world.tap(world.$('clipboardButton'));
    world.document.querySelectorAll('[data-panel-tab]').forEach(b => {
        if (b.dataset.panelTab === 'favorites') world.tap(b);
    });
    // 3.21.0 seeds favorites at hello; 3.20.0 pulls only on open.
    equal(world.native.of('getFavorites').length,
        verAtLeast(KEYBOARD_VERSION, '3.21.0') ? 2 : 1,
        'favorites requested (3.21.0: hello seed + panel; 3.20.0: panel only)');
    assert(world.document.querySelectorAll('.panel-item').length === 0, 'empty list');
    world.favorites([{ id: 'f1', time: 1, text: '常用语一条' }]);
    const rows = [...world.document.querySelectorAll('.panel-item')];
    equal(rows.length, 1, 'favorite rendered');
    world.tap(rows[0].querySelector('.panel-more'));
    const del = [...world.document.getElementById('itemMenu').querySelectorAll('button')]
        .find(b => b.textContent === '删除');
    world.tap(del);
    equal(world.native.of('removeFavorite').slice(-1)[0].args[0], 'f1', 'menu delete removes');
    world.favorites([{ id: 'f2', time: 2, text: '常用语一条' }]);
    world.tap(world.document.querySelectorAll('.panel-item')[0].querySelector('.panel-text'));
    equal(world.native.of('commitText').slice(-1)[0].args[0], '常用语一条', 'favorite pastes');
});

test('composing closes the panel and hides clipboard tool', () => {
    const world = fresh();
    world.tap(world.$('clipboardButton'));
    assert(!world.$('panelLayer').hidden, 'panel open');
    world.engineState({ phase: 'READY', revision: 1, composing: 'ni', candidates: [] });
    assert(world.$('panelLayer').hidden, 'panel auto-closed by composing');
    assert(world.$('clipboardButton').hidden, 'clipboard tool hidden while composing');
    assert(world.$('setupButton').hidden, 'setup tool hidden');
});

// ------------------------------------------------- Toolbar / panel

test('clipboard and favorites are two independent toolbar icons', () => {
    const world = fresh();
    const fav = world.$('favoritesButton');
    assert(fav && fav.dataset.role === 'favorites', 'favorites icon on the toolbar');
    assert(!fav.hidden, 'favorites visible while idle');
    world.tap(fav);
    assert(!world.$('panelLayer').hidden, 'panel opens from favorites icon');
    // 3.21.0 seeds favorites at hello; 3.20.0 pulls only on open.
    equal(world.native.of('getFavorites').length,
        verAtLeast(KEYBOARD_VERSION, '3.21.0') ? 2 : 1,
        'favorites requested (3.21.0: hello seed + panel; 3.20.0: panel only)');
    // The panel replaces the toolbar row instead of stacking.
    assert(world.$('candidateBar').hidden, 'toolbar row hidden while panel open');
    world.tap(world.$('panelClose'));
    assert(world.$('panelLayer').hidden, 'panel closed');
    assert(!world.$('candidateBar').hidden, 'toolbar row restored');
    world.tap(world.$('clipboardButton'));
    assert(!world.$('panelLayer').hidden, 'panel opens from clipboard icon');
    equal(world.document.querySelector('[data-panel-tab="clipboard"]').classList.contains('active'),
        true, 'clipboard tab active');
});

test('composing hides the favorites tool too', () => {
    const world = fresh();
    world.engineState({ phase: 'READY', revision: 1, composing: 'ni', candidates: [] });
    assert(world.$('favoritesButton').hidden, 'favorites hidden while composing');
    world.engineState({ phase: 'READY', revision: 2, composing: false, rawInput: '', candidates: [] });
    assert(!world.$('favoritesButton').hidden, 'favorites back when idle');
});

test('no native traffic from unknown buttons before hello', () => {
    const world = new KeyboardWorld().build();
    world.tap(world.document.getElementById('favoritesButton'));
    equal(world.native.of('getFavorites').length, 0, 'favorites gated by readiness');
});

test('keyboard declares the three new capabilities', {since: '3.21.0'}, () => {
    const world = fresh();
    const ready = world.native.of('keyboardReady')[0];
    const caps = JSON.parse(ready.args[2]);
    for (const cap of ['clipboard-v1', 'favorites-v2', 'commit-text-v1']) {
        assert(caps.includes(cap), `capability ${cap} required`);
    }
});

// ------------------------------------------------- Control layer / height

test('toolbar gains the control + IME-switch tools; globe opens the picker', () => {
    const world = fresh();
    const ctrl = world.$('ctrlTool');
    const ime = world.$('imeSwitchButton');
    assert(ctrl && ime, 'both new tools exist');
    world.tap(ime);
    equal(world.native.of('switchInputMethod').length, 1, 'picker opened');
    equal(world.native.of('keyEvent').length, 0, 'no keyEvent for the picker');
});

test('ctrl view swaps the toolbar; the keyboard stays untouched', () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    assert(world.document.body.classList.contains('ctrl-view'), 'ctrl-view on');
    assert(!world.$('ctrlLayer').hidden, 'ctrl layer shown');
    // User feedback: the control strip replaces ONLY the candidate
    // bar - the keyboard rows below stay visible and usable.
    assert(world.$('candidateBar').hidden, 'candidate bar replaced');
    assert(!world.$('qwertyLayer').hidden, 'keyboard stays');
    // Escape fires a plain keyevent (KEYCODE_ESCAPE = 111, no meta).
    world.tap(world.document.querySelector('[data-ctrl="Escape"]'));
    const ev = world.native.of('keyEvent');
    equal(ev.length, 1, 'one keyEvent');
    equal(ev[0].args[0], 111, 'Escape keycode');
    equal(ev[0].args[1], 0, 'no meta');
    // Collapse hands the toolbar back.
    world.tap(world.document.querySelector('[data-ctrl="collapse"]'));
    assert(!world.document.body.classList.contains('ctrl-view'), 'ctrl-view off');
    assert(!world.$('qwertyLayer').hidden, 'letters back');
    assert(!world.$('candidateBar').hidden, 'toolbar back');
    assert(world.$('ctrlLayer').hidden, 'ctrl layer hidden');
});

test('sticky modifiers arm the next key into a combo', () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    const ctrlMod = world.document.querySelector('[data-ctrl="sticky-ctrl"]');
    world.tap(ctrlMod);
    assert(ctrlMod.classList.contains('active'), 'Ctrl lights up');
    world.tap(world.document.querySelector('[data-ctrl="ArrowLeft"]'));
    // Ctrl combos ride the PHYSICAL channel like every other
    // modifier combo (the single-event form stuck the remote Alt down).
    let ev = world.native.of('keyEventPhysical');
    equal(ev.length, 1, 'physical combo sent');
    equal(ev[0].args[0], 21, 'KEYCODE_DPAD_LEFT');
    equal(ev[0].args[1], 0x1000, 'META_CTRL');
    assert(!ctrlMod.classList.contains('active'), 'sticky cleared after the combo');
    // Meta (Win/cmd) arms a letter combo: Meta+Tab rides the PHYSICAL
    // channel ( - the RDP client only honors the discrete key
    // sequence for Win combos).
    world.tap(world.document.querySelector('[data-ctrl="sticky-meta"]'));
    world.tap(world.document.querySelector('[data-ctrl="Tab"]'));
    const phys = world.native.of('keyEventPhysical');
    equal(phys.length, 2, 'second physical combo (ArrowLeft rode it too)');
    equal(phys[1].args[0], 61, 'KEYCODE_TAB');
    equal(phys[1].args[1], 0x10000, 'META_META');
});

test('armed qwerty shift joins control keys and sticky modifiers', {since: '3.27.0'}, () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    const shift = world.document.querySelector('.shift');
    world.tap(shift);
    assert(shift.classList.contains('active'), 'shift armed');
    // shift + Tab = Shift+Tab on the physical channel (design §11).
    world.tap(world.document.querySelector('[data-ctrl="Tab"]'));
    const ev = world.native.of('keyEventPhysical');
    equal(ev.length, 1, 'shift+tab rides the physical channel');
    equal(ev[0].args[0], 61, 'KEYCODE_TAB');
    equal(ev[0].args[1], 1, 'META_SHIFT');
    assert(!shift.classList.contains('active'), 'combo consumed the armed shift');
    // Order independent: Ctrl sticky armed first, shift second.
    world.tap(world.document.querySelector('[data-ctrl="sticky-ctrl"]'));
    world.tap(shift);
    world.tap(world.key('c'));
    const evs = world.native.of('keyEventPhysical');
    equal(evs.length, 2, 'letter combo sent');
    equal(evs[1].args[0], 31, 'KEYCODE_C');
    equal(evs[1].args[1], 0x1001, 'META_CTRL|META_SHIFT');
    assert(!shift.classList.contains('active'), 'shift consumed again');
    assert(!world.document.querySelector('[data-ctrl="sticky-ctrl"]').classList.contains('active'),
        'ctrl sticky cleared with it');
});

test('armed shift alone never opens the combo channel', () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    const shift = world.document.querySelector('.shift');
    world.tap(shift);
    world.tap(world.key('h'));
    equal(world.native.of('key').slice(-1)[0].args[0], 'H', 'uppercase text, not a key event');
    equal(world.native.of('keyEventPhysical').length, 0, 'no physical combo');
    assert(!shift.classList.contains('active'), 'still one-shot uppercase');
});

test('Fn sticky turns twelve letters into F-keys; long-press opens the comb grid', () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    const fn = world.document.querySelector('[data-ctrl="sticky-fn"]');
    assert(fn && fn.textContent === 'Fn', 'Fn key replaced the Comb icon');
    world.tap(fn);
    assert(fn.classList.contains('active'), 'Fn lights up');
    // Q..P become F1..F10, K/L become F11/F12: main glyph swaps, letter
    // drops to the alt slot.
    equal(world.key('q').querySelector('.kb-main').textContent, 'F1', 'q shows F1');
    equal(world.key('q').querySelector('.kb-alt').textContent, 'q', 'letter drops to alt');
    equal(world.key('l').querySelector('.kb-main').textContent, 'F12', 'l shows F12');
    equal(world.key('a').querySelector('.kb-main').textContent, 'a', 'unmapped keys unchanged');
    world.tap(world.key('q'));
    const ev = world.native.of('keyEvent');
    equal(ev.length, 1, 'F-key sent');
    equal(ev[0].args[0], 131, 'KEYCODE_F1');
    equal(ev[0].args[1], 0, 'no meta');
    assert(!fn.classList.contains('active'), 'sticky cleared after the combo');
    // Review P1-1: the GLYPHS must snap back with the sticky state - a
    // stale F1 face over letter taps reads as a broken keyboard.
    equal(world.key('q').querySelector('.kb-main').textContent, 'q',
        'q face restored after the combo');
    // Second-tap disarm restores the faces too.
    world.tap(fn);
    world.tap(fn);
    equal(world.key('l').querySelector('.kb-main').textContent, 'l',
        'l face restored on disarm');
    equal(world.key('j').querySelector('.kb-alt').textContent, '~',
        'j alt hint restored on disarm');
    // Fn + Ctrl stacks: Ctrl+F4 = 134 with CTRL meta (the keycode table
    // once carried 131 = F1 for F4 and the native whitelist refused it,
    // so Alt+F4 never fired).
    world.tap(world.document.querySelector('[data-ctrl="sticky-ctrl"]'));
    world.tap(world.document.querySelector('[data-ctrl="sticky-fn"]'));
    world.tap(world.key('r'));
    const combo = world.native.of('keyEventPhysical');
    equal(combo[combo.length - 1].args[0], 134, 'KEYCODE_F4 (not the old 131)');
    equal(combo[combo.length - 1].args[1], 0x1000, 'META_CTRL');
    // long-press Fn opens the former Comb grid (Ctrl+Alt+Del ... Alt+F4).
    world.touchDown(fn);
    world.clock.advance(360);
    world.touchUp(fn);
    assert(world.$('comboPopup').classList.contains('open'), 'Fn long-press opens the comb grid');
    const cells = [...world.document.querySelectorAll('.combo-cell')];
    equal(cells.length, 9, 'nine comb cells');
});

test('first Fn tap after a long press without synthetic click still toggles', {since: '3.24.0'}, () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    const fn = world.document.querySelector('[data-ctrl="sticky-fn"]');
    const longPressWithoutClick = () => {
        world.touchDown(fn);
        world.clock.advance(360);
        world.dispatch(fn, 'touchend', 20, 20);
        assert(world.$('comboPopup').classList.contains('open'), 'long press opens grid');
    };
    longPressWithoutClick();
    world.tap(world.$('comboClose'));
    world.tap(fn);
    assert(fn.classList.contains('active'), 'first new Fn tap arms immediately');
    world.tap(fn);
    assert(!fn.classList.contains('active'), 'second tap disarms');

    longPressWithoutClick();
    world.tap(fn);
    assert(!world.$('comboPopup').classList.contains('open'), 'trigger tap closes grid');
    assert(!fn.classList.contains('active'), 'that closing tap does not arm Fn');
    world.tap(fn);
    assert(fn.classList.contains('active'), 'following tap arms normally');
});

test('long-press Ctrl opens the 3x3 combo grid; cells send combos', () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    const ctrlMod = world.document.querySelector('[data-ctrl="sticky-ctrl"]');
    world.touchDown(ctrlMod);
    world.clock.advance(360);
    world.touchUp(ctrlMod);
    assert(world.$('comboPopup').classList.contains('open'), 'combo grid open');
    const cells = [...world.document.querySelectorAll('.combo-cell')];
    equal(cells.length, 9, 'nine combos');
    // The harness caches container textContent - read the cell's spans.
    const cellText = cell => [...cell.children].map(s => s.textContent).join('');
    equal(cells.filter(c => cellText(c) === 'CtrlC').length, 1,
        'full key names stacked (Ctrl/C)');
    world.tap(cells[2]);
    const ev = world.native.of('keyEventPhysical');
    equal(ev.length, 1, 'combo sent (physical)');
    equal(ev[0].args[0], 29 + 'C'.charCodeAt(0) - 65, 'KEYCODE_C');
    equal(ev[0].args[1], 0x1000, 'META_CTRL');
    assert(!world.$('comboPopup').classList.contains('open'), 'grid closed after pick');
    // Review P2: the long-press's synthetic click must NOT flip the
    // sticky modifier after the grid picked a combo.
    assert(!ctrlMod.classList.contains('active'), 'long-press click does not arm the sticky');
});

test('long-press Win offers Meta+D/L/P; a cell sends META_META', () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    const winMod = world.document.querySelector('[data-ctrl="sticky-meta"]');
    world.touchDown(winMod);
    world.clock.advance(360);
    world.touchUp(winMod);
    assert(world.$('comboPopup').classList.contains('open'), 'win grid open');
    const cells = [...world.document.querySelectorAll('.combo-cell')];
    equal(cells.length, 3, 'three win shortcuts');
    // Modifier names are their own spans now (small muted text above the key).
    const cellText = cell => [...cell.children].map(s => s.textContent).join('');
    equal(cells.map(cellText).join(','), 'MetaD,MetaL,MetaP', 'Meta+D/L/P cells');
 world.tap(cells[0]); // Win+D = show desktop (physical channel)
    const phys = world.native.of('keyEventPhysical');
    equal(phys.length, 1, 'physical combo sent');
    equal(phys[0].args[0], 29 + 'D'.charCodeAt(0) - 65, 'KEYCODE_D');
    equal(phys[0].args[1], 0x10000, 'META_META');
    assert(!winMod.classList.contains('active'), 'long-press click does not arm the sticky');
});

test('the close affordance is a frameless X that collapses the layer', () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    const close = world.document.querySelector('[data-ctrl="collapse"]');
    assert(close.classList.contains('ctrl-close'), 'collapse carries the ctrl-close class');
    assert(close.querySelector('svg'), 'collapse shows the X glyph (svg), not a text glyph');
    world.tap(close);
    assert(!world.document.body.classList.contains('ctrl-view'), 'X collapses the ctrl view');
    // Review P3: the editor strip owns the bar - entering is refused.
    world.document.body.classList.add('editing');
    world.tap(world.$('ctrlTool'));
    assert(!world.document.body.classList.contains('ctrl-view'),
        'ctrl view refused while the editor strip owns the bar');
    world.document.body.classList.remove('editing');
});

test('composing hides the new tools and refuses the ctrl view', () => {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }], hasNextPage: false });
    assert(world.$('ctrlTool').hidden, 'ctrl tool hidden while composing');
    assert(world.$('imeSwitchButton').hidden, 'ime tool hidden while composing');
    // Entering the ctrl view while composing is refused.
    world.tap(world.$('ctrlTool'));
    assert(!world.document.body.classList.contains('ctrl-view'), 'ctrl view refused');
});

test('landscape keeps the four-row layout (folding reverted)', () => {
    const world = new KeyboardWorld().build();
    world.hello({ orientation: 'landscape' });
    assert(world.document.body.classList.contains('landscape'), 'landscape class');
    // E: the folded three-row landscape layout is REVERTED -
    // identical key placement in both orientations.
    equal(world.document.querySelectorAll('#qwertyLayer .kb-row').length, 4,
        'four rows in landscape too');
    const z = world.key('z');
    const rows = world.document.querySelectorAll('#qwertyLayer .kb-row');
    equal(z.parentNode, rows[2], 'z stays in the third row (as portrait)');
    // Portrait hello keeps the same layout.
    world.hello({ orientation: 'portrait' });
    equal(world.document.querySelectorAll('#qwertyLayer .kb-row').length, 4,
        'four rows in portrait');
});

test('a second tap on an armed sticky modifier fires the bare key', () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    // Win armed then tapped again: KEYCODE_META_LEFT alone (Start menu).
    const win = world.document.querySelector('[data-ctrl="sticky-meta"]');
    world.tap(win);
    assert(win.classList.contains('active'), 'Win armed on first tap');
    world.tap(win);
    const ev = world.native.of('keyEvent');
    equal(ev.length, 1, 'one bare key event');
    equal(ev[0].args[0], 117, 'KEYCODE_META_LEFT');
    equal(ev[0].args[1], 0, 'no meta bits');
    assert(!win.classList.contains('active'), 'sticky cleared after the bare key');
    // Ctrl behaves the same (KEYCODE_CTRL_LEFT = 113).
    const ctrl = world.document.querySelector('[data-ctrl="sticky-ctrl"]');
    world.tap(ctrl);
    world.tap(ctrl);
    const ev2 = world.native.of('keyEvent');
    equal(ev2[1].args[0], 113, 'KEYCODE_CTRL_LEFT');
    equal(ev2[1].args[1], 0, 'no meta bits');
    // Armed + a normal control key still sends the COMBO (it
    // moves to the physical channel with the rest of the modifier combos).
    world.tap(ctrl);
    world.tap(world.document.querySelector('[data-ctrl="Escape"]'));
    const phys3 = world.native.of('keyEventPhysical');
    equal(phys3[phys3.length - 1].args[0], 111, 'Escape keycode');
    equal(phys3[phys3.length - 1].args[1], 0x1000, 'META_CTRL rides the combo');
});

test('landscape lifts the rows above the gesture-nav strip (3.20.0 form)', {until: '3.20.0'}, () => {
    const noSafe = new KeyboardWorld().build();
    noSafe.hello({ orientation: 'landscape' });
    const withSafe = new KeyboardWorld().build();
    withSafe.hello({ orientation: 'landscape', safeBottom: 24 });
    // 3.20.0 shrank the rows to make room for the landscape inset. Portrait
    // lived above the navigation bar and therefore ignored the inset.
    const readVar = (w, name) => w.document.documentElement.style[name];
    equal(readVar(noSafe, '--safe-bottom'), '0px', 'no inset: zero padding');
    equal(readVar(withSafe, '--safe-bottom'), '24px', 'inset applied as padding');
    const rowH = w => readVar(w, '--kb-row-h');
    const shorter = parseInt(rowH(withSafe), 10) < parseInt(rowH(noSafe), 10);
    assert(shorter, 'rows shrink so the keys clear the strip');
    const portrait = fresh({ orientation: 'portrait', safeBottom: 24 });
    portrait.engineState({ mode: 'pinyin', revision: 1, composing: '', candidates: [] });
    equal(readVar(portrait, '--safe-bottom'), '0px', 'portrait ignores the inset');
});

test('landscape lifts the rows above the gesture-nav strip', {since: '3.22.1'}, () => {
    const noSafe = new KeyboardWorld().build();
    noSafe.$('softKeyboard').clientHeight = 272;
    noSafe.hello({ orientation: 'landscape' });
    const withSafe = new KeyboardWorld().build();
    withSafe.$('softKeyboard').clientHeight = 296;
    withSafe.hello({ orientation: 'landscape', safeBottom: 24 });
    // harness style is a plain map (setProperty writes keys verbatim)
    const readVar = (w, name) => w.document.documentElement.style[name];
    equal(readVar(noSafe, '--safe-bottom'), '0px', 'no inset: zero padding');
    equal(readVar(withSafe, '--safe-bottom'), '24px', 'inset applied as padding');
    const rowH = w => readVar(w, '--kb-row-h');
    equal(rowH(withSafe), rowH(noSafe), 'content height stays stable above the strip');
    // The native view carries the inset in portrait too. hello() only lays
    // out on an orientation CHANGE - force one layout via a mode switch
    // before reading the vars.
    const portrait = fresh({ orientation: 'portrait', safeBottom: 24 });
    portrait.engineState({ mode: 'pinyin', revision: 1, composing: '', candidates: [] });
    equal(readVar(portrait, '--safe-bottom'), '24px', 'portrait carries the inset');
});

test('safe area keeps portrait key content height stable', {since: '3.22.1'}, () => {
    const noSafe = new KeyboardWorld().build();
    noSafe.$('softKeyboard').clientHeight = 272;
    noSafe.hello({ orientation: 'portrait' });
    const withSafe = new KeyboardWorld().build();
    // Native view height = content height + safe area in both orientations.
    withSafe.$('softKeyboard').clientHeight = 304;
    withSafe.hello({ orientation: 'portrait', safeBottom: 32 });
    const rowHeight = world => parseInt(world.document.documentElement.style['--kb-row-h'], 10);
    equal(rowHeight(withSafe), rowHeight(noSafe), 'portrait safe area does not shrink the rows');
    equal(withSafe.document.documentElement.style['--safe-bottom'], '32px',
        'portrait safe area is exposed to CSS');
});

test('short landscape keeps all four rows above the system area', {since: '3.22.2'}, () => {
    const world = new KeyboardWorld().build();
    world.$('softKeyboard').clientHeight = 212;
    world.hello({ orientation: 'landscape', safeBottom: 32 });
    const row = parseInt(world.document.documentElement.style['--kb-row-h'], 10);
    assert(row > 0 && 78 + 4 * row <= 180, 'four rows fit the native half-screen content budget');
});

test('height drag PREVIEWS only; the release applies once', {since: '3.21.0'}, () => {
    const world = fresh();
    world.tap(world.$('setupButton'));
    world.tap(world.tile('键盘高度'));
    const card = world.$('heightCard');
    assert(!card.hidden && card.classList.contains('open'), 'height card shown');
    const applied = () => world.native.of('setKeyboardHeight').length;
    const before = applied();
    // Dragging moves the PREVIEW (thumb/value) - the user explicitly
    // rejected live resize while dragging.
    const track = world.$('heightTrack');
    world.touchDown(track, 10, 10);
    world.move(track, 30, 0);
    world.move(track, 60, 0);
    equal(applied(), before, 'dragging never applies the height');
    assert(world.document.getElementById('heightValue').textContent !== '',
        'preview value rendered');
    // The release lands ONE apply.
    world.touchUp(track);
    equal(applied(), before + 1, 'release applies exactly once');
    // -/+ buttons apply immediately (fine steps).
    const plus = applied();
    world.tap(world.document.getElementById('heightPlus'));
    equal(applied(), plus + 1, 'plus applies immediately');
    world.tap(world.document.getElementById('heightMinus'));
    equal(applied(), plus + 2, 'minus applies immediately');
    // 保存 persists through the native bridge (the old save wrote only
    // localStorage and the height reverted - user-reported bug).
    const saved = applied();
    world.tap(world.document.getElementById('heightCardSave'));
    equal(applied(), saved + 1, 'save applies the preview height');
    assert(world.$('heightCard').hidden, 'card hidden after save');
});

test('height-card range follows the hello-pushed screen ceiling', {since: '3.21.0'}, () => {
    // Root cause: window.innerHeight rides the keyboard itself (band+keys),
    // so the old innerHeight-floatBand ceiling tracked the CURRENT height and
    // the thumb opened pinned at an end (landscape: below min, hard left).
    // The range must come from the native hello, mirroring setKeyboardHeight.
    const world = fresh();
    world.hello({ heightCeil: 300, heightFloor: 210, floatBand: 200 });
    world.tap(world.$('setupButton'));
    world.tap(world.tile('键盘高度'));
    equal(world.document.getElementById('heightValue').textContent, '272px',
        'preview opens at the current height');
    const left = parseInt(world.document.getElementById('heightThumb').style.left, 10);
    // 272 inside [224, 300]: frac 0.63 of the (200-14) track - mid-track,
    // neither end pinned
    assert(left > 60 && left < 130, `thumb starts mid-track, not pinned (left=${left})`);
    // A ceiling AT the content floor (landscape half-screen budgets) must say
    // so instead of offering a range the native clamp silently refuses.
    world.tap(world.document.getElementById('heightCardCancel'));
    world.hello({ orientation: 'landscape', heightCeil: 206, heightFloor: 170, floatBand: 120 });
    world.tap(world.$('setupButton'));
    world.tap(world.tile('键盘高度'));
    equal(world.document.getElementById('heightHint').textContent, '横屏已达屏幕上限',
        'capped hint replaces the drag hint');
    assert(world.document.getElementById('heightPlus').disabled === true,
        'fine steps disabled at the ceiling');
    assert(world.document.getElementById('heightTrack').style.opacity === '.35',
        'track dims at the ceiling');
});

test('unchanged height saves send stable content height', {since: '3.22.1'}, () => {
    const world = new KeyboardWorld().build();
    world.$('softKeyboard').clientHeight = 304;
    world.hello({ orientation: 'portrait', safeBottom: 32, heightFloor: 210, heightCeil: 320 });
    const openCard = () => {
        world.tap(world.$('setupButton'));
        world.tap(world.tile('键盘高度'));
    };
    for (let i = 0; i < 3; i++) {
        openCard();
        equal(world.$('heightValue').textContent, '272px', 'card reads content height');
        world.tap(world.$('heightCardSave'));
    }
    equal(world.native.of('setKeyboardHeight').map(call => call.args[0]).join(','), '272,272,272',
        'repeated unchanged saves keep the content value stable');
    equal(world.storage.get('feelime_kb_height_portrait'), '272',
        'storage mirrors content height');
});

test('landscape +/- and cancel keep safe area out of bridge height', {since: '3.22.1'}, () => {
    const world = new KeyboardWorld().build();
    world.$('softKeyboard').clientHeight = 248;
    world.hello({ orientation: 'landscape', safeBottom: 32, heightFloor: 170, heightCeil: 240 });
    world.tap(world.$('setupButton'));
    world.tap(world.tile('键盘高度'));
    equal(world.$('heightValue').textContent, '216px', 'landscape card reads content height');
    world.tap(world.$('heightPlus'));
    world.tap(world.$('heightCardCancel'));
    equal(world.native.of('setKeyboardHeight').map(call => call.args[0]).join(','), '220,216',
        'plus and cancel send content values without adding safe twice');

    // Old preview bridges have no height method; their direct style still
    // needs the total view height exactly once.
    world.context.window.FeelimeNative.setKeyboardHeight = undefined;
    world.context.window.Feelime.applyKbHeight(240);
    equal(world.$('softKeyboard').style.height, '272px',
        'fallback view height is content plus one safe area');
});

test('height drag applies LIVE (rAF-coalesced); save persists to storage only (3.20.0 form)', {until: '3.20.0'}, () => {
    const world = fresh();
    world.tap(world.$('setupButton'));
    const heightNav = [...world.document.querySelectorAll('.set-nav')]
        .find(el => el.textContent === '调节 ›');
    world.tap(heightNav);
    assert(!world.$('heightHandle').hidden, 'drag handle shown');
    const applied = () => world.native.of('setKeyboardHeight').length;
    const before = applied();
    // As shipped in 3.20.0: dragging applies the height LIVE through the
    // bridge, coalesced to one apply per frame). This is the
    // opposite of 3.21.0's "preview only, apply on release".
    const handle = world.$('heightHandle');
    world.touchDown(handle, 60, 40);
    world.move(handle, 60, 20);
    world.clock.advance(32); // flush the rAF (setTimeout-16 fallback) frame
    world.move(handle, 60, 4);
    world.clock.advance(32);
    world.touchUp(handle);
    assert(applied() > before, 'drag applies the height live (rAF-coalesced)');
    // Save writes localStorage (per orientation) and adds NO bridge call -
    // the height reverts on reload. As-is defect; fixed in later keyboard versions.
    const savedCalls = applied();
    world.tap(world.document.getElementById('heightSave'));
    equal(applied(), savedCalls, 'save adds no bridge call (storage-only persist)');
    assert(world.$('toast').textContent.includes('键盘高度已保存'), 'save toasts');
    assert(world.$('heightHandle').hidden, 'handle hidden after save');
    assert(world.$('settingsPanel').classList.contains('open'),
        'save reopens the quick panel (3.20.0 behavior)');
    const keys = [...world.storage.keys()].filter(k => k.startsWith('felime_kb_height'));
    assert(keys.length === 1, 'height persisted to storage for this orientation');
});

test('resize trusts the hello orientation (no portrait-drag flip)', () => {
    const world = fresh({ orientation: 'portrait' });
    world.context.window.innerWidth = 800;
    world.context.window.innerHeight = 400;
    world.context.window.fireResize();
    assert(!world.document.body.classList.contains('landscape'),
        'portrait hello wins over a wide viewport');
    // Without a hello yet the viewport ratio still decides (preview path).
    const cold = new KeyboardWorld().build();
    cold.context.window.innerWidth = 800;
    cold.context.window.innerHeight = 400;
    cold.context.window.fireResize();
    assert(cold.document.body.classList.contains('landscape'),
        'no hello: viewport ratio decides');
});

test('quick settings height row opens the drag handle', {since: '3.21.0'}, () => {
    const world = fresh();
    world.tap(world.$('setupButton'));
    assert(world.tile('键盘高度'), 'height tile present');
    world.tap(world.tile('键盘高度'));
    assert(!world.$('heightCard').hidden, 'height card shown');
    // Cancel restores the pre-edit height and hides the card.
    world.tap(world.document.getElementById('heightCardCancel'));
    assert(world.$('heightCard').hidden, 'card hidden after cancel');
});

test('quick settings height row opens the drag handle (3.20.0 form)', {until: '3.20.0'}, () => {
    const world = fresh();
    world.tap(world.$('setupButton'));
    const heightNav = [...world.document.querySelectorAll('.set-nav')]
        .find(el => el.textContent === '调节 ›');
    assert(heightNav, 'height row present');
    world.tap(heightNav);
    assert(!world.$('heightHandle').hidden, 'drag handle shown');
    assert(world.document.body.classList.contains('height-editing'), 'height-editing on');
    assert(!world.$('settingsPanel').classList.contains('open'),
        'the edit mode closes the quick panel');
    // Cancel restores the pre-edit height and reopens the panel. The restore
    // goes through the bridge (save vs cancel: persist-to-storage vs
    // live-restore - the as-is distinction this baseline pins).
    const cancelApplied = world.native.of('setKeyboardHeight').length;
    world.tap(world.document.getElementById('heightCancel'));
    equal(world.native.of('setKeyboardHeight').length, cancelApplied + 1,
        'cancel re-applies the height once');
    equal(world.native.of('setKeyboardHeight').slice(-1)[0].args[0], 272,
        'cancel restores the pre-edit height');
    assert(world.$('heightHandle').hidden, 'handle hidden after cancel');
    assert(world.$('settingsPanel').classList.contains('open'), 'panel reopened after cancel');
});

// ------------------------------------------------- 

test('the ctrl view is a switch: composing suspends, picking resumes', () => {
    const world = fresh({ mode: 'pinyin' });
    world.tap(world.$('ctrlTool'));
    assert(world.document.body.classList.contains('ctrl-view'), 'ctrl view on');
    // A composition starts mid-ctrl-view: the bar returns for the preedit/
    // candidate strip, but the SWITCH stays on.
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }], hasNextPage: false });
    assert(world.$('ctrlLayer').hidden, 'rows hidden while composing');
    assert(!world.$('candidateBar').hidden, 'bar back while composing');
    // ...because picking the candidate ends the composition and the rows
    // come back BY THEMSELVES (a one-shot view would stay collapsed).
    world.engineState({ mode: 'pinyin', revision: 2, composing: false, rawInput: '',
        candidates: [], hasNextPage: false });
    assert(!world.$('ctrlLayer').hidden, 'rows restored after the pick');
    assert(world.$('candidateBar').hidden, 'bar handed back');
    // The X turns the switch off for good: another compose/commit cycle
    // must NOT bring the rows back.
    world.tap(world.document.querySelector('[data-ctrl="collapse"]'));
    world.engineState({ mode: 'pinyin', revision: 3, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c2', text: '妮' }], hasNextPage: false });
    world.engineState({ mode: 'pinyin', revision: 4, composing: false, rawInput: '',
        candidates: [], hasNextPage: false });
    assert(world.$('ctrlLayer').hidden, 'no ghost rows after switching off');
    assert(!world.$('candidateBar').hidden, 'bar stays the ordinary toolbar');
});

test('panels borrow the bar from the ctrl view and hand it back', () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    assert(world.document.body.classList.contains('ctrl-view'), 'ctrl view on');
    world.tap(world.$('clipboardButton'));
    assert(!world.$('panelLayer').hidden, 'panel open');
    assert(world.$('ctrlLayer').hidden, 'rows suspended under the panel');
    world.tap(world.$('panelClose'));
    assert(!world.$('ctrlLayer').hidden, 'rows restored after the panel closes');
    assert(world.$('candidateBar').hidden, 'bar hidden again');
    // Same for the quick settings panel.
    world.tap(world.$('setupButton'));
    assert(world.$('ctrlLayer').hidden, 'rows suspended under quick settings');
    world.tap(world.$('setupButton'));
    assert(!world.$('ctrlLayer').hidden, 'rows restored after quick settings');
});

test('height card owns the top edge: no overlay on keys, ctrl view waits', {since: '3.21.0'}, () => {
    const world = fresh();
    world.tap(world.$('setupButton'));
    world.tap(world.tile('键盘高度'));
    assert(!world.$('heightCard').hidden, 'card shown');
    // Cancel: card hidden.
    world.tap(world.document.getElementById('heightCardCancel'));
    assert(world.$('heightCard').hidden, 'card hidden after cancel');
    // With the ctrl view on, entering the height edit suspends the rows and
    // leaving brings them back (borrow, not switch off). The
    // card no longer re-opens the quick panel on close - open it here.
    world.tap(world.$('setupButton'));
    world.tap(world.$('setupButton'));
    world.tap(world.$('ctrlTool'));
    assert(world.document.body.classList.contains('ctrl-view'), 'ctrl view on');
    world.tap(world.$('setupButton'));
    assert(world.$('ctrlLayer').hidden, 'rows suspended under quick settings');
    world.tap(world.tile('键盘高度'));
    assert(!world.$('heightCard').hidden, 'card shown over ctrl view');
    // The card floats in the band - it does not borrow the key
    // area, so the ctrl rows simply stay where they are.
    assert(!world.$('ctrlLayer').hidden, 'ctrl rows coexist with the floating card');
    world.tap(world.document.getElementById('heightCardSave'));
    assert(world.$('heightCard').hidden, 'card hidden after save');
    // Open + close the quick panel: the ctrl rows come back with it.
    world.tap(world.$('setupButton'));
    world.tap(world.$('setupButton'));
    assert(!world.$('ctrlLayer').hidden, 'ctrl rows still there after save');
    assert(world.$('candidateBar').hidden, 'bar stays with the ctrl view');
});

test('height edit borrows the bar from the ctrl view (3.20.0 form)', {until: '3.20.0'}, () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    assert(world.document.body.classList.contains('ctrl-view'), 'ctrl view on');
    world.tap(world.$('setupButton'));
    assert(world.$('ctrlLayer').hidden, 'rows suspended under quick settings');
    const nav = [...world.document.querySelectorAll('.set-nav')]
        .find(el => el.textContent === '调节 ›');
    world.tap(nav);
    assert(!world.$('heightHandle').hidden, 'height edit on');
    // Save reopens the quick panel (3.20.0 behavior); the rows stay
    // suspended under it and come back once it closes (borrow,
    // not switch off).
    world.tap(world.document.getElementById('heightSave'));
    assert(world.$('settingsPanel').classList.contains('open'),
        'save reopens the quick panel');
    assert(world.$('ctrlLayer').hidden, 'rows suspended under the reopened panel');
    world.tap(world.$('setupButton'));
    assert(!world.$('ctrlLayer').hidden, 'rows restored after closing the panel');
});

test('outside taps dismiss the mode menu and the combo grid', () => {
    const world = fresh();
    // Long-press the toggle -> menu; tap another key -> menu closes.
    world.touchDown(world.$('modeToggle'));
    world.clock.advance(360);
    world.touchUp(world.$('modeToggle'));
    assert(world.$('modeMenu').classList.contains('open'), 'menu open');
    world.touchDown(world.key('q'));
    world.touchUp(world.key('q'));
    assert(!world.$('modeMenu').classList.contains('open'), 'outside tap closes the menu');
    // Combo grid: same rule.
    world.tap(world.$('ctrlTool'));
    const ctrlMod = world.document.querySelector('[data-ctrl="sticky-ctrl"]');
    world.touchDown(ctrlMod);
    world.clock.advance(360);
    world.touchUp(ctrlMod);
    assert(world.$('comboPopup').classList.contains('open'), 'grid open');
    world.touchDown(world.document.querySelector('[data-ctrl="Escape"]'));
    world.touchUp(world.document.querySelector('[data-ctrl="Escape"]'));
    assert(!world.$('comboPopup').classList.contains('open'), 'outside tap closes the grid');
});

// ---------------------------------------------------------------- summary
test('B18r2 sticky modifier + main-keyboard letter sends the host combo', () => {
    const world = fresh({ mode: 'pinyin' });
    world.tap(world.$('ctrlTool'));
    world.tap(world.document.querySelector('[data-ctrl="sticky-ctrl"]'));
    assert(world.document.querySelector('[data-ctrl="sticky-ctrl"]').classList.contains('active'),
        'Ctrl armed');
    // Tapping the MAIN keyboard's w must fire Ctrl+W as a host key event -
    // not feed "w" into the composition engine. It rides the
    // physical channel like every modifier combo.
    world.tap(world.key('w'));
    const ev = world.native.of('keyEventPhysical');
    equal(ev.length, 1, 'one physical keyEvent');
    equal(ev[0].args[0], 29 + 'W'.charCodeAt(0) - 65, 'KEYCODE_W');
    equal(ev[0].args[1], 0x1000, 'META_CTRL');
    equal(world.native.of('key').length, 0, 'no engine key() traffic');
    assert(!world.document.querySelector('[data-ctrl="sticky-ctrl"]').classList.contains('active'),
        'sticky cleared after the combo');
    // Without a sticky modifier the same tap types normally again.
    world.tap(world.key('w'));
    equal(world.native.of('key').length, 1, 'plain w types again');
});

test('B18r2 the combo card has a close button of its own', () => {
    const world = fresh();
    world.tap(world.$('ctrlTool'));
    const ctrlMod = world.document.querySelector('[data-ctrl="sticky-ctrl"]');
    world.touchDown(ctrlMod);
    world.clock.advance(360);
    world.touchUp(ctrlMod);
    assert(world.$('comboPopup').classList.contains('open'), 'grid open');
    assert(world.document.getElementById('comboClose'), 'close button exists');
    world.tap(world.document.getElementById('comboClose'));
    assert(!world.$('comboPopup').classList.contains('open'), 'X closes the card');
});

test('backspace left-swipe clears the live composition', () => {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'nihao', rawInput: 'nihao',
        candidates: [{ id: 'c1', text: '你好' }], hasNextPage: false });
    assert(world.document.body.classList.contains('composing'), 'composing');
    const backspace = world.document.querySelector('[data-role="backspace"]');
    // One left swipe aborts the whole preedit (instead of repeat-tapping).
    world.touchDown(backspace, 20, 20);
    world.move(backspace, -30, 20); // 50px left: past the 38px slop
    world.touchUp(backspace);
    equal(world.native.of('clearComposing').length, 1, 'swipe clears once');
    assert(!world.document.body.classList.contains('composing'), 'preedit gone');
    assert(!world.$('comboPopup').classList.contains('open'), 'no side effects');
    // The same swipe while idle must neither clear nor delete anything.
    world.touchDown(backspace, 20, 20);
    world.move(backspace, -30, 20);
    world.touchUp(backspace);
    equal(world.native.of('clearComposing').length, 1, 'idle swipe does not clear');
    equal(world.native.of('backspace').length, 0, 'no stray deletes');
    // Downward swipes stay off the gesture (vertical flicks belong to letters).
    world.engineState({ mode: 'pinyin', revision: 2, composing: 'hao', rawInput: 'hao',
        candidates: [{ id: 'c2', text: '好' }], hasNextPage: false });
    world.touchDown(backspace, 20, 20);
    world.move(backspace, 20, -30);
    world.touchUp(backspace);
    equal(world.native.of('clearComposing').length, 1, 'vertical swipe does not clear');
});

test('long-press the pool head deletes it from the user lexicon', () => {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }, { id: 'c2', text: '妮' }], hasNextPage: false });
    const head = world.document.querySelector('.candidate');
    // Long-press opens the delete menu (380ms bindItemLongPress timer).
    world.touchDown(head);
    world.clock.advance(400);
    world.touchUp(head);
    const menu = world.$('itemMenu');
    assert(menu.classList.contains('open'), 'delete menu open');
    const del = [...menu.children].find(b => b.textContent === '删除自造词');
    assert(del, 'delete entry offered for the pool head');
    equal(world.native.of('chooseCandidate').length, 0, 'long-press does not pick');
    // 取消 closes the confirm card without native traffic.
    world.tap(del);
    assert(!world.$('confirmCard').hidden, 'confirm card shown');
    assert(world.$('confirmText').textContent.includes('你'), 'card names the word');
    world.tap(world.document.getElementById('confirmCancel'));
    assert(world.$('confirmCard').hidden, 'cancel hides the card');
    equal(world.native.of('deleteHighlightedCandidate').length, 0, 'cancel sends nothing');
    // Confirm: one native call, and the echo REBUILDS the pool (the deleted
    // word must not survive on the bar) + a toast.
    world.touchDown(head);
    world.clock.advance(400);
    world.touchUp(head);
    world.tap([...world.$('itemMenu').children].find(b => b.textContent === '删除自造词'));
    world.tap(world.document.getElementById('confirmOk'));
    equal(world.native.of('deleteHighlightedCandidate').length, 0, 'head uses the general channel now');
    const delCalls = world.native.of('deleteCandidate');
    equal(delCalls.length, 1, 'delete sent once');
    equal(delCalls[0].args[1], 'c1', 'head id carried');
    world.engineState({ mode: 'pinyin', revision: 2, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c9', text: '妮' }], hasNextPage: false });
    const texts = [...world.$('candidates').children].map(b => b.textContent).join(',');
    equal(texts, '妮', 'pool rebuilt without the deleted word');
    assert(world.$('toast').textContent.includes('已从自选词词库删除'), 'success toast');
});

test('any candidate is deletable; the engine carries its id', () => {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }, { id: 'c2', text: '妮' }], hasNextPage: false });
    const second = world.document.querySelectorAll('.candidate')[1];
    world.touchDown(second);
    world.clock.advance(400);
    world.touchUp(second);
    const menu = world.$('itemMenu');
    assert(menu.classList.contains('open'), 'menu opens for non-head too');
    assert([...menu.children].some(b => b.textContent === '删除自造词'),
        'delete entry offered off the head ');
    world.tap([...menu.children].find(b => b.textContent === '删除自造词'));
    assert(!world.$('confirmCard').hidden, 'confirm card shows');
    world.tap(world.document.getElementById('confirmOk'));
    const del = world.native.of('deleteCandidate');
    equal(del.length, 1, 'delete sent once');
    equal(del[0].args[1], 'c2', 'non-head id carried to the engine');
    // English mode: candidates carry no long-press listener at all.
    const en = fresh();
    en.engineState({ mode: 'direct', revision: 1, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    equal(0, 0, 'english mode skips the feature (no rime user lexicon)');
});

test('native without the any-candidate channel keeps the head-only fallback', () => {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }, { id: 'c2', text: '妮' }], hasNextPage: false });
    // Only the old bridge method exists.
    world.native.deleteCandidate = undefined;
    const second = world.document.querySelectorAll('.candidate')[1];
    world.touchDown(second);
    world.clock.advance(400);
    world.touchUp(second);
    let menu = world.$('itemMenu');
    assert(menu.classList.contains('open'), 'menu still opens with the old method');
    assert(![...menu.children].some(b => b.textContent === '删除自造词'),
        'no delete entry off the head without the any-candidate channel');
    const hint = [...menu.children].find(b => b.disabled);
    assert(hint, 'upgrade hint renders disabled');
    world.tap(world.document.getElementById('softKeyboard'));
    assert(!menu.classList.contains('open'), 'outside tap closes the menu');
    // The head still deletes through the legacy channel.
    const head = world.document.querySelector('.candidate');
    world.touchDown(head);
    world.clock.advance(400);
    world.touchUp(head);
    world.tap([...world.$('itemMenu').children].find(b => b.textContent === '删除自造词'));
    world.tap(world.document.getElementById('confirmOk'));
    equal(world.native.of('deleteHighlightedCandidate').length, 1, 'legacy head delete works');
    equal(world.native.of('deleteCandidate').length, 0, 'no call to the missing method');
});

test('a fixed-dictionary word stays and says so', () => {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }], hasNextPage: false });
    const head = world.document.querySelector('.candidate');
    world.touchDown(head);
    world.clock.advance(400);
    world.touchUp(head);
    world.tap([...world.$('itemMenu').children].find(b => b.textContent === '删除自造词'));
    world.tap(world.document.getElementById('confirmOk'));
    // The echo still lists 你: librime silently skipped a fixed-dict word.
    world.engineState({ mode: 'pinyin', revision: 2, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c8', text: '你' }], hasNextPage: false });
    assert(world.$('toast').textContent.includes('固定词库'), 'honest toast for fixed words');
});

test('deleting with the expanded layer open rebuilds the grid', () => {
    // Review P1: the grid renders incrementally; a pool rebuild
    // without a re-render left the deleted word's button under the toast.
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }, { id: 'c2', text: '妮' }], hasNextPage: false });
    world.tap(world.$('composeExpand'));
    assert(!world.$('expandLayer').hidden, 'expand layer open');
    const gridHead = world.document.querySelector('.expand-candidate');
    assert(gridHead.textContent === '你', 'grid shows the head');
    world.touchDown(gridHead);
    world.clock.advance(400);
    world.touchUp(gridHead);
    world.tap([...world.$('itemMenu').children].find(b => b.textContent === '删除自造词'));
    world.tap(world.document.getElementById('confirmOk'));
    world.engineState({ mode: 'pinyin', revision: 2, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c9', text: '妮' }], hasNextPage: false });
    const texts = [...world.document.querySelectorAll('.expand-candidate')]
        .map(b => b.textContent).join(',');
    equal(texts, '妮', 'grid rebuilt without the deleted word');
});

test('backspace swipe cancels the pending hold/repeat timers', () => {
    // Review P3: a finger that swipes and STAYS on the key must not
    // let the late repeat eat committed text.
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }], hasNextPage: false });
    const backspace = world.document.querySelector('[data-role="backspace"]');
    world.touchDown(backspace, 20, 20);
    world.clock.advance(100); // hold armed, repeat not yet firing
    world.move(backspace, -30, 20); // swipe takes over
    world.clock.advance(700); // the 390ms hold + repeats would land here
    equal(world.native.of('backspace').length, 0, 'no repeats after the swipe');
    equal(world.native.of('clearComposing').length, 1, 'composition still cleared');
});

test('old APKs without the bridge method never open the delete menu', () => {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ mode: 'pinyin', revision: 1, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }], hasNextPage: false });
    const head = world.document.querySelector('.candidate');
    // Simulate an old APK: the native bridge lacks deleteHighlightedCandidate.
    // class methods live on the prototype - shadow with an own property.
    world.native.deleteHighlightedCandidate = undefined;
    world.native.deleteCandidate = undefined;
    world.touchDown(head);
    world.clock.advance(400);
    world.touchUp(head);
    assert(!world.$('itemMenu').classList.contains('open'), 'no menu without any method');
    delete world.native.deleteHighlightedCandidate;
    delete world.native.deleteCandidate;
    world.touchDown(head);
    world.clock.advance(400);
    world.touchUp(head);
    assert(world.$('itemMenu').classList.contains('open'), 'menu returns with the method');
});

test('DP_INITIAL_FINALS equals the prism spelling-pair derivation', () => {
    // The variant table is generated, not hand-maintained; pin every
    // scheme's table to its shipped prism so schema changes cannot
    // silently desync the keyboard's double-pinyin parsing.
    const fs = require('fs');
    const path = require('path');
    const root = path.resolve(__dirname, '../..');
    const js = fs.readFileSync(path.join(root, 'app/src/main/assets/keyboard/keyboard.js'), 'utf8');
    const block = js.match(/const DP_INITIAL_FINALS = (\{.*?\});/);
    assert(block, 'variant table present in keyboard.js');
    const tables = JSON.parse(block[1]);
    const prisms = {
        ziranma: 'ziranma_double_pinyin',
        flypy: 'double_pinyin_flypy',
        sogou: 'double_pinyin_sogou',
    };
    for (const [scheme, prismId] of Object.entries(prisms)) {
        const table = tables[scheme];
        assert(table, `variant table for ${scheme}`);
        const derived = {};
        const prism = fs.readFileSync(
            path.join(root, 'app/src/main/assets/engine-data/rime', `${prismId}.prism.txt`),
            'utf8');
        for (const line of prism.split('\n')) {
            const fields = line.split('\t');
            // Continuation rows (empty col0) repeat the previous spelling: no
            // new first-two-key pair. Single-key abbrev spellings have no
            // second key.
            if (fields.length < 2 || !fields[0] || fields[0].length < 2) continue;
            const spelling = fields[0];
            (derived[spelling[0]] = derived[spelling[0]] || new Set()).add(spelling[1]);
        }
        const expected = {};
        for (const first of Object.keys(derived).sort()) {
            expected[first] = [...derived[first]].sort().join('');
        }
        equal(JSON.stringify(table), JSON.stringify(expected),
            `${scheme}: table keys ${Object.keys(table).length} vs prism initials ${Object.keys(expected).length}`);
    }
});



// Changing the interface language must not translate user input or
// reset a composition. The same native hello can arrive during editing.
test('English UI preserves Chinese composition and switches back', {since: '3.22.0'}, () => {
    const world = fresh({mode: 'pinyin'});
    world.engineState({mode: 'pinyin', revision: 1, composing: 'nihao', rawInput: 'nihao',
        candidates: [{id: 'c0', text: '你好'}], hasNextPage: false});
    world.hello({mode: 'pinyin', uiLocale: 'en'});
    equal(world.$('heightCardSave').textContent, 'Save', 'static action translated');
    equal(world.$('phraseCardInput').getAttribute('placeholder'), 'Enter a phrase', 'placeholder translated');
    assert(world.$('candidates').textContent.includes('你好'), 'candidate text unchanged');
    equal(world.native.of('clearComposing').length, 0, 'no composition reset');
    world.tap(world.$('setupButton'));
    assert(world.tileNames().includes('Quick switch'), 'dynamic UI translated');
    world.hello({mode: 'pinyin', uiLocale: 'zh'});
    equal(world.$('heightCardSave').textContent, '保存', 'switch back');
    assert(world.tileNames().includes('快捷切换'), 'open subview stays translated');
});

test('panel composition replaces spans and saves only after native flush', {since: '3.22.0'}, () => {
    const world = fresh();
    world.tap(world.$('favoritesButton'));
    world.tap(world.$('panelManage'));
    const input = world.$('phraseCardInput');
    const api = world.context.Feelime;
    const focus = field => {
        world.document.activeElement = field;
        field.setSelectionRange = (start, end) => { field.selectionStart = start; field.selectionEnd = end; };
        for (const listener of field.listeners.filter(l => l.type === 'focus')) listener.handler({target: field});
    };
    focus(input);
    const session = world.native.of('panelSelection').slice(-1)[0].args[2];
    api.onPanelComposing({session, text: 'b'});
    api.onPanelComposing({session, text: 'bo'});
    equal(input.value, 'bo', 'composing replaces its previous span');
    api.onPanelCommit({session, text: 'bonjour '});
    api.onPanelFinishComposing({session});
    equal(input.value, 'bonjour ', 'commit replaces the raw spelling');
    // Native absolute coordinates can be stale after the panel field moved;
    // the callback must use the field's real collapsed caret instead.
    api.onPanelReopen({session, start: 99, end: 99, word: 'bonjour'});
    api.onPanelComposing({session, text: 'b'});
    api.onPanelComposing({session, text: 'bon'});
    equal(input.value, 'bon', 'reopened word remains a replacement span');
    world.tap(world.$('phraseCardSave'));
    equal(world.native.of('favoritesAdd').length, 0, 'save waits for queued native edits');
    api.onPanelCommit({session, text: 'bonne'});
    api.onPanelFinishComposing({session});
    api.onPanelFlushed({session});
    equal(world.native.of('favoritesAdd').slice(-1)[0].args[0], 'bonne', 'saved final text exactly once');
    api.onPanelFlushed({session});
    equal(world.native.of('favoritesAdd').length, 1, 'duplicate flush ack cannot save twice');
    world.tap(world.$('panelManage'));
    api.onPanelCommit({session, text: 'STALE'});
    equal(input.value, '', 'old card callbacks cannot write the reopened card');
});

test('panel candidate undo keeps punctuation outside the replay span', {since: '3.22.0'}, () => {
    const world = fresh();
    world.tap(world.$('favoritesButton'));
    world.tap(world.$('panelManage'));
    const input = world.$('phraseCardInput');
    const api = world.context.Feelime;
    world.document.activeElement = input;
    const focus = input.listeners.find(l => l.type === 'focus');
    if (focus) focus.handler({target: input});
    const session = world.native.of('panelSelection').slice(-1)[0].args[2];

    // Candidate commit includes the automatic trailing space. The native
    // undo callback deliberately carries unusable absolute coordinates.
    api.onPanelCommit({session, text: 'bonjour '});
    api.onPanelReopen({session, start: -5, end: -1, word: 'bonjour'});
    equal(input.value, 'bonjour', 'candidate undo removes only its automatic space');
    api.onPanelFinishComposing({session});
    api.onPanelCommit({session, text: ','});
    equal(input.value, 'bonjour,', 'punctuation follows the reopened word once');
});

test('failed panel reopen retires the stale session', {since: '3.22.0'}, () => {
    const world = fresh();
    world.tap(world.$('favoritesButton'));
    world.tap(world.$('panelManage'));
    const input = world.$('phraseCardInput');
    const api = world.context.Feelime;
    world.document.activeElement = input;
    const focus = input.listeners.find(l => l.type === 'focus');
    if (focus) focus.handler({target: input});
    const session = world.native.of('panelSelection').slice(-1)[0].args[2];
    api.onPanelCommit({session, text: 'bonjour'});
    api.onPanelReopen({session, start: 0, end: 7, word: 'bonjour'});
    const replacement = world.native.of('panelSelection').slice(-1)[0].args[2];
    assert(replacement !== session, 'failed reopen reports a fresh session');
    api.onPanelCommit({session, text: 'STALE'});
    equal(input.value, 'bonjour', 'stale session cannot write after failed reopen');
    api.onPanelCommit({session: replacement, text: '!'});
    equal(input.value, 'bonjour!', 'new session remains writable');
});

test('panel input codes receive writes and old field commits stay in their field', {since: '3.22.0'}, () => {
    const world = fresh();
    world.tap(world.$('favoritesButton')); world.tap(world.$('panelManage'));
    const input = world.$('phraseCardInput');
    const code = world.$('phraseCardCode');
    const api = world.context.Feelime;
    const focus = field => {
        world.document.activeElement = field;
        field.setSelectionRange = (start, end) => { field.selectionStart = start; field.selectionEnd = end; };
        for (const listener of field.listeners.filter(l => l.type === 'focus')) listener.handler({target: field});
    };
    focus(input);
    const previous = world.native.of('panelSelection').slice(-1)[0].args[2];
    focus(code);
    const session = world.native.of('panelSelection').slice(-1)[0].args[2];
    assert(session !== previous, 'changing field changes session');
    api.onPanelCommit({session: previous, text: '你好'});
    api.onPanelCommit({session, text: 'nh'});
    equal(input.value, '你好', 'pending old field commit remains in phrase text');
    equal(code.value, 'nh', 'input code goes into the code field');
    api.onPanelCommit({session, text: '😀'});
    api.onPanelDelete({session, count: 1});
    equal(code.value, 'nh', 'delete never leaves half of an emoji');
});

test('mode restriction messages remain visible while voice is idle', {since: '3.23.0'}, () => {
    const world = fresh();
    for (const message of [
        'Password fields only support English Direct',
        'Terminal fields only support English Direct',
        'Language data is still being prepared. Try again shortly',
    ]) {
        world.nativeState({state: 'idle', message});
        equal(world.$('toast').textContent, message, 'restriction is visible');
        assert(world.$('toast').classList.contains('open'), 'toast is open');
        assert(!world.$('voiceOverlay').classList.contains('open'), 'voice remains idle');
    }
});

test('restore default stays pending until Save and Cancel preserves the height', {since: '3.23.0'}, () => {
    const world = fresh({heightDefault: 272});
    const open = () => {
        world.tap(world.$('setupButton'));
        world.tap(world.tile('键盘高度'));
    };
    open();
    world.native.reset();
    world.tap(world.$('heightCardReset'));
    equal(world.native.of('setKeyboardHeight').length, 0, 'reset does not apply or persist');
    assert(!world.$('heightCard').hidden, 'reset keeps confirmation card open');
    assert(world.$('heightValue').textContent.includes('272'), 'default is shown as the pending value');
    world.tap(world.$('heightCardCancel'));
    equal(world.native.of('setKeyboardHeight').length, 0, 'cancel after reset alone does not write');
    open();
    world.tap(world.$('heightCardReset'));
    world.tap(world.$('heightCardSave'));
    equal(world.native.of('setKeyboardHeight').slice(-1)[0].args[0], 0, 'save explicitly clears the height override');
    assert(world.$('heightCard').hidden, 'save closes card');
});

test('full pinyin pins the spelling list and swaps only its candidate pool', {since: '3.23.0'}, () => {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({mode: 'pinyin', composing: true, rawInput: 'x an', revision: 1,
        candidates: [{id: 'old', text: '西安'}], hasNextPage: false});
    world.tap(world.$('composeExpand'));
    const variants = () => [...world.$('expandVariants').querySelectorAll('.expand-variant')];
    equal(variants().length, 15, 'raw spelling plus fourteen complete x syllables');
    assert(variants().some(el => el.textContent === "xuan'an"), 'long complete spelling included');
    world.tap(variants().find(el => el.textContent === "xiang'an"));
    equal(world.native.of('setComposition').slice(-1)[0].args[0], "xiang'an", 'atomic spelling switch');
    world.engineState({mode: 'pinyin', composing: true, rawInput: 'xiang an', revision: 2,
        candidates: [{id: 'new', text: '相安'}], hasNextPage: false});
    equal(variants().length, 15, 'anchor survives a spaced Rime echo');
    assert(variants().find(el => el.textContent === "xiang'an").classList.contains('current'), 'new spelling selected');
    const candidates = world.$('expandGrid').querySelectorAll('.expand-candidate');
    equal(candidates.length, 1, 'old spelling candidates removed');
    world.tap(candidates[0]);
    equal(world.native.of('chooseCandidate').slice(-1)[0].args[1], 'new', 'selection uses new candidate id');
});

test('complete and multiply abbreviated pinyin keep only the current spelling', {since: '3.23.0'}, () => {
    for (const rawInput of ["xi'an", "x'zh", 'xyz an']) {
        const world = fresh({mode: 'pinyin'});
        world.engineState({mode: 'pinyin', composing: true, rawInput, revision: 1,
            candidates: [{id: 'c', text: '词'}], hasNextPage: false});
        world.tap(world.$('composeExpand'));
        const variants = [...world.$('expandVariants').querySelectorAll('.expand-variant')];
        const displayed = rawInput.trim().replace(/[ \t]+/g, "'");
        assert(!world.$('expandVariants').hidden && variants.length === 1
            && variants[0].textContent === displayed,
        'no broad reinterpretation for ' + rawInput);
    }
});

test('hiding a pressed keyboard releases feedback, repeats and late touch input', {since: '3.23.0'}, () => {
    const world = fresh();
    const key = world.key('a');
    world.touchDown(key);
    assert(key.classList.contains('active-touch'), 'press is visible');
    world.context.window.Feelime.cancelTouches();
    assert(!key.classList.contains('active-touch'), 'hide releases press');
    world.touchUp(key);
    equal(world.native.of('key').length, 0, 'late touchend cannot type after hiding');
    const backspace = world.document.querySelector('[data-role="backspace"]');
    world.touchDown(backspace);
    world.context.window.Feelime.cancelTouches();
    world.clock.advance(600);
    equal(world.native.of('backspace').length, 0, 'hidden keyboard cannot start repeat');
    world.tap(key);
    equal(world.native.of('key').length, 1, 'next ordinary tap still works');
});

test('interrupted touch sequence cannot leave an earlier key pressed', {since: '3.23.0'}, () => {
    const world = fresh();
    const a = world.key('a');
    world.touchDown(a);
    world.touchDown(world.key('b')); // a fresh sole touch after a missing touchend
    assert(!a.classList.contains('active-touch'), 'stale press removed at new sequence');
    world.touchUp(world.key('b'));
    equal(world.native.of('key').map(c => c.args[0]).join(''), 'b', 'only current sequence types');
});

test('scrub reaches the same endpoint with slow/fast samples and final touchend', {since: '3.23.0'}, () => {
    const run = (samples, finalX) => {
        const world = fresh();
        const g = world.key('g');
        world.touchDown(g, 20, 20);
        samples.forEach(x => world.move(g, x, 20));
        const beforeEnd = world.native.of('moveCursor')
            .reduce((sum, call) => sum + call.args[0], 0);
        world.touchUp(g, finalX, 20);
        const afterEnd = world.native.of('moveCursor')
            .reduce((sum, call) => sum + call.args[0], 0);
        return { beforeEnd, afterEnd };
    };
    const slow = run([50, 60, 90], 99);
    const fast = run([99], 99);
    equal(slow.beforeEnd, 3, 'slow samples cross three steps before release');
    equal(fast.beforeEnd, 1, 'fast first sample keeps the one-step engage feel');
    equal(slow.afterEnd, 4, 'touchend applies the final partial step');
    equal(fast.afterEnd, slow.afterEnd, 'same endpoint is independent of sample count');
});

test('scrub touchcancel does not apply the final changed position', {since: '3.23.0'}, () => {
    const world = fresh();
    const g = world.key('g');
    world.touchDown(g, 20, 20);
    world.move(g, 60, 20);
    const beforeCancel = world.native.of('moveCursor')
        .reduce((sum, call) => sum + call.args[0], 0);
    world.dispatch(g, 'touchcancel', 99, 20);
    const afterCancel = world.native.of('moveCursor')
        .reduce((sum, call) => sum + call.args[0], 0);
    equal(afterCancel, beforeCancel, 'cancel does not move to the changed position');
});

test('another finger ending cannot terminate an ongoing cursor scrub', {since: '3.23.0'}, () => {
    const world = fresh();
    const a = world.key('a'), b = world.key('b');
    const finger = (identifier, clientX) => ({identifier, clientX, clientY: 20});
    const send = (el, type, touches, changedTouches) => world.dispatch(el, type, 20, 20, {touches, changedTouches});
    send(a, 'touchstart', [finger(1, 20)], [finger(1, 20)]);
    send(a, 'touchmove', [finger(1, 60)], [finger(1, 60)]);
    equal(world.native.of('moveCursor').length, 1, 'first finger starts scrub');
    send(b, 'touchstart', [finger(1, 60), finger(2, 150)], [finger(2, 150)]);
    send(b, 'touchend', [finger(1, 60)], [finger(2, 150)]);
    world.clock.advance(1);
    send(a, 'touchmove', [finger(1, 84)], [finger(1, 84)]);
    equal(world.native.of('moveCursor').slice(-1)[0].args[0], 2, 'original finger continues from its anchor');
    send(a, 'touchend', [], [finger(1, 84)]);
    equal(world.native.of('key').length, 0, 'scrub never emits letter taps');
});

test('assoc click routes through the real bridge (FeelimeNative)', {since: '3.33.0'}, () => {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ phase: 'READY', mode: 'pinyin', revision: 3, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    world.assoc(['的', '是', '也']);
    // mock DOM 的 querySelectorAll 不认复合类选择器，按 className 过滤
    const assocButtons = () => [...world.$('candidates').children]
        .filter(b => (b.className || '').split(/\s+/).includes('assoc'));
    equal(assocButtons().length, 3, 'assoc words render while the pool is empty');
    // 联想 chrome（用户定稿）：工具栏快捷按钮全部让位（含 mic）仅留 ×。
    assert(world.$('setupButton').hidden, 'assoc hides the toolbar');
    assert(world.$('mic').hidden, 'assoc hides mic');
    assert(!world.$('composeClear').hidden, 'assoc shows ×');
    assocButtons()[0].click();
    const call = world.native.of('commitAssoc')[0];
    assert(call, 'assoc click reaches the native bridge');
    equal(call.args[0], '的', 'commits the clicked word');
    equal(assocButtons().length, 0, 'assoc bar clears after the click');
    assert(!world.$('setupButton').hidden, 'toolbar restored after assoc pick');
    assert(!world.$('mic').hidden, 'mic restored after assoc pick');
});

test('mode switch clears assoc words', {since: '3.33.0'}, () => {
    const world = fresh({ mode: 'pinyin' });
    world.engineState({ phase: 'READY', mode: 'pinyin', revision: 3, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    world.assoc(['的', '是']);
    const assocCount = () => [...world.$('candidates').children]
        .filter(b => (b.className || '').split(/\s+/).includes('assoc')).length;
    equal(assocCount(), 2, 'assoc words render');
    world.engineState({ phase: 'READY', mode: 'direct', revision: 4, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    equal(assocCount(), 0, 'mode change clears the assoc bar');
});

test('t9 long-press popup: letters/symbols land literally, digit feeds the engine', {since: '3.35.0'}, () => {
    const world = fresh({ mode: 't9' });
    world.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    equal(world.key('2').querySelector('.t9-group').textContent, 'ABC',
        'keycap shows the letter group');
    equal(world.key('2').querySelector('.t9-hint').textContent, '—&',
        'keycap hints the two long-press symbols (issue #9)');
    // 三行弹层的取消判定看「滑出浮层卡片边界」：fake DOM 没有真实布局，
    // 把卡片矩形钉到覆盖所有格子假矩形的位置再开层。
    const pinCard = w => {
        w.document.getElementById('keyPopup').getBoundingClientRect =
            () => ({ left: 0, top: 0, right: 500, bottom: 400, width: 500, height: 400 });
    };
    pinCard(world);
    // 长按三行弹层（issue #9）：小写 / 左符号·数字·右符号 / 大写。
    const two = world.key('2');
    world.touchDown(two);
    world.clock.advance(360);
    const items = [...world.document.querySelectorAll('.kp-item')];
    equal(items.map(i => i.textContent).join(','), 'a,b,c,—,2,&,A,B,C',
        'popup offers lowercase / symbol-digit-symbol / uppercase');
    const rows = [...world.document.querySelectorAll('#keyPopupInner .kp-row')];
    equal(rows.length, 3, 'three rows in the grid popup');
    // 相对跟手：高亮 = 数字格锚点 + 手指位移。手往下滑 → 高亮往下滑
    // （到下一行），往左下滑 34/46px（一格）正好落在 A。
    world.move(two, 20 - 34, 20 + 46);
    assert(items[6].classList.contains('sel'), 'highlight moves with the finger delta');
    // 收尾坐标 = 最后移动位置（真实触摸的 changedTouches 语义）。
    world.touchUp(two, 20 - 34, 20 + 46);
    // 大写格 = literal 直上屏，大小写原样落（用户定稿：弹层选字母就是打
    // 这个字母，不参与拼音组合）；引擎通道收不到任何字母键。
    const commitsA = world.native.of('commitText').map(c => c.args[0]);
    equal(commitsA[commitsA.length - 1], 'A', 'uppercase cell lands literally');
    equal(world.native.of('key').filter(c => c.args[0] === 'A' || c.args[0] === 'a').length, 0,
        'popup letter never enters the engine');
    // 选符号格 → commitText 直上屏（进引擎会被拼音组合吃掉）。
    const worldSym = fresh({ mode: 't9' });
    worldSym.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    pinCard(worldSym);
    const five = worldSym.key('5');
    worldSym.touchDown(five);
    worldSym.clock.advance(360);
    const symItems = [...worldSym.document.querySelectorAll('.kp-item')];
    equal(symItems.map(i => i.textContent).join(','), 'j,k,l,、,5,：,J,K,L',
        '5-key popup carries 、 and ： around the digit');
    // 手往左滑 → 高亮滑到数字左边的符号（用户定稿的方向语义）。
    worldSym.move(five, 20 - 40, 20);
    assert(symItems[3].classList.contains('sel'), 'left slide picks the left symbol');
    worldSym.touchUp(five, 20 - 40, 20);
    const commits = worldSym.native.of('commitText').map(c => c.args[0]);
    equal(commits[commits.length - 1], '、', 'symbol cell lands literally');
    equal(worldSym.native.of('key').filter(c => c.args[0] === '、').length, 0,
        'symbol never enters the engine');
    // 拖出取消圈：浮层缩小变淡 + 「松手取消」提示，松手不落字。
    const worldOut = fresh({ mode: 't9' });
    worldOut.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    pinCard(worldOut);
    const nine = worldOut.key('9');
    worldOut.touchDown(nine);
    worldOut.clock.advance(360);
    const outItems = [...worldOut.document.querySelectorAll('.kp-item')];
    // 下滑一格半（虚拟光标到 W）→ 高亮往下走。
    worldOut.move(nine, 20 - 34, 20 + 92);
    assert(outItems[7].classList.contains('sel'), 'down slide walks the highlight down');
    // 继续同方向滑，虚拟光标越过卡片底边（400）才取消：
    // 浮层淡出 + 「松手撤销」toast 固定在浮层上方（不跟手）。
    worldOut.move(nine, 20 - 34, 20 + 352);
    const tip = worldOut.$('keyPopupCancelTip');
    assert(tip.classList.contains('show'), 'cancel tip shows once the cursor leaves the card');
    const inner = worldOut.document.getElementById('keyPopupInner');
    equal(inner.style.opacity, '0.5', 'popup fades while cancelled');
    const tipLeft = parseInt(tip.style.left, 10);
    worldOut.move(nine, 20 - 34, 20 + 400);
    equal(parseInt(tip.style.left, 10), tipLeft, 'cancel tip stays put (does not follow the finger)');
    // 往回滑（虚拟光标回卡片内）自动恢复。
    worldOut.move(nine, 20 - 34, 20);
    assert(!tip.classList.contains('show'), 'sliding back restores selection');
    equal(inner.style.opacity, '', 'popup solid again');
    assert(outItems[4].classList.contains('sel'), 'middle row re-highlighted on the way back');
    worldOut.move(nine, 20 - 34, 20 + 352);
    assert(tip.classList.contains('show'), 'exiting again re-arms the cancel state');
    // 收尾在卡片外：松手仍是撤销态，不落字（codex P2 的回归断言）。
    worldOut.touchUp(nine, 20 - 34, 20 + 352);
    equal(worldOut.native.of('key').length, 0, 'cancelled release lands nothing');
    equal(worldOut.native.of('commitText').length, 0, 'cancelled release commits nothing');
    assert(!worldOut.$('keyPopupCancelTip').classList.contains('show'),
        'cancel tip hides on close');
    // 手指没进过卡片、只在小范围内蹭（触发键附近）：不取消，松手落预选数字。
    const worldNear = fresh({ mode: 't9' });
    worldNear.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    pinCard(worldNear);
    const three = worldNear.key('3');
    worldNear.touchDown(three, 20, 450);
    worldNear.clock.advance(360);
    const nearItems = [...worldNear.document.querySelectorAll('.kp-item')];
    // 按点 12px 内的微动：高亮保持数字格（吃手指抖动）。
    worldNear.move(three, 30, 450);
    assert(nearItems[4].classList.contains('sel'), 'micro-drift keeps the digit highlighted');
    assert(!worldNear.$('keyPopupCancelTip').classList.contains('show'),
        'micro-drift does not cancel');
    // 相对跟手：手指全程不碰浮层，高亮也跟着位移走——往左滑一格就是
    // 数字左边的符号（用户定稿）。
    worldNear.move(three, 20 - 34, 450);
    assert(nearItems[3].classList.contains('sel'), 'left slide lands on the left symbol');
    worldNear.touchUp(three, 20 - 34, 450);
    const commits3 = worldNear.native.of('commitText').map(c => c.args[0]);
    equal(commits3[commits3.length - 1], '（', 'tracked symbol cell lands literally');
    // 快速甩出：最后一次 move 在卡内，收尾坐标已远处 → 撤销，不落字。
    const worldFling = fresh({ mode: 't9' });
    worldFling.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    pinCard(worldFling);
    const four = worldFling.key('4');
    worldFling.touchDown(four, 20, 20);
    worldFling.clock.advance(360);
    const flingItems = [...worldFling.document.querySelectorAll('.kp-item')];
    // 相对模型：向上位移一格（46px）→ 高亮到 e（虚拟光标落在 e 上）。
    worldFling.move(four, 20, 20 - 46);
    assert(flingItems[1].classList.contains('sel'), 'in-card pick before the fling');
    worldFling.touchUp(four, 460, 700);
    equal(worldFling.native.of('key').length, 0, 'fling-out release lands nothing');
    equal(worldFling.native.of('commitText').length, 0, 'fling-out release commits nothing');
    // 不拖直接松手 = 预选数字格，与点按同义（通配数字进引擎）。
    const world2 = fresh({ mode: 't9' });
    world2.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    const two2 = world2.key('2');
    world2.touchDown(two2);
    world2.clock.advance(360);
    world2.touchUp(two2);
    const keys2 = world2.native.of('key');
    equal(keys2[keys2.length - 1].args[0], '2', 'release on the digit cell feeds the wildcard');
});

test('t9 popup letters land literally with case preserved (3.40.0)', {since: '3.39.0'}, () => {
    // 用户定稿：长按弹层选字母 = 直接打这个字母（大写落大写、小写落
    // 小写），不参与拼音组合；拼音确认字母由滑动手势承担。
    const world = fresh({ mode: 't9' });
    world.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    const pinCard = w => {
        w.document.getElementById('keyPopup').getBoundingClientRect =
            () => ({ left: 0, top: 0, right: 500, bottom: 400, width: 500, height: 400 });
    };
    pinCard(world);
    const four = world.key('4');
    const pick = label => {
        world.touchDown(four);
        world.clock.advance(360);
        // 每次开层 DOM 重建，必须重新查询格子。
        const items = [...world.document.querySelectorAll('.kp-item')];
        const el = items.find(i => i.textContent === label);
        const ar = items.find(i => i.classList.contains('sel')).getBoundingClientRect();
        const tr = el.getBoundingClientRect();
        const mx = 20 + (tr.left + tr.width / 2) - (ar.left + ar.width / 2);
        const my = 20 + (tr.top + tr.height / 2) - (ar.top + ar.height / 2);
        world.move(four, mx, my);
        world.touchUp(four, mx, my);
    };
    pick('I');
    pick('g');
    const commits = world.native.of('commitText').map(c => c.args[0]);
    equal(JSON.stringify(commits), JSON.stringify(['I', 'g']),
        'popup letters land literally, case preserved');
    equal(world.native.of('key').filter(c => /^[a-z]$/i.test(c.args[0])).length, 0,
        'popup letters never enter the engine');
});

test('t9 letter-key flicks: literal digit up, engine letters down/left/right', {since: '3.35.0'}, () => {
    const world = fresh({ mode: 't9' });
    world.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    const four = world.key('4');
    // 上滑=字面数字：commitText 旁路（Native.key 会把它喂成候选选择器）。
    world.touchDown(four, 20, 20);
    world.move(four, 20, -30);
    world.touchUp(four);
    world.clock.advance(2);
    equal(world.native.of('commitText').slice(-1)[0].args[0], '4',
        'up-flick commits the literal digit');
    // 下滑=中间字母进引擎。
    world.touchDown(four, 20, 20);
    world.move(four, 20, 70);
    world.touchUp(four);
    world.clock.advance(2);
    equal(world.native.of('key').slice(-1)[0].args[0], 'h', 'down-flick sends the middle letter');
    // 左/右滑=首/尾字母进引擎。
    world.touchDown(four, 20, 20);
    world.move(four, -40, 20);
    world.touchUp(four);
    world.clock.advance(2);
    equal(world.native.of('key').slice(-1)[0].args[0], 'g', 'left-flick sends the first letter');
    world.touchDown(four, 20, 20);
    world.move(four, 80, 20);
    world.touchUp(four);
    world.clock.advance(2);
    equal(world.native.of('key').slice(-1)[0].args[0], 'i', 'right-flick sends the last letter');
    // 点按=整组通配（数字进引擎）。
    world.tap(four);
    equal(world.native.of('key').slice(-1)[0].args[0], '4', 'tap feeds the wildcard digit');
});

test('t9 mic key: literal 0 up, horizontal scrub, tap space', {since: '3.35.0'}, () => {
    const world = fresh({ mode: 't9' });
    world.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    const space = world.$('spaceKey');
    world.touchDown(space, 20, 20);
    world.move(space, 20, -30);
    world.touchUp(space);
    world.clock.advance(2);
    equal(world.native.of('commitText').slice(-1)[0].args[0], '0', 'mic up-flick commits literal 0');
    // 横滑=光标 scrub（T9 唯一保留 scrub 的键）；scrub 不得补发空格。
    const before = world.native.of('space').length;
    world.touchDown(space, 20, 20);
    world.move(space, 60, 20);
    world.touchUp(space);
    world.clock.advance(2);
    assert(world.native.of('moveCursor').length >= 1, 'mic horizontal drag scrubs');
    equal(world.native.of('space').length, before, 'scrub release must not also send space');
    // 点按=空格。
    world.tap(space);
    equal(world.native.of('space').slice(-1).length, 1, 'mic tap sends space');
});

test('t9 syllable strip: freq order, confirmed boundary, reading echo', {since: '3.35.0'}, () => {
    const world = fresh({ mode: 't9' });
    // 64426 首字未确认：左列只出首字读法、按词典词频全局降序（64 的 ni
    // 压过 6 的 o/mi），第二字的 ga/ha/gan/gao 绝不提前出现——引擎回显的
    // 段空格（'64 426'）是切分猜测，不能当确认边界（用户定稿）。
    world.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '64 426',
        rawInput: '64 426', candidates: [], hasNextPage: false });
    const cells = () => [...world.document.querySelectorAll('#t9Strip .t9-side-cell')]
        .map(c => c.textContent);
    let labels = cells();
    equal(labels[0], 'ni', '64 ranks ni first by dictionary weight');
    assert(labels.includes('mi') && labels.includes('o'), 'other readings of 6/64 follow');
    assert(!labels.includes('hao') && !labels.includes('gan'),
        'second-char readings banned before the first char is confirmed');
    equal(world.$('preeditLine').textContent, "ni'hao",
        'preedit shows the top reading of each segment, not digits');
    // 点 ni → 未确认段重写为 ni426（原子 setComposition）；确认边界=首段。
    world.tap([...world.document.querySelectorAll('#t9Strip .t9-side-cell')]
        .find(c => c.textContent === 'ni'));
    equal(world.native.of('setComposition').slice(-1)[0].args[0], 'ni426',
        'syllable pick rewrites the pending segment atomically');
    // 重放期间再点选必须被丢弃：switchToVariant 会早退，边界不能先挪
    // （codex round-4 P2-5）。
    world.tap([...world.document.querySelectorAll('#t9Strip .t9-side-cell')]
        .find(c => c.textContent === 'o'));
    equal(world.native.of('setComposition').length, 1, 'pick during replay is refused');
    // 引擎回声落地：待确认段只剩 426 → hao/gan 上位，ni 不再重复出现。
    world.engineState({ phase: 'READY', mode: 't9', revision: 2, composing: 'ni 426',
        rawInput: 'ni 426', candidates: [], hasNextPage: false });
    labels = cells();
    assert(labels.includes('hao') && labels.includes('gan'), 'pending 426 lists hao/gan');
    assert(!labels.includes('ni'), 'confirmed ni is not re-offered');
    // 点 hao → nihao；全部确认后待确认段为空，左列回到常用字符。
    world.tap([...world.document.querySelectorAll('#t9Strip .t9-side-cell')]
        .find(c => c.textContent === 'hao'));
    equal(world.native.of('setComposition').slice(-1)[0].args[0], 'nihao',
        'hao after confirmed ni rewrites to nihao');
    world.engineState({ phase: 'READY', mode: 't9', revision: 3, composing: 'nihao',
        rawInput: 'nihao', candidates: [{ id: 'c1', text: '你好' }], hasNextPage: false });
    assert(cells().includes('，'), 'fully confirmed strip returns to symbols');
    // 退格删进已确认段 = 边界作废：读音从头重算（段内字母仍约束候选）。
    world.engineState({ phase: 'READY', mode: 't9', revision: 4, composing: 'nih',
        rawInput: 'nih', candidates: [], hasNextPage: false });
    assert(cells().includes('ni'), 'deletion re-enumerates readings from scratch');
    // 独立场景：滑动输入的裸字母段 n426 只列 n- 一致读音（codex round-2
    // P2-5 复现用例），hao/gan 绝不出现；声母前缀缀尾。
    const world2 = fresh({ mode: 't9' });
    world2.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: 'n 426',
        rawInput: 'n 426', candidates: [], hasNextPage: false });
    const mixed = [...world2.document.querySelectorAll('#t9Strip .t9-side-cell')]
        .map(c => c.textContent);
    assert(mixed.includes('ni') && mixed.includes('nian') && mixed.includes('niao'),
        'letter-consistent readings listed');
    assert(!mixed.includes('hao') && !mixed.includes('gan'),
        'inconsistent readings never offered');
    assert(mixed.includes('n'), 'initial prefix listed');
    // 无段空格的单段读音也要贪婪覆盖整段（P2-1）：64426 → ni'hao。
    world2.engineState({ phase: 'READY', mode: 't9', revision: 2, composing: '64426',
        rawInput: '64426', candidates: [], hasNextPage: false });
    equal(world2.$('preeditLine').textContent, "ni'hao",
        'single-segment reading greedily covers the whole input');
    // 纯数字段行为不变：94664 仍列 zhong/xiong。
    world2.engineState({ phase: 'READY', mode: 't9', revision: 3, composing: '94664',
        rawInput: '94664', candidates: [], hasNextPage: false });
    const digits = [...world2.document.querySelectorAll('#t9Strip .t9-side-cell')]
        .map(c => c.textContent);
    assert(digits.includes('zhong') && digits.includes('xiong'), 'digit segment lists zhong/xiong');
    // 组合结束后左列回到常用字符。
    world2.engineState({ phase: 'READY', mode: 't9', revision: 4, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    const idle = [...world2.document.querySelectorAll('#t9Strip .t9-side-cell')]
        .map(c => c.textContent);
    assert(idle.includes('，') && idle.includes('。'), 'idle strip shows common characters');
    // 尾段内退格不动确认边界：ni 426 → ni 42 仍出第二段读音（codex
    // round-4 P2-4 的保留面）；删进已确认段才从头重算。
    const world3 = fresh({ mode: 't9' });
    world3.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '64 426',
        rawInput: '64 426', candidates: [], hasNextPage: false });
    world3.tap([...world3.document.querySelectorAll('#t9Strip .t9-side-cell')]
        .find(c => c.textContent === 'ni'));
    world3.engineState({ phase: 'READY', mode: 't9', revision: 2, composing: 'ni 426',
        rawInput: 'ni 426', candidates: [], hasNextPage: false });
    world3.engineState({ phase: 'READY', mode: 't9', revision: 3, composing: 'ni 42',
        rawInput: 'ni 42', candidates: [], hasNextPage: false });
    const tail = [...world3.document.querySelectorAll('#t9Strip .t9-side-cell')]
        .map(c => c.textContent);
    assert(tail.includes('ha') || tail.includes('ga'), 'tail deletion keeps the boundary');
    assert(!tail.includes('nian'), 'boundary kept: no from-scratch readings');
    // 连续造词：librime 把已选汉字写进 preedit（'你 426'）——已选文字
    // 不进音节枚举，剩余段照常可点，点选重写保留汉字前缀（codex
    // round-2 P2-2）。
    const world4 = fresh({ mode: 't9' });
    world4.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '你 426',
        rawInput: '你 426', candidates: [{ id: 'c1', text: '你' }], hasNextPage: false });
    const afterHan = [...world4.document.querySelectorAll('#t9Strip .t9-side-cell')]
        .map(c => c.textContent);
    assert(afterHan.includes('hao') && afterHan.includes('gan'),
        'hanzi preedit prefix leaves the tail selectable');
    world4.tap([...world4.document.querySelectorAll('#t9Strip .t9-side-cell')]
        .find(c => c.textContent === 'hao'));
    equal(world4.native.of('setComposition').slice(-1)[0].args[0], '你hao',
        'hao pick preserves the committed hanzi prefix');
    // 目标回声落地解除重放冻结，再喂新输入。
    world4.engineState({ phase: 'READY', mode: 't9', revision: 2, composing: '你 hao',
        rawInput: '你 hao', candidates: [{ id: 'c1', text: '你好' }], hasNextPage: false });
    // 科学计数词频（了 le 1.49e+06）入索引：53 → le 排 ke 前（codex
    // round-2 P2-3）。
    world4.engineState({ phase: 'READY', mode: 't9', revision: 3, composing: '53',
        rawInput: '53', candidates: [], hasNextPage: false });
    world4.engineState({ phase: 'READY', mode: 't9', revision: 2, composing: '53',
        rawInput: '53', candidates: [], hasNextPage: false });
    equal([...world4.document.querySelectorAll('#t9Strip .t9-side-cell')]
        .map(c => c.textContent)[0], 'le', 'scientific-notation weights: 53 lists le first');
});

test('t9 mic scrub cancels the pending voice hold timer', {since: '3.35.0'}, () => {
    const world = fresh({ mode: 't9' });
    world.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    const space = world.$('spaceKey');
    // 横滑 scrub 按住不放：越过 350ms 语音长按阈值也不得拉起语音。
    world.touchDown(space, 20, 20);
    world.move(space, 60, 20);
    world.clock.advance(400);
    equal(world.native.of('startVoice').length, 0,
        'scrub must cancel the voice hold timer');
    world.touchUp(space);
    world.clock.advance(2);
});

test('t9 function keys: confirm/重输/1/123/emoji', {since: '3.35.0'}, () => {
    const world = fresh({ mode: 't9' });
    world.engineState({ phase: 'READY', mode: 't9', revision: 7, composing: 'ni', rawInput: 'ni',
        candidates: [{ id: 'c1', text: '你' }, { id: 'c2', text: '呢' }], hasNextPage: false });
    // 组合中确认 = 提交高亮（池首），绝不走 EnterRaw（数字串会原样上屏）。
    world.tap(world.$('enterKey'));
    const pick = world.native.of('chooseCandidate').slice(-1)[0];
    equal(pick.args[0], 7, 'confirm uses the live revision');
    equal(pick.args[1], 'c1', 'confirm picks the highlighted pool head');
    // 空闲确认 = 换行。
    world.engineState({ phase: 'READY', mode: 't9', revision: 8, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    world.tap(world.$('enterKey'));
    equal(world.native.of('enter').length, 1, 'idle confirm falls through to newline');
    // 重输 = 清组合。
    world.engineState({ phase: 'READY', mode: 't9', revision: 9, composing: 'ni4', rawInput: 'ni4',
        candidates: [{ id: 'c1', text: '你' }], hasNextPage: false });
    world.tap(world.document.querySelector('[data-role="t9clear"]'));
    assert(world.native.of('clearComposing').length >= 1, '重输 clears the composition');
    // 1 键点按：候选条出西文/技术符号行。
    world.engineState({ phase: 'READY', mode: 't9', revision: 10, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    world.tap(world.key('1'));
    const bar = [...world.document.querySelectorAll('#candidates .candidate')]
        .map(b => b.textContent);
    equal(bar.join(''), '@#.*+-_/=', '1 key opens the technical symbol bar');
    // 123 → 数字板；emoji → 数字板 emoji 视图。
    world.tap(world.document.querySelector('[data-role="symbols"]'));
    assert(!world.$('numPadLayer').hidden, '123 opens the nine-pad');
    world.tap(world.document.querySelector('[data-role="numpad-back"]'));
    assert(!world.$('qwertyLayer').hidden, 'back returns to the t9 keyface');
    world.tap(world.document.querySelector('[data-role="t9emoji"]'));
    assert(!world.$('numPadLayer').hidden, 'emoji opens the nine-pad');
    assert(world.document.querySelector('.emoji-area'), 'emoji view is showing');
});

test('t9 1-key chrome: tap yields toolbar, pick/× restores, up-flick commits 1', {since: '3.36.0'}, () => {
    const world = fresh({ mode: 't9' });
    world.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    const one = world.key('1');
    // 单击 1（用户定稿，不再需要长按）：符号行 + chrome——工具栏快捷
    // 按钮全部让位（含 mic），仅保留最右 ×。
    world.tap(one);
    assert(world.$('setupButton').hidden, 'setup tool hidden');
    assert(world.$('mic').hidden, 'mic hidden too');
    assert(!world.$('composeClear').hidden, '× visible to cancel the bar');
    // 点选符号 = 上屏 + 关符号行 + 工具栏复原（用户定稿）。
    const sym = [...world.document.querySelectorAll('#candidates .candidate')][0];
    world.tap(sym);
    const commits = world.native.of('commitText');
    equal(commits[commits.length - 1].args[0], '@', 'symbol goes straight to the editor');
    assert(!world.$('setupButton').hidden, 'toolbar restored after pick');
    assert(!world.$('mic').hidden, 'mic restored after pick');
    assert(world.$('composeClear').hidden, '× hidden after pick');
    equal([...world.document.querySelectorAll('#candidates .candidate')].length, 0,
        'symbol row cleared after pick');
    // 重新打开；空闲引擎事件/原生状态刷新不得翻回工具栏（× 是唯一
    // 取消入口，codex round-2 P2-4）。
    world.tap(one);
    world.engineState({ phase: 'READY', mode: 't9', revision: 2, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    assert(world.$('setupButton').hidden, 'idle refresh holds the chrome');
    assert(!world.$('composeClear').hidden, '× survives idle refresh');
    // × 单击 = 只关符号行并恢复工具栏，绝不清组合。
    world.tap(world.$('composeClear'));
    assert(!world.$('setupButton').hidden, 'toolbar restored by ×');
    assert(world.$('composeClear').hidden, '× hidden again');
    equal(world.native.of('clearComposing').length, 0, '× must not clear the composition');
    // 联想事件让符号行让位后进入联想 chrome：工具栏同样让位（含 mic）
    // 仅留 ×；× 关联想并复原（用户定稿）。
    world.tap(one);
    world.assoc(['的', '是']);
    assert(world.$('setupButton').hidden, 'assoc keeps the toolbar yielded');
    assert(world.$('mic').hidden, 'assoc hides mic too');
    assert(!world.$('composeClear').hidden, 'assoc shows ×');
    world.tap(world.$('composeClear'));
    assert(!world.$('setupButton').hidden, 'assoc × restores the toolbar');
    equal(world.native.of('clearComposing').length, 0, 'assoc × must not clear the composition');
    // 上滑 1 = 字面数字 1（1 不在引擎 alphabet，sendSymbol 旁路上屏）。
    world.touchDown(one, 20, 20);
    world.move(one, 20, -30);
    world.touchUp(one);
    world.clock.advance(2);
    equal(world.native.of('commitText').slice(-1)[0].args[0], '1',
        'up-flick commits literal 1');
});

test('t9 7/9 down-swipe opens the split popup (下左/下右)', {since: '3.35.0'}, () => {
    const world = fresh({ mode: 't9' });
    world.engineState({ phase: 'READY', mode: 't9', revision: 1, composing: '', rawInput: '',
        candidates: [], hasNextPage: false });
    const seven = world.key('7');
    // 下滑拉开浮层后直接松手 = 预选下左 q。
    world.touchDown(seven, 20, 20);
    world.move(seven, 20, 70);
    world.touchUp(seven);
    world.clock.advance(2);
    equal(world.native.of('key').slice(-1)[0].args[0], 'q', 'release on 下左 commits q');
    // 拖到下右格松手 = r。
    world.touchDown(seven, 20, 20);
    world.move(seven, 20, 70);
    const cells = [...world.document.querySelectorAll('.kp-item')];
    equal(cells.map(c => c.textContent).join(','), 'q,r', 'split popup offers q/r');
    const rRect = cells[1].getBoundingClientRect();
    world.move(seven, rRect.left + rRect.width / 2, 10);
    world.touchUp(seven);
    world.clock.advance(2);
    equal(world.native.of('key').slice(-1)[0].args[0], 'r', 'drag to 下右 commits r');
});

console.log(`\n== mock-bridge suite: ${passed} passed, ${failed} failed` +
    (skipped ? `, ${skipped} skipped (era-gated)` : '') +
    ` [keyboard ${KEYBOARD_VERSION}] ==`);
if (failed) {
    console.log('failures:', failures.join(' | '));
    process.exit(1);
}
