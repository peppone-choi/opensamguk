package com.opensam.dto

data class CreateGeneralRequest(
    val name: String,
    val cityId: Long = 0,
    val nationId: Long = 0,
    val leadership: Short = 50,
    val strength: Short = 50,
    val intel: Short = 50,
    val politics: Short = 50,
    val charm: Short = 50,
    val crewType: Short = 0,
)

data class SelectNpcRequest(val generalId: Long)
