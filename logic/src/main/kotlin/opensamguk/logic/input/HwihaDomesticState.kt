package opensamguk.logic.input

import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.StrategicTopologySnapshot

/*
 * 휘하 내정 입력의 저장 꼴. 전부 기존 meta(jsonb) 에 typed codec 으로 싣고 recorder → flush 로만 쓴다.
 * 모든 reader 는 키가 없으면 null(아직 입력 없음), 꼴이 어긋나면 IllegalArgumentException 이다 —
 * 오염된 저장값을 빈 상태로 바꿔 읽지 않는다.
 *
 * | 키 | 어디 | 내용 |
 * |---|---|---|
 * | `hwihaPlacement` | 배치된 카드의 장수 meta | 대기·현행 배치, 부임(도착) 순 |
 * | `hwihaPlacementMarch` | 같은 장수 meta | 부임 행군 진행(경로·커서) |
 * | `hwihaCountyPolicy` | 縣治 城 meta | 縣 방침 대기·현행, 마지막 적용 |
 * | `hwihaCountyWorks` | 縣治 城 meta | 진행 중 공사·완공 목록(시야 스트림 공개 꼴 `{"version":1,"works":[{kind,status,…}]}`) |
 * | `hwihaScoutPosts` | 배치 주인 장수 meta | 정찰 배치 공개 투영(시야 스트림이 읽는다, 정본은 `hwihaPlacement`) |
 * | `hwihaCountyMonthly` | 縣治 城 meta | 치적 비교용 지난달 지표 |
 * | `hwihaCommanderyPolicies` | 세력 nation meta | 郡 방침 대기·현행 |
 * | `hwihaCorpsPolicies` | 군단 주인 장수 meta | 군단 방침 대기·현행 |
 */

private fun invalid(what: String): Nothing = throw IllegalArgumentException("invalid HWIHA $what")
private fun Map<*, *>.int(key: String, what: String): Int = this[key] as? Int ?: invalid(what)
private fun Map<*, *>.string(key: String, what: String): String = this[key] as? String ?: invalid(what)
private fun Map<*, *>.exactLong(key: String, what: String): Long = when (val value = this[key]) {
    is Int -> value.toLong()
    is Long -> value
    else -> invalid(what)
}
private fun Map<*, *>.phaseOrNull(key: String): HwihaPhase? = this[key]?.let { HwihaPhase.read(it) }
private val requestIdPattern = Regex("[A-Za-z0-9._:-]{1,128}")

/** 접수된 배치 한 건. 카드의 다음 턴에 현행이 된다(§4). */
data class HwihaPlacementOrder(
    val requestId: String,
    val ownerGeneralId: Int,
    val retainerId: Int,
    val post: PlacementPost,
    val target: PlacementTarget,
    val requestedAt: HwihaPhase,
) {
    init {
        require(requestIdPattern.matches(requestId) && ownerGeneralId > 0 && retainerId > 0)
        require(PlacementTarget.matches(post, target))
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "requestId" to requestId, "ownerGeneralId" to ownerGeneralId, "retainerId" to retainerId,
        "post" to post.name, "target" to target.toMetaValue(), "requestedAt" to requestedAt.toMetaValue(),
    )

    companion object {
        private val fields = setOf("requestId", "ownerGeneralId", "retainerId", "post", "target", "requestedAt")
        fun read(raw: Any?): HwihaPlacementOrder {
            val value = raw as? Map<*, *> ?: invalid("placement order")
            require(value.keys == fields) { "invalid HWIHA placement order fields" }
            val post = PlacementPost.valueOf(value.string("post", "placement post"))
            return HwihaPlacementOrder(value.string("requestId", "placement"), value.int("ownerGeneralId", "placement"),
                value.int("retainerId", "placement"), post, PlacementTarget.read(value["target"]),
                HwihaPhase.read(value["requestedAt"]))
        }
    }
}

/** 현행 배치. [arrivedAt] 이 있으면 자리에 앉은 것이다(행군이 목적지에 닿은 순). */
data class HwihaActivePlacement(val order: HwihaPlacementOrder, val since: HwihaPhase, val arrivedAt: HwihaPhase?) {
    init {
        require(order.post != PlacementPost.NONE) { "a release is never an active placement" }
        require(since >= order.requestedAt && (arrivedAt == null || arrivedAt >= since))
    }

