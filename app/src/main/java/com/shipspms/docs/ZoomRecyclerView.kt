package com.shipspms.docs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView

/**
 * A vertical RecyclerView with pinch-to-zoom, double-tap zoom and horizontal panning.
 * Pages scroll normally; when zoomed in, the whole list is drawn scaled.
 */
class ZoomRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private val minScale = 1f
    private val maxScale = 5f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // Stop any list scroll that started with the first finger.
            cancelListScroll()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(scale * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomTo(if (scale > 1.5f) 1f else 2.5f, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scale <= 1f || scaleDetector.isInProgress) return false
            tx -= distanceX
            // At the very top/bottom of the document the list can't scroll, so pan the zoomed view instead.
            if ((distanceY > 0 && !canScrollVertically(1)) || (distanceY < 0 && !canScrollVertically(-1))) {
                ty -= distanceY
            }
            clamp()
            invalidate()
            return true
        }
    })

    private fun cancelListScroll() {
        val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.onTouchEvent(cancel)
        cancel.recycle()
    }

    fun resetZoom() {
        scale = 1f; tx = 0f; ty = 0f
        invalidate()
    }

    private fun zoomTo(newScaleRaw: Float, focusX: Float, focusY: Float) {
        val newScale = newScaleRaw.coerceIn(minScale, maxScale)
        val ratio = newScale / scale
        tx = focusX - (focusX - tx) * ratio
        ty = focusY - (focusY - ty) * ratio
        scale = newScale
        clamp()
        invalidate()
    }

    private fun clamp() {
        tx = tx.coerceIn(width * (1 - scale), 0f)
        ty = ty.coerceIn(height * (1 - scale), 0f)
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)
        if (scaleDetector.isInProgress || e.pointerCount > 1) return true

        // Let the list scroll, but at the zoomed speed so the page follows the finger.
        val adjusted = MotionEvent.obtain(e)
        adjusted.transform(Matrix().apply { setScale(1f, 1f / scale) })
        val handled = super.onTouchEvent(adjusted)
        adjusted.recycle()
        return handled || scale > 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clamp()
    }
}
