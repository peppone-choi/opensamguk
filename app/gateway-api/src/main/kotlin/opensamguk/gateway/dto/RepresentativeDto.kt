package opensamguk.gateway.dto

import jakarta.validation.constraints.NotNull

/** 계정 대표 장수 후보 — 계정이 가진 플레이어 장수(세계별). */
data class RepresentativeCandidate(
    val generalId: Int,
    val name: String,
    val worldId: Int,
    val scenarioCode: String?,
)

data class RepresentativeState(
    val generalId: Int?,
    val name: String?,
    val worldId: Int?,
)

data class RepresentativeResponse(
    val current: RepresentativeState,
    val candidates: List<RepresentativeCandidate>,
)

data class SetRepresentativeRequest(
    /** null 이면 대표 장수 해제. */
    /** null = 대표 장수 해제(서비스가 처리). `@NotNull` 이면 해제 요청이 400 으로 막힌다(PR 비평 F1). */
    val generalId: Int?,
)
