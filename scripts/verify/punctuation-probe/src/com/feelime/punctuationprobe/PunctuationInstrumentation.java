package com.feelime.punctuationprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Runs the production punctuation path inside the installed Feelime APK.
 *
 * This APK deliberately has no copy of Feelime or sherpa classes. The target
 * context's class loader supplies those classes, so a successful run proves
 * the exact production ModelStore, model source, JNI punctuation operator,
 * and TranscriptPostProcessor are loadable together.
 */
public final class PunctuationInstrumentation extends Instrumentation {
    private static final String TAG = "FeelimePunctuationProbe";
    private static final String TARGET_PACKAGE = "com.feelime.ime";
    private static final String MODEL_ROLE = "punctuation";
    private static final String PREFS = "feelime_asr";
    private static final String STRIP_FINAL_PERIOD = "strip_final_period";

    private static final String CN_INPUT = "你好世界";
    private static final String MIXED_INPUT = "你好 how are you 我很好谢谢";

    private Bundle arguments;

    @Override
    public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : new Bundle(args);
        // Instrumentation's documented lifecycle enters onStart through
        // start(); without this explicit hand-off an instrumentation launched
        // with `am instrument` can remain in onCreate indefinitely.
        start();
    }

    @Override
    public void onStart() {
        super.onStart();
        Bundle results = new Bundle();
        initializeCaseResults(results);
        boolean allPass;
        try {
            allPass = runProbe(results);
        } catch (Throwable error) {
            allPass = false;
            putFailure(results, error);
            Log.e(TAG, "production punctuation probe failed", error);
        }
        results.putString("all_pass", Boolean.toString(allPass));
        finish(allPass ? 0 : 1, results);
    }

    private boolean runProbe(Bundle results) throws Exception {
        Context target = getTargetContext();
        require(TARGET_PACKAGE.equals(target.getPackageName()),
                "instrumentation target package is " + target.getPackageName());
        ClassLoader loader = target.getClassLoader();
        require(loader != null, "target class loader is null");

        Class<?> storeClass = loader.loadClass(TARGET_PACKAGE + ".ModelStore");
        Class<?> factoryClass = loader.loadClass(TARGET_PACKAGE + ".ModelConnectionFactory");
        Class<?> sourceClass = loader.loadClass(TARGET_PACKAGE + ".ModelSource");
        Class<?> postClass = loader.loadClass(TARGET_PACKAGE + ".TranscriptPostProcessor");

        Constructor<?> storeConstructor = storeClass.getConstructor(
                Context.class, factoryClass, Boolean.TYPE);
        // The explicit null factory and false allowHttp arguments are part of
        // this probe contract: no network transport or cleartext mirror may
        // be hidden behind the production lookup.
        Object store = storeConstructor.newInstance(target, null, Boolean.FALSE);
        Object postProcessor = null;
        SharedPreferences preferences = target.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        PreferenceSnapshot originalPreference = null;
        boolean preferenceRestored = false;
        boolean postReleased = false;
        boolean storeReleased = false;
        boolean allPass = true;
        String mixedKeepOutput = null;

        results.putString("model_store_constructor", "Context,ModelConnectionFactory,null,false");
        try {
            originalPreference = PreferenceSnapshot.read(preferences);
            Method sourceFor = storeClass.getMethod("sourceFor", String.class);
            Object source = sourceFor.invoke(store, MODEL_ROLE);
            boolean sourcePass = source != null;
            String sourceClassName = source == null ? "null" : source.getClass().getName();
            String sourceKind = sourceKind(sourceClassName);
            results.putString("source_class", sourceClassName);
            results.putString("source_kind", sourceKind);
            results.putString("source_nonnull", Boolean.toString(sourcePass));
            allPass &= sourcePass;
            if (!sourcePass) {
                throw new IllegalStateException("sourceFor(punctuation) returned null");
            }
            if ("Unknown".equals(sourceKind)) {
                throw new IllegalStateException("unknown ModelSource type: " + sourceClassName);
            }
            allPass &= checkExpectedSource(results, sourceKind);

            Constructor<?> postConstructor = postClass.getConstructor(
                    Context.class, storeClass, sourceClass);
            postProcessor = postConstructor.newInstance(target, store, source);
            Method finalMethod = postClass.getMethod("final", String.class);

            // The first two runs keep the model's terminal punctuation. The
            // second mixed run enables the user preference and must remove a
            // terminal period while leaving the internal punctuation intact.
            CaseResult chinese = executeCase(
                    results, preferences, finalMethod, postProcessor,
                    "case_cn", CN_INPUT, false);
            boolean chineseShape = chinese.pass && hasChinesePunctuation(chinese.output);
            results.putString("case_cn_pass", Boolean.toString(chineseShape));
            allPass &= chineseShape;
            if (!chineseShape) {
                putCaseFailure(results, "case_cn",
                        "output lacks Chinese punctuation");
            }

            CaseResult mixedKeep = executeCase(
                    results, preferences, finalMethod, postProcessor,
                    "case_mixed_keep", MIXED_INPUT, false);
            mixedKeepOutput = mixedKeep.output;
            boolean keepShape = mixedKeep.pass && hasChineseText(mixedKeep.output)
                    && hasEnglishText(mixedKeep.output)
                    && hasPunctuation(mixedKeep.output)
                    && endsWithPeriod(mixedKeep.output);
            results.putString("case_mixed_keep_pass", Boolean.toString(keepShape));
            allPass &= keepShape;
            if (!keepShape) {
                putCaseFailure(results, "case_mixed_keep",
                        "keep output must contain Chinese/English punctuation and end in a period");
            }

            CaseResult mixedStrip = executeCase(
                    results, preferences, finalMethod, postProcessor,
                    "case_mixed_strip", MIXED_INPUT, true);
            String expectedStripOutput = withoutFinalPeriod(mixedKeepOutput);
            boolean stripShape = mixedStrip.pass && hasChineseText(mixedStrip.output)
                    && hasEnglishText(mixedStrip.output)
                    && hasPunctuation(mixedStrip.output)
                    && !endsWithPeriod(mixedStrip.output)
                    && mixedKeepOutput != null
                    && expectedStripOutput != null
                    && expectedStripOutput.equals(mixedStrip.output);
            results.putString("case_mixed_strip_expected",
                    expectedStripOutput == null ? "<unavailable>" : expectedStripOutput);
            results.putString("case_mixed_strip_pass", Boolean.toString(stripShape));
            allPass &= stripShape;
            if (!stripShape) {
                putCaseFailure(results, "case_mixed_strip",
                        "strip output must retain internal punctuation and remove only the terminal period");
            }
        } catch (Throwable error) {
            allPass = false;
            putFailure(results, error);
            Log.e(TAG, "production punctuation invocation failed", error);
        } finally {
            if (originalPreference != null) {
                try {
                    preferenceRestored = originalPreference.restore(preferences);
                } catch (Throwable error) {
                    allPass = false;
                    putFailure(results, error);
                }
            }
            results.putString("preference_restored", Boolean.toString(preferenceRestored));
            allPass &= preferenceRestored;

            // Release the production objects after preference restoration so
            // every native object created by this probe has a deterministic
            // final cleanup point.
            if (postProcessor != null) {
                try {
                    postClassRelease(postProcessor);
                    postReleased = true;
                } catch (Throwable error) {
                    allPass = false;
                    putFailure(results, error);
                }
            }
            if (store != null) {
                try {
                    storeClassRelease(store);
                    storeReleased = true;
                } catch (Throwable error) {
                    allPass = false;
                    putFailure(results, error);
                }
            }
            results.putString("post_processor_released", Boolean.toString(postReleased));
            results.putString("model_store_released", Boolean.toString(storeReleased));
            allPass &= postReleased && storeReleased;
        }
        return allPass;
    }

    private boolean checkExpectedSource(Bundle results, String actual) {
        String expected = arguments == null
                ? "" : arguments.getString("expected_source", "");
        expected = expected == null ? "" : expected.trim().toLowerCase(Locale.US);
        if (expected.length() == 0) {
            results.putString("expected_source", "");
            results.putString("source_expectation_pass", "true");
            return true;
        }
        String canonical;
        if ("asset".equals(expected) || "assets".equals(expected)) {
            canonical = "Assets";
        } else if ("download".equals(expected) || "downloaded".equals(expected)
                || "directory".equals(expected)) {
            canonical = "Downloaded";
        } else {
            results.putString("expected_source", expected);
            results.putString("source_expectation_pass", "false");
            putCaseFailure(results, "source", "expected_source must be assets or downloaded");
            return false;
        }
        boolean pass = canonical.equals(actual);
        results.putString("expected_source", canonical);
        results.putString("source_expectation_pass", Boolean.toString(pass));
        if (!pass) {
            putCaseFailure(results, "source",
                    "expected " + canonical + ", observed " + actual);
        }
        return pass;
    }

    private static CaseResult executeCase(Bundle results,
                                           SharedPreferences preferences,
                                           Method finalMethod,
                                           Object postProcessor,
                                           String name,
                                           String input,
                                           boolean stripPeriod) {
        results.putString(name + "_input", input);
        String output = "<not run>";
        boolean pass = false;
        try {
            if (!preferences.edit().putBoolean(STRIP_FINAL_PERIOD, stripPeriod).commit()) {
                throw new IllegalStateException("could not persist " + STRIP_FINAL_PERIOD);
            }
            if (preferences.getBoolean(STRIP_FINAL_PERIOD, !stripPeriod) != stripPeriod) {
                throw new IllegalStateException("preference did not become " + stripPeriod);
            }
            Object value = invoke(finalMethod, postProcessor, input);
            output = value == null ? "<null>" : String.valueOf(value);
            pass = value != null && output.trim().length() > 0;
        } catch (Throwable error) {
            output = "<error: " + errorDescription(error) + ">";
            Log.e(TAG, name + " failed", error);
        }
        results.putString(name + "_output", output);
        results.putString(name + "_pass", Boolean.toString(pass));
        Log.i(TAG, name + " input=" + input + " output=" + output + " pass=" + pass);
        return new CaseResult(output, pass);
    }

    private static Object invoke(Method method, Object receiver, Object... args)
            throws Throwable {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw cause == null ? error : cause;
        }
    }

    private static void postClassRelease(Object postProcessor) throws Throwable {
        Method release = postProcessor.getClass().getMethod("release");
        invoke(release, postProcessor);
    }

    private static void storeClassRelease(Object store) throws Throwable {
        Method release = store.getClass().getMethod("release");
        invoke(release, store);
    }

    private static String sourceKind(String className) {
        if (className.endsWith("$Assets")) return "Assets";
        if (className.endsWith("$Directory")) return "Downloaded";
        return "Unknown";
    }

    private static boolean hasChineseText(String text) {
        return text != null && text.matches(".*[\\u3400-\\u9fff].*");
    }

    private static boolean hasEnglishText(String text) {
        return text != null && text.matches(".*[A-Za-z].*");
    }

    private static boolean hasChinesePunctuation(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            if ("，。！？、；：‘’“”《》（）【】…—".indexOf(text.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPunctuation(String text) {
        return hasChinesePunctuation(text)
                || (text != null && text.matches(".*[,.!?;:].*"));
    }

    private static boolean endsWithPeriod(String text) {
        if (text == null) return false;
        String clean = text.trim();
        return clean.endsWith(".") || clean.endsWith("。");
    }

    private static String withoutFinalPeriod(String text) {
        if (text == null) return null;
        String clean = text.trim();
        if (!endsWithPeriod(clean)) return null;
        return clean.substring(0, clean.length() - 1).trim();
    }

    private static void initializeCaseResults(Bundle results) {
        results.putString("case_cn_input", CN_INPUT);
        results.putString("case_cn_output", "<not run>");
        results.putString("case_cn_pass", "false");
        results.putString("case_mixed_keep_input", MIXED_INPUT);
        results.putString("case_mixed_keep_output", "<not run>");
        results.putString("case_mixed_keep_pass", "false");
        results.putString("case_mixed_strip_input", MIXED_INPUT);
        results.putString("case_mixed_strip_output", "<not run>");
        results.putString("case_mixed_strip_pass", "false");
        results.putString("preference_restored", "false");
        results.putString("post_processor_released", "false");
        results.putString("model_store_released", "false");
    }

    private static void putCaseFailure(Bundle results, String name, String message) {
        results.putString(name + "_detail", message);
        Log.e(TAG, name + ": " + message);
    }

    private static void putFailure(Bundle results, Throwable error) {
        String detail = errorDescription(error);
        String previous = results.getString("failure", "");
        if (previous.length() > 0) detail = previous + "; " + detail;
        results.putString("failure", detail);
    }

    private static String errorDescription(Throwable error) {
        Throwable cause = error;
        if (cause instanceof InvocationTargetException
                && ((InvocationTargetException) cause).getCause() != null) {
            cause = ((InvocationTargetException) cause).getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.length() == 0) message = cause.toString();
        return cause.getClass().getName() + ":" + message
                .replace('\n', ' ').replace('\r', ' ');
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class CaseResult {
        final String output;
        final boolean pass;

        CaseResult(String output, boolean pass) {
            this.output = output;
            this.pass = pass;
        }
    }

    private static final class PreferenceSnapshot {
        final boolean present;
        final boolean value;

        private PreferenceSnapshot(boolean present, boolean value) {
            this.present = present;
            this.value = value;
        }

        static PreferenceSnapshot read(SharedPreferences preferences) {
            boolean present = preferences.contains(STRIP_FINAL_PERIOD);
            Object value = preferences.getAll().get(STRIP_FINAL_PERIOD);
            if (present && !(value instanceof Boolean)) {
                throw new IllegalStateException(
                        STRIP_FINAL_PERIOD + " preference is not boolean");
            }
            return new PreferenceSnapshot(present, present && (Boolean) value);
        }

        boolean restore(SharedPreferences preferences) {
            boolean committed;
            if (present) {
                committed = preferences.edit()
                        .putBoolean(STRIP_FINAL_PERIOD, value).commit();
            } else {
                committed = preferences.edit().remove(STRIP_FINAL_PERIOD).commit();
            }
            if (!committed) return false;
            boolean nowPresent = preferences.contains(STRIP_FINAL_PERIOD);
            Object now = preferences.getAll().get(STRIP_FINAL_PERIOD);
            return present == nowPresent
                    && (!present || (now instanceof Boolean && value == (Boolean) now));
        }
    }
}
