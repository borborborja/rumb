package cat.hudpro.opentracks.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Container for the map page inside the ViewPager2. The pager intercepts horizontal drags, stealing
 * the gestures meant for the map (panning would flip to "Dades"). This frame claims every gesture
 * that starts AWAY from the screen edges for the map (`requestDisallowInterceptTouchEvent`), while
 * gestures starting within [EDGE_DP] of the left/right edge still page-swipe — the same edge-swipe
 * pattern map apps use inside pagers. The top switcher pill keeps working regardless.
 */
class MapGestureFrame(context: Context) : FrameLayout(context) {

    private val edgePx = (EDGE_DP * context.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inEdge = event.x <= edgePx || event.x >= width - edgePx
                parent?.requestDisallowInterceptTouchEvent(!inEdge)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private companion object {
        const val EDGE_DP = 28f
    }
}
