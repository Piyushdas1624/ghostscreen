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
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import com.etaegord.ghostscreen.data.PreferencesManager
import com.etaegord.ghostscreen.util.OpacityCurve
import com.etaegord.ghostscreen.util.TiltSensorManager
import com.etaegord.ghostscreen.util.ShakeDetector
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

    // Spotlight mode components
    private var spotlightGripView: SpotlightGripView? = null
    private var spotlightX = 0f
    private var spotlightY = 0f
    private var spotlightRadius = 95f * density

    // AI Sensitive field micro-shield capsules
    private val sensitiveFieldRects = mutableListOf<RectF>()

    // Notification Privacy Shield ("Inverse Box")
    private var isNotificationShieldActive = false
    private var notificationBannerHeight = 112f * density
    private var notificationShieldTimer: Runnable? = null

    // Shoulder-Surfer Tilt & Panic Double-Shake
    private var isTiltBlackout = false
    private var isPanicBlackout = false
    private val isEmergencyBlackout get() = isTiltBlackout || isPanicBlackout

    private val tiltSensorManager by lazy {
        TiltSensorManager(context) { isBlackout ->
            if (preferencesManager.isTiltShieldEnabled) {
                setTiltBlackout(isBlackout)
            } else {
                setTiltBlackout(false)
            }
        }
    }
    private val shakeDetector by lazy {
        ShakeDetector(context) {
            togglePanicBlackout()
        }
    }

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
    private var cornerAlphaAnimator: ValueAnimator? = null
    private var overlayFadeAnimator: ValueAnimator? = null
    private var modeSwitchAnimator: ValueAnimator? = null

    private fun cancelOverlayFade() {
        overlayFadeAnimator?.cancel()
        overlayFadeAnimator = null
    }

    private fun cancelCornerAlphaFade() {
        cornerAlphaAnimator?.cancel()
        cornerAlphaAnimator = null
    }

    // ── Exit button position ────────────────────────────────────────
    private var exitBtnX = -1f
    private var exitBtnY = -1f

    enum class Mode { SPLIT, BOX, SPOTLIGHT }

    companion object {
        private const val TAG         = "GhostScreenOverlay"
        private const val STRIP_H_DP  = 52f
        private const val EYE_R_DP    = 20f
        private const val IDLE_MS     = 2500L
        private const val BOX_DIM_MS  = 2500L
        private const val BOX_EDIT_MS = 5000L
        private const val DIM_ALPHA   = 38
        private const val DROP_DIM_ALPHA = DIM_ALPHA
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

    private fun getDynamicAccentColor(): Int {
        return if (preferencesManager.isDynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { ContextCompat.getColor(context, android.R.color.system_accent1_500) } catch (_: Exception) { COLOR_ACCENT }
        } else {
            COLOR_ACCENT
        }
    }

    private fun getDynamicAccentRingColor(): Int {
        return if (preferencesManager.isDynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { ContextCompat.getColor(context, android.R.color.system_accent1_200) } catch (_: Exception) { COLOR_DROP_RING }
        } else {
            COLOR_DROP_RING
        }
    }

    private fun getDynamicSurfaceContainerColor(): Int {
        return if (preferencesManager.isDynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { ContextCompat.getColor(context, android.R.color.system_neutral1_800) } catch (_: Exception) { COLOR_DROP_BG }
        } else {
            COLOR_DROP_BG
        }
    }

    private fun getDynamicAccentShadowColor(): Int {
        val accent = getDynamicAccentColor()
        return (0x55 shl 24) or (accent and 0x00FFFFFF)
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

        val savedMode = when (preferencesManager.overlayModeName.uppercase()) {
            "BOX" -> Mode.BOX
            "SPOTLIGHT" -> Mode.SPOTLIGHT
            else -> Mode.SPLIT
        }
        overlayMode = savedMode
        boxEditActive = false
        cornerAlpha_ = FULL_ALPHA
        liveOpacity = preferencesManager.transparency
        mainHandler.removeCallbacks(dimRun); mainHandler.removeCallbacks(editDimRun)

        // Restore the saved viewport from prefs instead of hardcoding 0.35/0.65.
        val half = (preferencesManager.cutoutHeightPct / 2f).coerceIn(0.02f, 0.45f)
        stripTopY = ((preferencesManager.cutoutYPct - half) * screenH).coerceIn(0f, screenH.toFloat())
        stripBotY = ((preferencesManager.cutoutYPct + half) * screenH).coerceIn(0f, screenH.toFloat())

        boxTop = stripTopY
        boxBottom = stripBotY
        boxLeft = screenW * 0.08f
        boxRight = screenW * 0.92f

        overlayAlpha = 0
        when (overlayMode) {
            Mode.SPLIT -> {
                addMaskWindows()
                addMask()
                addSplitWindows()
            }
            Mode.BOX -> {
                addMaskWindows()
                addMask()
                addBoxWindows()
            }
            Mode.SPOTLIGHT -> {
                spotlightRadius = (preferencesManager.spotlightRadiusDp * density).coerceIn(40 * density, screenW * 0.45f)
                spotlightX = screenW / 2f
                spotlightY = screenH / 2f
                addMask()
                addSpotlightWindows()
            }
        }
        addExitButton()
        isShowing = true
        isFadingOut = false
        isTiltBlackout = false
        isPanicBlackout = false
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 50, 30), -1))

        if (preferencesManager.isTiltShieldEnabled) {
            tiltSensorManager.thresholdDegrees = preferencesManager.tiltAngleThreshold
            tiltSensorManager.isEnabled = true
            tiltSensorManager.startListening()
        } else {
            tiltSensorManager.isEnabled = false
            tiltSensorManager.stopListening()
        }
        if (preferencesManager.isPanicShakeEnabled) {
            shakeDetector.isEnabled = true
            shakeDetector.startListening()
        } else {
            shakeDetector.isEnabled = false
            shakeDetector.stopListening()
        }

        animOverlayFade(FULL_ALPHA)
    }

    fun setSensitiveFieldRects(rects: List<RectF>) {
        mainHandler.post {
            sensitiveFieldRects.clear()
            sensitiveFieldRects.addAll(rects)
            if (sensitiveFieldRects.isNotEmpty()) {
                if (!isShowing) {
                    updateScreenDimensions()
                    if (maskView == null) {
                        addMask()
                    }
                }
            } else if (!isShowing && !isNotificationShieldActive) {
                maskView?.let { safeRemove(it) }
                maskView = null
            }
            maskView?.invalidate()
        }
    }

    fun showNotificationShield(bannerHeight: Float = 112f * density, durationMs: Long = 3500L) {
        mainHandler.post {
            if (!preferencesManager.isNotificationShieldEnabled) {
                hideNotificationShield()
                return@post
            }
            notificationBannerHeight = bannerHeight
            isNotificationShieldActive = true
            if (!isShowing) {
                updateScreenDimensions()
                if (maskView == null) {
                    addMask()
                }
            }
            maskView?.invalidate()
            notificationShieldTimer?.let { mainHandler.removeCallbacks(it) }
            val timer = Runnable {
                hideNotificationShield()
            }
            notificationShieldTimer = timer
            mainHandler.postDelayed(timer, durationMs)
        }
    }

    fun hideNotificationShield() {
        mainHandler.post {
            notificationShieldTimer?.let { mainHandler.removeCallbacks(it) }
            notificationShieldTimer = null
            if (!isNotificationShieldActive && maskView == null) return@post
            isNotificationShieldActive = false
            if (!isShowing && !isEmergencyBlackout && sensitiveFieldRects.isEmpty()) {
                maskView?.let { safeRemove(it) }
                maskView = null
            } else {
                maskView?.invalidate()
            }
        }
    }

    fun setTiltBlackout(active: Boolean) {
        mainHandler.post {
            if (active && !preferencesManager.isTiltShieldEnabled) {
                if (isTiltBlackout) {
                    isTiltBlackout = false
                    updateBlackoutMask()
                }
                return@post
            }
            if (isTiltBlackout == active) return@post
            isTiltBlackout = active
            if (active) {
                vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 60), -1))
            }
            updateBlackoutMask()
        }
    }

    fun togglePanicBlackout() {
        mainHandler.post {
            if (!preferencesManager.isPanicShakeEnabled) {
                if (isPanicBlackout) {
                    isPanicBlackout = false
                    updateBlackoutMask()
                }
                return@post
            }
            isPanicBlackout = !isPanicBlackout
            if (isPanicBlackout) {
                vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 50, 80), -1))
            }
            updateBlackoutMask()
        }
    }

    fun setEmergencyBlackout(active: Boolean) {
        mainHandler.post {
            if (active) {
                if (!preferencesManager.isTiltShieldEnabled && !preferencesManager.isPanicShakeEnabled) {
                    if (isTiltBlackout || isPanicBlackout) {
                        isTiltBlackout = false
                        isPanicBlackout = false
                        updateBlackoutMask()
                    }
                    return@post
                }
                isTiltBlackout = true
                vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 60), -1))
            } else {
                isTiltBlackout = false
                isPanicBlackout = false
            }
            updateBlackoutMask()
        }
    }

    private fun updateBlackoutMask() {
        if (isEmergencyBlackout) {
            if (!isShowing) {
                updateScreenDimensions()
                if (maskView == null) {
                    addMask()
                }
            }
        } else {
            if (!isShowing && !isNotificationShieldActive && sensitiveFieldRects.isEmpty()) {
                maskView?.let { safeRemove(it) }
                maskView = null
            }
        }
        maskView?.invalidate()
    }

    fun reloadPreferences() {
        mainHandler.post {
            // Unconditionally update sensor configuration even when overlay is hidden
            if (preferencesManager.isTiltShieldEnabled) {
                tiltSensorManager.thresholdDegrees = preferencesManager.tiltAngleThreshold
                tiltSensorManager.isEnabled = true
                if (isShowing) tiltSensorManager.startListening()
            } else {
                tiltSensorManager.isEnabled = false
                tiltSensorManager.stopListening()
                isTiltBlackout = false
            }
            if (preferencesManager.isPanicShakeEnabled) {
                shakeDetector.isEnabled = true
                if (isShowing) shakeDetector.startListening()
            } else {
                shakeDetector.isEnabled = false
                shakeDetector.stopListening()
                isPanicBlackout = false
            }
            updateBlackoutMask()
        }
    }

    fun hide() {
        if (!isShowing || isFadingOut) return

        // Persist viewport so it survives across show/hide cycles.
        if (overlayMode == Mode.SPLIT && stripBotY > stripTopY && screenH > 0) {
            val center = ((stripTopY + stripBotY) / 2f) / screenH.toFloat()
            val height = (stripBotY - stripTopY) / screenH.toFloat()
            preferencesManager.cutoutYPct = center.coerceIn(0f, 1f)
            preferencesManager.cutoutHeightPct = height.coerceIn(0.05f, 0.9f)
        } else if (overlayMode == Mode.BOX && boxBottom > boxTop && screenH > 0) {
            val center = ((boxTop + boxBottom) / 2f) / screenH.toFloat()
            val height = (boxBottom - boxTop) / screenH.toFloat()
            preferencesManager.cutoutYPct = center.coerceIn(0f, 1f)
            preferencesManager.cutoutHeightPct = height.coerceIn(0.05f, 0.9f)
        }

        mainHandler.removeCallbacks(dimRun)
        mainHandler.removeCallbacks(editDimRun)
        cancelCornerAlphaFade()

        isFadingOut = true
        animOverlayFade(0) {
            doTeardown()
        }
    }

    private fun doTeardown() {
        removeSplitWindows()
        removeBoxWindows()
        removeSpotlightWindows()
        removeMaskWindows()
        tiltSensorManager.stopListening()
        shakeDetector.stopListening()
        isTiltBlackout = false
        isPanicBlackout = false
        maskView?.let { safeRemove(it) }; maskView = null
        exitBtnView?.let { safeRemove(it) }; exitBtnView = null

        boxEditActive = false
        mainHandler.removeCallbacks(dimRun)
        mainHandler.removeCallbacks(editDimRun)
        modeSwitchAnimator?.cancel()
        modeSwitchAnimator = null
        cancelOverlayFade()
        cancelCornerAlphaFade()
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
            Mode.SPOTLIGHT -> {
                spotlightX = spotlightX.coerceIn(spotlightRadius, screenW - spotlightRadius)
                spotlightY = spotlightY.coerceIn(spotlightRadius, screenH - spotlightRadius)
                updateSpotlightGripPosition()
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
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).also {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        it.gravity = Gravity.TOP or Gravity.START
    }

    private fun safeAdd(v: View, p: WindowManager.LayoutParams) = try { windowManager.addView(v, p) } catch (e: Exception) { Log.e(TAG, "addView", e) }
    private fun safeRemove(v: View) = try { windowManager.removeView(v) } catch (_: Exception) {}
    private fun safeUpdate(v: View) {
        try {
            if (v.isAttachedToWindow) {
                windowManager.updateViewLayout(v, v.layoutParams)
            }
        } catch (e: Exception) { Log.e(TAG, "safeUpdate", e) }
    }

    // ══════════════════════════════════════════════════════════════════
    // Layer management
    // ══════════════════════════════════════════════════════════════════
    private fun addMask() {
        maskView = MaskView(context); safeAdd(maskView!!, passiveParams())
    }

    private fun addMaskWindows() {
        if (!preferencesManager.isBlurEnabled || !isHardwareBlurSupported) return
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
        val half = csz / 2
        // Corners: 0=TL, 1=TR, 2=BL, 3=BR centered on each corner
        fun addCorner(i: Int, cx: Float, cy: Float) {
            cornerViews[i] = CornerHandleView(context, i).also {
                safeAdd(it, activeParams(csz, csz).apply {
                    x = (cx - half).toInt().coerceIn(-half, screenW - half)
                    y = (cy - half).toInt().coerceIn(-half, screenH - half)
                })
            }
        }
        addCorner(0, boxLeft, boxTop)
        addCorner(1, boxRight, boxTop)
        addCorner(2, boxLeft, boxBottom)
        addCorner(3, boxRight, boxBottom)

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

    private fun addSpotlightWindows() {
        val gripW = (228f * density).toInt()
        val gripH = (44f * density).toInt()
        val gx = (spotlightX - gripW / 2f).toInt().coerceIn(10 * density.toInt(), screenW - gripW - 10 * density.toInt())
        val preferredY = (spotlightY + spotlightRadius + 14f * density).toInt()
        val gy = if (preferredY <= screenH - gripH - 16 * density) preferredY else (spotlightY - spotlightRadius - gripH - 14f * density).toInt().coerceAtLeast(16 * density.toInt())
        spotlightGripView = SpotlightGripView(context).also {
            safeAdd(it, activeParams(gripW, gripH).apply {
                x = gx
                y = gy
            })
        }
    }

    private fun removeSpotlightWindows() {
        spotlightGripView?.let { safeRemove(it) }
        spotlightGripView = null
    }

    private fun updateSpotlightGripPosition() {
        spotlightGripView?.let {
            val gripW = (228f * density).toInt()
            val gripH = (44f * density).toInt()
            val p = it.layoutParams as WindowManager.LayoutParams
            p.x = (spotlightX - gripW / 2f).toInt().coerceIn(10 * density.toInt(), screenW - gripW - 10 * density.toInt())
            val preferredY = (spotlightY + spotlightRadius + 14f * density).toInt()
            p.y = if (preferredY <= screenH - gripH - 16 * density) preferredY else (spotlightY - spotlightRadius - gripH - 14f * density).toInt().coerceAtLeast(16 * density.toInt())
            safeUpdate(it)
            it.invalidate()
        }
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
            val h = stripTopY.toInt().coerceAtLeast(1)
            if (p.height != h) {
                p.height = h
                safeUpdate(it)
            }
        }
        maskBotView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            val y = stripBotY.toInt()
            val h = (screenH - stripBotY).toInt().coerceAtLeast(1)
            if (p.y != y || p.height != h) {
                p.y = y
                p.height = h
                safeUpdate(it)
            }
        }
        maskView?.invalidate()
    }

    private fun updateMaskWindowLayouts() {
        if (!preferencesManager.isBlurEnabled || !isHardwareBlurSupported) return
        maskTopView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            val h = boxTop.toInt().coerceAtLeast(1)
            if (p.height != h) {
                p.height = h
                safeUpdate(it)
            }
        }
        maskBotView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            val y = boxBottom.toInt()
            val h = (screenH - boxBottom).toInt().coerceAtLeast(1)
            if (p.y != y || p.height != h) {
                p.y = y
                p.height = h
                safeUpdate(it)
            }
        }
        maskLeftView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            val y = boxTop.toInt()
            val w = boxLeft.toInt().coerceAtLeast(1)
            val h = (boxBottom - boxTop).toInt().coerceAtLeast(1)
            if (p.y != y || p.width != w || p.height != h) {
                p.y = y
                p.width = w
                p.height = h
                safeUpdate(it)
            }
        }
        maskRightView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            val x = boxRight.toInt()
            val y = boxTop.toInt()
            val w = (screenW - boxRight).toInt().coerceAtLeast(1)
            val h = (boxBottom - boxTop).toInt().coerceAtLeast(1)
            if (p.x != x || p.y != y || p.width != w || p.height != h) {
                p.x = x
                p.y = y
                p.width = w
                p.height = h
                safeUpdate(it)
            }
        }
    }

    private fun updateCornerPositions() {
        val csz = (CORNER_DP * density).toInt()
        val half = csz / 2
        fun updateCorner(i: Int, cx: Float, cy: Float) {
            cornerViews[i]?.let {
                val p = it.layoutParams as WindowManager.LayoutParams
                val nx = (cx - half).toInt().coerceIn(-half, screenW - half)
                val ny = (cy - half).toInt().coerceIn(-half, screenH - half)
                if (p.x != nx || p.y != ny) {
                    p.x = nx
                    p.y = ny
                    safeUpdate(it)
                }
            }
        }
        updateCorner(0, boxLeft, boxTop)
        updateCorner(1, boxRight, boxTop)
        updateCorner(2, boxLeft, boxBottom)
        updateCorner(3, boxRight, boxBottom)

        opacityBtnView?.let {
            val dropW = (DROP_W_DP * density).toInt()
            val dropH = (DROP_H_DP * density).toInt()
            val p = it.layoutParams as WindowManager.LayoutParams
            val nx = ((boxLeft + boxRight) / 2 - dropW / 2).toInt().coerceIn(0, screenW - dropW)
            val preferredY = (boxBottom + 12 * density).toInt()
            val maxY = screenH - dropH - 12 * density
            val ny = if (preferredY <= maxY) preferredY else (boxTop - dropH - 12 * density).toInt().coerceAtLeast(0)
            if (p.x != nx || p.y != ny) {
                p.x = nx
                p.y = ny
                safeUpdate(it)
                it.invalidate()
            }
        }
        
        if (preferencesManager.isBlurEnabled && isHardwareBlurSupported) {
            updateMaskWindowLayouts()
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
        overlayMode = Mode.BOX
        preferencesManager.overlayModeName = "BOX"

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
                        preferencesManager.overlayModeName = "SPLIT"
                        stripTopY = boxTop
                        stripBotY = (boxBottom).coerceAtLeast(boxTop + MERGE_GAP_DP * density * 4)
                        removeBoxWindows()
                        removeMaskWindows()
                        addMaskWindows()
                        addSplitWindows()
                        maskView?.invalidate()
                    }
                }
            })
        }
        modeSwitchAnimator?.start()
    }

    fun enterSpotlightMode() {
        if (overlayMode == Mode.SPOTLIGHT) return
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 40, 60), -1))
        overlayMode = Mode.SPOTLIGHT
        preferencesManager.overlayModeName = "SPOTLIGHT"

        spotlightRadius = (preferencesManager.spotlightRadiusDp * density).coerceIn(40 * density, screenW * 0.45f)
        spotlightX = screenW / 2f
        spotlightY = screenH / 2f

        removeSplitWindows()
        removeBoxWindows()
        removeMaskWindows()
        addSpotlightWindows()
        maskView?.invalidate()
    }

    fun enterSplitModeDirect() {
        if (overlayMode == Mode.SPLIT) return
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 20, 40, 20), -1))
        overlayMode = Mode.SPLIT
        preferencesManager.overlayModeName = "SPLIT"

        val half = (preferencesManager.cutoutHeightPct / 2f).coerceIn(0.02f, 0.45f)
        stripTopY = ((preferencesManager.cutoutYPct - half) * screenH).coerceIn(0f, screenH.toFloat())
        stripBotY = ((preferencesManager.cutoutYPct + half) * screenH).coerceIn(0f, screenH.toFloat())

        removeBoxWindows()
        removeSpotlightWindows()
        removeMaskWindows()
        addMaskWindows()
        addSplitWindows()
        maskView?.invalidate()
    }

    // ══════════════════════════════════════════════════════════════════
    // Shared corner dimming (controlled from any corner)
    // ══════════════════════════════════════════════════════════════════
    private val dimRun = Runnable {
        animCornerAlpha(DIM_ALPHA)
        boxEditActive = false
    }
    private val editDimRun = Runnable {
        animCornerAlpha(DIM_ALPHA)
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
        animCornerAlpha(FULL_ALPHA)
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
        updateCornerPositions()
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
    private fun animCornerAlpha(targetAlpha: Int, durationMs: Long = 260L) {
        cancelCornerAlphaFade()
        val start = cornerAlpha_
        if (start == targetAlpha) return
        val animator = ValueAnimator.ofInt(start, targetAlpha).apply {
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
                cornerAlpha_ = va.animatedValue as Int
                invalidateCorners()
                maskView?.invalidate()
            }
        }
        cornerAlphaAnimator = animator
        animator.start()
    }

    // ══════════════════════════════════════════════════════════════════
    // VIEW 1 — Fullscreen passive mask
    // Draws: dark mask + clear window + box border/corners (in box mode)
    // ══════════════════════════════════════════════════════════════════
    private inner class MaskWindowView(ctx: Context) : View(ctx) {
        override fun onDraw(canvas: Canvas) {
            // Kept empty: actual mask rendering is unified in MaskView to eliminate
            // multi-surface overlap artifacts and tearing.
            // This window exists solely to host FLAG_BLUR_BEHIND on supported platforms.
        }
    }

    private inner class MaskView(ctx: Context) : View(ctx) {
        private val fillP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val noiseP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edgeP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }

        override fun onDraw(canvas: Canvas) {
            val sw = width.toFloat().coerceAtLeast(screenW.toFloat())
            val sh = height.toFloat().coerceAtLeast(screenH.toFloat())

            // 1. Emergency Blackout (6-DoF Tilt / Panic Shake)
            if (isTiltBlackout && preferencesManager.isTiltShieldEnabled) {
                canvas.drawColor(Color.BLACK)
                val accent = getDynamicAccentColor()
                val warningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 14f * density
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }
                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * density
                    color = accent
                }
                val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = 0xEE161426.toInt()
                }
                val cy = sh / 2f
                val cx = sw / 2f
                val badgeW = 290f * density
                val badgeH = 50f * density
                val r = RectF(cx - badgeW / 2, cy - badgeH / 2, cx + badgeW / 2, cy + badgeH / 2)
                canvas.drawRoundRect(r, 25f * density, 25f * density, badgeBg)
                canvas.drawRoundRect(r, 25f * density, 25f * density, badgePaint)
                canvas.drawText("🛡️ TILT SHIELD LOCK (Upright to restore)", cx, cy + 5f * density, warningPaint)
                return
            } else if (isPanicBlackout && preferencesManager.isPanicShakeEnabled) {
                canvas.drawColor(Color.BLACK)
                val accent = getDynamicAccentColor()
                val warningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 14f * density
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }
                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * density
                    color = accent
                }
                val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = 0xEE161426.toInt()
                }
                val cy = sh / 2f
                val cx = sw / 2f
                val badgeW = 310f * density
                val badgeH = 50f * density
                val r = RectF(cx - badgeW / 2, cy - badgeH / 2, cx + badgeW / 2, cy + badgeH / 2)
                canvas.drawRoundRect(r, 25f * density, 25f * density, badgeBg)
                canvas.drawRoundRect(r, 25f * density, 25f * density, badgePaint)
                canvas.drawText("🚨 PANIC SHIELD LOCK (Double-shake to restore)", cx, cy + 5f * density, warningPaint)
                return
            }

            val isBlur = preferencesManager.isBlurEnabled
            val realPct = OpacityCurve.displayToRealPct(opacity())
            val rawColor = getParsedColor()
            val alphaMult = overlayAlpha / 255f

            if (isBlur) {
                val solidAlpha = OpacityCurve.realPctToAlpha(realPct)
                val tintAlpha = if (isHardwareBlurSupported) {
                    ((realPct / 100f * 160) * alphaMult).toInt().coerceIn(0, 255)
                } else {
                    (solidAlpha * alphaMult).toInt().coerceIn(0, 255)
                }
                fillP.color = rawColor
                fillP.alpha = tintAlpha

                noiseP.shader = getNoiseShader()
                val noiseAlpha = ((realPct / 100f * 110) * alphaMult).toInt().coerceIn(0, 255)
                noiseP.alpha = noiseAlpha
            } else {
                fillP.color = rawColor
                fillP.alpha = (OpacityCurve.realPctToAlpha(realPct) * alphaMult).toInt().coerceIn(0, 255)
            }

            fun drawMaskRect(l: Float, t: Float, r: Float, b: Float) {
                if (r <= l || b <= t) return
                canvas.drawRect(l, t, r, b, fillP)
                if (isBlur) {
                    canvas.drawRect(l, t, r, b, noiseP)
                }
            }

            // Mode Rendering — ONLY render when the main privacy mask is actively showing!
            if (isShowing) {
                when (overlayMode) {
                    Mode.SPLIT -> {
                        drawMaskRect(0f, 0f, sw, stripTopY)
                        drawMaskRect(0f, stripBotY, sw, sh)
                    }
                    Mode.BOX -> {
                        drawMaskRect(0f, 0f, sw, boxTop)
                        drawMaskRect(0f, boxBottom, sw, sh)
                        drawMaskRect(0f, boxTop, boxLeft, boxBottom)
                        drawMaskRect(boxRight, boxTop, sw, boxBottom)

                        val accent = getDynamicAccentColor()
                        val accentShadow = getDynamicAccentShadowColor()
                        val effectiveAlpha = ((cornerAlpha_ / 255f) * (overlayAlpha / 255f) * 255).toInt()

                        edgeP.color = accent
                        edgeP.alpha = effectiveAlpha
                        edgeP.setShadowLayer(5 * density, 0f, 0f, accentShadow)
                        canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, edgeP)
                        edgeP.clearShadowLayer()
                    }
                    Mode.SPOTLIGHT -> {
                        val spotlightPath = Path().apply {
                            fillType = Path.FillType.EVEN_ODD
                            addRect(0f, 0f, sw, sh, Path.Direction.CW)
                            addCircle(spotlightX, spotlightY, spotlightRadius, Path.Direction.CCW)
                        }
                        canvas.drawPath(spotlightPath, fillP)
                        if (isBlur) {
                            canvas.drawPath(spotlightPath, noiseP)
                        }

                        val accent = getDynamicAccentColor()
                        val accentShadow = getDynamicAccentShadowColor()
                        val effectiveAlpha = ((cornerAlpha_ / 255f) * (overlayAlpha / 255f) * 255).toInt()

                        edgeP.color = accent
                        edgeP.alpha = effectiveAlpha
                        edgeP.setShadowLayer(6 * density, 0f, 0f, accentShadow)
                        canvas.drawCircle(spotlightX, spotlightY, spotlightRadius, edgeP)
                        edgeP.clearShadowLayer()
                    }
                }
            }

            // ── AI Sensitive Field Micro-Shields ──
            if (sensitiveFieldRects.isNotEmpty()) {
                val accent = getDynamicAccentColor()
                // Use user's selected overlay color from settings with high opacity
                val shieldColor = (rawColor and 0x00FFFFFF) or 0xFA000000.toInt()
                val shieldBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = shieldColor
                }
                edgeP.color = accent
                edgeP.alpha = 220
                edgeP.strokeWidth = 1.5f * density
                for (fRect in sensitiveFieldRects) {
                    canvas.drawRoundRect(fRect, 6f * density, 6f * density, shieldBgP)
                    if (isBlur) {
                        canvas.drawRoundRect(fRect, 6f * density, 6f * density, noiseP)
                    }
                    canvas.drawRoundRect(fRect, 6f * density, 6f * density, edgeP)
                }
            }

            // ── Notification Privacy Shield ("Inverse Box") ──
            if (isNotificationShieldActive) {
                val bannerRect = RectF(12f * density, 8f * density, sw - 12f * density, notificationBannerHeight)
                val notifBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = 0xF812111A.toInt()
                }
                canvas.drawRoundRect(bannerRect, 18f * density, 18f * density, notifBgP)
                if (isBlur) {
                    canvas.drawRoundRect(bannerRect, 18f * density, 18f * density, noiseP)
                }
                val accent = getDynamicAccentColor()
                edgeP.color = accent
                edgeP.alpha = 220
                edgeP.strokeWidth = 2f * density
                edgeP.setShadowLayer(4 * density, 0f, 0f, getDynamicAccentShadowColor())
                canvas.drawRoundRect(bannerRect, 18f * density, 18f * density, edgeP)
                edgeP.clearShadowLayer()

                val notifTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 12f * density
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    alpha = 220
                }
                canvas.drawText("🔒 Notification Obscured for Privacy", sw / 2f, notificationBannerHeight / 2f + 4f * density, notifTextPaint)
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

        private var stripAlphaAnimator: ValueAnimator? = null

        private fun animStripAlpha(targetAlpha: Int, durationMs: Long = 260L) {
            stripAlphaAnimator?.cancel()
            stripAlphaAnimator = null
            val start = alpha_
            if (start == targetAlpha) return
            val animator = ValueAnimator.ofInt(start, targetAlpha).apply {
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
                    alpha_ = va.animatedValue as Int
                    invalidate()
                }
            }
            stripAlphaAnimator = animator
            animator.start()
        }

        private val idleRun = Runnable { animStripAlpha(DIM_ALPHA) }
        private val longRun = Runnable { bump(); enterBoxMode() }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null); scheduleIdle() }

        private fun scheduleIdle() { mainHandler.removeCallbacks(idleRun); mainHandler.postDelayed(idleRun, IDLE_MS) }
        private fun wakeUp() { mainHandler.removeCallbacks(idleRun); animStripAlpha(FULL_ALPHA); scheduleIdle() }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            mainHandler.removeCallbacks(idleRun)
            mainHandler.removeCallbacks(longRun)
            stripAlphaAnimator?.cancel()
            stripAlphaAnimator = null
        }

        override fun onDraw(canvas: Canvas) {
            val effectiveAlpha = (alpha_ * (overlayAlpha / 255f)).toInt()
            val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2f
            val merged = (stripBotY - stripTopY) < MERGE_GAP_DP * density
            val th = 3f * density
            val gap = 16f * density
            val accentColor = getDynamicAccentColor()
            
            trackBg.color = Color.WHITE; trackBg.alpha = (effectiveAlpha * 0.18f).toInt()
            canvas.drawRoundRect(gap, cy - th / 2, w - gap, cy + th / 2, th / 2, th / 2, trackBg)
            
            val minX = gap + EYE_R_DP * density
            val maxX = w - gap - EYE_R_DP * density
            val range = maxX - minX
            val eyeX = minX + (opacity() / 100f) * range

            trackFg.color = accentColor
            trackFg.alpha = effectiveAlpha
            canvas.drawRoundRect(gap, cy - th / 2, eyeX, cy + th / 2, th / 2, th / 2, trackFg)
            
            val aArr = 7 * density
            arrow_.alpha = (effectiveAlpha * 0.30f).toInt()
            canvas.drawLine(14 * density, cy, 14 * density + aArr, cy - aArr, arrow_)
            canvas.drawLine(14 * density, cy, 14 * density + aArr, cy + aArr, arrow_)
            val rx = w - 14 * density
            canvas.drawLine(rx, cy, rx - aArr, cy - aArr, arrow_)
            canvas.drawLine(rx, cy, rx - aArr, cy + aArr, arrow_)
            
            drawEye(canvas, eyeX, cy, merged, effectiveAlpha, accentColor)
        }

        private fun drawEye(canvas: Canvas, cx: Float, cy: Float, merged: Boolean, effectiveAlpha: Int, accentColor: Int) {
            val r = EYE_R_DP * density
            glowDefaultP.color = accentColor
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
                    }
                    if (isDragging) {
                        if (dragMode == DRAG_VERT) {
                            if (wasMerged) { stripTopY = screenH * 0.35f; stripBotY = screenH * 0.65f; wasMerged = false }
                            else {
                                val sh = height.toFloat(); val mg = MERGE_GAP_DP * density
                                val dyRaw = e.rawY - lastRawY
                                if (isTop) {
                                    stripTopY = (stripTopY + dyRaw).coerceIn(sh / 2, stripBotY - mg)
                                    if (preferencesManager.isBlurEnabled && isHardwareBlurSupported) {
                                        maskTopView?.let {
                                            val p = it.layoutParams as WindowManager.LayoutParams
                                            val h = stripTopY.toInt().coerceAtLeast(1)
                                            if (p.height != h) {
                                                p.height = h
                                                safeUpdate(it)
                                            }
                                        }
                                    }
                                } else {
                                    stripBotY = (stripBotY + dyRaw).coerceIn(stripTopY + mg, screenH - sh / 2)
                                    if (preferencesManager.isBlurEnabled && isHardwareBlurSupported) {
                                        maskBotView?.let {
                                            val p = it.layoutParams as WindowManager.LayoutParams
                                            val y = stripBotY.toInt()
                                            val h = (screenH - stripBotY).toInt().coerceAtLeast(1)
                                            if (p.y != y || p.height != h) {
                                                p.y = y
                                                p.height = h
                                                safeUpdate(it)
                                            }
                                        }
                                    }
                                }
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
                            updateStripLayouts()
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
            val accent = getDynamicAccentColor()
            val accentShadow = getDynamicAccentShadowColor()

            bgP.color = accent
            bgP.alpha = effectiveAlpha
            bgP.setShadowLayer(6 * density, 0f, 0f, accentShadow)
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
                    boxEditActive = true
                    mainHandler.removeCallbacks(hintRun)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lx
                    val dy = e.rawY - ly
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        bump()
                    }
                    if (dragging) {
                        dragBoxCorner(cornerIdx, dx, dy)
                        scheduleEditDim()
                        lx = e.rawX
                        ly = e.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(hintRun)
                    if (dragging) {
                        updateCornerPositions()
                        maskView?.invalidate()
                        if (boxBottom > boxTop && screenH > 0) {
                            preferencesManager.cutoutYPct = ((boxTop + boxBottom) / 2f / screenH).coerceIn(0f, 1f)
                            preferencesManager.cutoutHeightPct = ((boxBottom - boxTop) / screenH).coerceIn(0.05f, 0.9f)
                        }
                    }
                    scheduleEditDim()
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
        private var btnAlphaAnimator: ValueAnimator? = null
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

        private fun animDropAlpha(targetAlpha: Int, durationMs: Long = 260L) {
            btnAlphaAnimator?.cancel()
            btnAlphaAnimator = null
            val start = btnAlpha
            if (start == targetAlpha) return
            val animator = ValueAnimator.ofInt(start, targetAlpha).apply {
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
                    btnAlpha = va.animatedValue as Int
                    invalidate()
                }
            }
            btnAlphaAnimator = animator
            animator.start()
        }

        private val idleRun  = Runnable { animDropAlpha(DROP_DIM_ALPHA) }
        private val longRun  = Runnable { bump(); enterSpotlightMode() }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null); scheduleIdle() }

        private fun scheduleIdle() { mainHandler.removeCallbacks(idleRun); mainHandler.postDelayed(idleRun, IDLE_MS) }
        fun wakeUp() { mainHandler.removeCallbacks(idleRun); animDropAlpha(FULL_ALPHA); scheduleIdle() }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            mainHandler.removeCallbacks(idleRun)
            mainHandler.removeCallbacks(longRun)
            btnAlphaAnimator?.cancel()
            btnAlphaAnimator = null
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = 3f * density
            pillRect.set(pad, pad, w - pad, h - pad)
            val cornerR = (h - 2 * pad) / 2f

            val effectiveAlpha = ((btnAlpha / 255f) * (overlayAlpha / 255f) * 255).toInt()
            val surfaceColor = getDynamicSurfaceContainerColor()
            val accentRingColor = getDynamicAccentRingColor()
            val accentColor = getDynamicAccentColor()
            val shadowColor = getDynamicAccentShadowColor()

            // Elevation drop shadow + solid opaque pitch-dark background
            // Uses dynamic surface container tone so it matches Material 3 Expressive theme
            bgP.color = surfaceColor
            bgP.alpha = ((overlayAlpha / 255f) * 255).toInt().coerceIn(0, 255)
            bgP.setShadowLayer(6 * density, 0f, 2 * density, shadowColor)
            canvas.drawRoundRect(pillRect, cornerR, cornerR, bgP)
            bgP.clearShadowLayer()

            // Dynamic accent border
            ringP.color = accentRingColor
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
            dropDotP.color = accentColor
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

    // ══════════════════════════════════════════════════════════════════
    // VIEW 5 — Spotlight Controller Toolbar (SPOTLIGHT mode)
    // Left: Drag handle | Center: Circle size - / + | Right: Opacity slider & autohide
    // ══════════════════════════════════════════════════════════════════
    @SuppressLint("ViewConstructor")
    private inner class SpotlightGripView(ctx: Context) : View(ctx) {
        private var btnAlpha = FULL_ALPHA
        private var btnAlphaAnimator: ValueAnimator? = null
        private var startRawX = 0f
        private var startRawY = 0f
        private var startSpotX = 0f
        private var startSpotY = 0f
        private var startRadius = 0f
        private var startOp = 0
        private var isDragging = false
        private var touchMode = 0
        private var lastHapticNotch = -1

        private val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
        }
        private val divP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val iconFillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f * density
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val subTextP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8f * density
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val btnSymbolP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f * density
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val dropPath = Path()
        private val pillRect = RectF()

        private val idleRun = Runnable { animAlpha(DROP_DIM_ALPHA) }
        private val longRun = Runnable { bump(); enterSplitModeDirect() }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            scheduleIdle()
        }

        private fun scheduleIdle() {
            mainHandler.removeCallbacks(idleRun)
            mainHandler.postDelayed(idleRun, IDLE_MS)
        }

        fun wakeUp() {
            mainHandler.removeCallbacks(idleRun)
            animAlpha(FULL_ALPHA)
            scheduleIdle()
        }

        private fun animAlpha(targetAlpha: Int) {
            btnAlphaAnimator?.cancel()
            val start = btnAlpha
            if (start == targetAlpha) return
            btnAlphaAnimator = ValueAnimator.ofInt(start, targetAlpha).apply {
                duration = 260L
                addUpdateListener { va ->
                    if (!isShowing) return@addUpdateListener
                    btnAlpha = va.animatedValue as Int
                    invalidate()
                }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            mainHandler.removeCallbacks(idleRun)
            mainHandler.removeCallbacks(longRun)
            btnAlphaAnimator?.cancel()
            btnAlphaAnimator = null
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = 3f * density
            pillRect.set(pad, pad, w - pad, h - pad)
            val cornerR = (h - 2 * pad) / 2f

            val effectiveAlpha = ((btnAlpha / 255f) * (overlayAlpha / 255f) * 255).toInt().coerceIn(0, 255)
            val surfaceColor = getDynamicSurfaceContainerColor()
            val accentRingColor = getDynamicAccentRingColor()
            val accentColor = getDynamicAccentColor()
            val shadowColor = getDynamicAccentShadowColor()

            // 1. Pill Background with proper autohide alpha & drop shadow
            bgP.color = surfaceColor
            bgP.alpha = effectiveAlpha
            if (effectiveAlpha > 100) {
                bgP.setShadowLayer(6 * density, 0f, 2 * density, shadowColor)
            } else {
                bgP.clearShadowLayer()
            }
            canvas.drawRoundRect(pillRect, cornerR, cornerR, bgP)
            bgP.clearShadowLayer()

            // 2. Pill Border
            ringP.color = accentRingColor
            ringP.alpha = effectiveAlpha
            canvas.drawRoundRect(pillRect, cornerR, cornerR, ringP)

            // 3. Section Dividers
            val div1X = 58f * density
            val div2X = 142f * density
            divP.color = accentRingColor
            divP.alpha = (effectiveAlpha * 0.45f).toInt()
            canvas.drawLine(div1X, 8f * density, div1X, h - 8f * density, divP)
            canvas.drawLine(div2X, 8f * density, div2X, h - 8f * density, divP)

            // ── Section 1: Drag / Move Handle (0 .. 58dp) ──
            val dragCx = 29f * density
            val dragCy = h / 2f - 2f * density
            iconP.color = accentColor
            iconP.alpha = effectiveAlpha
            iconFillP.color = accentColor
            iconFillP.alpha = effectiveAlpha

            // Center dot
            canvas.drawCircle(dragCx, dragCy, 2.5f * density, iconFillP)
            // 4 directional arrows (✥ crosshair)
            val arm = 7.5f * density
            canvas.drawLine(dragCx - arm, dragCy, dragCx + arm, dragCy, iconP)
            canvas.drawLine(dragCx, dragCy - arm, dragCx, dragCy + arm, iconP)
            // Subtitle
            subTextP.color = Color.WHITE
            subTextP.alpha = (effectiveAlpha * 0.75f).toInt()
            canvas.drawText("DRAG", dragCx, h - 5f * density, subTextP)

            // ── Section 2: Circle Size Controls (58dp .. 142dp) ──
            val sizeMinusCx = 76f * density
            val sizeCenterCx = 100f * density
            val sizePlusCx = 124f * density
            val midCy = h / 2f

            btnSymbolP.alpha = effectiveAlpha
            canvas.drawText("−", sizeMinusCx, midCy + 5f * density, btnSymbolP)
            canvas.drawText("+", sizePlusCx, midCy + 5f * density, btnSymbolP)

            // Circle glyph in center with size
            iconP.color = accentColor
            iconP.alpha = effectiveAlpha
            canvas.drawCircle(sizeCenterCx, midCy - 3f * density, 5.5f * density, iconP)
            subTextP.color = Color.WHITE
            subTextP.alpha = (effectiveAlpha * 0.85f).toInt()
            val radDp = (spotlightRadius / density).toInt()
            canvas.drawText("${radDp}dp", sizeCenterCx, h - 5f * density, subTextP)

            // ── Section 3: Opacity Adjustment (142dp .. 228dp) ──
            val dropCx = 158f * density
            val dropCy = midCy
            val dr = 4.5f * density
            dropPath.reset()
            dropPath.moveTo(dropCx, dropCy - dr * 1.3f)
            dropPath.cubicTo(dropCx + dr * 1.05f, dropCy - dr * 0.15f, dropCx + dr * 0.95f, dropCy + dr * 0.95f, dropCx, dropCy + dr * 1.1f)
            dropPath.cubicTo(dropCx - dr * 0.95f, dropCy + dr * 0.95f, dropCx - dr * 1.05f, dropCy - dr * 0.15f, dropCx, dropCy - dr * 1.3f)
            iconFillP.color = Color.WHITE
            iconFillP.alpha = effectiveAlpha
            canvas.drawPath(dropPath, iconFillP)

            // Droplet center dot
            val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = accentColor
                alpha = effectiveAlpha
            }
            canvas.drawCircle(dropCx, dropCy + dr * 0.35f, dr * 0.32f, dotP)

            // Opacity text
            val textCx = 193f * density
            val textCy = midCy - (textP.descent() + textP.ascent()) / 2f
            textP.alpha = effectiveAlpha
            canvas.drawText(pctStrings[opacity()], textCx, textCy, textP)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    wakeUp()
                    tick()
                    startRawX = e.rawX
                    startRawY = e.rawY
                    startSpotX = spotlightX
                    startSpotY = spotlightY
                    startRadius = spotlightRadius
                    startOp = opacity()
                    isDragging = false
                    lastHapticNotch = opacity() / 10

                    val x = e.x
                    if (x < 58f * density) {
                        touchMode = 1 // DRAG
                        mainHandler.postDelayed(longRun, 520L)
                    } else if (x <= 142f * density) {
                        touchMode = 2 // SIZE
                    } else {
                        touchMode = 3 // OPACITY
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startRawX
                    val dy = e.rawY - startRawY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        mainHandler.removeCallbacks(longRun)
                    }
                    when (touchMode) {
                        1 -> { // DRAG
                            if (isDragging) {
                                spotlightX = (startSpotX + dx).coerceIn(spotlightRadius, screenW - spotlightRadius)
                                spotlightY = (startSpotY + dy).coerceIn(spotlightRadius, screenH - spotlightRadius)
                                updateSpotlightGripPosition()
                                maskView?.invalidate()
                            }
                        }
                        2 -> { // SIZE (continuous drag also supported)
                            if (isDragging) {
                                val newR = (startRadius + dx * 0.4f).coerceIn(45f * density, screenW * 0.45f)
                                if (newR != spotlightRadius) {
                                    spotlightRadius = newR
                                    preferencesManager.spotlightRadiusDp = (spotlightRadius / density).toInt()
                                    updateSpotlightGripPosition()
                                    maskView?.invalidate()
                                    invalidate()
                                }
                            }
                        }
                        3 -> { // OPACITY
                            if (isDragging) {
                                val newOp = (startOp + (dx / (65f * density) * 100).toInt()).coerceIn(0, 100)
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
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longRun)
                    if (touchMode == 2 && !isDragging) {
                        // Tap minus or plus without dragging
                        if (e.x < 88f * density) {
                            spotlightRadius = (spotlightRadius - 15f * density).coerceAtLeast(45f * density)
                            preferencesManager.spotlightRadiusDp = (spotlightRadius / density).toInt()
                            updateSpotlightGripPosition()
                            maskView?.invalidate()
                            invalidate()
                            tick()
                        } else if (e.x > 112f * density) {
                            spotlightRadius = (spotlightRadius + 15f * density).coerceAtMost(screenW * 0.45f)
                            preferencesManager.spotlightRadiusDp = (spotlightRadius / density).toInt()
                            updateSpotlightGripPosition()
                            maskView?.invalidate()
                            invalidate()
                            tick()
                        }
                    } else if (touchMode == 3) {
                        if (!isDragging) {
                            // Tap on opacity section cycles by +10%
                            val cur = opacity()
                            val next = if (cur >= 100) 10 else ((cur / 10) + 1) * 10
                            setOpacityLive(next)
                            tick()
                            invalidate()
                        }
                        persistOpacity()
                    }
                    scheduleIdle()
                    isDragging = false
                    touchMode = 0
                    return true
                }
            }
            return false
        }
    }
}