    fun toMetaValue(): Map<String, Any?> = linkedMapOf(
        "order" to order.toMetaValue(), "since" to since.toMetaValue(), "arrivedAt" to arrivedAt?.toMetaValue(),
    )

    companion object {
        fun read(raw: Any?): HwihaActivePlacement {
            val value = raw as? Map<*, *> ?: invalid("active placement")
            require(value.keys == setOf("order", "since", "arrivedAt")) { "invalid HWIHA active placement fields" }
            return HwihaActivePlacement(HwihaPlacementOrder.read(value["order"]), HwihaPhase.read(value["since"]),
                value.phaseOrNull("arrivedAt"))
        }
    }
}

data class HwihaPlacementState(val active: HwihaActivePlacement?, val pending: HwihaPlacementOrder?) {
    init {
        require(active != null || pending != null) { "an empty placement is removed, not stored" }
        require(active == null || pending == null || active.order.retainerId == pending.retainerId)
    }

    fun toMetaValue(): Map<String, Any?> = linkedMapOf(
        "version" to 1, "active" to active?.toMetaValue(), "pending" to pending?.toMetaValue(),
    )

    /** 현행이든 대기든 [countyId] 의 縣令 자리를 잡고 있는가(대기가 다른 자리여도 바뀌기 전까지는 잡고 있다). */
    fun claimsMagistracy(countyId: Int): Boolean =
        listOfNotNull(active?.order, pending).any { it.post == PlacementPost.MAGISTRATE && it.target == PlacementTarget.County(countyId) }

    companion object {
        const val META_KEY = "hwihaPlacement"
        fun read(meta: Map<String, Any?>): HwihaPlacementState? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("placement")
            require(value.keys == setOf("version", "active", "pending") && value["version"] == 1) { "invalid HWIHA placement schema" }
            return HwihaPlacementState(value["active"]?.let(HwihaActivePlacement::read), value["pending"]?.let(HwihaPlacementOrder::read))
        }
    }
}

/** 배치 부임 행군. [requestId] 가 현행 배치와 다르면 낡은 진행이다. */
data class HwihaPlacementMarch(val requestId: String, val checkpoint: HwihaMarchCheckpoint) {
    init { require(requestIdPattern.matches(requestId)) }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "requestId" to requestId, "checkpoint" to checkpoint.toMetaValue())

    companion object {
        const val META_KEY = "hwihaPlacementMarch"
        fun read(meta: Map<String, Any?>, topology: StrategicTopologySnapshot, metrics: LandMarchMetricSnapshot): HwihaPlacementMarch? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("placement march")
            require(value.keys == setOf("version", "requestId", "checkpoint") && value["version"] == 1) { "invalid HWIHA placement march schema" }
            return HwihaPlacementMarch(value.string("requestId", "placement march"), HwihaMarchCheckpoint.read(value["checkpoint"], topology, metrics))
        }
    }
}

/** 걸린 방침. */
data class HwihaPolicySetting(val policy: String, val requestId: String, val actorId: Int, val since: HwihaPhase) {
    init { require(policy.isNotBlank() && requestIdPattern.matches(requestId) && actorId > 0) }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("policy" to policy, "requestId" to requestId, "actorId" to actorId,
        "since" to since.toMetaValue())
    companion object {
        fun read(raw: Any?): HwihaPolicySetting {
            val value = raw as? Map<*, *> ?: invalid("policy setting")
            require(value.keys == setOf("policy", "requestId", "actorId", "since"))
            return HwihaPolicySetting(value.string("policy", "policy"), value.string("requestId", "policy"),
                value.int("actorId", "policy"), HwihaPhase.read(value["since"]))
        }
    }
}

