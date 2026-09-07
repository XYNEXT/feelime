package com.feelime.verify.uidump;

import android.os.Bundle;
import android.os.Environment;

import com.android.uiautomator.core.Configurator;
import com.android.uiautomator.core.UiDevice;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Dump the current accessibility hierarchy without changing device state.
 *
 * <p>The legacy runner can spend its default idle wait inside a continuously
 * animated input method.  Set the timeout before asking UiDevice for the
 * hierarchy so the dump remains an observation-only operation.</p>
 */
public final class UiDumpTest extends UiAutomatorTestCase {
    private static final String OUTPUT_PARAMETER = "output";
    private static final String DEFAULT_OUTPUT = "/sdcard/feelime-ui-dump.xml";
    private static final String DUMP_NAME = "feelime-ui-dump.xml";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Configurator.getInstance().setWaitForIdleTimeout(0);
    }

    public void testDumpWindowHierarchy() {
        Configurator.getInstance().setWaitForIdleTimeout(0);
        Bundle params = getParams();
        String output = params == null ? null : params.getString(OUTPUT_PARAMETER);
        if (output == null || output.length() == 0) {
            output = DEFAULT_OUTPUT;
        }
        // UiDevice resolves its argument below Environment.getDataDirectory()
        // + local/tmp on the legacy runner.  Resolve the same location here;
        // some shell runners expose /data as that process's data directory,
        // which otherwise makes a hard-coded /data/local/tmp check stale.
        File dumpFile = new File(
                new File(Environment.getDataDirectory(), "local/tmp"), DUMP_NAME);
        File dumpParent = dumpFile.getParentFile();
        if (!dumpParent.isDirectory() && !dumpParent.mkdirs()) {
            throw new AssertionError("cannot create UI dump directory " + dumpParent);
        }
        dumpFile.delete();
        UiDevice device = UiDevice.getInstance();
        // The legacy dump method reads the root once and returns silently on
        // a just-connected UiAutomation service.  This read-only query uses
        // the runner's bounded root retry while the idle timeout remains 0.
        device.getCurrentPackageName();
        device.dumpWindowHierarchy(DUMP_NAME);
        if (!dumpFile.isFile() || dumpFile.length() == 0) {
            throw new AssertionError("UiDevice dump did not create " + dumpFile);
        }
        copy(dumpFile, new File(output));
    }

    private static void copy(File source, File destination) {
        byte[] buffer = new byte[8192];
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        } catch (IOException error) {
            throw new AssertionError("cannot copy UI dump to " + destination, error);
        }
    }
}
