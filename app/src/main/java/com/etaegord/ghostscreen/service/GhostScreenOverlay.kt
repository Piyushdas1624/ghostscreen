package com.etaegord.ghostscreen.service

import android.annotation.SuppressLint
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
import com.etaegord.ghostscreen.data.PreferencesManager
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

    private var screenW = 0
    private var screenH = 0

    // ── Windows ─────────────────────────────────────────────────────
    private var maskView: MaskView? = null
    private var stripTopView: StripView? = null
    private var stripBotView: StripView? = null
    // Box mode: 4 corner handles + opacity drop + exit (always last)
    private val cornerViews = arrayOfNulls<CornerHandleView>(4)  // TL, TR, BL, BR
    private var opacityBtnView: OpacityDropView? = null
    private var exitBtnView: ExitButtonView? = null

    private var overlayMode = Mode.SPLIT
    private var isShowing = false

    // ── Opacity ─────────────────────────────────────────────────────
    private var opacity: Int
        get() = preferencesManager.transparency
        set(v) { preferencesManager.transparency = v.coerceIn(0, 100); maskView?.invalidate() }

    // ── SPLIT state ─────────────────────────────────────────────────
    private var stripTopY = 0f
    private var stripBotY = 0f

    // ── BOX state ───────────────────────────────────────────────────
    private var boxLeft   = 0f; private var boxTop    = 0f
    private var boxRight  = 0f; private var boxBottom = 0f

    // ── Shared edit state (used across corner handles) ───────────────
    private var boxEditActive = false
    private var cornerAlpha_  = FULL_ALPHA   // shared dimming for all corners + border

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
        stripTopY = screenH * 0.35f
        stripBotY = screenH * 0.65f

        addMask()
        addSplitWindows()
        addExitButton()
        isShowing = true; overlayMode = Mode.SPLIT
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 50, 30), -1))
    }

    fun hide() {
        if (!isShowing) return
        removeSplitWindows()
        removeBoxWindows()
        maskView?.let { safeRemove(it) }; maskView = null
        exitBtnView?.let { safeRemove(it) }; exitBtnView = null
        isShowing = false
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
        val cornerPositions = listOf(
            Pair(boxLeft, boxTop), Pair(boxRight - csz, boxTop),
            Pair(boxLeft, boxBottom - csz), Pair(boxRight - csz, boxBottom - csz)
        )
        cornerPositions.forEachIndexed { i, (cx, cy) ->
            cornerViews[i] = CornerHandleView(context, i).also {
                safeAdd(it, activeParams(csz, csz).apply { x = cx.toInt(); y = cy.toInt() })
            }
        }
        val dropSz = (DROP_DP * density).toInt()
        val extraH  = (28 * density).toInt()  // room for % label above button
        opacityBtnView = OpacityDropView(context).also {
            safeAdd(it, activeParams(dropSz, dropSz + extraH).apply {
                x = ((boxLeft + boxRight) / 2 - dropSz / 2).toInt().coerceIn(0, screenW - dropSz)
                y = (boxBottom + 10 * density).toInt().coerceAtMost(screenH - dropSz - extraH)
            })
        }
        exitBtnView?.let { safeRemove(it) }; exitBtnView = null
        addExitButton()
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
        maskView?.invalidate()
    }

    private fun updateCornerPositions() {
        val csz = (CORNER_DP * density).toInt()
        val positions = listOf(
            Pair(boxLeft, boxTop), Pair(boxRight - csz, boxTop),
            Pair(boxLeft, boxBottom - csz), Pair(boxRight - csz, boxBottom - csz)
        )
        positions.forEachIndexed { i, (cx, cy) ->
            cornerViews[i]?.let {
                val p = it.layoutParams as WindowManager.LayoutParams
                p.x = cx.toInt().coerceIn(0, screenW - csz)
                p.y = cy.toInt().coerceIn(0, screenH - csz)
                safeUpdate(it); it.invalidate()
            }
        }
        opacityBtnView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            val dropSz = it.width
            p.x = ((boxLeft + boxRight) / 2 - dropSz / 2).toInt().coerceIn(0, screenW - dropSz)
            p.y = (boxBottom + 10 * density).toInt().coerceAtMost(screenH - it.height)
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
        overlayMode = Mode.BOX
        boxLeft = screenW * 0.08f; boxTop = stripTopY
        boxRight = screenW * 0.92f; boxBottom = stripBotY
        removeSplitWindows(); addBoxWindows(); maskView?.invalidate()
    }

    private fun enterSplitMode() {
        if (overlayMode == Mode.SPLIT) return
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 20, 40, 20), -1))
        overlayMode = Mode.SPLIT
        stripTopY = boxTop
        stripBotY = (boxBottom).coerceAtLeast(boxTop + MERGE_GAP_DP * density * 4)
        removeBoxWindows()
        addSplitWindows()
        exitBtnView?.let { safeRemove(it) }; exitBtnView = null
        addExitButton()
        maskView?.invalidate()
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
    private fun animAlpha(get: () -> Int, set: (Int) -> Unit, target: Int) {
        val steps = 14; val step = (target - get()) / steps.toFloat(); var i = 0
        val r = object : Runnable {
            override fun run() {
                if (i++ < steps) { set((get() + step).toInt().coerceIn(0, 255)); mainHandler.postDelayed(this, 13) }
                else set(target)
            }
        }
        mainHandler.post(r)
    }

    // ══════════════════════════════════════════════════════════════════
    // VIEW 1 — Fullscreen passive mask
    // Draws: dark mask + clear window + box border/corners (in box mode)
    // ══════════════════════════════════════════════════════════════════
    private inner class MaskView(ctx: Context) : View(ctx) {
        private val fillP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val clearP = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val frostP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val edgeP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
        private val dotP   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        init { setLayerType(LAYER_TYPE_HARDWARE, null) }

        override fun onDraw(canvas: Canvas) {
            val alpha = ((opacity / 100f) * 255).toInt().coerceIn(0, 255)
            val blurEnabled = preferencesManager.isBlurEnabled
            val rawColor = try { Color.parseColor(preferencesManager.color) } catch (_: Exception) { Color.BLACK }

            // Fill mask
            fillP.color = rawColor; fillP.alpha = alpha
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillP)

            // Window cutout or frost
            val winRect = when (overlayMode) {
                Mode.SPLIT -> { val gap = stripBotY - stripTopY; if (gap > 0) RectF(0f, stripTopY, width.toFloat(), stripBotY) else null }
                Mode.BOX   -> RectF(boxLeft, boxTop, boxRight, boxBottom)
            }
            if (winRect != null) {
                if (blurEnabled) {
                    // Frosted glass — partial opacity in window instead of full clear
                    frostP.color = Color.WHITE
                    frostP.alpha = ((opacity / 100f) * 180 + 30).toInt().coerceIn(40, 220)
                    frostP.maskFilter = BlurMaskFilter((opacity / 100f * 16 + 4) * density, BlurMaskFilter.Blur.NORMAL)
                    canvas.drawRect(winRect, frostP)
                    frostP.maskFilter = null
                } else {
                    canvas.drawRect(winRect, clearP)
                }
            }

            // Box mode: draw border + corner dots on top (MaskView is NOT_TOUCHABLE so fine to draw here)
            if (overlayMode == Mode.BOX) {
                edgeP.color = Color.parseColor("#7C4DFF"); edgeP.alpha = cornerAlpha_
                edgeP.setShadowLayer(5 * density, 0f, 0f, Color.parseColor("#887C4DFF"))
                canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, edgeP)
                edgeP.clearShadowLayer()
                dotP.color = Color.parseColor("#7C4DFF"); dotP.alpha = cornerAlpha_
                listOf(Pair(boxLeft, boxTop), Pair(boxRight, boxTop), Pair(boxLeft, boxBottom), Pair(boxRight, boxBottom))
                    .forEach { (hx, hy) -> canvas.drawCircle(hx, hy, 12 * density, dotP) }
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

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2f
            val merged = (stripBotY - stripTopY) < MERGE_GAP_DP * density
            val th = 3f * density
            trackBg.color = Color.WHITE; trackBg.alpha = (alpha_ * 0.18f).toInt()
            canvas.drawRoundRect(0f, cy - th / 2, w, cy + th / 2, th / 2, th / 2, trackBg)
            val opX = w * (opacity / 100f)
            trackFg.alpha = alpha_
            canvas.drawRoundRect(0f, cy - th / 2, opX, cy + th / 2, th / 2, th / 2, trackFg)
            val aArr = 7 * density
            arrow_.alpha = (alpha_ * 0.30f).toInt()
            canvas.drawLine(14 * density, cy, 14 * density + aArr, cy - aArr, arrow_)
            canvas.drawLine(14 * density, cy, 14 * density + aArr, cy + aArr, arrow_)
            val rx = w - 14 * density
            canvas.drawLine(rx, cy, rx - aArr, cy - aArr, arrow_)
            canvas.drawLine(rx, cy, rx - aArr, cy + aArr, arrow_)
            val eyeX = opX.coerceIn(EYE_R_DP * density, w - EYE_R_DP * density)
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
                // Opacity % — drawn below pupil with 6dp gap so it never clips outside the pill
                tPaint.textSize = 7.5f * density; tPaint.color = Color.WHITE
                tPaint.alpha = (alpha_ * 0.80f).toInt()
                // Pill inner space: pill height = r*1.24, pupil at cy, text at cy + r*0.42 (still inside pill)
                canvas.drawText("${opacity}%", cx, cy + r * 0.46f, tPaint)
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
                    if (!wasMerged) mainHandler.postDelayed(longRun, 520L)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(e.x - pressStartX); val dy = abs(e.rawY - pressStartRawY)
                    if (!isDragging && (dx > 5 * density || dy > 5 * density)) { isDragging = true; mainHandler.removeCallbacks(longRun) }
                    if (isDragging) {
                        if (dy > dx) {
                            if (wasMerged) { stripTopY = screenH * 0.35f; stripBotY = screenH * 0.65f; wasMerged = false }
                            else moveStrip(e.rawY - lastRawY)
                        } else {
                            opacity = ((e.x / width.toFloat()) * 100).toInt().coerceIn(0, 100)
                            stripTopView?.invalidate(); stripBotView?.invalidate()
                        }
                        lastRawY = e.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longRun); scheduleIdle(); return true
                }
            }
            return false
        }

        private fun moveStrip(dy: Float) {
            val sh = height.toFloat(); val mg = MERGE_GAP_DP * density
            if (isTop) stripTopY = (stripTopY + dy).coerceIn(sh / 2, stripBotY - mg)
            else stripBotY = (stripBotY + dy).coerceIn(stripTopY + mg, screenH - sh / 2)
            updateStripLayouts()
        }
    }

    // ══════════════════════════════════════════════════════════════════
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
                    }
                    if (dragging && boxEditActive) {
                        applyBoxDelta(cornerIdx, dx, dy)
                        scheduleEditDim()
                    }
                    lx = e.rawX; ly = e.rawY; return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(hintRun); return true
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
                val label = "${opacity}%"
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
                    wakeUp(); tick(); startX = e.rawX; startOp = opacity; dragging = false
                    showLabel = true; invalidate()
                    mainHandler.postDelayed(longRun, 520L)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    if (!dragging && abs(dx) > 7 * density) { dragging = true; mainHandler.removeCallbacks(longRun) }
                    if (dragging) {
                        opacity = (startOp + (dx / (screenW * 0.6f) * 100).toInt()).coerceIn(0, 100)
                        showLabel = true; maskView?.invalidate(); invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longRun)
                    mainHandler.removeCallbacks(hideLbl)
                    mainHandler.postDelayed(hideLbl, 1400)
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
                MotionEvent.ACTION_UP -> { if (!dragging) { tick(); hide() } else { btnAlpha = 95; invalidate() }; return true }
            }
            return false
        }
    }
}