/** 접수된 방침 변경. [policy] null 은 거두기다. */
data class HwihaPolicyOrder(val policy: String?, val requestId: String, val actorId: Int, val requestedAt: HwihaPhase) {
    init { require((policy == null || policy.isNotBlank()) && requestIdPattern.matches(requestId) && actorId > 0) }
    fun toMetaValue(): Map<String, Any?> = linkedMapOf("policy" to policy, "requestId" to requestId, "actorId" to actorId,
        "requestedAt" to requestedAt.toMetaValue())
    companion object {
        fun read(raw: Any?): HwihaPolicyOrder {
            val value = raw as? Map<*, *> ?: invalid("policy order")
            require(value.keys == setOf("policy", "requestId", "actorId", "requestedAt"))
            val policy = value["policy"]?.let { it as? String ?: invalid("policy order") }
            return HwihaPolicyOrder(policy, value.string("requestId", "policy"), value.int("actorId", "policy"),
                HwihaPhase.read(value["requestedAt"]))
        }
    }
}

/** 방침 칸 하나: 현행 + 다음 효력 시점에 바뀔 대기. 둘 다 비면 칸을 지운다. */
data class HwihaPolicySlot(val active: HwihaPolicySetting?, val pending: HwihaPolicyOrder?) {
    val isEmpty: Boolean get() = active == null && pending == null
    fun toMetaValue(): Map<String, Any?> = linkedMapOf("active" to active?.toMetaValue(), "pending" to pending?.toMetaValue())

    /** 대기를 현행으로 옮긴다. 거두기면 현행이 사라진다. */
    fun activate(now: HwihaPhase): HwihaPolicySlot {
        val order = pending ?: return this
        return HwihaPolicySlot(order.policy?.let { HwihaPolicySetting(it, order.requestId, order.actorId, now) }, null)
    }

    companion object {
        fun read(raw: Any?): HwihaPolicySlot {
            val value = raw as? Map<*, *> ?: invalid("policy slot")
            require(value.keys == setOf("active", "pending"))
            return HwihaPolicySlot(value["active"]?.let(HwihaPolicySetting::read), value["pending"]?.let(HwihaPolicyOrder::read))
        }
    }
}

/** 縣 방침의 마지막 적용(순마다 한 번). [result] 는 APPLIED 이거나 건너뛴 사유 코드다. */
data class HwihaPolicyApplication(val at: HwihaPhase, val policy: String, val seat: String, val result: String) {
    init { require(policy.isNotBlank() && seat in setOf("SEATED", "EMPTY") && result.isNotBlank()) }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("at" to at.toMetaValue(), "policy" to policy, "seat" to seat, "result" to result)
    companion object {
        fun read(raw: Any?): HwihaPolicyApplication {
            val value = raw as? Map<*, *> ?: invalid("policy application")
            require(value.keys == setOf("at", "policy", "seat", "result"))
            return HwihaPolicyApplication(HwihaPhase.read(value["at"]), value.string("policy", "application"),
                value.string("seat", "application"), value.string("result", "application"))
        }
    }
}

data class HwihaCountyPolicyState(val slot: HwihaPolicySlot, val lastApplied: HwihaPolicyApplication?) {
    init { slot.active?.let { require(CountyPolicy.entries.any { p -> p.name == it.policy }) } }
    fun toMetaValue(): Map<String, Any?> = linkedMapOf("version" to 1, "slot" to slot.toMetaValue(),
        "lastApplied" to lastApplied?.toMetaValue())
    companion object {
        const val META_KEY = "hwihaCountyPolicy"
        fun read(meta: Map<String, Any?>): HwihaCountyPolicyState? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("county policy")
            require(value.keys == setOf("version", "slot", "lastApplied") && value["version"] == 1) { "invalid HWIHA county policy schema" }
            val slot = HwihaPolicySlot.read(value["slot"])
            slot.pending?.policy?.let { require(CountyPolicy.entries.any { p -> p.name == it }) }
            return HwihaCountyPolicyState(slot, value["lastApplied"]?.let(HwihaPolicyApplication::read))
        }
    }
}

data class HwihaCommanderyPolicy(val commanderyId: String, val slot: HwihaPolicySlot) {
    init {
        require(HwihaDomesticIds.commandery(commanderyId) && !slot.isEmpty)
        listOfNotNull(slot.active?.policy, slot.pending?.policy).forEach { p -> require(CountyPolicy.entries.any { it.name == p }) }
    }
}

