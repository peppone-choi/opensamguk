package opensamguk.logic.domestic

import opensamguk.logic.economy.Resources
import opensamguk.logic.input.MarchCheckpoint
import opensamguk.logic.input.Phase
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.StrategicTopologySnapshot

/*
 * 휘하 내정 입력의 저장 꼴. 전부 기존 meta(jsonb) 에 typed codec 으로 싣고 recorder → flush 로만 쓴다.
 * 모든 reader 는 키가 없으면 null(아직 입력 없음), 꼴이 어긋나면 IllegalArgumentException 이다 —
 * 오염된 저장값을 빈 상태로 바꿔 읽지 않는다.
 *
 * | 키 | 어디 | 내용 |
 * |---|---|---|
 * | `placement` | 배치된 카드의 장수 meta | 대기·현행 배치, 부임(도착) 순 |
 * | `placementMarch` | 같은 장수 meta | 부임 행군 진행(경로·커서) |
 * | `countyPolicy` | 縣治 城 meta | 縣 방침 대기·현행, 마지막 적용 |
 * | `countyWorks` | 縣治 城 meta | 진행 중 공사·완공 목록(시야 스트림 공개 꼴 `{"version":1,"works":[{kind,status,…}]}`) |
 * | `scoutPosts` | 배치 주인 장수 meta | 정찰 배치 공개 투영(시야 스트림이 읽는다, 정본은 `placement`) |
 * | `countyMonthly` | 縣治 城 meta | 치적 비교용 지난달 지표 |
 * | `commanderyPolicies` | 세력 nation meta | 郡 방침 대기·현행 |
 * | `corpsPolicies` | 군단 주인 장수 meta | 군단 방침 대기·현행 |
 */

private fun invalid(what: String): Nothing = throw IllegalArgumentException("invalid HWIHA $what")
private fun Map<*, *>.int(key: String, what: String): Int = this[key] as? Int ?: invalid(what)
private fun Map<*, *>.string(key: String, what: String): String = this[key] as? String ?: invalid(what)
private fun Map<*, *>.exactLong(key: String, what: String): Long = when (val value = this[key]) {
    is Int -> value.toLong()
    is Long -> value
    else -> invalid(what)
}
private fun Map<*, *>.phaseOrNull(key: String): Phase? = this[key]?.let { Phase.read(it) }
private val requestIdPattern = Regex("[A-Za-z0-9._:-]{1,128}")

/** 접수된 배치 한 건. 카드의 다음 턴에 현행이 된다(§4). */
data class PlacementOrder(
    val requestId: String,
    val ownerGeneralId: Int,
    val retainerId: Int,
    val post: PlacementPost,
    val target: PlacementTarget,
    val requestedAt: Phase,
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
        fun read(raw: Any?): PlacementOrder {
            val value = raw as? Map<*, *> ?: invalid("placement order")
            require(value.keys == fields) { "invalid HWIHA placement order fields" }
            val post = PlacementPost.valueOf(value.string("post", "placement post"))
            return PlacementOrder(value.string("requestId", "placement"), value.int("ownerGeneralId", "placement"),
                value.int("retainerId", "placement"), post, PlacementTarget.read(value["target"]),
                Phase.read(value["requestedAt"]))
        }
    }
}

/** 현행 배치. [arrivedAt] 이 있으면 자리에 앉은 것이다(행군이 목적지에 닿은 순). */
data class ActivePlacement(val order: PlacementOrder, val since: Phase, val arrivedAt: Phase?) {
    init {
        require(order.post != PlacementPost.NONE) { "a release is never an active placement" }
        require(since >= order.requestedAt && (arrivedAt == null || arrivedAt >= since))
    }

    fun toMetaValue(): Map<String, Any?> = linkedMapOf(
        "order" to order.toMetaValue(), "since" to since.toMetaValue(), "arrivedAt" to arrivedAt?.toMetaValue(),
    )

