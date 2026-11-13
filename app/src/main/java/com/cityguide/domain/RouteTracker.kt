package com.cityguide.domain

import com.cityguide.data.LatLon
import com.cityguide.data.Step
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Прив'язка положення користувача до лінії маршруту. */
data class Snap(
    val segment: Int,
    /** Пройдена відстань вздовж маршруту, м. */
    val along: Double,
    /** Відстань від користувача до лінії маршруту, м. */
    val offRoute: Double,
    val point: LatLon
)

/**
 * Відстежує прогрес вздовж маршруту: де користувач, скільки пройдено,
 * який маневр і яка зупинка наступні.
 *
 * [stops] це зупинки, що залишилися, а [firstStopIndex] їхній індекс у повному маршруті.
 */
class RouteTracker(
    val geometry: List<LatLon>,
    val steps: List<Step>,
    val stops: List<LatLon>,
    val firstStopIndex: Int
) {
    private val cum = DoubleArray(geometry.size)
    val stepAlong: DoubleArray
    private val stopAlong: DoubleArray

    /** Довжина лінії маршруту, м. */
    val length: Double

    init {
        for (i in 1 until geometry.size) {
            cum[i] = cum[i - 1] + Geo.distance(geometry[i - 1], geometry[i])
        }
        length = cum.lastOrNull() ?: 0.0
        stepAlong = alongForward(steps.map { LatLon(it.lat, it.lon) })
        stopAlong = alongForward(stops)
    }

    /** Для кожної точки шукає найближчу вершину маршруту, рухаючись лише вперед (порядок збережено). */
    private fun alongForward(points: List<LatLon>): DoubleArray {
        var from = 0
        return DoubleArray(points.size) { k ->
            var best = from
            var bestDist = Double.MAX_VALUE
            for (i in from until geometry.size) {
                val d = Geo.distance(geometry[i], points[k])
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            from = best
            cum[best]
        }
    }

    fun snap(p: LatLon, hintSegment: Int): Snap {
        val last = geometry.size - 2
        if (last < 0) return Snap(0, 0.0, 0.0, geometry.firstOrNull() ?: p)
        val hint = hintSegment.coerceIn(0, last)
        val local = search(p, max(0, hint - 3), min(last, hint + 120))
        if (local.offRoute < 60.0) return local
        val global = search(p, 0, last)
        return if (global.offRoute < local.offRoute) global else local
    }

    private fun search(p: LatLon, from: Int, to: Int): Snap {
        var best = Snap(from, cum[from], Double.MAX_VALUE, geometry[from])
        for (i in from..to) {
            val a = geometry[i]
            val b = geometry[i + 1]
            val cosLat = cos(Math.toRadians(a.lat))
            val bx = (b.lon - a.lon) * cosLat * 111_320.0
            val by = (b.lat - a.lat) * 110_540.0
            val px = (p.lon - a.lon) * cosLat * 111_320.0
            val py = (p.lat - a.lat) * 110_540.0
            val len2 = bx * bx + by * by
            val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val dx = px - t * bx
            val dy = py - t * by
            val d = sqrt(dx * dx + dy * dy)
            if (d < best.offRoute) {
                best = Snap(
                    segment = i,
                    along = cum[i] + t * sqrt(len2),
                    offRoute = d,
                    point = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                )
            }
        }
        return best
    }

    /** Індекс наступного маневру (без старту й прибуття) або null, якщо поворотів більше немає. */
    fun nextStepIndex(along: Double): Int? = steps.indices.firstOrNull { k ->
        steps[k].type != "depart" && steps[k].type != "arrive" && stepAlong[k] > along + 3.0
    }

    /** Де вздовж маршруту розташована зупинка з повним індексом [absoluteIndex]. */
    fun alongToStop(absoluteIndex: Int): Double =
        stopAlong.getOrNull(absoluteIndex - firstStopIndex) ?: length
}
