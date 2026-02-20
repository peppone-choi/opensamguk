package com.opensam.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import jakarta.annotation.PostConstruct
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service

@Service
class GameConstService {

    private lateinit var constants: Map<String, Any>

    @PostConstruct
    fun init() {
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        val resource = ClassPathResource("data/game_const.json")
        constants = mapper.readValue(resource.inputStream, object : TypeReference<Map<String, Any>>() {})
    }

    fun getInt(key: String): Int = (constants[key] as Number).toInt()

    fun getDouble(key: String): Double = (constants[key] as Number).toDouble()

    fun getString(key: String): String = constants[key] as String

    fun getAll(): Map<String, Any> = constants
}
