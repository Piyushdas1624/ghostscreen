package com.etaegord.ghostscreen.service

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.*
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import com.etaegord.ghostscreen.data.PreferencesManager
import com.etaegord.ghostscreen.util.OpacityCurve
import kotlin.math.*

/**
 * GhostScreenOverlay v5 — True touch passthrough in box mode.
 *
 * Architecture change:
 * ─────────────────────
 * The full-screen BoxView is GONE. In box mode we now use:
 *  • MaskView (passthrough, NOT_TOUCHABLE) — draws dark mask + clear box + box border + corner dots
 *  • 4 × CornerHandleView  — tiny 56 dp windows placed exactly at each corner
 *  • 1 × OpacityDropView   — below the box centre
 *  • ExitButtonView        — always the topmost window
 *
 * This means the inside of the box has NO overlay window → all touches
 * reach the underlying app. Only the 4 corner handles and drop button consume touches.
 *
 * SPLIT mode: unchanged (two full-width strip windows with eye controllers).
 */
class GhostScreenOverlay(
    private val context: Context,
    private val preferencesManager: PreferencesManager
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    // There is no public API to check whether FLAG_BLUR_BEHIND is honoured, so
    // we ALWAYS draw a frosted-glass fallback layer (translucent tint + noise
    // shader) as the primary frost effect, and additionally request
    // FLAG_BLUR_BEHIND on Android 12+ as a bonus enhancement where supported.
    // This guarantees a visible frost on every device.

    private var screenW = 0
    private var screenH = 0

    // ── Windows ─────────────────────────────────────────────────────
    private var maskView: MaskView? = null
    private var maskTopView: MaskWindowView? = null
    private var maskBotView: MaskWindowView? = null
    private var maskLeftView: MaskWindowView? = null
    private var maskRightView: MaskWindowView? = null
    
    private var stripTopView: StripView? = null
    private var stripBotView: StripView? = null
    // Box mode: 4 corner handles + opacity drop + exit (always last)
    private val cornerViews = arrayOfNulls<CornerHandleView>(4)  // TL, TR, BL, BR
    private var opacityBtnView: OpacityDropView? = null
    private var exitBtnView: ExitButtonView? = null

    private var overlayMode = Mode.SPLIT
    private var isShowing = false
    fun isShowing(): Boolean = isShowing

    // ── Cached color parsing to eliminate per-frame allocations ─────
    private var cachedColorString: String? = null
    private var cachedColorInt: Int = Color.BLACK
    private fun getParsedColor(): Int {
        val currentColor = preferencesManager.color
        if (currentColor != cachedColorString) {
            cachedColorString = currentColor
            cachedColorInt = try { Color.parseColor(currentColor) } catch (_: Exception) { Color.BLACK }
        }
        return cachedColorInt
    }

    // ── Opacity ─────────────────────────────────────────────────────
    private var noiseShader: android.graphics.BitmapShader? = null
    private fun getNoiseShader(): android.graphics.BitmapShader {
        if (noiseShader != null) return noiseShader!!
        val w = 96
        val h = 96
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val random = java.util.Random()
        for (i in pixels.indices) {
            val v = 180 + random.nextInt(76)
            pixels[i] = android.graphics.Color.argb(255, v, v, v)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        noiseShader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        return noiseShader!!
    }

    // Opacity lives in memory while the overlay is up so the slider feels
    // instant. It is persisted to disk only on release (see persistOpacity()).
    // The displayed value is 0–100; the *real* alpha is remapped through
    // OpacityCurve so the mask is visible from the first notch.
    private var liveOpacity: Int = preferencesManager.transparency
    private fun opacity(): Int = liveOpacity
    private fun setOpacity(displayPct: Int) {
        liveOpacity = displayPct.coerceIn(0, 100)
        invalidateMasks()
    }
    private fun setOpacityLive(displayPct: Int) {
        liveOpacity = displayPct.coerceIn(0, 100)
        maskView?.invalidate()
    }
    private fun persistOpacity() {
        preferencesManager.transparency = liveOpacity
        invalidateMasks()
    }

    private fun invalidateMasks() {
        maskTopView?.invalidate()
        maskBotView?.invalidate()
        maskLeftView?.invalidate()
        maskRightView?.invalidate()
        maskView?.invalidate()
    }

    // ── SPLIT state ─────────────────────────────────────────────────
    private var stripTopY = 0f
    private var stripBotY = 0f

    // ── BOX state ───────────────────────────────────────────────────
    private var boxLeft   = 0f; private var boxTop    = 0f
    private var boxRight  = 0f; private var boxBottom = 0f

    // ── Shared edit state (used across corner handles) ───────────────
    private var boxEditActive = false
    private var cornerAlpha_  = FULL_ALPHA   // shared dimming for all corners + border

    // ── Tracked alpha-fade ticker ───────────────────────────────────
    // The fade used to be an anonymous self-reposting Runnable created fresh
    // on every animAlpha() call and never cancellable — so a hide() landing
    // mid-fade left it ticking for ~182ms, calling invalidateCorners() on
    // corner views that had just been nulled (teardown flicker / NPE risk).
    // One reusable ticker is enough: animAlpha always supersedes any in-flight
    // fade, and hide() cancels it. This preserves the manual-ticker invariant
    // (no Compose animation APIs touch the overlay).
    private var alphaTicker: Runnable? = null
    private var alphaAnimator: ValueAnimator? = null
    private var modeSwitchAnimator: ValueAnimator? = null

    private fun cancelAlphaFade() {
        alphaAnimator?.cancel()
        alphaAnimator = null
        alphaTicker?.let { mainHandler.removeCallbacks(it) }
        alphaTicker = null
    }

    // ── Exit button position ────────────────────────────────────────
    private var exitBtnX = -1f
    private var exitBtnY = -1f

    enum class Mode { SPLIT, BOX }

    companion object {
        private const val TAG         = "GhostScreenOverlay"
        private const val STRIP_H_DP  = 52f
        private const val EYE_R_DP    = 20f
        private const val IDLE_MS     = 2500L
        private const val BOX_DIM_MS  = 2500L
        private const val BOX_EDIT_MS = 5000L
        private const val DIM_ALPHA   = 38
        private const val FULL_ALPHA  = 255
        private const val MERGE_GAP_DP= 8f
        private const val EXIT_DP     = 46f
        private const val DROP_DP     = 50f
        private const val CORNER_DP   = 56f   // size of each corner handle window
        private const val MIN_BOX_DP  = 80f
        private val COLOR_ACCENT      = Color.parseColor("#7C4DFF")
        private val COLOR_ACCENT_SHADOW = Color.parseColor("#887C4DFF")
    }

    // ══════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════
    fun show() {
        if (isShowing) return
        updateScreenDimensions()
        if (exitBtnX < 0) {
            exitBtnX = screenW - EXIT_DP * density - 20 * density
            exitBtnY = screenH * 0.50f - EXIT_DP * density / 2
        }

        // Always start fresh in SPLIT mode with the user's saved viewport.
        // Resetting state here is critical: a previous BOX session would otherwise
        // leak overlayMode/stale box geometry, and a mid-drag hide would leave
        // isDraggingMasksHidden=true, both of which break the next show().
        overlayMode = Mode.SPLIT
        isDraggingMasksHidden = false
        boxEditActive = false
        cornerAlpha_ = FULL_ALPHA
        liveOpacity = preferencesManager.transparency
        mainHandler.removeCallbacks(dimRun); mainHandler.removeCallbacks(editDimRun)

        // Restore the saved viewport from prefs instead of hardcoding 0.35/0.65.
        val half = (preferencesManager.cutoutHeightPct / 2f).coerceIn(0.02f, 0.45f)
        stripTopY = ((preferencesManager.cutoutYPct - half) * screenH).coerceIn(0f, screenH.toFloat())
        stripBotY = ((preferencesManager.cutoutYPct + half) * screenH).coerceIn(0f, screenH.toFloat())

        addMask()
        addMaskWindows()
        addSplitWindows()
        addExitButton()
        isShowing = true
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 50, 30), -1))
    }

    fun hide() {
        if (!isShowing) return

        // Persist the current SPLIT viewport so it survives across show/hide cycles.
        // (Only meaningful in SPLIT mode; BOX positions are session-only by design.)
        if (overlayMode == Mode.SPLIT && stripBotY > stripTopY) {
            val center = ((stripTopY + stripBotY) / 2f) / screenH.toFloat()
            val height = (stripBotY - stripTopY) / screenH.toFloat()
            preferencesManager.cutoutYPct = center.coerceIn(0f, 1f)
            preferencesManager.cutoutHeightPct = height.coerceIn(0.05f, 0.9f)
        }

        removeSplitWindows()
        removeBoxWindows()
        removeMaskWindows()
        maskView?.let { safeRemove(it) }; maskView = null
        exitBtnView?.let { safeRemove(it) }; exitBtnView = null

        // Reset transient edit state so the next show() starts clean.
        isDraggingMasksHidden = false
        boxEditActive = false
        mainHandler.removeCallbacks(dimRun); mainHandler.removeCallbacks(editDimRun)
        modeSwitchAnimator?.cancel()
        modeSwitchAnimator = null
        cancelAlphaFade()   // stop any in-flight fade from mutating the views we just tore down
        isShowing = false
    }

    fun onConfigurationChanged() {
        if (!isShowing) return
        val oldW = screenW
        val oldH = screenH
        updateScreenDimensions()
        if (oldW == screenW && oldH == screenH) return

        // Exit button clamped to new screen bounds
        val sz = (EXIT_DP * density).toInt()
        exitBtnX = exitBtnX.coerceIn(0f, (screenW - sz).toFloat())
        exitBtnY = exitBtnY.coerceIn(0f, (screenH - sz).toFloat())
        exitBtnView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            p.x = exitBtnX.toInt()
            p.y = exitBtnY.toInt()
            safeUpdate(it)
        }

        when (overlayMode) {
            Mode.SPLIT -> {
                val half = (preferencesManager.cutoutHeightPct / 2f).coerceIn(0.02f, 0.45f)
                stripTopY = ((preferencesManager.cutoutYPct - half) * screenH).coerceIn(0f, screenH.toFloat())
                stripBotY = ((preferencesManager.cutoutYPct + half) * screenH).coerceIn(0f, screenH.toFloat())
                updateStripLayouts()
            }
            Mode.BOX -> {
                val m = MIN_BOX_DP * density
                val bw = (boxRight - boxLeft).coerceIn(m, screenW.toFloat())
                val bh = (boxBottom - boxTop).coerceIn(m, screenH.toFloat())
                boxLeft = boxLeft.coerceIn(0f, (screenW - bw).coerceAtLeast(0f))
                boxTop = boxTop.coerceIn(0f, (screenH - bh).coerceAtLeast(0f))
                boxRight = boxLeft + bw
                boxBottom = boxTop + bh
                updateCornerPositions()
            }
        }
        maskView?.invalidate()
    }

    // ══════════════════════════════════════════════════════════════════
    // Dimensions
    // ══════════════════════════════════════════════════════════════════
    private fun updateScreenDimensions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            @Suppress("DEPRECATION")
            val m = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            screenW = m.widthPixels; screenH = m.heightPixels
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Window param factories
    // ══════════════════════════════════════════════════════════════════
    private fun passiveParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).also {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun maskParams(x: Int, y: Int, w: Int, h: Int) = WindowManager.LayoutParams(
        w.coerceAtLeast(1), h.coerceAtLeast(1),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).also {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        it.gravity = Gravity.TOP or Gravity.START
        it.x = x
        it.y = y
        // Request the system frost on Android 12+ as a bonus enhancement. Where
        // the device honours it, the content behind is really blurred; where it
        // doesn't, the frosted-glass shader drawn in MaskWindowView carries the
        // effect on its own.
        if (preferencesManager.isBlurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            it.flags = it.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            it.blurBehindRadius = (preferencesManager.blurRadius * 3).coerceIn(1, 100)
        }
    }

    private fun activeParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).also {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        it.gravity = Gravity.TOP or Gravity.START
    }

    private fun safeAdd(v: View, p: WindowManager.LayoutParams) = try { windowManager.addView(v, p) } catch (e: Exception) { Log.e(TAG, "addView", e) }
    private fun safeRemove(v: View) = try { windowManager.removeView(v) } catch (_: Exception) {}
    private fun safeUpdate(v: View) = try { windowManager.updateViewLayout(v, v.layoutParams) } catch (_: Exception) {}

    // ══════════════════════════════════════════════════════════════════
    // Layer management
    // ══════════════════════════════════════════════════════════════════
    private fun addMask() {
        maskView = MaskView(context); safeAdd(maskView!!, passiveParams())
    }

    private fun addMaskWindows() {
        if (overlayMode == Mode.SPLIT) {
            val th = stripTopY.toInt()
            maskTopView = MaskWindowView(context).also {
                safeAdd(it, maskParams(0, 0, screenW, th))
            }
            val bh = (screenH - stripBotY).toInt()
            maskBotView = MaskWindowView(context).also {
                safeAdd(it, maskParams(0, stripBotY.toInt(), screenW, bh))
            }
        } else {
            val th = boxTop.toInt()
            maskTopView = MaskWindowView(context).also {
                safeAdd(it, maskParams(0, 0, screenW, th))
            }
            val bh = (screenH - boxBottom).toInt()
            maskBotView = MaskWindowView(context).also {
                safeAdd(it, maskParams(0, boxBottom.toInt(), screenW, bh))
            }
            val lh = (boxBottom - boxTop).toInt()
            val lw = boxLeft.toInt()
            maskLeftView = MaskWindowView(context).also {
                safeAdd(it, maskParams(0, boxTop.toInt(), lw, lh))
            }
            val rw = (screenW - boxRight).toInt()
            maskRightView = MaskWindowView(context).also {
                safeAdd(it, maskParams(boxRight.toInt(), boxTop.toInt(), rw, lh))
            }
        }
    }

    private fun removeMaskWindows() {
        maskTopView?.let { safeRemove(it) }; maskTopView = null
        maskBotView?.let { safeRemove(it) }; maskBotView = null
        maskLeftView?.let { safeRemove(it) }; maskLeftView = null
        maskRightView?.let { safeRemove(it) }; maskRightView = null
    }

    private fun addSplitWindows() {
        val sh = (STRIP_H_DP * density).toInt()
        stripTopView = StripView(context, true).also {
            safeAdd(it, activeParams(screenW, sh).apply { x = 0; y = (stripTopY - sh / 2).toInt() })
        }
        stripBotView = StripView(context, false).also {
            safeAdd(it, activeParams(screenW, sh).apply { x = 0; y = (stripBotY - sh / 2).toInt() })
        }
    }

    private fun removeSplitWindows() {
        stripTopView?.let { safeRemove(it) }; stripTopView = null
        stripBotView?.let { safeRemove(it) }; stripBotView = null
    }

    /** Add 4 small corner handle windows + opacity drop button */
    private fun addBoxWindows() {
        boxEditActive = false; cornerAlpha_ = FULL_ALPHA
        val csz = (CORNER_DP * density).toInt()
        // Corners: 0=TL, 1=TR, 2=BL, 3=BR
        fun addCorner(i: Int, cx: Float, cy: Float) {
            cornerViews[i] = CornerHandleView(context, i).also {
                safeAdd(it, activeParams(csz, csz).apply { x = cx.toInt(); y = cy.toInt() })
            }
        }
        addCorner(0, boxLeft, boxTop)
        addCorner(1, boxRight - csz, boxTop)
        addCorner(2, boxLeft, boxBottom - csz)
        addCorner(3, boxRight - csz, boxBottom - csz)

        val dropSz = (DROP_DP * density).toInt()
        val extraH  = (28 * density).toInt()  // room for % label above button
        opacityBtnView = OpacityDropView(context).also {
            safeAdd(it, activeParams(dropSz, dropSz + extraH).apply {
                x = ((boxLeft + boxRight) / 2 - dropSz / 2).toInt().coerceIn(0, screenW - dropSz)
                y = (boxBottom + 10 * density).toInt().coerceAtMost(screenH - dropSz - extraH)
            })
        }
        scheduleDim(BOX_DIM_MS)
    }

    private fun removeBoxWindows() {
        mainHandler.removeCallbacks(dimRun); mainHandler.removeCallbacks(editDimRun)
        for (i in 0..3) { cornerViews[i]?.let { safeRemove(it) }; cornerViews[i] = null }
        opacityBtnView?.let { safeRemove(it) }; opacityBtnView = null
    }

    private fun addExitButton() {
        val sz = (EXIT_DP * density).toInt()
        exitBtnView = ExitButtonView(context).also {
            safeAdd(it, activeParams(sz, sz).apply { x = exitBtnX.toInt(); y = exitBtnY.toInt() })
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Position updaters
    // ══════════════════════════════════════════════════════════════════
    private fun updateStripLayouts() {
        val sh = (STRIP_H_DP * density).toInt()
        stripTopView?.let { (it.layoutParams as WindowManager.LayoutParams).y = (stripTopY - sh / 2).toInt(); safeUpdate(it) }
        stripBotView?.let { (it.layoutParams as WindowManager.LayoutParams).y = (stripBotY - sh / 2).toInt(); safeUpdate(it) }
        
        maskTopView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            p.height = stripTopY.toInt().coerceAtLeast(1)
            safeUpdate(it)
            it.invalidate()
        }
        maskBotView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            p.y = stripBotY.toInt()
            p.height = (screenH - stripBotY).toInt().coerceAtLeast(1)
            safeUpdate(it)
            it.invalidate()
        }
        maskView?.invalidate()
    }

    private fun updateCornerPositions() {
        val csz = (CORNER_DP * density).toInt()
        fun updateCorner(i: Int, cx: Float, cy: Float) {
            cornerViews[i]?.let {
                val p = it.layoutParams as WindowManager.LayoutParams
                p.x = cx.toInt().coerceIn(0, screenW - csz)
                p.y = cy.toInt().coerceIn(0, screenH - csz)
                safeUpdate(it); it.invalidate()
            }
        }
        updateCorner(0, boxLeft, boxTop)
        updateCorner(1, boxRight - csz, boxTop)
        updateCorner(2, boxLeft, boxBottom - csz)
        updateCorner(3, boxRight - csz, boxBottom - csz)

        opacityBtnView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            val dropSz = it.width
            p.x = ((boxLeft + boxRight) / 2 - dropSz / 2).toInt().coerceIn(0, screenW - dropSz)
            p.y = (boxBottom + 10 * density).toInt().coerceAtMost(screenH - it.height)
            safeUpdate(it)
        }
        
        maskTopView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            p.height = boxTop.toInt().coerceAtLeast(1)
            safeUpdate(it)
            it.invalidate()
        }
        maskBotView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            p.y = boxBottom.toInt()
            p.height = (screenH - boxBottom).toInt().coerceAtLeast(1)
            safeUpdate(it)
            it.invalidate()
        }
        maskLeftView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            p.y = boxTop.toInt()
            p.width = boxLeft.toInt().coerceAtLeast(1)
            p.height = (boxBottom - boxTop).toInt().coerceAtLeast(1)
            safeUpdate(it)
            it.invalidate()
        }
        maskRightView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            p.x = boxRight.toInt()
            p.y = boxTop.toInt()
            p.width = (screenW - boxRight).toInt().coerceAtLeast(1)
            p.height = (boxBottom - boxTop).toInt().coerceAtLeast(1)
            safeUpdate(it)
            it.invalidate()
        }
        maskView?.invalidate()
    }

    /**
     * Lightweight corner move used during an active box drag: repositions ONLY
     * the 4 corner handle windows and the full-screen MaskView border/dots.
     * The 4 mask windows are expensive cross-process relayouts, so they are
     * hidden for the duration of the drag (setMaskWindowsVisible) and rebuilt
     * once via updateCornerPositions() on ACTION_UP. This is what removes the
     * corner-drag lag.
     */
    private fun updateCornerHandlesOnly() {
        val csz = (CORNER_DP * density).toInt()
        fun updateHandle(i: Int, cx: Float, cy: Float) {
            cornerViews[i]?.let {
                val p = it.layoutParams as WindowManager.LayoutParams
                p.x = cx.toInt().coerceIn(0, screenW - csz)
                p.y = cy.toInt().coerceIn(0, screenH - csz)
                safeUpdate(it)
            }
        }
        updateHandle(0, boxLeft, boxTop)
        updateHandle(1, boxRight - csz, boxTop)
        updateHandle(2, boxLeft, boxBottom - csz)
        updateHandle(3, boxRight - csz, boxBottom - csz)
        maskView?.invalidate()
    }

    private fun updateExitPos() {
        exitBtnView?.let { val p = it.layoutParams as WindowManager.LayoutParams; p.x = exitBtnX.toInt(); p.y = exitBtnY.toInt(); safeUpdate(it) }
    }

    // ══════════════════════════════════════════════════════════════════
    // Mode transitions
    // ══════════════════════════════════════════════════════════════════
    private fun enterBoxMode() {
        if (overlayMode == Mode.BOX) return
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 20, 40, 80), -1))
        isDraggingMasksHidden = false // Bug 8: reset before rebuilding windows
        overlayMode = Mode.BOX

        val startLeft = 0f
        val startRight = screenW.toFloat()
        val targetLeft = screenW * 0.08f
        val targetRight = screenW * 0.92f

        boxTop = stripTopY
        boxBottom = stripBotY
        boxLeft = startLeft
        boxRight = startRight

        removeSplitWindows()
        removeMaskWindows()
        addMaskWindows()
        addBoxWindows()
        exitBtnView?.let { safeRemove(it) }; exitBtnView = null
        addExitButton()
        maskView?.invalidate()

        modeSwitchAnimator?.cancel()
        modeSwitchAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                PathInterpolator(0.18f, 0.89f, 0.32f, 1.25f)
            } else {
                OvershootInterpolator(1.2f)
            }
            addUpdateListener { va ->
                if (!isShowing || overlayMode != Mode.BOX) {
                    va.cancel()
                    return@addUpdateListener
                }
                val frac = va.animatedFraction
                boxLeft = startLeft + (targetLeft - startLeft) * frac
                boxRight = startRight + (targetRight - startRight) * frac
                updateCornerPositions()
                maskView?.invalidate()
            }
        }
        modeSwitchAnimator?.start()
    }

    private fun enterSplitMode() {
        if (overlayMode == Mode.SPLIT) return
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 20, 40, 20), -1))
        isDraggingMasksHidden = false // Bug 8: reset before rebuilding windows

        val startLeft = boxLeft
        val startRight = boxRight
        val targetLeft = 0f
        val targetRight = screenW.toFloat()

        modeSwitchAnimator?.cancel()
        modeSwitchAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                PathInterpolator(0.2f, 0f, 0f, 1f)
            } else {
                FastOutSlowInInterpolator()
            }
            addUpdateListener { va ->
                if (!isShowing || overlayMode != Mode.BOX) {
                    va.cancel()
                    return@addUpdateListener
                }
                val frac = va.animatedFraction
                boxLeft = startLeft + (targetLeft - startLeft) * frac
                boxRight = startRight + (targetRight - startRight) * frac
                updateCornerPositions()
                maskView?.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (isShowing && overlayMode == Mode.BOX) {
                        overlayMode = Mode.SPLIT
                        stripTopY = boxTop
                        stripBotY = (boxBottom).coerceAtLeast(boxTop + MERGE_GAP_DP * density * 4)
                        removeBoxWindows()
                        removeMaskWindows()
                        addMaskWindows()
                        addSplitWindows()
                        exitBtnView?.let { safeRemove(it) }; exitBtnView = null
                        addExitButton()
                        maskView?.invalidate()
                    }
                }
            })
        }
        modeSwitchAnimator?.start()
    }

    // ══════════════════════════════════════════════════════════════════
    // Shared corner dimming (controlled from any corner)
    // ══════════════════════════════════════════════════════════════════
    private val dimRun = Runnable {
        animAlpha({ cornerAlpha_ }, { cornerAlpha_ = it; invalidateCorners() }, DIM_ALPHA)
        boxEditActive = false
    }
    private val editDimRun = Runnable {
        animAlpha({ cornerAlpha_ }, { cornerAlpha_ = it; invalidateCorners() }, DIM_ALPHA)
        boxEditActive = false
    }

    fun scheduleDim(ms: Long) {
        mainHandler.removeCallbacks(dimRun); mainHandler.removeCallbacks(editDimRun)
        mainHandler.postDelayed(dimRun, ms)
    }
    fun scheduleEditDim() {
        mainHandler.removeCallbacks(editDimRun); mainHandler.postDelayed(editDimRun, BOX_EDIT_MS)
    }
    fun wakeCorners() {
        mainHandler.removeCallbacks(dimRun); mainHandler.removeCallbacks(editDimRun)
        animAlpha({ cornerAlpha_ }, { cornerAlpha_ = it; invalidateCorners() }, FULL_ALPHA)
    }

    private fun invalidateCorners() { for (i in 0..3) cornerViews[i]?.invalidate() }

    // Box resize/move — called by corner handles
    fun applyBoxDelta(cornerIdx: Int, dx: Float, dy: Float) {
        val m = MIN_BOX_DP * density
        when (cornerIdx) {
            0 -> { boxLeft = min(boxLeft + dx, boxRight - m); boxTop = min(boxTop + dy, boxBottom - m) }   // TL
            1 -> { boxRight = max(boxRight + dx, boxLeft + m); boxTop = min(boxTop + dy, boxBottom - m) }  // TR
            2 -> { boxLeft = min(boxLeft + dx, boxRight - m); boxBottom = max(boxBottom + dy, boxTop + m) }// BL
            3 -> { boxRight = max(boxRight + dx, boxLeft + m); boxBottom = max(boxBottom + dy, boxTop + m)}// BR
        }
        val bw = (boxRight - boxLeft).coerceAtLeast(m)
        val bh = (boxBottom - boxTop).coerceAtLeast(m)
        boxLeft = boxLeft.coerceIn(0f, (screenW - bw).coerceAtLeast(0f))
        boxTop  = boxTop.coerceIn(0f, (screenH - bh).coerceAtLeast(0f))
        boxRight = boxLeft + bw; boxBottom = boxTop + bh
        updateCornerPositions()
    }

    // Box drag — called by corner handles during active drag (cheap: updates handles + invalidates passive mask outline)
    fun dragBoxCorner(cornerIdx: Int, dx: Float, dy: Float) {
        val m = MIN_BOX_DP * density
        when (cornerIdx) {
            0 -> { boxLeft = min(boxLeft + dx, boxRight - m); boxTop = min(boxTop + dy, boxBottom - m) }   // TL
            1 -> { boxRight = max(boxRight + dx, boxLeft + m); boxTop = min(boxTop + dy, boxBottom - m) }  // TR
            2 -> { boxLeft = min(boxLeft + dx, boxRight - m); boxBottom = max(boxBottom + dy, boxTop + m) }// BL
            3 -> { boxRight = max(boxRight + dx, boxLeft + m); boxBottom = max(boxBottom + dy, boxTop + m)}// BR
        }
        val bw = (boxRight - boxLeft).coerceAtLeast(m)
        val bh = (boxBottom - boxTop).coerceAtLeast(m)
        boxLeft = boxLeft.coerceIn(0f, (screenW - bw).coerceAtLeast(0f))
        boxTop  = boxTop.coerceIn(0f, (screenH - bh).coerceAtLeast(0f))
        boxRight = boxLeft + bw; boxBottom = boxTop + bh
        updateCornerHandlesOnly()
        maskView?.invalidate()
    }

    // ══════════════════════════════════════════════════════════════════
    // Haptics
    // ══════════════════════════════════════════════════════════════════
    private fun vibrate(effect: VibrationEffect) {
        if (!preferencesManager.isVibrateOnActivationEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.vibrate(effect)
            else { @Suppress("DEPRECATION") val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator; if (v.hasVibrator()) v.vibrate(effect) }
        } catch (e: Exception) { Log.w(TAG, "Vibrate: ${e.message}") }
    }
    fun tick() = vibrate(VibrationEffect.createOneShot(16, 80))
    fun bump() = vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))

    // ══════════════════════════════════════════════════════════════════
    // Alpha animation helper
    // ══════════════════════════════════════════════════════════════════
    private fun animAlpha(get: () -> Int, set: (Int) -> Unit, target: Int, durationMs: Long = 260L) {
        cancelAlphaFade()
        val start = get()
        if (start == target) return
        val animator = ValueAnimator.ofInt(start, target).apply {
            duration = durationMs
            interpolator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                PathInterpolator(0.2f, 0f, 0f, 1f)
            } else {
                FastOutSlowInInterpolator()
            }
            addUpdateListener { va ->
                if (!isShowing) {
                    va.cancel()
                    return@addUpdateListener
                }
                set(va.animatedValue as Int)
            }
        }
        alphaAnimator = animator
        animator.start()
    }

    // ══════════════════════════════════════════════════════════════════
    // Drag Optimization
    // ══════════════════════════════════════════════════════════════════
    private var isDraggingMasksHidden = false

    private fun setMaskWindowsVisible(visible: Boolean) {
        if (isDraggingMasksHidden == !visible) return
        isDraggingMasksHidden = !visible
        if (visible) {
            if (overlayMode == Mode.SPLIT) updateStripLayouts() else updateCornerPositions()
            maskTopView?.visibility = View.VISIBLE
            maskBotView?.visibility = View.VISIBLE
            maskLeftView?.visibility = View.VISIBLE
            maskRightView?.visibility = View.VISIBLE
        } else {
            maskTopView?.visibility = View.GONE
            maskBotView?.visibility = View.GONE
            maskLeftView?.visibility = View.GONE
            maskRightView?.visibility = View.GONE
        }
        maskView?.invalidate()
    }

    // ══════════════════════════════════════════════════════════════════
    // VIEW 1 — Fullscreen passive mask
    // Draws: dark mask + clear window + box border/corners (in box mode)
    // ══════════════════════════════════════════════════════════════════
    private inner class MaskWindowView(ctx: Context) : View(ctx) {
        private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        // Blur A: pre-allocate noise Paint to avoid GC churn in onDraw
        private val noiseP = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val isBlur = preferencesManager.isBlurEnabled
            val realPct = OpacityCurve.displayToRealPct(opacity())
            val w = width.toFloat(); val h = height.toFloat()
            val rawColor = getParsedColor()

            if (isBlur) {
                val tintAlpha = (realPct / 100f * 150).toInt().coerceIn(0, 180)
                fillP.color = rawColor; fillP.alpha = tintAlpha
                canvas.drawRect(0f, 0f, w, h, fillP)

                noiseP.shader = getNoiseShader()
                noiseP.alpha = (realPct / 100f * 120).toInt().coerceIn(0, 150)
                canvas.drawRect(0f, 0f, w, h, noiseP)
                return
            }

            // Solid color mode.
            fillP.color = rawColor
            fillP.alpha = OpacityCurve.realPctToAlpha(realPct)
            canvas.drawRect(0f, 0f, w, h, fillP)
        }
    }

    private inner class MaskView(ctx: Context) : View(ctx) {
        private val edgeP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
        private val dotP   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        init { setLayerType(LAYER_TYPE_HARDWARE, null) }

        // Blur B: pre-allocate noise Paint for drag-fallback frost rendering
        private val noiseP = Paint(Paint.ANTI_ALIAS_FLAG)

        // Pre-allocated RectFs to avoid GC churn on every drag frame
        private val rTop = RectF()
        private val rBottom = RectF()
        private val rLeft = RectF()
        private val rRight = RectF()

        override fun onDraw(canvas: Canvas) {
            if (isDraggingMasksHidden) {
                val isBlur = preferencesManager.isBlurEnabled
                val realPct = OpacityCurve.displayToRealPct(opacity())
                // Mirror MaskWindowView so dragging stays visually consistent.
                val alpha = if (isBlur)
                    (realPct / 100f * 150).toInt().coerceIn(0, 180)
                else
                    OpacityCurve.realPctToAlpha(realPct)
                val rawColor = getParsedColor()
                fillP.color = rawColor
                fillP.alpha = alpha

                val w = width.toFloat()
                val h = height.toFloat()

                if (overlayMode == Mode.SPLIT) {
                    rTop.set(0f, 0f, w, stripTopY)
                    rBottom.set(0f, stripBotY, w, h)
                    canvas.drawRect(rTop, fillP)
                    canvas.drawRect(rBottom, fillP)

                    if (isBlur) {
                        noiseP.shader = getNoiseShader()
                        noiseP.alpha = (realPct / 100f * 120).toInt().coerceIn(0, 150)
                        canvas.drawRect(rTop, noiseP)
                        canvas.drawRect(rBottom, noiseP)
                    }
                } else {
                    rTop.set(0f, 0f, w, boxTop)
                    rBottom.set(0f, boxBottom, w, h)
                    rLeft.set(0f, boxTop, boxLeft, boxBottom)
                    rRight.set(boxRight, boxTop, w, boxBottom)
                    canvas.drawRect(rTop, fillP)
                    canvas.drawRect(rBottom, fillP)
                    canvas.drawRect(rLeft, fillP)
                    canvas.drawRect(rRight, fillP)

                    if (isBlur) {
                        noiseP.shader = getNoiseShader()
                        noiseP.alpha = (realPct / 100f * 120).toInt().coerceIn(0, 150)
                        canvas.drawRect(rTop, noiseP)
                        canvas.drawRect(rBottom, noiseP)
                        canvas.drawRect(rLeft, noiseP)
                        canvas.drawRect(rRight, noiseP)
                    }
                }
            }

            // Box mode: draw border + corner dots on top (MaskView is NOT_TOUCHABLE so fine to draw here)
            if (overlayMode == Mode.BOX) {
                edgeP.color = COLOR_ACCENT; edgeP.alpha = cornerAlpha_
                edgeP.setShadowLayer(5 * density, 0f, 0f, COLOR_ACCENT_SHADOW)
                canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, edgeP)
                edgeP.clearShadowLayer()

                dotP.color = COLOR_ACCENT; dotP.alpha = cornerAlpha_
                val dotR = 12 * density
                canvas.drawCircle(boxLeft, boxTop, dotR, dotP)
                canvas.drawCircle(boxRight, boxTop, dotR, dotP)
                canvas.drawCircle(boxLeft, boxBottom, dotR, dotP)
                canvas.drawCircle(boxRight, boxBottom, dotR, dotP)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // VIEW 2 — Full-width strip controller (SPLIT mode)
    // ══════════════════════════════════════════════════════════════════
    @SuppressLint("ViewConstructor")
    private inner class StripView(ctx: Context, private val isTop: Boolean) : View(ctx) {
        private var alpha_  = FULL_ALPHA
        private var isDragging = false; private var wasMerged = false
        private var pressStartX = 0f; private var pressStartRawY = 0f; private var lastRawY = 0f
        private var lastHapticNotch = -1

        private val trackBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val trackFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#7C4DFF") }
        private val eyeBg   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val eyeStr  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; strokeCap = Paint.Cap.ROUND }
        private val eyeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val border_ = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
        private val arrow_  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * density; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
        private val tPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }

        private val idleRun = Runnable { animAlpha({ alpha_ }, { alpha_ = it; invalidate() }, DIM_ALPHA) }
        private val longRun = Runnable { bump(); enterBoxMode() }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null); scheduleIdle() }

        private fun scheduleIdle() { mainHandler.removeCallbacks(idleRun); mainHandler.postDelayed(idleRun, IDLE_MS) }
        private fun wakeUp() { mainHandler.removeCallbacks(idleRun); animAlpha({ alpha_ }, { alpha_ = it; invalidate() }, FULL_ALPHA); scheduleIdle() }

        // Bug fix (handler leak): posted runnables hold an implicit reference to this
        // inner-class View (and through it the outer overlay + service Context). Clear
        // them when the view is detached so it can be GC'd instead of lingering until
        // the delay expires / firing on a detached view.
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            mainHandler.removeCallbacks(idleRun)
            mainHandler.removeCallbacks(longRun)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2f
            val merged = (stripBotY - stripTopY) < MERGE_GAP_DP * density
            val th = 3f * density
            val gap = 16f * density
            
            trackBg.color = Color.WHITE; trackBg.alpha = (alpha_ * 0.18f).toInt()
            canvas.drawRoundRect(gap, cy - th / 2, w - gap, cy + th / 2, th / 2, th / 2, trackBg)
            
            val minX = gap + EYE_R_DP * density
            val maxX = w - gap - EYE_R_DP * density
            val range = maxX - minX
            val eyeX = minX + (opacity() / 100f) * range

            trackFg.alpha = alpha_
            canvas.drawRoundRect(gap, cy - th / 2, eyeX, cy + th / 2, th / 2, th / 2, trackFg)
            
            val aArr = 7 * density
            arrow_.alpha = (alpha_ * 0.30f).toInt()
            canvas.drawLine(14 * density, cy, 14 * density + aArr, cy - aArr, arrow_)
            canvas.drawLine(14 * density, cy, 14 * density + aArr, cy + aArr, arrow_)
            val rx = w - 14 * density
            canvas.drawLine(rx, cy, rx - aArr, cy - aArr, arrow_)
            canvas.drawLine(rx, cy, rx - aArr, cy + aArr, arrow_)
            
            drawEye(canvas, eyeX, cy, merged)
        }

        private fun drawEye(canvas: Canvas, cx: Float, cy: Float, merged: Boolean) {
            val r = EYE_R_DP * density
            val gP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = if (merged) Color.parseColor("#FF6D00") else Color.parseColor("#7C4DFF")
                alpha = (alpha_ * 0.20f).toInt()
                maskFilter = BlurMaskFilter(r * 0.9f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(cx, cy, r * 1.15f, gP)
            eyeBg.color = if (merged) Color.parseColor("#1A0D00") else Color.parseColor("#1A1A2E")
            eyeBg.alpha = (alpha_ * 0.90f).toInt()
            canvas.drawRoundRect(cx - r, cy - r * 0.62f, cx + r, cy + r * 0.62f, r * 0.5f, r * 0.5f, eyeBg)
            border_.color = if (merged) Color.parseColor("#99FF6D00") else Color.parseColor("#55FFFFFF")
            border_.alpha = alpha_
            canvas.drawRoundRect(cx - r, cy - r * 0.62f, cx + r, cy + r * 0.62f, r * 0.5f, r * 0.5f, border_)
            if (merged) {
                eyeStr.color = Color.parseColor("#FF6D00"); eyeStr.alpha = alpha_
                canvas.drawLine(cx, cy - r * 0.38f, cx, cy + r * 0.38f, eyeStr)
                val a2 = r * 0.22f
                canvas.drawLine(cx - a2, cy - r * 0.18f, cx, cy - r * 0.38f, eyeStr)
                canvas.drawLine(cx + a2, cy - r * 0.18f, cx, cy - r * 0.38f, eyeStr)
                canvas.drawLine(cx - a2, cy + r * 0.18f, cx, cy + r * 0.38f, eyeStr)
                canvas.drawLine(cx + a2, cy + r * 0.18f, cx, cy + r * 0.38f, eyeStr)
            } else {
                eyeStr.color = Color.WHITE; eyeStr.alpha = alpha_
                val path = Path().apply {
                    moveTo(cx - r * 0.65f, cy)
                    quadTo(cx, cy - r * 0.42f, cx + r * 0.65f, cy)
                    quadTo(cx, cy + r * 0.42f, cx - r * 0.65f, cy)
                }
                canvas.drawPath(path, eyeStr)
                eyeFill.color = Color.WHITE; eyeFill.alpha = alpha_
                canvas.drawCircle(cx, cy, r * 0.20f, eyeFill)
                // Opacity % — drawn below the pill entirely so it doesn't overlap the eye during horizontal dragging
                tPaint.textSize = 10f * density; tPaint.color = Color.WHITE
                tPaint.alpha = (alpha_ * 0.90f).toInt()
                // Text baseline is below the pill: pill ends at cy + r * 0.62f.
                // 5dp gap below the pill before the text starts.
                canvas.drawText("${opacity()}%", cx, cy + r * 0.62f + 5 * density + tPaint.textSize * 0.8f, tPaint)
                // Chevrons outside pill (above/below)
                val chP = Paint(arrow_).apply { alpha = (alpha_ * 0.35f).toInt() }
                val cs = 5 * density; val offY = r * 0.72f + 4 * density
                canvas.drawLine(cx - cs, cy - offY - cs, cx, cy - offY, chP); canvas.drawLine(cx, cy - offY, cx + cs, cy - offY - cs, chP)
                canvas.drawLine(cx - cs, cy + offY + cs, cx, cy + offY, chP); canvas.drawLine(cx, cy + offY, cx + cs, cy + offY + cs, chP)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    wakeUp(); tick()
                    pressStartX = e.x; pressStartRawY = e.rawY; lastRawY = e.rawY
                    isDragging = false; wasMerged = (stripBotY - stripTopY) < MERGE_GAP_DP * density
                    lastHapticNotch = opacity() / 10
                    if (!wasMerged) mainHandler.postDelayed(longRun, 520L)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(e.x - pressStartX); val dy = abs(e.rawY - pressStartRawY)
                    if (!isDragging && (dx > 5 * density || dy > 5 * density)) { 
                        isDragging = true; mainHandler.removeCallbacks(longRun)
                        if (dy > dx) setMaskWindowsVisible(false) 
                    }
                    if (isDragging) {
                        if (dy > dx) {
                            if (wasMerged) { stripTopY = screenH * 0.35f; stripBotY = screenH * 0.65f; wasMerged = false }
                            else {
                                val sh = height.toFloat(); val mg = MERGE_GAP_DP * density
                                val dyRaw = e.rawY - lastRawY
                                if (isTop) stripTopY = (stripTopY + dyRaw).coerceIn(sh / 2, stripBotY - mg)
                                else stripBotY = (stripBotY + dyRaw).coerceIn(stripTopY + mg, screenH - sh / 2)
                                maskView?.invalidate()
                                val ypos = if (isTop) stripTopY else stripBotY
                                (layoutParams as WindowManager.LayoutParams).y = (ypos - sh / 2).toInt()
                                safeUpdate(this@StripView)
                            }
                        } else {
                            val gap = 16f * density
                            val minX = gap + EYE_R_DP * density
                            val maxX = width - gap - EYE_R_DP * density
                            val range = maxX - minX
                            if (range > 0) {
                                // Live in-memory update only (no disk write per move).
                                val newOp = (((e.x - minX) / range) * 100).toInt().coerceIn(0, 100)
                                val notch = newOp / 10
                                if (notch != lastHapticNotch) {
                                    lastHapticNotch = notch
                                    tick()
                                }
                                setOpacityLive(newOp)
                            }
                            stripTopView?.invalidate(); stripBotView?.invalidate()
                        }
                        lastRawY = e.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        setMaskWindowsVisible(true)
                        // Persist viewport + opacity once, on release.
                        if (stripBotY > stripTopY && screenH > 0) {
                            preferencesManager.cutoutYPct = ((stripTopY + stripBotY) / 2f / screenH).coerceIn(0f, 1f)
                            preferencesManager.cutoutHeightPct = ((stripBotY - stripTopY) / screenH).coerceIn(0.05f, 0.9f)
                        }
                        persistOpacity()
                    }
                    mainHandler.removeCallbacks(longRun); scheduleIdle(); return true
                }
            }
            return false
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // VIEW 3 — Corner handle (BOX mode) — tiny 56dp window per corner
    //          TL=0, TR=1, BL=2, BR=3
    // ══════════════════════════════════════════════════════════════════
    @SuppressLint("ViewConstructor")
    private inner class CornerHandleView(ctx: Context, private val cornerIdx: Int) : View(ctx) {
        private var lx = 0f; private var ly = 0f; private var dragging = false

        private val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#7C4DFF") }
        private val xP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * density; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }

        private val hintRun = Runnable {
            if (!boxEditActive) {
                boxEditActive = true; bump(); invalidateCorners()
                scheduleEditDim()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            mainHandler.removeCallbacks(hintRun)
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            bgP.alpha = cornerAlpha_
            bgP.setShadowLayer(6 * density, 0f, 0f, Color.parseColor("#557C4DFF"))
            canvas.drawCircle(r, r, r * 0.86f, bgP)
            bgP.clearShadowLayer()
            val s = r * 0.38f
            xP.alpha = cornerAlpha_
            canvas.drawLine(r - s, r, r + s, r, xP)
            canvas.drawLine(r, r - s, r, r + s, xP)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    wakeCorners(); tick(); lx = e.rawX; ly = e.rawY; dragging = false
                    mainHandler.postDelayed(hintRun, 380)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lx; val dy = e.rawY - ly
                    if (!dragging && (abs(dx) > 5 * density || abs(dy) > 5 * density)) {
                        dragging = true; mainHandler.removeCallbacks(hintRun)
                        if (!boxEditActive) { boxEditActive = true; bump() }
                        // Hide the 4 mask windows for the drag so we don't pay
                        // 4 cross-process relayouts per pixel — the full-screen
                        // MaskView draws the border so the outline stays visible.
                        setMaskWindowsVisible(false)
                    }
                    if (dragging && boxEditActive) {
                        dragBoxCorner(cornerIdx, dx, dy)
                        scheduleEditDim()
                    }
                    lx = e.rawX; ly = e.rawY; return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(hintRun)
                    if (dragging) {
                        // Drag is over: restore mask windows and do the full
                        // (expensive) relayout once.
                        updateCornerPositions()
                        setMaskWindowsVisible(true)
                    }
                    return true
                }
            }
            return false
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // VIEW 4 — Opacity drop button (BOX mode)
    // ══════════════════════════════════════════════════════════════════
    @SuppressLint("ViewConstructor")
    private inner class OpacityDropView(ctx: Context) : View(ctx) {
        private var btnAlpha = FULL_ALPHA
        private var startX = 0f; private var startOp = 0; private var dragging = false
        private var showLabel = false
        private var lastHapticNotch = -1

        private val bgP    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dropP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * density; color = Color.WHITE }
        private val lblBg  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#CC1A1A2E") }
        private val lblP   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f * density; color = Color.WHITE; textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

        private val idleRun  = Runnable { animAlpha({ btnAlpha }, { btnAlpha = it; invalidate() }, DIM_ALPHA) }
        private val hideLbl  = Runnable { showLabel = false; invalidate() }
        private val longRun  = Runnable { bump(); enterSplitMode() }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null); scheduleIdle() }

        private fun scheduleIdle() { mainHandler.removeCallbacks(idleRun); mainHandler.postDelayed(idleRun, IDLE_MS) }
        private fun wakeUp() { mainHandler.removeCallbacks(idleRun); animAlpha({ btnAlpha }, { btnAlpha = it; invalidate() }, FULL_ALPHA); scheduleIdle() }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            mainHandler.removeCallbacks(idleRun)
            mainHandler.removeCallbacks(hideLbl)
            mainHandler.removeCallbacks(longRun)
        }

        override fun onDraw(canvas: Canvas) {
            // Reserve top area for the % pop-up label
            val extraH = (28 * density)
            val dropTop = extraH
            val dropSz  = width.toFloat()
            val cx = dropSz / 2; val cy = dropTop + dropSz / 2
            val r  = dropSz * 0.44f

            // Glow
            bgP.color = Color.parseColor("#7C4DFF"); bgP.alpha = (btnAlpha * 0.28f).toInt()
            bgP.setShadowLayer(10 * density, 0f, 2 * density, Color.parseColor("#557C4DFF"))
            canvas.drawCircle(cx, cy, r * 1.06f, bgP); bgP.clearShadowLayer()

            // Body
            bgP.color = Color.parseColor("#5E35B1"); bgP.alpha = btnAlpha
            canvas.drawCircle(cx, cy, r, bgP)

            // Highlight
            val hlP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.parseColor("#AAAAFF"); alpha = (btnAlpha * 0.45f).toInt() }
            canvas.drawArc(cx - r * 0.58f, cy - r * 0.58f, cx + r * 0.58f, cy + r * 0.58f, 200f, 100f, false, hlP)

            // Drop shape
            dropP.alpha = btnAlpha
            val dr = r * 0.42f
            val path = Path().apply {
                moveTo(cx, cy - dr * 1.1f)
                cubicTo(cx + dr, cy - dr * 0.2f, cx + dr * 0.9f, cy + dr, cx, cy + dr * 1.05f)
                cubicTo(cx - dr * 0.9f, cy + dr, cx - dr, cy - dr * 0.2f, cx, cy - dr * 1.1f)
            }
            canvas.drawPath(path, dropP)

            // Opacity % pop-up label — drawn in the reserved area above the button
            if (showLabel) {
                val label = "${opacity()}%"
                val lbW = lblP.measureText(label); val pad = 8 * density
                val lbX = cx; val lbY = extraH - 6 * density
                canvas.drawRoundRect(lbX - lbW / 2 - pad, lbY - 20 * density, lbX + lbW / 2 + pad, lbY + 4 * density, 8 * density, 8 * density, lblBg)
                lblP.alpha = btnAlpha
                canvas.drawText(label, lbX, lbY, lblP)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    wakeUp(); tick(); startX = e.rawX; startOp = opacity(); dragging = false
                    lastHapticNotch = opacity() / 10
                    showLabel = true; invalidate()
                    mainHandler.postDelayed(longRun, 520L)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    if (!dragging && abs(dx) > 7 * density) { dragging = true; mainHandler.removeCallbacks(longRun) }
                    if (dragging) {
                        val newOp = (startOp + (dx / (screenW * 0.6f) * 100).toInt()).coerceIn(0, 100)
                        val notch = newOp / 10
                        if (notch != lastHapticNotch) {
                            lastHapticNotch = notch
                            tick()
                        }
                        setOpacityLive(newOp)
                        showLabel = true; maskView?.invalidate(); invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longRun)
                    mainHandler.removeCallbacks(hideLbl)
                    mainHandler.postDelayed(hideLbl, 1400)
                    persistOpacity()
                    scheduleIdle(); return true
                }
            }
            return false
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // VIEW 5 — Floating exit button (always topmost window)
    // ══════════════════════════════════════════════════════════════════
    @SuppressLint("ViewConstructor")
    private inner class ExitButtonView(ctx: Context) : View(ctx) {
        private var btnAlpha = 95
        private var dragging = false; private var lrx = 0f; private var lry = 0f
        private var dxa = 0f; private var dya = 0f

        private val bgP   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#CC1A1A2E") }
        private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.parseColor("#88FFFFFF") }
        private val xP    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * density; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f - density; val cx = width / 2f; val cy = height / 2f
            bgP.alpha = btnAlpha
            bgP.setShadowLayer(8 * density, 0f, 2 * density, Color.parseColor("#44000000"))
            canvas.drawCircle(cx, cy, r, bgP); bgP.clearShadowLayer()
            ringP.alpha = btnAlpha; canvas.drawCircle(cx, cy, r, ringP)
            val xm = r * 0.36f; xP.alpha = btnAlpha
            canvas.drawLine(cx - xm, cy - xm, cx + xm, cy + xm, xP)
            canvas.drawLine(cx - xm, cy + xm, cx + xm, cy - xm, xP)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { lrx = e.rawX; lry = e.rawY; dragging = false; dxa = 0f; dya = 0f; btnAlpha = 230; invalidate(); return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lrx; val dy = e.rawY - lry
                    dxa += abs(dx); dya += abs(dy)
                    if (!dragging && (dxa > 7 * density || dya > 7 * density)) dragging = true
                    if (dragging) {
                        exitBtnX = (exitBtnX + dx).coerceIn(0f, screenW - width.toFloat())
                        exitBtnY = (exitBtnY + dy).coerceIn(0f, screenH - height.toFloat())
                        updateExitPos()
                    }
                    lrx = e.rawX; lry = e.rawY; return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        tick()
                        // Bug 4: Fully disarm so the service doesn't immediately
                        // re-show the overlay for a protected app.
                        preferencesManager.isArmed = false
                        preferencesManager.isManualOverrideActive = false
                        hide()
                    } else {
                        btnAlpha = 95; invalidate()
                    }
                    return true
                }
            }
            return false
        }
    }
}
