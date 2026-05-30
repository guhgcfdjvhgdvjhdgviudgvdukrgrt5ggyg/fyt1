package com.ghosttype.ime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.ghosttype.utils.SettingsStore

/**
 * Draws a small orange floating dot overlay that the user can drag over the SEND button
 * of any chat app. The position is saved to SharedPreferences. AutoTypeEngine then uses
 * AccessibilityService.dispatchGesture() to "tap" that exact (x,y) after each typed line.
 *
 * - When LOCKED: dot is non-touchable (pointer events pass through to the app underneath)
 *   so it does not interfere with normal use.
 * - When UNLOCKED: dot is draggable, on each ACTION_UP its (x,y) is persisted.
 *
 * Requires SYSTEM_ALERT_WINDOW (android.permission.SYSTEM_ALERT_WINDOW) — must be granted
 * by user via Settings.canDrawOverlays() flow.
 */
class FloatingPointerService : Service() {

    private var wm: WindowManager? = null
    private var dot: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var dotSizePx: Int = 0   // cached so coordinate math stays consistent

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        showOverlay()
    }

    override fun onDestroy() {
        hideOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOCK    -> setLocked(true)
            ACTION_UNLOCK  -> setLocked(false)
            ACTION_REFRESH -> applyLockedFlag()
            ACTION_RESIZE  -> applySize()
        }
        return START_STICKY
    }

    private fun currentSizeDp(): Int =
        SettingsStore.prefs(this).getInt(SettingsStore.KEY_POINTER_SIZE_DP, 28).coerceIn(16, 72)

    private fun showOverlay() {
        if (dot != null) return
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val sizeDp = currentSizeDp()
        val sizePx = dp(sizeDp)
        dotSizePx = sizePx                     // cache for coordinate math
        val halfPx = sizePx / 2

        val container = FrameLayout(this)
        val ring = TextView(this).apply {
            text = "●"
            textSize = sizeDp * 0.75f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF8C00"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33000000"))
                setStroke(dp(2), Color.parseColor("#FF8C00"))
            }
            alpha = 0.9f
        }
        container.addView(
            ring,
            FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val prefs = SettingsStore.prefs(this)
        // Stored values are the CENTER of the dot (screen coordinates).
        // params.x / params.y are the TOP-LEFT corner, so subtract half.
        val storedCx = prefs.getInt(SettingsStore.KEY_POINTER_X, -1)
        val storedCy = prefs.getInt(SettingsStore.KEY_POINTER_Y, -1)
        val locked = prefs.getBoolean(SettingsStore.KEY_POINTER_LOCKED, false)

        params = WindowManager.LayoutParams(
            sizePx, sizePx,
            type,
            currentFlags(locked),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Convert stored center → top-left corner for WindowManager
            x = if (storedCx >= 0) (storedCx - halfPx).coerceAtLeast(0) else 200
            y = if (storedCy >= 0) (storedCy - halfPx).coerceAtLeast(0) else 800
        }

        // Drag handler (only effective when not locked)
        var downX = 0
        var downY = 0
        var touchX = 0f
        var touchY = 0f
        container.setOnTouchListener { _, ev ->
            val p = params ?: return@setOnTouchListener false
            val half = dotSizePx / 2
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = p.x; downY = p.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = (downX + (ev.rawX - touchX)).toInt()
                    p.y = (downY + (ev.rawY - touchY)).toInt()
                    runCatching { wm?.updateViewLayout(container, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Save the CENTER of the dot as the click target coordinate
                    SettingsStore.prefs(this).edit()
                        .putInt(SettingsStore.KEY_POINTER_X, p.x + half)
                        .putInt(SettingsStore.KEY_POINTER_Y, p.y + half)
                        .apply()
                    true
                }
                else -> false
            }
        }

        try {
            wm?.addView(container, params)
            dot = container
        } catch (_: Throwable) { /* overlay permission missing */ }
    }

    private fun currentFlags(locked: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (locked) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    private fun setLocked(locked: Boolean) {
        SettingsStore.prefs(this).edit().putBoolean(SettingsStore.KEY_POINTER_LOCKED, locked).apply()
        applyLockedFlag()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var hidingForClick = false

    /**
     * Fully detach the dot from the window stack for [durationMs] and then
     * re-attach it. Called by AutoTypeEngine right before it dispatches its
     * accessibility tap.
     *
     * Why removal (and not just FLAG_NOT_TOUCHABLE):
     *   On Android 9+ the system can still route AccessibilityService
     *   `dispatchGesture()` taps to TYPE_APPLICATION_OVERLAY windows even
     *   when those windows are flagged FLAG_NOT_TOUCHABLE. Result: the tap
     *   lands on (or is consumed by) our floating dot instead of the SEND
     *   button under it, and "auto-type line khatm hone ke baad pointer
     *   click kaam nahi krta" — exactly the bug the user reported.
     *
     *   Removing the view from WindowManager guarantees no overlay is in
     *   the way during the click. We re-add it ~[durationMs] later in the
     *   same configuration (locked/unlocked, position, etc.).
     */
    fun temporarilyHideForClick(durationMs: Long = 600L) {
        // If already hiding, cancel the pending re-add and force a fresh hide
        // cycle. Previously `return` here caused the dot to stay visible →
        // accessibility gesture landed on the dot instead of the send button.
        val v = dot ?: return
        val p = params ?: return

        hidingForClick = true
        // Remove any pending re-add callbacks before scheduling new ones
        mainHandler.removeCallbacksAndMessages(HIDE_TOKEN)

        // removeView MUST run on the main thread (WindowManager requirement)
        mainHandler.post {
            try { wm?.removeView(v) } catch (_: Throwable) {}
        }
        // Re-add after durationMs and clear the flag
        mainHandler.postAtTime({
            try {
                wm?.addView(v, p)
                applyLockedFlag()
            } catch (_: Throwable) {}
            hidingForClick = false
        }, HIDE_TOKEN, android.os.SystemClock.uptimeMillis() + durationMs)
    }

    private fun applyLockedFlag() {
        val v = dot ?: return
        val p = params ?: return
        val locked = SettingsStore.prefs(this).getBoolean(SettingsStore.KEY_POINTER_LOCKED, false)
        p.flags = currentFlags(locked)
        if (locked) {
            v.visibility = View.INVISIBLE
            v.alpha = 0f
        } else {
            v.visibility = View.VISIBLE
            v.alpha = 0.9f
        }
        runCatching { wm?.updateViewLayout(v, p) }
    }

    /** Rebuild the dot at the new size saved in SharedPreferences. */
    private fun applySize() {
        val v = dot ?: return
        val p = params ?: return
        val sizeDp = currentSizeDp()
        val sizePx = dp(sizeDp)
        dotSizePx = sizePx                     // keep cache in sync
        // Resize the WindowManager layout params
        p.width  = sizePx
        p.height = sizePx
        runCatching { wm?.updateViewLayout(v, p) }
        // Resize the inner view and its children
        v.layoutParams = v.layoutParams?.also {
            it.width  = sizePx
            it.height = sizePx
        }
        // Update text size of the inner TextView
        (v as? FrameLayout)?.getChildAt(0)?.let { child ->
            (child as? TextView)?.apply {
                textSize = sizeDp * 0.75f
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)
            }
        }
        v.requestLayout()
    }

    private fun hideOverlay() {
        val v = dot ?: return
        runCatching { wm?.removeView(v) }
        dot = null
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private val HIDE_TOKEN = Any()   // tag for pending re-add runnables

        const val ACTION_LOCK    = "com.ghosttype.pointer.LOCK"
        const val ACTION_UNLOCK  = "com.ghosttype.pointer.UNLOCK"
        const val ACTION_REFRESH = "com.ghosttype.pointer.REFRESH"
        const val ACTION_RESIZE  = "com.ghosttype.pointer.RESIZE"

        @Volatile var instance: FloatingPointerService? = null

        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, FloatingPointerService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingPointerService::class.java))
        }
        fun lock(ctx: Context) {
            val i = Intent(ctx, FloatingPointerService::class.java).setAction(ACTION_LOCK)
            ctx.startService(i)
        }
        fun unlock(ctx: Context) {
            val i = Intent(ctx, FloatingPointerService::class.java).setAction(ACTION_UNLOCK)
            ctx.startService(i)
        }
        fun resize(ctx: Context) {
            val i = Intent(ctx, FloatingPointerService::class.java).setAction(ACTION_RESIZE)
            ctx.startService(i)
        }

        fun isRunning(): Boolean = instance != null
    }
}
