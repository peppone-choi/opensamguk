package com.opensam.model

data class CityConst(
    val id: Int,
    val name: String,
    val level: Int,
    val region: Int,
    val population: Int,
    val agriculture: Int,
    val commerce: Int,
    val security: Int,
    val defence: Int,
    val wall: Int,
    val x: Int,
    val y: Int,
    val connections: List<Int>
)
