package org.example.feelimecursorprobe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.SurroundingText;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Ordinary EditText host for comparing cursor movement through an IME.
 *
 * The wrapped InputConnection deliberately makes the two full-snapshot APIs
 * unavailable and rejects native left/right key events. The bounded text
 * APIs and setSelection continue to the real EditText, which makes the
 * fallback path observable without changing the production editor.
 */
public final class MainActivity extends Activity {
    private static final String PRESET = "Feelime cursor probe 😀 / ASCII 123";

    private ProbeEditText editor;
    private TextView textValue;
    private TextView selectionValue;
    private TextView inputTypeValue;
    private TextView countersValue;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = label("Feelime cursor InputConnection probe", 22);
        title.setId(R.id.probe_title);
        root.addView(title, fullWidth());

        TextView instructions = label(
                "普通 EditText；getExtractedText / getSurroundingText 返回 null，" +
                        "DPAD 左右被拦截。请用 Feelime 光标键，然后查看下方计数。", 14);
        instructions.setId(R.id.probe_instructions);
        instructions.setTextColor(Color.DKGRAY);
        addTop(root, instructions, 8);

        editor = new ProbeEditText(this, this::refreshDiagnostics);
        editor.setId(R.id.probe_editor);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setSingleLine(false);
        editor.setMinLines(3);
        editor.setMaxLines(5);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTextSize(20);
        editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        editor.setText(PRESET);
        editor.setSelection(PRESET.length());
        addTop(root, editor, 18);

        textValue = diagnostic("text");
        textValue.setId(R.id.probe_text);
        addTop(root, textValue, 16);

        selectionValue = diagnostic("selection");
        selectionValue.setId(R.id.probe_selection);
        addTop(root, selectionValue, 6);

        inputTypeValue = diagnostic("inputType");
        inputTypeValue.setId(R.id.probe_input_type);
        addTop(root, inputTypeValue, 6);

        countersValue = diagnostic("apiCounters");
        countersValue.setId(R.id.probe_counters);
        addTop(root, countersValue, 6);

        Button reset = button("Reset text");
        reset.setId(R.id.probe_reset);
        reset.setOnClickListener(view -> {
            editor.setText(PRESET);
            editor.setSelection(PRESET.length());
            refreshDiagnostics();
        });
        addTop(root, reset, 16);

        Button middle = button("Put cursor in the middle");
        middle.setId(R.id.probe_middle);
        middle.setOnClickListener(view -> {
            int middleOffset = editor.getText().length() / 2;
            editor.requestFocus();
            editor.setSelection(middleOffset);
            refreshDiagnostics();
        });
        addTop(root, middle, 8);

        Button showIme = button("Show keyboard");
        showIme.setId(R.id.probe_show_ime);
        showIme.setOnClickListener(view -> showEditorKeyboard());
        addTop(root, showIme, 8);

        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshDiagnostics();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        editor.post(() -> {
            editor.requestFocus();
            refreshDiagnostics();
            showEditorKeyboard();
        });
        setContentView(scroll);
    }

    private void showEditorKeyboard() {
        editor.requestFocus();
        android.view.inputmethod.InputMethodManager manager =
                (android.view.inputmethod.InputMethodManager) getSystemService(
                        Context.INPUT_METHOD_SERVICE);
        editor.post(() -> manager.showSoftInput(editor,
                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
        refreshDiagnostics();
    }

    private void refreshDiagnostics() {
        if (editor == null || textValue == null) return;
        textValue.setText("text=" + editor.getText());
        selectionValue.setText(String.format(Locale.US,
                "selectionStart=%d selectionEnd=%d",
                editor.getSelectionStart(), editor.getSelectionEnd()));
        inputTypeValue.setText(String.format(Locale.US,
                "inputType=0x%08x (ordinary TYPE_CLASS_TEXT)", editor.getInputType()));
        countersValue.setText("apiCounters=" + editor.stats().format());
    }

    private TextView label(String text, float size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        return view;
    }

    private TextView diagnostic(String name) {
        TextView view = label(name + "=", 14);
        view.setTextIsSelectable(true);
        return view;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addTop(LinearLayout root, View view, int marginDp) {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(marginDp);
        root.addView(view, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    static final class ProbeStats {
        private int extracted;
        private int surrounding;
        private int before;
        private int after;
        private int selected;
        private int setSelection;
        private int sendKey;
        private int blockedDpad;

        synchronized void extracted() {
            extracted++;
        }

        synchronized void surrounding() {
            surrounding++;
        }

        synchronized void before() {
            before++;
        }

        synchronized void after() {
            after++;
        }

        synchronized void selected() {
            selected++;
        }

        synchronized void setSelection() {
            setSelection++;
        }

        synchronized void sendKey(boolean blocked) {
            sendKey++;
            if (blocked) blockedDpad++;
        }

        synchronized String format() {
            return String.format(Locale.US,
                    "getExtractedText=%d getSurroundingText=%d " +
                            "getTextBeforeCursor=%d getTextAfterCursor=%d " +
                            "getSelectedText=%d setSelection=%d sendKeyEvent=%d " +
                            "blockedDpadLeftRight=%d",
                    extracted, surrounding, before, after, selected,
                    setSelection, sendKey, blockedDpad);
        }
    }

    static final class ProbeEditText extends EditText {
        private final Runnable onChanged;
        private final ProbeStats stats = new ProbeStats();

        ProbeEditText(Context context, Runnable onChanged) {
            super(context);
            this.onChanged = onChanged;
        }

        ProbeStats stats() {
            return stats;
        }

        void changedFromInputConnection() {
            post(onChanged);
        }

        @Override
        public void onSelectionChanged(int start, int end) {
            super.onSelectionChanged(start, end);
            if (onChanged != null) onChanged.run();
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            InputConnection target = super.onCreateInputConnection(outAttrs);
            if (target == null) return null;
            // Keep this a normal text editor. The probe only changes the
            // InputConnection methods under test, never EditorInfo.inputType.
            return new ProbeInputConnection(target, this, stats);
        }
    }

    private static final class ProbeInputConnection extends InputConnectionWrapper {
        private final ProbeEditText editor;
        private final ProbeStats stats;

        ProbeInputConnection(InputConnection target, ProbeEditText editor, ProbeStats stats) {
            super(target, false);
            this.editor = editor;
            this.stats = stats;
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            stats.extracted();
            editor.changedFromInputConnection();
            return null;
        }

        @Override
        public SurroundingText getSurroundingText(int beforeLength, int afterLength, int flags) {
            stats.surrounding();
            editor.changedFromInputConnection();
            return null;
        }

        @Override
        public CharSequence getTextBeforeCursor(int length, int flags) {
            stats.before();
            CharSequence result = super.getTextBeforeCursor(length, flags);
            editor.changedFromInputConnection();
            return result;
        }

        @Override
        public CharSequence getTextAfterCursor(int length, int flags) {
            stats.after();
            CharSequence result = super.getTextAfterCursor(length, flags);
            editor.changedFromInputConnection();
            return result;
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            stats.selected();
            CharSequence result = super.getSelectedText(flags);
            editor.changedFromInputConnection();
            return result;
        }

        @Override
        public boolean setSelection(int start, int end) {
            stats.setSelection();
            boolean result = super.setSelection(start, end);
            editor.changedFromInputConnection();
            return result;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            boolean blocked = event != null &&
                    (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ||
                            event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT);
            stats.sendKey(blocked);
            editor.changedFromInputConnection();
            if (blocked) return false;
            boolean result = super.sendKeyEvent(event);
            editor.changedFromInputConnection();
            return result;
        }
    }
}
