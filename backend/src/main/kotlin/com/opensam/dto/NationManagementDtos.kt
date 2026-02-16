package com.opensam.dto

data class NationPolicyInfo(
    val rate: Int, val bill: Int, val secretLimit: Int, val strategicCmdLimit: Int,
    val notice: String, val scoutMsg: String,
)

data class UpdatePolicyRequest(val rate: Int? = null, val bill: Int? = null, val secretLimit: Int? = null, val strategicCmdLimit: Int? = null)

data class UpdateNoticeRequest(val notice: String)

data class UpdateScoutMsgRequest(val scoutMsg: String)

data class OfficerInfo(val id: Long, val name: String, val picture: String, val officerLevel: Int, val cityId: Long)

data class AppointOfficerRequest(val generalId: Long, val officerLevel: Int, val officerCity: Int? = null)

data class ExpelRequest(val generalId: Long)
