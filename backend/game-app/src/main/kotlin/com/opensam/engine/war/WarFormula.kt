package com.opensam.engine.war

val DEX_THRESHOLDS = intArrayOf(
    0,
    350,
    1375,
    3500,
    7125,
    12650,
    20475,
    31000,
    44625,
    61750,
    82775,
    108100,
    138125,
    173250,
    213875,
    260400,
    313225,
    372750,
    439375,
    513500,
    595525,
    685850,
    784875,
    893000,
    1010625,
    1138150,
    1275975,
)

fun getTechLevel(tech: Float): Int = (tech / 100f).toInt().coerceIn(0, 30)

fun getTechAbil(tech: Float): Int = getTechLevel(tech) * 25

fun getTechCost(tech: Float): Double = 1.0 + getTechLevel(tech) * 0.15

fun getDexLevel(dex: Int): Int = DEX_THRESHOLDS.indexOfLast { dex >= it }.coerceAtLeast(0)

fun getDexLog(dex1: Int, dex2: Int): Double = (getDexLevel(dex1) - getDexLevel(dex2)) / 55.0 + 1.0
