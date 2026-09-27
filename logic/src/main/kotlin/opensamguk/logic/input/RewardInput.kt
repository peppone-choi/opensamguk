package opensamguk.logic.input

import kotlinx.serialization.json.*

/** 상사(賞賜) — 휘하 인물 카드에 창고 금을 내려 충성을 올린다. 행위자는 인증된 호출자이고 본문에 없다. */
data class RewardRequest(val actorId: Int, val retainerId: Int, val money: Long) {
    init { require(actorId > 0 && retainerId > 0 && money > 0) }
}

object RewardInput {
    const val INPUT_ID = "court.reward"
    /** 한 번에 내릴 수 있는 금 상한 — 입력 검증용 경계(규칙 수치가 아니다). */
    const val MAX_MONEY = 1_000_000_000L

    fun parse(actorId: Int, rawJson: String?): RewardRequest? {
        if (actorId <= 0 || rawJson == null) return null
        return try {
            val fields = FlatArguments(rawJson).read()
            if (fields.keys != setOf("retainerId", "money")) return null
            fun positive(value: JsonElement?): Long? {
                val primitive = value as? JsonPrimitive ?: return null
                if (primitive.isString || !Regex("[1-9][0-9]{0,9}").matches(primitive.content)) return null
                return primitive.content.toLongOrNull()
            }
            val retainer = positive(fields["retainerId"])?.takeIf { it <= Int.MAX_VALUE } ?: return null
            val money = positive(fields["money"])?.takeIf { it <= MAX_MONEY } ?: return null
            RewardRequest(actorId, retainer.toInt(), money)
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: RewardRequest): String =
        buildJsonObject { put("retainerId", request.retainerId); put("money", request.money) }.toString()
}

/** 결정권자의 다음 턴에 실행할 상사 한 건(발령 대기와 같은 방식). */
data class QueuedReward(val requestId: String, val ownerUserId: Int, val retainerId: Int, val money: Long) {
    init {
        require(requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(ownerUserId > 0 && retainerId > 0 && money in 1..RewardInput.MAX_MONEY)
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf("requestId" to requestId, "ownerUserId" to ownerUserId,
        "retainerId" to retainerId, "money" to money)

    companion object {
        const val META_KEY = "queuedReward"

        fun read(meta: Map<String, Any?>): QueuedReward? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(value.keys == setOf("requestId", "ownerUserId", "retainerId", "money"))
            val money = when (val raw = value["money"]) { is Int -> raw.toLong(); is Long -> raw; else -> invalid() }
            return QueuedReward(value["requestId"] as? String ?: invalid(), value["ownerUserId"] as? Int ?: invalid(),
                value["retainerId"] as? Int ?: invalid(), money)
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("invalid HWIHA reward queue")
    }
}