    companion object {
        fun read(raw: Any?): ActivePlacement {
            val value = raw as? Map<*, *> ?: invalid("active placement")
            require(value.keys == setOf("order", "since", "arrivedAt")) { "invalid HWIHA active placement fields" }
            return ActivePlacement(PlacementOrder.read(value["order"]), Phase.read(value["since"]),
                value.phaseOrNull("arrivedAt"))
        }
    }
}

data class PlacementState(val active: ActivePlacement?, val pending: PlacementOrder?) {
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
        const val META_KEY = "placement"
        fun read(meta: Map<String, Any?>): PlacementState? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("placement")
            require(value.keys == setOf("version", "active", "pending") && value["version"] == 1) { "invalid HWIHA placement schema" }
            return PlacementState(value["active"]?.let(ActivePlacement::read), value["pending"]?.let(PlacementOrder::read))
        }
    }
}

/** 배치 부임 행군. [requestId] 가 현행 배치와 다르면 낡은 진행이다. */
data class PlacementMarch(val requestId: String, val checkpoint: MarchCheckpoint) {
    init { require(requestIdPattern.matches(requestId)) }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "requestId" to requestId, "checkpoint" to checkpoint.toMetaValue())

    companion object {
        const val META_KEY = "placementMarch"
        fun read(meta: Map<String, Any?>, topology: StrategicTopologySnapshot, metrics: LandMarchMetricSnapshot): PlacementMarch? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("placement march")
            require(value.keys == setOf("version", "requestId", "checkpoint") && value["version"] == 1) { "invalid HWIHA placement march schema" }
            return PlacementMarch(value.string("requestId", "placement march"), MarchCheckpoint.read(value["checkpoint"], topology, metrics))
        }
    }
}

/** 걸린 방침. */
data class PolicySetting(val policy: String, val requestId: String, val actorId: Int, val since: Phase) {
    init { require(policy.isNotBlank() && requestIdPattern.matches(requestId) && actorId > 0) }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("policy" to policy, "requestId" to requestId, "actorId" to actorId,
        "since" to since.toMetaValue())
    companion object {
        fun read(raw: Any?): PolicySetting {
            val value = raw as? Map<*, *> ?: invalid("policy setting")
            require(value.keys == setOf("policy", "requestId", "actorId", "since"))
            return PolicySetting(value.string("policy", "policy"), value.string("requestId", "policy"),
                value.int("actorId", "policy"), Phase.read(value["since"]))
        }
    }
}

/** 접수된 방침 변경. [policy] null 은 거두기다. */
data class PolicyOrder(val policy: String?, val requestId: String, val actorId: Int, val requestedAt: Phase) {
    init { require((policy == null || policy.isNotBlank()) && requestIdPattern.matches(requestId) && actorId > 0) }
    fun toMetaValue(): Map<String, Any?> = linkedMapOf("policy" to policy, "requestId" to requestId, "actorId" to actorId,
        "requestedAt" to requestedAt.toMetaValue())
    companion object {
        fun read(raw: Any?): PolicyOrder {
            val value = raw as? Map<*, *> ?: invalid("policy order")
            require(value.keys == setOf("policy", "requestId", "actorId", "requestedAt"))
            val policy = value["policy"]?.let { it as? String ?: invalid("policy order") }
            return PolicyOrder(policy, value.string("requestId", "policy"), value.int("actorId", "policy"),
                Phase.read(value["requestedAt"]))
        }
    }
}

/** 방침 칸 하나: 현행 + 다음 효력 시점에 바뀔 대기. 둘 다 비면 칸을 지운다. */
data class PolicySlot(val active: PolicySetting?, val pending: PolicyOrder?) {
    val isEmpty: Boolean get() = active == null && pending == null
    fun toMetaValue(): Map<String, Any?> = linkedMapOf("active" to active?.toMetaValue(), "pending" to pending?.toMetaValue())

    /** 대기를 현행으로 옮긴다. 거두기면 현행이 사라진다. */
    fun activate(now: Phase): PolicySlot {
        val order = pending ?: return this
        return PolicySlot(order.policy?.let { PolicySetting(it, order.requestId, order.actorId, now) }, null)
    }

