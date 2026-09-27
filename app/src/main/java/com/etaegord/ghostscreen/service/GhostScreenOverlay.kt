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
import android.view.animation.DecelerateInterpolator
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
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isHardwareBlurSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && try { windowManager.isCrossWindowBlurEnabled } catch (_: Exception) { false }

    private val pctStrings = Array(101) { "$it%" }

    // There is no public API to check whether FLAG_BLUR_BEHIND is honoured on older
    // platforms, so we ALWAYS draw a frosted-glass fallback layer (translucent tint + noise
    // shader) as the primary frost effect, and additionally request
    // FLAG_BLUR_BEHIND on Android 12+ as a bonus enhancement where supported.
    // When hardware blur is disabled by OS/GPU (e.g. low-end MediaTek), we boost
    // the tint so content remains obscured.

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
    private var isFadingOut = false
    private var overlayAlpha = FULL_ALPHA
    fun isShowing(): Boolean = isShowing
    fun isFullyShowing(): Boolean = isShowing && !isFadingOut
    fun isFadingOut(): Boolean = isFadingOut

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
    private var liveOpacity: Int = preferencesManager.transparency.coerceIn(0, 100)
    private fun opacity(): Int = liveOpacity.coerceIn(0, 100)
    private fun setOpacity(displayPct: Int) {
        val clamped = displayPct.coerceIn(0, 100)
        if (clamped == liveOpacity) return
        liveOpacity = clamped
        invalidateMasks()
    }
    private fun setOpacityLive(displayPct: Int) {
        val clamped = displayPct.coerceIn(0, 100)
        if (clamped == liveOpacity) return
        liveOpacity = clamped
        invalidateMasks()
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
    private var componentAlphaAnimator: ValueAnimator? = null
    private var overlayFadeAnimator: ValueAnimator? = null
    private var modeSwitchAnimator: ValueAnimator? = null

    private fun cancelOverlayFade() {
        overlayFadeAnimator?.cancel()
        overlayFadeAnimator = null
    }

    private fun cancelAlphaFade() {
        componentAlphaAnimator?.cancel()
        componentAlphaAnimator = null
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
        private const val DROP_DIM_ALPHA = 240
        private const val DIM_ALPHA   = 38
        private const val FULL_ALPHA  = 255
        private const val MERGE_GAP_DP= 8f
        private const val EXIT_DP     = 46f
        private const val DROP_W_DP   = 76f
        private const val DROP_H_DP   = 36f
        private const val CORNER_DP   = 56f   // size of each corner handle window
        private const val MIN_BOX_DP  = 80f
        private const val DRAG_NONE   = 0
        private const val DRAG_VERT   = 1
        private const val DRAG_HORIZ  = 2
        private const val COLOR_ACCENT          = 0xFF7C4DFF.toInt()
        private const val COLOR_ACCENT_SHADOW   = 0x887C4DFF.toInt()
        private const val COLOR_CORNER_SHADOW   = 0x557C4DFF.toInt()
        private const val COLOR_EYE_BG_MERGED   = 0xFF1A0D00.toInt()
        private const val COLOR_EYE_BG_DEFAULT  = 0xFF1A1A2E.toInt()
        private const val COLOR_BORDER_MERGED   = 0x99FF6D00.toInt()
        private const val COLOR_BORDER_DEFAULT  = 0x55FFFFFF.toInt()
        private const val COLOR_EYE_STR_MERGED  = 0xFFFF6D00.toInt()
        private const val COLOR_DROP_BG         = 0xFF121020.toInt()
        private const val COLOR_DROP_SHADOW     = 0x99000000.toInt()
        private const val COLOR_DROP_RING       = 0xFFB388FF.toInt()
        private const val COLOR_EXIT_BG         = 0xCC1A1A2E.toInt()
        private const val COLOR_EXIT_RING       = 0x88FFFFFF.toInt()
        private const val COLOR_EXIT_SHADOW     = 0x44000000.toInt()
    }

    private fun animOverlayFade(targetAlpha: Int, onEnd: (() -> Unit)? = null) {
        cancelOverlayFade()
        val startAlpha = overlayAlpha
        if (startAlpha == targetAlpha) {
            onEnd?.invoke()
            return
        }
        val animator = ValueAnimator.ofInt(startAlpha, targetAlpha).apply {
            duration = 110L
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                overlayAlpha = va.animatedValue as Int
                invalidateMasks()
                stripTopView?.invalidate()
                stripBotView?.invalidate()
                cornerViews.forEach { it?.invalidate() }
                opacityBtnView?.invalidate()
                exitBtnView?.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        onEnd?.invoke()
                    }
                }
            })
        }
        overlayFadeAnimator = animator
        animator.start()
    }

    // ══════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════
    fun show() {
        if (isShowing) {
            if (isFadingOut) {
                isFadingOut = false
                animOverlayFade(FULL_ALPHA)
            }
            return
        }
        updateScreenDimensions()
        getNoiseShader() // Pre-warm noise shader so no allocation happens during gesture
        if (exitBtnX < 0) {
            exitBtnX = screenW - EXIT_DP * density - 20 * density
            exitBtnY = screenH * 0.50f - EXIT_DP * density / 2
        }

        // Always start fresh in SPLIT mode with the user's saved viewport.
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

        overlayAlpha = 0
        addMask()
        addMaskWindows()
        addSplitWindows()
        addExitButton()
        isShowing = true
        isFadingOut = false
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 50, 30), -1))

        animOverlayFade(FULL_ALPHA)
    }

    fun hide() {
        if (!isShowing || isFadingOut) return

        // Persist the current SPLIT viewport so it survives across show/hide cycles.
        if (overlayMode == Mode.SPLIT && stripBotY > stripTopY) {
            val center = ((stripTopY + stripBotY) / 2f) / screenH.toFloat()
            val height = (stripBotY - stripTopY) / screenH.toFloat()
            preferencesManager.cutoutYPct = center.coerceIn(0f, 1f)
            preferencesManager.cutoutHeightPct = height.coerceIn(0.05f, 0.9f)
        }

        mainHandler.removeCallbacks(dimRun)
        mainHandler.removeCallbacks(editDimRun)
        cancelAlphaFade()

        isFadingOut = true
        animOverlayFade(0) {
            doTeardown()
        }
    }

    private fun doTeardown() {
        removeSplitWindows()
        removeBoxWindows()
        removeMaskWindows()
        maskView?.let { safeRemove(it) }; maskView = null
        exitBtnView?.let { safeRemove(it) }; exitBtnView = null

        isDraggingMasksHidden = false
        boxEditActive = false
        mainHandler.removeCallbacks(dimRun)
        mainHandler.removeCallbacks(editDimRun)
        modeSwitchAnimator?.cancel()
        modeSwitchAnimator = null
        cancelOverlayFade()
        cancelAlphaFade()
        isShowing = false
        isFadingOut = false
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

        val dropW = (DROP_W_DP * density).toInt()
        val dropH = (DROP_H_DP * density).toInt()
        val preferredY = (boxBottom + 12 * density).toInt()
        val maxY = screenH - dropH - 12 * density
        val btnY = if (preferredY <= maxY) preferredY else (boxTop - dropH - 12 * density).toInt().coerceAtLeast(0)
        opacityBtnView = OpacityDropView(context).also {
            safeAdd(it, activeParams(dropW, dropH).apply {
                x = ((boxLeft + boxRight) / 2 - dropW / 2).toInt().coerceIn(0, screenW - dropW)
                y = btnY
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
            val dropW = (DROP_W_DP * density).toInt()
            val dropH = (DROP_H_DP * density).toInt()
            val p = it.layoutParams as WindowManager.LayoutParams
            p.x = ((boxLeft + boxRight) / 2 - dropW / 2).toInt().coerceIn(0, screenW - dropW)
            val preferredY = (boxBottom + 12 * density).toInt()
            val maxY = screenH - dropH - 12 * density
            p.y = if (preferredY <= maxY) preferredY else (boxTop - dropH - 12 * density).toInt().coerceAtLeast(0)
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
     * Lightweight corner move used during an active box drag: repositions
     * the 4 corner handle windows, the opacity button, and the full-screen MaskView border/dots.
     * The 4 mask windows are expensive cross-process relayouts, so they are
     * hidden for the duration of the drag (setMaskWindowsVisible) and rebuilt
     * once via updateCornerPositions() on ACTION_UP.
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
        opacityBtnView?.let {
            val dropW = (DROP_W_DP * density).toInt()
            val dropH = (DROP_H_DP * density).toInt()
            val p = it.layoutParams as WindowManager.LayoutParams
            p.x = ((boxLeft + boxRight) / 2 - dropW / 2).toInt().coerceIn(0, screenW - dropW)
            val preferredY = (boxBottom + 12 * density).toInt()
            val maxY = screenH - dropH - 12 * density
            p.y = if (preferredY <= maxY) preferredY else (boxTop - dropH - 12 * density).toInt().coerceAtLeast(0)
            safeUpdate(it)
        }
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
        opacityBtnView?.wakeUp()
    }

    private fun invalidateCorners() { for (i in 0..3) cornerViews[i]?.invalidate() }

    // Box resize/move — routes to lightweight dragBoxCorner
    fun applyBoxDelta(cornerIdx: Int, dx: Float, dy: Float) {
        dragBoxCorner(cornerIdx, dx, dy)
    }

    // Box drag — called by corner handles during active drag (cheap: updates handles + invalidates passive mask outline)
    fun dragBoxCorner(cornerIdx: Int, dx: Float, dy: Float) {
        val m = MIN_BOX_DP * density
        when (cornerIdx) {
            0 -> { // Top-Left
                boxLeft = (boxLeft + dx).coerceIn(0f, boxRight - m)
                boxTop  = (boxTop + dy).coerceIn(0f, boxBottom - m)
            }
            1 -> { // Top-Right
                boxRight = (boxRight + dx).coerceIn(boxLeft + m, screenW.toFloat())
                boxTop   = (boxTop + dy).coerceIn(0f, boxBottom - m)
            }
            2 -> { // Bottom-Left
                boxLeft   = (boxLeft + dx).coerceIn(0f, boxRight - m)
                boxBottom = (boxBottom + dy).coerceIn(boxTop + m, screenH.toFloat())
            }
            3 -> { // Bottom-Right
                boxRight  = (boxRight + dx).coerceIn(boxLeft + m, screenW.toFloat())
                boxBottom = (boxBottom + dy).coerceIn(boxTop + m, screenH.toFloat())
            }
        }
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
        componentAlphaAnimator = animator
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
            val alphaMult = overlayAlpha / 255f

            if (isBlur) {
                val solidAlpha = OpacityCurve.realPctToAlpha(realPct)
                val tintAlpha = if (isHardwareBlurSupported) {
                    ((realPct / 100f * 160) * alphaMult).toInt().coerceIn(0, 255)
                } else {
                    (solidAlpha * alphaMult).toInt().coerceIn(0, 255)
                }
                fillP.color = rawColor; fillP.alpha = tintAlpha
                canvas.drawRect(0f, 0f, w, h, fillP)

                noiseP.shader = getNoiseShader()
                val noiseAlpha = ((realPct / 100f * 110) * alphaMult).toInt().coerceIn(0, 255)
                noiseP.alpha = noiseAlpha
                canvas.drawRect(0f, 0f, w, h, noiseP)
                return
            }

            // Solid color mode.
            fillP.color = rawColor
            fillP.alpha = (OpacityCurve.realPctToAlpha(realPct) * alphaMult).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, fillP)
        }
    }

    private inner class MaskView(ctx: Context) : View(ctx) {
        private val edgeP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
        private val dotP   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // Blur B: pre-allocate noise Paint for drag-fallback frost rendering
        private val noiseP = Paint(Paint.ANTI_ALIAS_FLAG)

        // Pre-allocated RectFs to avoid GC churn on every drag frame
        private val rTop = RectF()
        private val rBottom = RectF()
        private val rLeft = RectF()
        private val rRight = RectF()

        override fun onDraw(canvas: Canvas) {
            val alphaMult = overlayAlpha / 255f
            if (isDraggingMasksHidden) {
                val isBlur = preferencesManager.isBlurEnabled
                val realPct = OpacityCurve.displayToRealPct(opacity())
                val solidAlpha = OpacityCurve.realPctToAlpha(realPct)
                val baseAlpha = if (isBlur) {
                    if (isHardwareBlurSupported) (realPct / 100f * 160).toInt().coerceIn(0, 255) else solidAlpha
                } else {
                    solidAlpha
                }
                val rawColor = getParsedColor()
                fillP.color = rawColor
                fillP.alpha = (baseAlpha * alphaMult).toInt().coerceIn(0, 255)

                val w = width.toFloat()
                val h = height.toFloat()

                if (overlayMode == Mode.SPLIT) {
                    rTop.set(0f, 0f, w, stripTopY)
                    rBottom.set(0f, stripBotY, w, h)
                    canvas.drawRect(rTop, fillP)
                    canvas.drawRect(rBottom, fillP)

                    if (isBlur) {
                        noiseP.shader = getNoiseShader()
                        val noiseAlpha = ((realPct / 100f * 110) * alphaMult).toInt().coerceIn(0, 255)
                        noiseP.alpha = noiseAlpha
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
                        val noiseAlpha = ((realPct / 100f * 110) * alphaMult).toInt().coerceIn(0, 255)
                        noiseP.alpha = noiseAlpha
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
    // ══════════════════════════════════════════════════════════════════
    // VIEW 2 — Full-width strip controller (SPLIT mode)
    // ══════════════════════════════════════════════════════════════════
    @SuppressLint("ViewConstructor")
    private inner class StripView(ctx: Context, private val isTop: Boolean) : View(ctx) {
        private var alpha_  = FULL_ALPHA
        private var isDragging = false; private var wasMerged = false
        private var dragMode = DRAG_NONE
        private var pressStartX = 0f; private var pressStartRawY = 0f; private var lastRawY = 0f
        private var lastHapticNotch = -1
        private var isNearEye = false

        private val trackBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val trackFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#7C4DFF") }
        private val eyeBg   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val eyeStr  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; strokeCap = Paint.Cap.ROUND }
        private val eyeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val border_ = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
        private val arrow_  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * density; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
        private val tPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }

        // Pre-allocated draw objects to eliminate onDraw GC churn
        private val glowMergedP  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = Color.parseColor("#FF6D00")
            maskFilter = BlurMaskFilter(EYE_R_DP * density * 0.9f, BlurMaskFilter.Blur.NORMAL)
        }
        private val glowDefaultP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = Color.parseColor("#7C4DFF")
            maskFilter = BlurMaskFilter(EYE_R_DP * density * 0.9f, BlurMaskFilter.Blur.NORMAL)
        }
        private val eyePath = Path()
        private val chP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.6f * density; strokeCap = Paint.Cap.ROUND; color = Color.WHITE
        }

        private val idleRun = Runnable { animAlpha({ alpha_ }, { alpha_ = it; invalidate() }, DIM_ALPHA) }
        private val longRun = Runnable { bump(); enterBoxMode() }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null); scheduleIdle() }

        private fun scheduleIdle() { mainHandler.removeCallbacks(idleRun); mainHandler.postDelayed(idleRun, IDLE_MS) }
        private fun wakeUp() { mainHandler.removeCallbacks(idleRun); animAlpha({ alpha_ }, { alpha_ = it; invalidate() }, FULL_ALPHA); scheduleIdle() }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            mainHandler.removeCallbacks(idleRun)
            mainHandler.removeCallbacks(longRun)
        }

        override fun onDraw(canvas: Canvas) {
            val effectiveAlpha = (alpha_ * (overlayAlpha / 255f)).toInt()
            val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2f
            val merged = (stripBotY - stripTopY) < MERGE_GAP_DP * density
            val th = 3f * density
            val gap = 16f * density
            
            trackBg.color = Color.WHITE; trackBg.alpha = (effectiveAlpha * 0.18f).toInt()
            canvas.drawRoundRect(gap, cy - th / 2, w - gap, cy + th / 2, th / 2, th / 2, trackBg)
            
            val minX = gap + EYE_R_DP * density
            val maxX = w - gap - EYE_R_DP * density
            val range = maxX - minX
            val eyeX = minX + (opacity() / 100f) * range

            trackFg.alpha = effectiveAlpha
            canvas.drawRoundRect(gap, cy - th / 2, eyeX, cy + th / 2, th / 2, th / 2, trackFg)
            
            val aArr = 7 * density
            arrow_.alpha = (effectiveAlpha * 0.30f).toInt()
            canvas.drawLine(14 * density, cy, 14 * density + aArr, cy - aArr, arrow_)
            canvas.drawLine(14 * density, cy, 14 * density + aArr, cy + aArr, arrow_)
            val rx = w - 14 * density
            canvas.drawLine(rx, cy, rx - aArr, cy - aArr, arrow_)
            canvas.drawLine(rx, cy, rx - aArr, cy + aArr, arrow_)
            
            drawEye(canvas, eyeX, cy, merged, effectiveAlpha)
        }

        private fun drawEye(canvas: Canvas, cx: Float, cy: Float, merged: Boolean, effectiveAlpha: Int) {
            val r = EYE_R_DP * density
            val gP = if (merged) glowMergedP else glowDefaultP
            gP.alpha = (effectiveAlpha * 0.20f).toInt()
            canvas.drawCircle(cx, cy, r * 1.15f, gP)

            eyeBg.color = if (merged) COLOR_EYE_BG_MERGED else COLOR_EYE_BG_DEFAULT
            eyeBg.alpha = (effectiveAlpha * 0.90f).toInt()
            canvas.drawRoundRect(cx - r, cy - r * 0.62f, cx + r, cy + r * 0.62f, r * 0.5f, r * 0.5f, eyeBg)

            border_.color = if (merged) COLOR_BORDER_MERGED else COLOR_BORDER_DEFAULT
            border_.alpha = effectiveAlpha
            canvas.drawRoundRect(cx - r, cy - r * 0.62f, cx + r, cy + r * 0.62f, r * 0.5f, r * 0.5f, border_)

            if (merged) {
                eyeStr.color = COLOR_EYE_STR_MERGED; eyeStr.alpha = effectiveAlpha
                canvas.drawLine(cx, cy - r * 0.38f, cx, cy + r * 0.38f, eyeStr)
                val a2 = r * 0.22f
                canvas.drawLine(cx - a2, cy - r * 0.18f, cx, cy - r * 0.38f, eyeStr)
                canvas.drawLine(cx + a2, cy - r * 0.18f, cx, cy - r * 0.38f, eyeStr)
                canvas.drawLine(cx - a2, cy + r * 0.18f, cx, cy + r * 0.38f, eyeStr)
                canvas.drawLine(cx + a2, cy + r * 0.18f, cx, cy + r * 0.38f, eyeStr)
            } else {
                eyeStr.color = Color.WHITE; eyeStr.alpha = effectiveAlpha
                eyePath.reset()
                eyePath.moveTo(cx - r * 0.65f, cy)
                eyePath.quadTo(cx, cy - r * 0.42f, cx + r * 0.65f, cy)
                eyePath.quadTo(cx, cy + r * 0.42f, cx - r * 0.65f, cy)
                canvas.drawPath(eyePath, eyeStr)

                eyeFill.color = Color.WHITE; eyeFill.alpha = effectiveAlpha
                canvas.drawCircle(cx, cy, r * 0.20f, eyeFill)

                // Opacity % — cached string, zero per-frame allocation
                tPaint.textSize = 10f * density; tPaint.color = Color.WHITE
                tPaint.alpha = (effectiveAlpha * 0.90f).toInt()
                canvas.drawText(pctStrings[opacity()], cx, cy + r * 0.62f + 5 * density + tPaint.textSize * 0.8f, tPaint)

                // Chevrons outside pill (above/below)
                chP.alpha = (effectiveAlpha * 0.35f).toInt()
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
                    dragMode = DRAG_NONE
                    lastHapticNotch = opacity() / 10
                    val gap = 16f * density
                    val minX = gap + EYE_R_DP * density
                    val maxX = width - gap - EYE_R_DP * density
                    val eyeX = minX + (opacity() / 100f) * (maxX - minX)
                    isNearEye = abs(e.x - eyeX) <= (EYE_R_DP * density * 2.2f)
                    if (!wasMerged) mainHandler.postDelayed(longRun, 520L)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(e.x - pressStartX); val dy = abs(e.rawY - pressStartRawY)
                    if (!isDragging && (dx > touchSlop || dy > touchSlop)) { 
                        isDragging = true; mainHandler.removeCallbacks(longRun)
                        dragMode = if (isNearEye) {
                            if (dy > dx * 1.8f) DRAG_VERT else DRAG_HORIZ
                        } else {
                            DRAG_VERT
                        }
                        if (dragMode == DRAG_VERT) setMaskWindowsVisible(false) 
                    }
                    if (isDragging) {
                        if (dragMode == DRAG_VERT) {
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
                                // Live in-memory update with real-time mask invalidation
                                val newOp = (((e.x - minX) / range) * 100).toInt().coerceIn(0, 100)
                                if (newOp != liveOpacity) {
                                    val notch = newOp / 10
                                    if (notch != lastHapticNotch) {
                                        lastHapticNotch = notch
                                        tick()
                                    }
                                    setOpacityLive(newOp)
                                    stripTopView?.invalidate()
                                    stripBotView?.invalidate()
                                }
                            }
                        }
                        lastRawY = e.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        if (dragMode == DRAG_VERT) {
                            setMaskWindowsVisible(true)
                            if (stripBotY > stripTopY && screenH > 0) {
                                preferencesManager.cutoutYPct = ((stripTopY + stripBotY) / 2f / screenH).coerceIn(0f, 1f)
                                preferencesManager.cutoutHeightPct = ((stripBotY - stripTopY) / screenH).coerceIn(0.05f, 0.9f)
                            }
                        } else if (dragMode == DRAG_HORIZ) {
                            persistOpacity()
                        }
                    }
                    dragMode = DRAG_NONE
                    isDragging = false
                    isNearEye = false
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

        private val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR_ACCENT }
        private val xP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * density; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }

        private val hintRun = Runnable {
            if (!boxEditActive) {
                boxEditActive = true; bump(); invalidateCorners()
                scheduleEditDim()
            }
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            mainHandler.removeCallbacks(hintRun)
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            val effectiveAlpha = ((cornerAlpha_ / 255f) * (overlayAlpha / 255f) * 255).toInt()
            bgP.alpha = effectiveAlpha
            bgP.setShadowLayer(6 * density, 0f, 0f, COLOR_CORNER_SHADOW)
            canvas.drawCircle(r, r, r * 0.86f, bgP)
            bgP.clearShadowLayer()
            val s = r * 0.38f
            xP.alpha = effectiveAlpha
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
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
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
                        setMaskWindowsVisible(true)
                    }
                    return true
                }
            }
            return false
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // VIEW 4 — Opacity pill badge (BOX mode)
    // Distinct, opaque, high-contrast pill badge with elevation & border
    // Displays teardrop icon + live percentage text
    // ══════════════════════════════════════════════════════════════════
    @SuppressLint("ViewConstructor")
    private inner class OpacityDropView(ctx: Context) : View(ctx) {
        private var btnAlpha = FULL_ALPHA
        private var startX = 0f; private var startY = 0f; private var startOp = 0; private var dragging = false
        private var lastHapticNotch = -1

        private val bgP       = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR_DROP_BG }
        private val ringP     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            color = COLOR_DROP_RING
        }
        private val dropFillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val dropDotP  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_ACCENT
        }
        private val dropPath  = Path()
        private val textP     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12.5f * density
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val pillRect  = RectF()

        private val idleRun  = Runnable { animAlpha({ btnAlpha }, { btnAlpha = it; invalidate() }, DROP_DIM_ALPHA) }
        private val longRun  = Runnable { bump(); enterSplitMode() }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null); scheduleIdle() }

        private fun scheduleIdle() { mainHandler.removeCallbacks(idleRun); mainHandler.postDelayed(idleRun, IDLE_MS) }
        fun wakeUp() { mainHandler.removeCallbacks(idleRun); animAlpha({ btnAlpha }, { btnAlpha = it; invalidate() }, FULL_ALPHA); scheduleIdle() }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            mainHandler.removeCallbacks(idleRun)
            mainHandler.removeCallbacks(longRun)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = 3f * density
            pillRect.set(pad, pad, w - pad, h - pad)
            val cornerR = (h - 2 * pad) / 2f

            val effectiveAlpha = ((btnAlpha / 255f) * (overlayAlpha / 255f) * 255).toInt()

            // Elevation drop shadow + solid opaque pitch-dark background
            // Background is solid opaque (#121020) so it never blends with underlying text
            // Multiplies with overlayAlpha so overlay fade in/out transitions smoothly
            bgP.alpha = ((overlayAlpha / 255f) * 255).toInt().coerceIn(0, 255)
            bgP.setShadowLayer(6 * density, 0f, 2 * density, COLOR_DROP_SHADOW)
            canvas.drawRoundRect(pillRect, cornerR, cornerR, bgP)
            bgP.clearShadowLayer()

            // High-contrast accent border
            ringP.alpha = effectiveAlpha
            canvas.drawRoundRect(pillRect, cornerR, cornerR, ringP)

            // Droplet icon on the left half of the pill
            val iconCx = pad + cornerR * 0.95f
            val iconCy = h / 2f
            val dr = cornerR * 0.44f

            dropPath.reset()
            dropPath.moveTo(iconCx, iconCy - dr * 1.15f)
            dropPath.cubicTo(iconCx + dr * 1.05f, iconCy - dr * 0.15f, iconCx + dr * 0.95f, iconCy + dr * 0.95f, iconCx, iconCy + dr * 1.1f)
            dropPath.cubicTo(iconCx - dr * 0.95f, iconCy + dr * 0.95f, iconCx - dr * 1.05f, iconCy - dr * 0.15f, iconCx, iconCy - dr * 1.15f)
            dropFillP.alpha = effectiveAlpha
            canvas.drawPath(dropPath, dropFillP)

            // Droplet center accent dot
            dropDotP.alpha = effectiveAlpha
            canvas.drawCircle(iconCx, iconCy + dr * 0.35f, dr * 0.28f, dropDotP)

            // Opacity % text on the right half of the pill
            val textCx = iconCx + cornerR + 13 * density
            val textCy = iconCy - (textP.descent() + textP.ascent()) / 2f
            textP.alpha = effectiveAlpha
            canvas.drawText(pctStrings[opacity()], textCx, textCy, textP)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    wakeUp(); tick(); startX = e.rawX; startY = e.rawY; startOp = opacity(); dragging = false
                    lastHapticNotch = opacity() / 10
                    mainHandler.postDelayed(longRun, 520L)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        mainHandler.removeCallbacks(longRun)
                    }
                    if (dragging) {
                        val newOp = (startOp + (dx / (screenW * 0.6f) * 100).toInt()).coerceIn(0, 100)
                        if (newOp != liveOpacity) {
                            val notch = newOp / 10
                            if (notch != lastHapticNotch) {
                                lastHapticNotch = notch
                                tick()
                            }
                            setOpacityLive(newOp)
                            invalidate()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longRun)
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
        private var startX = 0f; private var startY = 0f

        private val bgP   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR_EXIT_BG }
        private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = COLOR_EXIT_RING }
        private val xP    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * density; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f - density; val cx = width / 2f; val cy = height / 2f
            val effectiveAlpha = ((btnAlpha / 255f) * (overlayAlpha / 255f) * 255).toInt()
            bgP.alpha = effectiveAlpha
            bgP.setShadowLayer(8 * density, 0f, 2 * density, COLOR_EXIT_SHADOW)
            canvas.drawCircle(cx, cy, r, bgP); bgP.clearShadowLayer()
            ringP.alpha = effectiveAlpha; canvas.drawCircle(cx, cy, r, ringP)
            val xm = r * 0.36f; xP.alpha = effectiveAlpha
            canvas.drawLine(cx - xm, cy - xm, cx + xm, cy + xm, xP)
            canvas.drawLine(cx - xm, cy + xm, cx + xm, cy - xm, xP)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    lrx = e.rawX; lry = e.rawY
                    dragging = false
                    btnAlpha = 230
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lrx; val dy = e.rawY - lry
                    if (!dragging && (abs(e.rawX - startX) > touchSlop || abs(e.rawY - startY) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        exitBtnX = (exitBtnX + dx).coerceIn(0f, screenW - width.toFloat())
                        exitBtnY = (exitBtnY + dy).coerceIn(0f, screenH - height.toFloat())
                        updateExitPos()
                    }
                    lrx = e.rawX; lry = e.rawY; return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.action == MotionEvent.ACTION_UP && !dragging) {
                        tick()
                        // Bug 4: Fully disarm so the service doesn't immediately
                        // re-show the overlay for a protected app.
                        preferencesManager.isArmed = false
                        preferencesManager.isManualOverrideActive = false
                        hide()
                    } else {
                        btnAlpha = 95; invalidate()
                    }
                    dragging = false
                    return true
                }
            }
            return false
        }
    }
}
