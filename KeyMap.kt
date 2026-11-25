package com.vantora.app.vm

import android.view.KeyCharacterMap
import android.view.KeyEvent

/** Android key codes → X11 keysyms (what the RFB protocol sends). */
object KeyMap {
    fun toKeysym(keyCode: Int, e: KeyEvent?): Int {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return 0xFF0D
            KeyEvent.KEYCODE_DEL -> return 0xFF08
            KeyEvent.KEYCODE_FORWARD_DEL -> return 0xFFFF
            KeyEvent.KEYCODE_TAB -> return 0xFF09
            KeyEvent.KEYCODE_ESCAPE -> return 0xFF1B
            KeyEvent.KEYCODE_DPAD_LEFT -> return 0xFF51
            KeyEvent.KEYCODE_DPAD_UP -> return 0xFF52
            KeyEvent.KEYCODE_DPAD_RIGHT -> return 0xFF53
            KeyEvent.KEYCODE_DPAD_DOWN -> return 0xFF54
            KeyEvent.KEYCODE_MOVE_HOME -> return 0xFF50
            KeyEvent.KEYCODE_MOVE_END -> return 0xFF57
            KeyEvent.KEYCODE_PAGE_UP -> return 0xFF55
            KeyEvent.KEYCODE_PAGE_DOWN -> return 0xFF56
            KeyEvent.KEYCODE_INSERT -> return 0xFF63
            KeyEvent.KEYCODE_SHIFT_LEFT -> return 0xFFE1
            KeyEvent.KEYCODE_SHIFT_RIGHT -> return 0xFFE2
            KeyEvent.KEYCODE_CTRL_LEFT -> return 0xFFE3
            KeyEvent.KEYCODE_CTRL_RIGHT -> return 0xFFE4
            KeyEvent.KEYCODE_CAPS_LOCK -> return 0xFFE5
            KeyEvent.KEYCODE_ALT_LEFT -> return 0xFFE9
            KeyEvent.KEYCODE_ALT_RIGHT -> return 0xFFEA
            KeyEvent.KEYCODE_META_LEFT -> return 0xFFEB
            KeyEvent.KEYCODE_META_RIGHT -> return 0xFFEC
            KeyEvent.KEYCODE_SPACE -> return 0x20
        }
        if (keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return 0xFFBE + (keyCode - KeyEvent.KEYCODE_F1)
        // Base character without modifiers; Shift/Ctrl/Alt are sent as their own key events.
        val u = e?.getUnicodeChar(0) ?: 0
        return if (u > 0 && (u and KeyCharacterMap.COMBINING_ACCENT) == 0) u else 0
    }
}
