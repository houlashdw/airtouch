package com.airtouch.e2e

import com.airtouch.gesture.Landmark
import com.airtouch.gesture.Lm

/**
 * Синтетическая рука пальцами вверх для тестов.
 * [extended] — [указательный, средний, безымянный, мизинец]; [pinch] — кончик большого у кончика указательного.
 */
fun syntheticHand(
    cx: Float,
    cy: Float,
    extended: BooleanArray = booleanArrayOf(true, true, true, true),
    s: Float = 0.15f,
    pinch: Boolean = false,
    /** <1 — кисть наклонена к/от камеры: ладонь «короче» по вертикали, ширина та же. */
    tiltY: Float = 1f,
): List<Landmark> {
    val pts = MutableList(Lm.COUNT) { Landmark(cx, cy) }
    pts[Lm.WRIST] = Landmark(cx, cy + 0.5f * s)
    for (i in 1..4) pts[i] = Landmark(cx - 0.3f * s - 0.12f * s * i, cy + 0.35f * s - 0.1f * s * i)
    val mcpIds = intArrayOf(Lm.INDEX_MCP, Lm.MIDDLE_MCP, Lm.RING_MCP, Lm.PINKY_MCP)
    val xOff = floatArrayOf(-0.3f, -0.1f, 0.1f, 0.3f)
    for (f in 0 until 4) {
        val x = cx + xOff[f] * s
        val base = mcpIds[f]
        pts[base] = Landmark(x, cy - 0.1f * s)
        if (extended[f]) {
            pts[base + 1] = Landmark(x, cy - 0.4f * s)
            pts[base + 2] = Landmark(x, cy - 0.6f * s)
            pts[base + 3] = Landmark(x, cy - 0.8f * s)
        } else {
            pts[base + 1] = Landmark(x, cy - 0.35f * s)
            pts[base + 2] = Landmark(x, cy - 0.2f * s)
            pts[base + 3] = Landmark(x, cy - 0.05f * s)
        }
    }
    if (tiltY != 1f) for (i in pts.indices) pts[i] = Landmark(pts[i].x, cy + (pts[i].y - cy) * tiltY)
    if (pinch) {
        val tip = pts[Lm.INDEX_TIP]
        pts[Lm.THUMB_TIP] = Landmark(tip.x - 0.03f * s, tip.y + 0.03f * s)
    }
    return pts
}
