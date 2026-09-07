package com.feelime.ime.update

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Production wiring for the signed keyboard hot-update pipeline: embeds the
 * release verification key, adapts assets/preferences, and owns the singleton
 * [KeyboardStore]. The ADB debug installer and the setup-page network
 * installer share this exact path.
 */
object KeyboardUpdateCenter {
    const val ACTION_KEYBOARD_UPDATED = "com.feelime.ime.KEYBOARD_UPDATED"
    const val INBOX_EXTRA = "feelime.install"
    private const val INBOX_DIR = "keyboard-inbox"

    @Volatile
    private var storeInstance: KeyboardStore? = null

    fun store(context: Context): KeyboardStore {
        return storeInstance ?: synchronized(this) {
            storeInstance ?: KeyboardStore(
                root = File(context.filesDir, "keyboard"),
                builtIn = AssetsBuiltInKeyboard(context.applicationContext),
                verifier = KeyboardPackageVerifier(
                    releaseKeys = mapOf(ReleaseKeys.KEY_ID to ReleaseKeys.PUBLIC_KEY),
                    // Play may hot-update signed WebView resources, but an
                    // unsigned debug ZIP must never cross into that flavor.
                    allowUnsignedDebug =
                        com.feelime.ime.BuildConfig.DEBUG &&
                            !com.feelime.ime.BuildConfig.PLAY_DISTRIBUTION,
                ),
                state = SharedPreferencesKeyboardState(
                    context.applicationContext.getSharedPreferences("keyboard_update", Context.MODE_PRIVATE),
                ),
                allowUnsignedVersions =
                    com.feelime.ime.BuildConfig.DEBUG &&
                        !com.feelime.ime.BuildConfig.PLAY_DISTRIBUTION,
            ).also { storeInstance = it }
        }
    }

    fun activeKeyboard(context: Context): ActiveKeyboard = store(context).resolve()

    fun notifyUpdated(context: Context) {
        context.sendBroadcast(Intent(ACTION_KEYBOARD_UPDATED).setPackage(context.packageName))
    }

    fun inboxFile(context: Context): File = File(File(context.filesDir, INBOX_DIR), "inbox.zip")
}

class AssetsBuiltInKeyboard(private val context: Context) : BuiltInKeyboardSource {
    override fun version(): String =
        context.assets.open("keyboard/VERSION").bufferedReader().use { it.readText().trim() }

    override fun files(): Map<String, ByteArray> =
        KeyboardPackageVerifier.PAYLOAD_ENTRIES.associateWith { name ->
            context.assets.open("keyboard/$name").use { it.readBytes() }
        }
}

class SharedPreferencesKeyboardState(private val prefs: android.content.SharedPreferences) : KeyboardStateStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
