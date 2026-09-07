package android.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compatibility definition used by the Android 35 legacy runner on the test
 * emulator.  The runner references this annotation, although its framework
 * class path does not expose android.test.base.jar to runtest tests.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RepetitiveTest {
    int numIterations();
}
