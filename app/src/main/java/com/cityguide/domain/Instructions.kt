package com.cityguide.domain

import com.cityguide.data.Step

/** Підказки маневрів українською за даними OSRM (тип маневру + напрямок + вулиця). */
object Instructions {

    /** Стрілка для інтерфейсу. */
    fun arrow(step: Step): String = when {
        step.type == "roundabout" || step.type == "rotary" -> "↻"
        step.modifier == "uturn" -> "↶"
        step.modifier == "sharp left" || step.modifier == "left" -> "↰"
        step.modifier == "sharp right" || step.modifier == "right" -> "↱"
        step.modifier == "slight left" -> "↖"
        step.modifier == "slight right" -> "↗"
        else -> "↑"
    }

    /** Проста стрілка для підписів на карті (шрифт векторних тайлів має обмежений набір гліфів). */
    fun mapArrow(step: Step): String = when (step.modifier) {
        "left", "sharp left", "slight left" -> "←"
        "right", "sharp right", "slight right" -> "→"
        "uturn" -> "U"
        else -> "↑"
    }

    fun text(step: Step): String {
        val base = when (step.type) {
            "depart" -> "рушайте"
            "arrive" -> "ви прибули"
            "roundabout", "rotary" ->
                step.exit?.let { "на кільці виїдьте на $it-й виїзд" } ?: "проїдьте кільце"
            "roundabout turn" -> "на кільці ${direction(step.modifier)}"
            "fork" -> "на розвилці тримайтеся ${side(step.modifier)}"
            "merge" -> "приєднайтеся до руху"
            "end of road" -> "в кінці дороги ${direction(step.modifier)}"
            "continue", "new name" ->
                if (step.modifier == "left" || step.modifier == "right") {
                    direction(step.modifier)
                } else {
                    "продовжуйте рух прямо"
                }
            else -> direction(step.modifier)
        }
        val withStreet =
            if (step.street.isNotBlank() && step.type != "arrive" && step.type != "depart") {
                "$base на ${step.street}"
            } else {
                base
            }
        return withStreet.replaceFirstChar { it.uppercase() }
    }

    private fun direction(modifier: String?): String = when (modifier) {
        "left" -> "поверніть ліворуч"
        "right" -> "поверніть праворуч"
        "slight left" -> "візьміть трохи ліворуч"
        "slight right" -> "візьміть трохи праворуч"
        "sharp left" -> "поверніть різко ліворуч"
        "sharp right" -> "поверніть різко праворуч"
        "straight" -> "рухайтеся прямо"
        "uturn" -> "розверніться"
        else -> "рухайтеся далі"
    }

    private fun side(modifier: String?): String = when (modifier) {
        "left", "slight left", "sharp left" -> "ліворуч"
        "right", "slight right", "sharp right" -> "праворуч"
        else -> "прямо"
    }
}
