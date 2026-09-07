package com.google.android.apps.inputmethod.libs.mozc.session;

/** Exact public JNI surface expected by the pinned upstream libmozc.so. */
public final class MozcJNI {
  private MozcJNI() {}

  static {
    System.loadLibrary("mozc");
    if (!initialize()) {
      throw new UnsatisfiedLinkError("Mozc JNI registration failed");
    }
  }

  private static native boolean initialize();
  public static native byte[] evalCommand(byte[] command);
  public static native boolean onPostLoad(String userProfileDirectory, String dataFilePath);
  public static native String getDataVersion();
}