/** 세력 meta 의 郡 방침들(郡 식별자 순). 점령으로 縣이 넘어가면 새 세력의 郡 방침을 따른다. */
data class HwihaCommanderyPolicies(val entries: List<HwihaCommanderyPolicy>) {
    init { require(entries.map { it.commanderyId } == entries.map { it.commanderyId }.distinct().sorted()) }
    operator fun get(commanderyId: String): HwihaCommanderyPolicy? = entries.firstOrNull { it.commanderyId == commanderyId }
    fun with(commanderyId: String, slot: HwihaPolicySlot): HwihaCommanderyPolicies = HwihaCommanderyPolicies(
        (entries.filter { it.commanderyId != commanderyId } + listOfNotNull(slot.takeUnless { it.isEmpty }?.let {
            HwihaCommanderyPolicy(commanderyId, it) })).sortedBy { it.commanderyId })
    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "commanderies" to entries.map {
        linkedMapOf("commanderyId" to it.commanderyId, "slot" to it.slot.toMetaValue())
    })
    companion object {
        const val META_KEY = "hwihaCommanderyPolicies"
        fun read(meta: Map<String, Any?>): HwihaCommanderyPolicies? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("commandery policies")
            require(value.keys == setOf("version", "commanderies") && value["version"] == 1) { "invalid HWIHA commandery policy schema" }
            val rows = value["commanderies"] as? List<*> ?: invalid("commandery policies")
            return HwihaCommanderyPolicies(rows.map { raw ->
                val row = raw as? Map<*, *> ?: invalid("commandery policy")
                require(row.keys == setOf("commanderyId", "slot"))
                HwihaCommanderyPolicy(row.string("commanderyId", "commandery policy"), HwihaPolicySlot.read(row["slot"]))
            })
        }
    }
}

data class HwihaCorpsPolicy(val orderId: String, val commanderGeneralId: Int, val slot: HwihaPolicySlot) {
    init {
        require(HwihaDomesticIds.order(orderId) && commanderGeneralId > 0 && !slot.isEmpty)
        listOfNotNull(slot.active?.policy, slot.pending?.policy).forEach { p -> require(CorpsPolicy.entries.any { it.name == p }) }
    }
}

/** 군단 주인 장수 meta 의 군단 방침들(지휘 장수 순). 효력은 지휘 장수의 다음 턴부터다. */
data class HwihaCorpsPolicies(val entries: List<HwihaCorpsPolicy>) {
    init {
        require(entries.map { it.commanderGeneralId } == entries.map { it.commanderGeneralId }.distinct().sorted())
        require(entries.map { it.orderId }.distinct().size == entries.size)
    }
    fun forOrder(orderId: String): HwihaCorpsPolicy? = entries.firstOrNull { it.orderId == orderId }
    fun with(orderId: String, commanderGeneralId: Int, slot: HwihaPolicySlot): HwihaCorpsPolicies = HwihaCorpsPolicies(
        (entries.filter { it.orderId != orderId && it.commanderGeneralId != commanderGeneralId } +
            listOfNotNull(slot.takeUnless { it.isEmpty }?.let { HwihaCorpsPolicy(orderId, commanderGeneralId, it) }))
            .sortedBy { it.commanderGeneralId })
    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "corps" to entries.map {
        linkedMapOf("orderId" to it.orderId, "commanderGeneralId" to it.commanderGeneralId, "slot" to it.slot.toMetaValue())
    })
    companion object {
        const val META_KEY = "hwihaCorpsPolicies"
        fun read(meta: Map<String, Any?>): HwihaCorpsPolicies? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("corps policies")
            require(value.keys == setOf("version", "corps") && value["version"] == 1) { "invalid HWIHA corps policy schema" }
            val rows = value["corps"] as? List<*> ?: invalid("corps policies")
            return HwihaCorpsPolicies(rows.map { raw ->
                val row = raw as? Map<*, *> ?: invalid("corps policy")
                require(row.keys == setOf("orderId", "commanderGeneralId", "slot"))
                HwihaCorpsPolicy(row.string("orderId", "corps policy"), row.int("commanderGeneralId", "corps policy"),
                    HwihaPolicySlot.read(row["slot"]))
            })
        }
    }
}