    companion object {
        fun read(raw: Any?): PolicySlot {
            val value = raw as? Map<*, *> ?: invalid("policy slot")
            require(value.keys == setOf("active", "pending"))
            return PolicySlot(value["active"]?.let(PolicySetting::read), value["pending"]?.let(PolicyOrder::read))
        }
    }
}

/** 縣 방침의 마지막 적용(순마다 한 번). [result] 는 APPLIED 이거나 건너뛴 사유 코드다. */
data class PolicyApplication(val at: Phase, val policy: String, val seat: String, val result: String) {
    init { require(policy.isNotBlank() && seat in setOf("SEATED", "EMPTY") && result.isNotBlank()) }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("at" to at.toMetaValue(), "policy" to policy, "seat" to seat, "result" to result)
    companion object {
        fun read(raw: Any?): PolicyApplication {
            val value = raw as? Map<*, *> ?: invalid("policy application")
            require(value.keys == setOf("at", "policy", "seat", "result"))
            return PolicyApplication(Phase.read(value["at"]), value.string("policy", "application"),
                value.string("seat", "application"), value.string("result", "application"))
        }
    }
}

data class CountyPolicyState(val slot: PolicySlot, val lastApplied: PolicyApplication?) {
    init { slot.active?.let { require(CountyPolicy.entries.any { p -> p.name == it.policy }) } }
    fun toMetaValue(): Map<String, Any?> = linkedMapOf("version" to 1, "slot" to slot.toMetaValue(),
        "lastApplied" to lastApplied?.toMetaValue())
    companion object {
        const val META_KEY = "countyPolicy"
        fun read(meta: Map<String, Any?>): CountyPolicyState? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("county policy")
            require(value.keys == setOf("version", "slot", "lastApplied") && value["version"] == 1) { "invalid HWIHA county policy schema" }
            val slot = PolicySlot.read(value["slot"])
            slot.pending?.policy?.let { require(CountyPolicy.entries.any { p -> p.name == it }) }
            return CountyPolicyState(slot, value["lastApplied"]?.let(PolicyApplication::read))
        }
    }
}

data class CommanderyPolicy(val commanderyId: String, val slot: PolicySlot) {
    init {
        require(DomesticIds.commandery(commanderyId) && !slot.isEmpty)
        listOfNotNull(slot.active?.policy, slot.pending?.policy).forEach { p -> require(CountyPolicy.entries.any { it.name == p }) }
    }
}

/** 세력 meta 의 郡 방침들(郡 식별자 순). 점령으로 縣이 넘어가면 새 세력의 郡 방침을 따른다. */
data class CommanderyPolicies(val entries: List<CommanderyPolicy>) {
    init { require(entries.map { it.commanderyId } == entries.map { it.commanderyId }.distinct().sorted()) }
    operator fun get(commanderyId: String): CommanderyPolicy? = entries.firstOrNull { it.commanderyId == commanderyId }
    fun with(commanderyId: String, slot: PolicySlot): CommanderyPolicies = CommanderyPolicies(
        (entries.filter { it.commanderyId != commanderyId } + listOfNotNull(slot.takeUnless { it.isEmpty }?.let {
            CommanderyPolicy(commanderyId, it) })).sortedBy { it.commanderyId })
    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "commanderies" to entries.map {
        linkedMapOf("commanderyId" to it.commanderyId, "slot" to it.slot.toMetaValue())
    })
    companion object {
        const val META_KEY = "commanderyPolicies"
        fun read(meta: Map<String, Any?>): CommanderyPolicies? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("commandery policies")
            require(value.keys == setOf("version", "commanderies") && value["version"] == 1) { "invalid HWIHA commandery policy schema" }
            val rows = value["commanderies"] as? List<*> ?: invalid("commandery policies")
            return CommanderyPolicies(rows.map { raw ->
                val row = raw as? Map<*, *> ?: invalid("commandery policy")
                require(row.keys == setOf("commanderyId", "slot"))
                CommanderyPolicy(row.string("commanderyId", "commandery policy"), PolicySlot.read(row["slot"]))
            })
        }
    }
}

