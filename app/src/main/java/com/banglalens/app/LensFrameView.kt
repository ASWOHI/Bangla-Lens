package com.banglalens.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Green frame shown while dragging the magnifier: this is the area that will be read when you let go. */
class LensFrameView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2266BB6A }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E7D32.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * d
    }

    override fun onDraw(c: Canvas) {
        val r = RectF(2 * d, 2 * d, width - 2 * d, height - 2 * d)
        c.drawRoundRect(r, 12 * d, 12 * d, fill)
        c.drawRoundRect(r, 12 * d, 12 * d, border)
    }
}