/** 진행 중 공사. 비용은 진척에 비례해 순마다 나눠 낸다([charged] 가 지금까지 낸 누적). */
data class HwihaActiveWork(
    val work: DomesticWork,
    val requestId: String,
    val actorId: Int,
    val requestedAt: HwihaPhase,
    val progress: Int,
    val required: Int,
    val cost: HwihaResources,
    val charged: HwihaResources,
    val lastProgressAt: HwihaPhase?,
    val stopReason: String?,
) {
    init {
        require(requestIdPattern.matches(requestId) && actorId > 0 && required > 0 && progress in 0 until required)
        require(cost.debit(charged) != null) { "charged installments cannot exceed the total cost" }
        require(stopReason == null || stopReason.isNotBlank())
    }

    fun toMetaValue(): Map<String, Any?> = linkedMapOf(
        "kind" to work.name, "status" to HwihaCountyWorks.IN_PROGRESS, "requestId" to requestId, "actorId" to actorId,
        "requestedAt" to requestedAt.toMetaValue(), "progress" to progress, "required" to required, "cost" to cost.toMetaValue(),
        "charged" to charged.toMetaValue(), "lastProgressAt" to lastProgressAt?.toMetaValue(), "stopReason" to stopReason,
    )

    companion object {
        private val fields = setOf("kind", "status", "requestId", "actorId", "requestedAt", "progress", "required", "cost", "charged",
            "lastProgressAt", "stopReason")
        fun read(raw: Any?): HwihaActiveWork {
            val value = raw as? Map<*, *> ?: invalid("active work")
            require(value.keys == fields && value["status"] == HwihaCountyWorks.IN_PROGRESS) { "invalid HWIHA active work fields" }
            return HwihaActiveWork(DomesticWork.valueOf(value.string("kind", "work")), value.string("requestId", "work"),
                value.int("actorId", "work"), HwihaPhase.read(value["requestedAt"]), value.int("progress", "work"),
                value.int("required", "work"), resources(value["cost"]), resources(value["charged"]),
                value.phaseOrNull("lastProgressAt"), value["stopReason"]?.let { it as? String ?: invalid("work stop") })
        }

        internal fun resources(raw: Any?): HwihaResources {
            val value = raw as? Map<*, *> ?: invalid("resources")
            require(value.keys == setOf("money", "grain", "iron", "timber", "horses"))
            return HwihaResources(value.exactLong("money", "resources"), value.exactLong("grain", "resources"),
                value.exactLong("iron", "resources"), value.exactLong("timber", "resources"), value.exactLong("horses", "resources"))
        }
    }
}

data class HwihaCompletedWork(val work: DomesticWork, val completedAt: HwihaPhase) {
    fun toMetaValue(): Map<String, Any> = linkedMapOf("kind" to work.name, "status" to HwihaCountyWorks.COMPLETE,
        "completedAt" to completedAt.toMetaValue())
    companion object {
        fun read(raw: Any?): HwihaCompletedWork {
            val row = raw as? Map<*, *> ?: invalid("completed work")
            require(row.keys == setOf("kind", "status", "completedAt") && row["status"] == HwihaCountyWorks.COMPLETE)
            return HwihaCompletedWork(DomesticWork.valueOf(row.string("kind", "completed work")), HwihaPhase.read(row["completedAt"]))
        }
    }
}

/**
 * 縣治 城 meta `hwihaCountyWorks` = `{"version":1,"works":[…]}`. 시야 스트림과 맞춘 공개 꼴이다(비전 계약
 * `2026-09-23-hwiha-vision-contract.md` §3): 항목마다 `kind`(공사 코드)·`status` 가 있고, 완공은 `COMPLETE`, 진행 중은
 * `IN_PROGRESS`(멈춤 사유는 `stopReason`)다. 망루봉화 시야는 `kind == WATCHTOWER_BEACON && status == COMPLETE` 만 본다.
 * 완공 항목(완공 순·코드 순)이 앞, 진행 중 항목이 맨 뒤에 한 개 이하다.
 */
