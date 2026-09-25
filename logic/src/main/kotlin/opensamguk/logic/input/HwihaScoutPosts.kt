package opensamguk.logic.input

import opensamguk.logic.domestic.PlacementState

import opensamguk.logic.domestic.PlacementPost
import opensamguk.logic.domestic.PlacementTarget

import opensamguk.logic.domestic.DomesticCard

/**
 * 정찰 배치의 공개 투영. 배치 주인 장수 meta `hwihaScoutPosts` = `{"version":1,"posts":[{"retainerId":int,"provinceId":str,"status":str}]}`
 * — 시야 스트림(`HwihaMetaVisionSourceReader.scoutPosts`, 비전 계약 `2026-09-23-hwiha-vision-contract.md` §3)이 읽는 꼴 그대로다.
 * 정본은 카드 장수 meta 의 `hwihaPlacement` 이고, 이 투영은 그 정본에서 다시 만든다(엔진이 배치가 바뀔 때마다 동기화).
 *
 * status: `ACTIVE` = 부임지에 도착해 시야를 준다, `MOVING` = 부임 행군 중(시야 없음). 항목은 (retainerId, provinceId) 순이다.
 */
data class HwihaScoutPostEntry(val retainerId: Int, val provinceId: String, val status: String) {
    init {
        require(retainerId > 0 && provinceId.isNotBlank() && provinceId.length <= 128)
        require(status == ACTIVE || status == MOVING)
    }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("retainerId" to retainerId, "provinceId" to provinceId, "status" to status)

    companion object {
        const val ACTIVE = "ACTIVE"
        const val MOVING = "MOVING"
    }
}

object HwihaScoutPosts {
    const val META_KEY = "hwihaScoutPosts"

    /**
     * 주인 [ownerId] 가 직접 거느린 카드(`masterId == ownerId`)의 현행 정찰 배치에서 투영을 만든다. 배치 주인이 바뀐 카드·
     * 오염된 배치 기록은 싣지 않는다(오염은 카드 턴 재검사가 사유를 남기고 푼다). 비었으면 null(키를 지운다).
     */
    fun project(ownerId: Int, cards: List<DomesticCard>, placementOf: (generalId: Int) -> Map<String, Any?>?): Map<String, Any>? {
        val posts = cards.filter { it.masterId == ownerId && it.generalId != null }.sortedBy { it.id }.mapNotNull { card ->
            val meta = placementOf(card.generalId!!) ?: return@mapNotNull null
            val active = try { PlacementState.read(meta)?.active } catch (_: IllegalArgumentException) { null } ?: return@mapNotNull null
            val target = active.order.target as? PlacementTarget.Province ?: return@mapNotNull null
            if (active.order.post != PlacementPost.SCOUT || active.order.ownerGeneralId != ownerId || active.order.retainerId != card.id)
                return@mapNotNull null
            HwihaScoutPostEntry(card.id, target.provinceId,
                if (active.arrivedAt != null) HwihaScoutPostEntry.ACTIVE else HwihaScoutPostEntry.MOVING)
        }.sortedWith(compareBy({ it.retainerId }, { it.provinceId }))
        if (posts.isEmpty()) return null
        return linkedMapOf("version" to 1, "posts" to posts.map { it.toMetaValue() })
    }
}
