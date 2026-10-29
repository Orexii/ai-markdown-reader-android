package com.orexii.aimarkdownreader.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Speed lists; they must match the extension's src/speeds.ts and media/player.js. */
object Speeds {
    /** Every speed the reader supports: quarter steps from a quarter of normal speed to five times. */
    val ALL: List<Double> = (1..20).map { it / 4.0 }

    /** The short list offered unless more speeds are turned on. */
    val BASIC: List<Double> = listOf(0.5, 1.0, 1.5, 2.0)

    /** The supported speed closest to a value. */
    fun nearest(value: Double): Double {
        val wanted = if (value.isFinite()) value else 1.0
        return min(5.0, max(0.25, (wanted * 4).roundToLong() / 4.0))
    }

    fun isSpeed(value: Double): Boolean = value in ALL

    /** The speeds to offer: the short or the full list, plus the current speed if it is not in it. */
    fun choices(expanded: Boolean, current: Double): List<Double> {
        val list = (if (expanded) ALL else BASIC).toMutableList()
        if (isSpeed(current) && current !in list) list.add(current)
        return list.sorted()
    }

    fun faster(current: Double, expanded: Boolean): Double = choices(expanded, current).firstOrNull { it > current } ?: current

    fun slower(current: Double, expanded: Boolean): Double = choices(expanded, current).lastOrNull { it < current } ?: current

    fun format(speed: Double): String =
        BigDecimal(speed).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "×"
}
