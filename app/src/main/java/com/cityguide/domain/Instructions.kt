package com.cityguide.domain

import android.content.Context
import com.cityguide.R
import com.cityguide.data.Step

/** Підказки маневрів мовою системи за даними OSRM (тип маневру + напрямок + вулиця). */
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

    /**
     * Текст підказки. Рядки в ресурсах починаються з малої літери, щоб їх можна було
     * вставляти в речення («Через 50 метрів поверніть ліворуч»); для екрана [capitalize] = true.
     */
    fun text(context: Context, step: Step, capitalize: Boolean = true): String {
        val base = when (step.type) {
            "depart" -> context.getString(R.string.ins_depart)
            "arrive" -> context.getString(R.string.ins_arrive)
            "roundabout", "rotary" ->
                step.exit?.let { context.getString(R.string.ins_roundabout_exit, it) }
                    ?: context.getString(R.string.ins_roundabout)
            "roundabout turn" ->
                context.getString(R.string.ins_roundabout_turn, direction(context, step.modifier))
            "fork" -> context.getString(R.string.ins_fork, side(context, step.modifier))
            "merge" -> context.getString(R.string.ins_merge)
            "end of road" ->
                context.getString(R.string.ins_end_of_road, direction(context, step.modifier))
            "continue", "new name" ->
                if (step.modifier == "left" || step.modifier == "right") {
                    direction(context, step.modifier)
                } else {
                    context.getString(R.string.ins_straight_on)
                }
            else -> direction(context, step.modifier)
        }
        val withStreet =
            if (step.street.isNotBlank() && step.type != "arrive" && step.type != "depart") {
                context.getString(R.string.ins_on_street, base, step.street)
            } else {
                base
            }
        return if (capitalize) withStreet.replaceFirstChar { it.uppercase() } else withStreet
    }

    private fun direction(context: Context, modifier: String?): String = context.getString(
        when (modifier) {
            "left" -> R.string.dir_left
            "right" -> R.string.dir_right
            "slight left" -> R.string.dir_slight_left
            "slight right" -> R.string.dir_slight_right
            "sharp left" -> R.string.dir_sharp_left
            "sharp right" -> R.string.dir_sharp_right
            "straight" -> R.string.dir_straight
            "uturn" -> R.string.dir_uturn
            else -> R.string.dir_continue
        }
    )

    private fun side(context: Context, modifier: String?): String = context.getString(
        when (modifier) {
            "left", "slight left", "sharp left" -> R.string.side_left
            "right", "slight right", "sharp right" -> R.string.side_right
            else -> R.string.side_straight
        }
    )
}