data class CorpsPolicyAssignment(val orderId: String, val commanderGeneralId: Int, val slot: PolicySlot) {
    init {
        require(DomesticIds.order(orderId) && commanderGeneralId > 0 && !slot.isEmpty)
        listOfNotNull(slot.active?.policy, slot.pending?.policy).forEach { p -> require(CorpsPolicy.entries.any { it.name == p }) }
    }
}

/** 군단 주인 장수 meta 의 군단 방침들(지휘 장수 순). 효력은 지휘 장수의 다음 턴부터다. */
data class CorpsPolicyAssignments(val entries: List<CorpsPolicyAssignment>) {
    init {
        require(entries.map { it.commanderGeneralId } == entries.map { it.commanderGeneralId }.distinct().sorted())
        require(entries.map { it.orderId }.distinct().size == entries.size)
    }
    fun forOrder(orderId: String): CorpsPolicyAssignment? = entries.firstOrNull { it.orderId == orderId }
    fun with(orderId: String, commanderGeneralId: Int, slot: PolicySlot): CorpsPolicyAssignments = CorpsPolicyAssignments(
        (entries.filter { it.orderId != orderId && it.commanderGeneralId != commanderGeneralId } +
            listOfNotNull(slot.takeUnless { it.isEmpty }?.let { CorpsPolicyAssignment(orderId, commanderGeneralId, it) }))
            .sortedBy { it.commanderGeneralId })
    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "corps" to entries.map {
        linkedMapOf("orderId" to it.orderId, "commanderGeneralId" to it.commanderGeneralId, "slot" to it.slot.toMetaValue())
    })
    companion object {
        const val META_KEY = "corpsPolicies"
        fun read(meta: Map<String, Any?>): CorpsPolicyAssignments? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("corps policies")
            require(value.keys == setOf("version", "corps") && value["version"] == 1) { "invalid HWIHA corps policy schema" }
            val rows = value["corps"] as? List<*> ?: invalid("corps policies")
            return CorpsPolicyAssignments(rows.map { raw ->
                val row = raw as? Map<*, *> ?: invalid("corps policy")
                require(row.keys == setOf("orderId", "commanderGeneralId", "slot"))
                CorpsPolicyAssignment(row.string("orderId", "corps policy"), row.int("commanderGeneralId", "corps policy"),
                    PolicySlot.read(row["slot"]))
            })
        }
    }
}

