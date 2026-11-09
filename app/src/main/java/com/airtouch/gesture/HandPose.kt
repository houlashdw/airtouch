package com.airtouch.gesture

/** Статическая поза руки. OPEN_PALM = 3–4 выпрямленных пальца (ладонь редко раскрыта идеально). */
enum class HandPose { OPEN_PALM, FIST, POINTING, V_SIGN, OTHER }

object HandPoseClassifier {

    /**
     * Палец считается выпрямленным, если кончик заметно дальше от запястья, чем средний сустав (PIP).
     * Такой критерий не зависит от поворота кисти и расстояния до камеры.
     */
    private const val EXTENDED_RATIO = 1.15f

    private val fingers = arrayOf(
        Lm.INDEX_PIP to Lm.INDEX_TIP,
        Lm.MIDDLE_PIP to Lm.MIDDLE_TIP,
        Lm.RING_PIP to Lm.RING_TIP,
        Lm.PINKY_PIP to Lm.PINKY_TIP,
    )

    /** [указательный, средний, безымянный, мизинец]. Большой палец не учитываем — он ненадёжен в профиль. */
    fun extendedFingers(frame: HandFrame): BooleanArray = BooleanArray(fingers.size) { i ->
        val (pip, tip) = fingers[i]
        frame.dist(Lm.WRIST, tip) > frame.dist(Lm.WRIST, pip) * EXTENDED_RATIO
    }

    /** Обученной модели доверяем больше, чем правилам; если она не уверена — правила. */
    private const val MODEL_MIN_SCORE = 0.55f

    fun classify(frame: HandFrame): HandPose {
        if (frame.labelScore >= MODEL_MIN_SCORE) {
            when (frame.label) {
                "Open_Palm" -> return HandPose.OPEN_PALM
                "Closed_Fist" -> return HandPose.FIST
                "Pointing_Up" -> return HandPose.POINTING
                "Victory" -> return HandPose.V_SIGN
            }
        }
        val e = extendedFingers(frame)
        val count = e.count { it }
        return when {
            count >= 3 -> HandPose.OPEN_PALM
            count == 0 -> HandPose.FIST
            e[0] && count == 1 -> HandPose.POINTING
            e[0] && e[1] && count == 2 -> HandPose.V_SIGN
            else -> HandPose.OTHER
        }
    }
}
