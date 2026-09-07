package com.feelime.ime

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity

/** Transparent launcher/QS hand-off: the system picker needs a foreground
 * window, while selecting an IME remains an explicit Android system action. */
class ImePickerActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var pickerRequested = false
    private var pickerTookFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the hand-off window visually empty and let the picker own the
        // interaction. The theme supplies the transparent background.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        when {
            hasFocus && !pickerRequested -> {
                pickerRequested = true
                handler.post {
                    if (!isFinishing && !isDestroyed) {
                        getSystemService(InputMethodManager::class.java)
                            ?.showInputMethodPicker()
                    }
                    // If the picker cannot be shown (for example a restricted
                    // work profile), do not leave an empty activity behind.
                    handler.postDelayed({
                        if (!pickerTookFocus && !isFinishing) finish()
                    }, PICKER_TIMEOUT_MS)
                }
            }
            !hasFocus && pickerRequested -> pickerTookFocus = true
            hasFocus && pickerTookFocus && !isFinishing -> finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val PICKER_TIMEOUT_MS = 5_000L
    }
}