/** 진행 중 공사. 비용은 진척에 비례해 순마다 나눠 낸다([charged] 가 지금까지 낸 누적). */
data class ActiveWork(
    val work: DomesticWork,
    val requestId: String,
    val actorId: Int,
    val requestedAt: Phase,
    val progress: Int,
    val required: Int,
    val cost: Resources,
    val charged: Resources,
    val lastProgressAt: Phase?,
    val stopReason: String?,
    val edgeId: String? = null,
    val row: Int? = null,
    val col: Int? = null,
) {
    init {
        require(requestIdPattern.matches(requestId) && actorId > 0 && required > 0 && progress in 0 until required)
        require(cost.debit(charged) != null) { "charged installments cannot exceed the total cost" }
        require(stopReason == null || stopReason.isNotBlank())
        require((row == null) == (col == null) && (row == null || (row >= 0 && col!! >= 0)) &&
            (edgeId == null || DomesticIds.order(edgeId)))
    }

    fun toMetaValue(): Map<String, Any?> = linkedMapOf(
        "kind" to work.name, "status" to CountyWorks.IN_PROGRESS, "requestId" to requestId, "actorId" to actorId,
        "requestedAt" to requestedAt.toMetaValue(), "progress" to progress, "required" to required, "cost" to cost.toMetaValue(),
        "charged" to charged.toMetaValue(), "lastProgressAt" to lastProgressAt?.toMetaValue(), "stopReason" to stopReason,
    ).also { value ->
        if (edgeId != null) value["edgeId"] = edgeId
        if (row != null) { value["row"] = row; value["col"] = col }
    }

    companion object {
        private val fields = setOf("kind", "status", "requestId", "actorId", "requestedAt", "progress", "required", "cost", "charged",
            "lastProgressAt", "stopReason")
        fun read(raw: Any?): ActiveWork {
            val value = raw as? Map<*, *> ?: invalid("active work")
            require(value.keys == fields || value.keys == fields + "edgeId" ||
                value.keys == fields + setOf("edgeId", "row", "col")) { "invalid HWIHA active work fields" }
            require(value["status"] == CountyWorks.IN_PROGRESS)
            return ActiveWork(DomesticWork.valueOf(value.string("kind", "work")), value.string("requestId", "work"),
                value.int("actorId", "work"), Phase.read(value["requestedAt"]), value.int("progress", "work"),
                value.int("required", "work"), resources(value["cost"]), resources(value["charged"]),
                value.phaseOrNull("lastProgressAt"), value["stopReason"]?.let { it as? String ?: invalid("work stop") },
                value["edgeId"]?.let { it as? String ?: invalid("work edge") },
                value["row"]?.let { it as? Int ?: invalid("work row") },
                value["col"]?.let { it as? Int ?: invalid("work col") })
        }

        internal fun resources(raw: Any?): Resources {
            val value = raw as? Map<*, *> ?: invalid("resources")
            require(value.keys == setOf("money", "grain", "iron", "timber", "horses"))
            return Resources(value.exactLong("money", "resources"), value.exactLong("grain", "resources"),
                value.exactLong("iron", "resources"), value.exactLong("timber", "resources"), value.exactLong("horses", "resources"))
        }
    }
}

data class CompletedWork(val work: DomesticWork, val completedAt: Phase,
    val edgeId: String? = null, val row: Int? = null, val col: Int? = null) {
    init {
        require((row == null) == (col == null) && (row == null || (row >= 0 && col!! >= 0)) &&
            (edgeId == null || DomesticIds.order(edgeId)))
    }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("kind" to work.name, "status" to CountyWorks.COMPLETE,
        "completedAt" to completedAt.toMetaValue()).also { value ->
            if (edgeId != null) value["edgeId"] = edgeId
            if (row != null) { value["row"] = row; value["col"] = col ?: invalid("completed work cell") }
        }
    companion object {
        fun read(raw: Any?): CompletedWork {
            val row = raw as? Map<*, *> ?: invalid("completed work")
            val fields = setOf("kind", "status", "completedAt")
            require((row.keys == fields || row.keys == fields + "edgeId" ||
                row.keys == fields + setOf("edgeId", "row", "col")) && row["status"] == CountyWorks.COMPLETE)
            return CompletedWork(DomesticWork.valueOf(row.string("kind", "completed work")), Phase.read(row["completedAt"]),
                row["edgeId"]?.let { it as? String ?: invalid("completed work edge") },
                row["row"]?.let { it as? Int ?: invalid("completed work row") },
                row["col"]?.let { it as? Int ?: invalid("completed work col") })
        }
    }
}

/**
 * 縣治 城 meta `countyWorks` = `{"version":1,"works":[…]}`. 시야 스트림과 맞춘 공개 꼴이다(비전 계약
 * `2026-09-23-hwiha-vision-contract.md` §3): 항목마다 `kind`(공사 코드)·`status` 가 있고, 완공은 `COMPLETE`, 진행 중은
 * `IN_PROGRESS`(멈춤 사유는 `stopReason`)다. 망루봉화 시야는 `kind == WATCHTOWER_BEACON && status == COMPLETE` 만 본다.
 * 완공 항목(완공 순·코드 순)이 앞, 진행 중 항목이 맨 뒤에 한 개 이하다.
 */
