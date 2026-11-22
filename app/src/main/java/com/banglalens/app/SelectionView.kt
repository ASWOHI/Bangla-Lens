package com.banglalens.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Full-screen dim overlay; the user drags a rectangle over the text to read. */
class SelectionView(
    context: Context,
    private val onSelected: (Rect) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF66BB6A.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * d
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val textMain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B5E20.toInt(); textSize = 17 * d; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val textSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); textSize = 15 * d; textAlign = Paint.Align.CENTER
    }

    private val hintEn = context.getString(R.string.select_hint_en)
    private val hintBn = context.getString(R.string.select_hint_bn)
    private val cancelEn = context.getString(R.string.select_cancel_en)
    private val cancelBn = context.getString(R.string.select_cancel_bn)

    private val hintRect = RectF()
    private val cancelRect = RectF()
    private var sx = 0f
    private var sy = 0f
    private var sel: RectF? = null
    private var onCancelButton = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val hw = w * 0.9f
        hintRect.set((w - hw) / 2, 72 * d, (w + hw) / 2, 72 * d + 76 * d)
        val cw = 190 * d
        cancelRect.set((w - cw) / 2, h - 150 * d, (w + cw) / 2, h - 150 * d + 64 * d)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = sel
        if (r == null) {
            c.drawRect(0f, 0f, w, h, dim)
        } else {
            c.drawRect(0f, 0f, w, r.top, dim)
            c.drawRect(0f, r.bottom, w, h, dim)
            c.drawRect(0f, r.top, r.left, r.bottom, dim)
            c.drawRect(r.right, r.top, w, r.bottom, dim)
            c.drawRect(r, border)
        }
        c.drawRoundRect(hintRect, 18 * d, 18 * d, pill)
        c.drawText(hintEn, hintRect.centerX(), hintRect.top + 30 * d, textMain)
        c.drawText(hintBn, hintRect.centerX(), hintRect.top + 58 * d, textSub)
        c.drawRoundRect(cancelRect, 18 * d, 18 * d, pill)
        c.drawText(cancelEn, cancelRect.centerX(), cancelRect.top + 26 * d, textMain)
        c.drawText(cancelBn, cancelRect.centerX(), cancelRect.top + 50 * d, textSub)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onCancelButton = cancelRect.contains(e.x, e.y)
                sx = e.x; sy = e.y
                sel = null
            }
            MotionEvent.ACTION_MOVE -> if (!onCancelButton) {
                sel = RectF(minOf(sx, e.x), minOf(sy, e.y), maxOf(sx, e.x), maxOf(sy, e.y))
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (onCancelButton) {
                    if (cancelRect.contains(e.x, e.y)) onCancel()
                } else {
                    val r = sel
                    if (r != null && r.width() > 24 * d && r.height() > 14 * d) {
                        val loc = IntArray(2)
                        getLocationOnScreen(loc)
                        onSelected(
                            Rect(
                                (r.left + loc[0]).toInt(), (r.top + loc[1]).toInt(),
                                (r.right + loc[0]).toInt(), (r.bottom + loc[1]).toInt()
                            )
                        )
                    } else {
                        sel = null
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> { sel = null; invalidate() }
        }
        return true
    }
}
