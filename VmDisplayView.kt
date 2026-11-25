package com.vantora.app.vm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs

/**
 * Draws the real guest framebuffer received from QEMU and turns touch / mouse / keyboard input into RFB events.
 *  - tap = left click, long press = right click, two-finger tap = right click, two-finger drag = scroll
 *  - "drag mode" holds the left button while a finger moves
 *  - Bluetooth/USB mouse and hardware keyboards are forwarded directly
 */
class VmDisplayView(context: Context) : View(context) {
    var client: VncClient? = null
    var dragMode = false
    var nextTapRight = false
    var invertScroll = false

    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private var scale = 1f; private var offX = 0f; private var offY = 0f
    private var gx = 0; private var gy = 0

    private var downX = 0f; private var downY = 0f; private var downTime = 0L
    private var moved = false; private var two = false; private var scrolled = false
    private var scrollAcc = 0f; private var lastAvgY = 0f

    init { isFocusable = true; isFocusableInTouchMode = true; setBackgroundColor(Color.BLACK) }

    fun frameChanged() = postInvalidateOnAnimation()

    override fun onDraw(canvas: Canvas) {
        val c = client ?: return
        synchronized(c.lock) {
            val w = c.width; val h = c.height
            if (w <= 0 || h <= 0 || c.pixels.size < w * h) return
            var b = bmp
            if (b == null || b.width != w || b.height != h) { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp = b }
            b!!.setPixels(c.pixels, 0, w, 0, 0, w, h)
            scale = minOf(width.toFloat() / w, height.toFloat() / h)
            offX = (width - w * scale) / 2f; offY = (height - h * scale) / 2f
            m.reset(); m.postScale(scale, scale); m.postTranslate(offX, offY)
            canvas.drawBitmap(b, m, paint)
        }
    }

    private fun toGuest(x: Float, y: Float) {
        val c = client ?: return
        gx = ((x - offX) / scale).toInt().coerceIn(0, maxOf(0, c.width - 1))
        gy = ((y - offY) / scale).toInt().coerceIn(0, maxOf(0, c.height - 1))
    }

    private fun ptr(mask: Int) { client?.pointer(mask, gx, gy) }
    private fun click(mask: Int) { ptr(mask); ptr(0) }
    private fun wheel(up: Boolean) { click(if (up) 8 else 16) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            toGuest(e.x, e.y)
            var mask = 0
            val bs = e.buttonState
            if (bs and MotionEvent.BUTTON_PRIMARY != 0) mask = mask or 1
            if (bs and MotionEvent.BUTTON_TERTIARY != 0) mask = mask or 2
            if (bs and MotionEvent.BUTTON_SECONDARY != 0) mask = mask or 4
            ptr(mask); requestFocus(); return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = e.x; downY = e.y; downTime = e.eventTime
                moved = false; two = false; scrolled = false
                toGuest(e.x, e.y); ptr(if (dragMode) 1 else 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                two = true; scrolled = false; scrollAcc = 0f; lastAvgY = avgY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (two) {
                    val y = avgY(e)
                    scrollAcc += y - lastAvgY; lastAvgY = y
                    val step = 40f
                    while (scrollAcc > step) { wheel(!invertScroll); scrollAcc -= step; scrolled = true }
                    while (scrollAcc < -step) { wheel(invertScroll); scrollAcc += step; scrolled = true }
                } else {
                    if (abs(e.x - downX) > 12f || abs(e.y - downY) > 12f) moved = true
                    toGuest(e.x, e.y); ptr(if (dragMode) 1 else 0)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (two && !scrolled && e.eventTime - downTime < 350) click(4)
                two = false; moved = true   // swallow the final lift of the other finger
            }
            MotionEvent.ACTION_UP -> {
                if (dragMode) { toGuest(e.x, e.y); ptr(0) }
                else if (!moved && !two) {
                    val long = e.eventTime - downTime > 500
                    toGuest(e.x, e.y)
                    click(if (nextTapRight || long) 4 else 1)
                    nextTapRight = false
                }
            }
        }
        return true
    }

    private fun avgY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            toGuest(e.x, e.y)
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> { ptr(0); return true }
                MotionEvent.ACTION_SCROLL -> {
                    val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (v != 0f) wheel(if (invertScroll) v < 0 else v > 0)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(e)
    }

    fun sendKeysym(sym: Int, down: Boolean) { if (sym != 0) client?.key(down, sym) }
    fun tapKey(sym: Int) { sendKeysym(sym, true); sendKeysym(sym, false) }

    fun typeText(s: String) {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i); i += Character.charCount(cp)
            val sym = when {
                cp == '\n'.code -> 0xFF0D
                cp == '\t'.code -> 0xFF09
                cp in 0x20..0x7E || cp in 0xA0..0xFF -> cp
                else -> 0x01000000 + cp
            }
            tapKey(sym)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return false
        val sym = KeyMap.toKeysym(keyCode, event)
        if (sym == 0) return super.onKeyDown(keyCode, event)
        sendKeysym(sym, true); return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return false
        val sym = KeyMap.toKeysym(keyCode, event)
        if (sym == 0) return super.onKeyUp(keyCode, event)
        sendKeysym(sym, false); return true
    }

    // ---- soft keyboard ----
    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        val self = this
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean { self.typeText(text.toString()); return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(maxOf(1, beforeLength)) { self.tapKey(0xFF08) }
                repeat(afterLength) { self.tapKey(0xFFFF) }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) self.onKeyDown(event.keyCode, event) else self.onKeyUp(event.keyCode, event)
                return true
            }
        }
    }

    fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }
}