data class CountyWorks(val active: ActiveWork?, val completed: List<CompletedWork>) {
    init {
        require(completed.map { listOf(it.work, it.edgeId, it.row, it.col) }.distinct().size == completed.size)
        require(active == null || completed.none { it.work == active.work && it.edgeId == active.edgeId &&
            it.row == active.row && it.col == active.col })
        require(completed == completed.sortedWith(compareBy({ it.completedAt }, { it.work.ordinal },
            { it.edgeId ?: "" }, { it.row ?: -1 }, { it.col ?: -1 })))
    }
    fun toMetaValue(): Map<String, Any?> = linkedMapOf("version" to 1,
        "works" to completed.map { it.toMetaValue() } + listOfNotNull(active?.toMetaValue()))
    companion object {
        const val META_KEY = "countyWorks"
        const val IN_PROGRESS = "IN_PROGRESS"
        const val COMPLETE = "COMPLETE"
        fun read(meta: Map<String, Any?>): CountyWorks? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("county works")
            require(value.keys == setOf("version", "works") && value["version"] == 1) { "invalid HWIHA county works schema" }
            val rows = value["works"] as? List<*> ?: invalid("county works")
            val done = rows.filter { (it as? Map<*, *>)?.get("status") == COMPLETE }.map(CompletedWork::read)
            val open = rows.filter { (it as? Map<*, *>)?.get("status") != COMPLETE }.map(ActiveWork::read)
            require(open.size <= 1) { "one active work per county" }
            // The in-progress entry, if any, is last (JSON reload widens numbers, so compare positions, not values).
            require(open.isEmpty() || (rows.last() as Map<*, *>)["status"] != COMPLETE) { "county works out of canonical order" }
            return CountyWorks(open.singleOrNull(), done)
        }
    }
}

/** 縣 지표 묶음(§8.1 의 호구·전답·시장·민심·방비 + 치안). 민심은 정수로 비교한다. */
data class CountyIndicators(
    val population: Int, val agriculture: Int, val commerce: Int, val security: Int,
    val trust: Int, val defence: Int, val wall: Int,
) {
    fun toMetaValue(): Map<String, Any> = linkedMapOf("population" to population, "agriculture" to agriculture,
        "commerce" to commerce, "security" to security, "trust" to trust, "defence" to defence, "wall" to wall)

    /** 지난달보다 오른 지표 이름(고정 순서). */
    fun risenSince(previous: CountyIndicators): List<String> = buildList {
        if (population > previous.population) add("population")
        if (agriculture > previous.agriculture) add("agriculture")
        if (commerce > previous.commerce) add("commerce")
        if (security > previous.security) add("security")
        if (trust > previous.trust) add("trust")
        if (defence > previous.defence) add("defence")
        if (wall > previous.wall) add("wall")
    }

    companion object {
        fun read(raw: Any?): CountyIndicators {
            val value = raw as? Map<*, *> ?: invalid("indicators")
            require(value.keys == setOf("population", "agriculture", "commerce", "security", "trust", "defence", "wall"))
            return CountyIndicators(value.int("population", "indicators"), value.int("agriculture", "indicators"),
                value.int("commerce", "indicators"), value.int("security", "indicators"), value.int("trust", "indicators"),
                value.int("defence", "indicators"), value.int("wall", "indicators"))
        }
    }
}

/** 치적 비교용 월 지표(월 경계에 적는다). [stamp] 은 "YYYY-MM". */
data class CountyMonthly(val stamp: String, val indicators: CountyIndicators) {
    init { require(stamp.matches(Regex("[0-9]{4,}-[0-9]{2}"))) }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "stamp" to stamp, "indicators" to indicators.toMetaValue())
    companion object {
        const val META_KEY = "countyMonthly"
        fun read(meta: Map<String, Any?>): CountyMonthly? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid("county monthly")
            require(value.keys == setOf("version", "stamp", "indicators") && value["version"] == 1) { "invalid HWIHA county monthly schema" }
            return CountyMonthly(value.string("stamp", "county monthly"), CountyIndicators.read(value["indicators"]))
        }
    }
}