data class HwihaCountyWorks(val active: HwihaActiveWork?, val completed: List<HwihaCompletedWork>) {
    init {
        require(completed.map { it.work }.distinct().size == completed.size) { "a work completes once per county" }
        require(active == null || completed.none { it.work == active.work })
        require(completed == completed.sortedWith(compareBy({ it.completedAt }, { it.work.ordinal })))
    }
    fun toMetaValue(): Map<String, Any?> = linkedMapOf("version" to 1,
        "works" to completed.map { it.toMetaValue() } + listOfNotNull(active?.toMetaValue()))
    companion object {
        const val META_KEY = "hwihaCountyWorks"
        const val IN_PROGRESS = "IN_PROGRESS"
        const val COMPLETE = "COMPLETE"
        fun read(meta: Map<String, Any?>): HwihaCountyWorks? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("county works")
            require(value.keys == setOf("version", "works") && value["version"] == 1) { "invalid HWIHA county works schema" }
            val rows = value["works"] as? List<*> ?: invalid("county works")
            val done = rows.filter { (it as? Map<*, *>)?.get("status") == COMPLETE }.map(HwihaCompletedWork::read)
            val open = rows.filter { (it as? Map<*, *>)?.get("status") != COMPLETE }.map(HwihaActiveWork::read)
            require(open.size <= 1) { "one active work per county" }
            // The in-progress entry, if any, is last (JSON reload widens numbers, so compare positions, not values).
            require(open.isEmpty() || (rows.last() as Map<*, *>)["status"] != COMPLETE) { "county works out of canonical order" }
            return HwihaCountyWorks(open.singleOrNull(), done)
        }
    }
}

/** 縣 지표 묶음(§8.1 의 호구·전답·시장·민심·방비 + 치안). 민심은 정수로 비교한다. */
data class HwihaCountyIndicators(
    val population: Int, val agriculture: Int, val commerce: Int, val security: Int,
    val trust: Int, val defence: Int, val wall: Int,
) {
    fun toMetaValue(): Map<String, Any> = linkedMapOf("population" to population, "agriculture" to agriculture,
        "commerce" to commerce, "security" to security, "trust" to trust, "defence" to defence, "wall" to wall)

    /** 지난달보다 오른 지표 이름(고정 순서). */
    fun risenSince(previous: HwihaCountyIndicators): List<String> = buildList {
        if (population > previous.population) add("population")
        if (agriculture > previous.agriculture) add("agriculture")
        if (commerce > previous.commerce) add("commerce")
        if (security > previous.security) add("security")
        if (trust > previous.trust) add("trust")
        if (defence > previous.defence) add("defence")
        if (wall > previous.wall) add("wall")
    }

    companion object {
        fun read(raw: Any?): HwihaCountyIndicators {
            val value = raw as? Map<*, *> ?: invalid("indicators")
            require(value.keys == setOf("population", "agriculture", "commerce", "security", "trust", "defence", "wall"))
            return HwihaCountyIndicators(value.int("population", "indicators"), value.int("agriculture", "indicators"),
                value.int("commerce", "indicators"), value.int("security", "indicators"), value.int("trust", "indicators"),
                value.int("defence", "indicators"), value.int("wall", "indicators"))
        }
    }
}

/** 치적 비교용 월 지표(월 경계에 적는다). [stamp] 은 "YYYY-MM". */
data class HwihaCountyMonthly(val stamp: String, val indicators: HwihaCountyIndicators) {
    init { require(stamp.matches(Regex("[0-9]{4,}-[0-9]{2}"))) }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "stamp" to stamp, "indicators" to indicators.toMetaValue())
    companion object {
        const val META_KEY = "hwihaCountyMonthly"
        fun read(meta: Map<String, Any?>): HwihaCountyMonthly? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("county monthly")
            require(value.keys == setOf("version", "stamp", "indicators") && value["version"] == 1) { "invalid HWIHA county monthly schema" }
            return HwihaCountyMonthly(value.string("stamp", "county monthly"), HwihaCountyIndicators.read(value["indicators"]))
        }
    }
}
