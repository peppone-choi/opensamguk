package com.opensam.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import jakarta.annotation.PostConstruct
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service

@Service
class OfficerRankService {

    private lateinit var defaultRanks: Map<String, String>
    private lateinit var byNationLevel: Map<String, Map<String, Any>>

    @PostConstruct
    fun init() {
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        val resource = ClassPathResource("data/officer_ranks.json")
        val data: Map<String, Any> = mapper.readValue(resource.inputStream, object : TypeReference<Map<String, Any>>() {})

        @Suppress("UNCHECKED_CAST")
        defaultRanks = data["default"] as Map<String, String>
        @Suppress("UNCHECKED_CAST")
        byNationLevel = data["byNationLevel"] as Map<String, Map<String, Any>>
    }

    fun getRankTitle(officerLevel: Int, nationLevel: Int?): String {
        if (officerLevel < 5) {
            return defaultRanks[officerLevel.toString()] ?: "???"
        }

        if (nationLevel == null) {
            return defaultRanks[officerLevel.toString()] ?: "???"
        }

        val nationMap = byNationLevel[nationLevel.toString()] ?: return defaultRanks[officerLevel.toString()] ?: "???"

        @Suppress("UNCHECKED_CAST")
        val ranks = nationMap["ranks"] as? Map<String, String> ?: return defaultRanks[officerLevel.toString()] ?: "???"

        return ranks[officerLevel.toString()] ?: defaultRanks[officerLevel.toString()] ?: "???"
    }

    fun getNationTitle(nationLevel: Int): String? {
        val nationMap = byNationLevel[nationLevel.toString()] ?: return null
        return nationMap["title"] as? String
    }
}
