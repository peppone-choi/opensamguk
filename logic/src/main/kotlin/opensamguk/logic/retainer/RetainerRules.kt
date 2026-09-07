package opensamguk.logic.retainer

import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 4X-A 가신(휘하 인물)·부곡 — 순수 규칙 (specs/2026-09-06-retinue-buqu-vertical-slice v3, ADR-LITE-017).
 *
 * 여기에는 Spring/DB/world 가 없다. 게이트 순서(§4)·이름 정규화(S8)·월 정산 산식(§5)만 있고,
 * 부수 효과는 엔진 `RetainerHandler`/`RetainerMonthlyService` 가 소유한다.
 *
 * **잠정 상수**: 아래 숫자는 이 절편의 설계 상수다(플레이테스트로 조정). 실측 기준선이 아니며 게이트 임계값으로
 * 쓰지 않는다 — 응답 `rules.provisional = true` 로 노출되고 UI 가 「잠정」 칩을 붙인다.
 */
object RetainerRules {
    const val PROVISIONAL = true

    const val MAX_RETAINERS = 5
    const val MAX_BUGOK = 2
    const val PLEDGE_COST_GOLD = 500
    const val RETAINER_UPKEEP_GOLD = 30
    const val RETAINER_UPKEEP_RICE = 30
    const val MIN_BUGOK_TROOPS = 100
    const val COMMANDER_MORALE_BONUS = 6
    const val PROVISION_PER_TROOP_MONTH = 1
    const val PAY_GOLD_PER_100_TROOPS = 10
    const val MORALE_LOSS_UNPAID = 5
    const val FATIGUE_REST = 5
    const val FATIGUE_TRAIN = 10
    const val TRAINING_GAIN = 2
    const val LOYALTY_TASKED = 1
    const val LOYALTY_IDLE = -1
    const val LOYALTY_LOSS_UNPAID = -5

    const val ORIGIN_EXISTING = "EXISTING"
    const val ORIGIN_RECRUITED = "RECRUITED"
    val ORIGINS: Set<String> = setOf(ORIGIN_EXISTING, ORIGIN_RECRUITED)

    /** 관계(로드맵·07 아트보드): 막료/부장/문객. 부장만 부곡 지휘관이 될 수 있다. */
    const val RELATION_STAFF = "staff"
    const val RELATION_LIEUTENANT = "lieutenant"
    const val RELATION_GUEST = "guest"
    val RELATIONS: Set<String> = setOf(RELATION_STAFF, RELATION_LIEUTENANT, RELATION_GUEST)
    val RELATION_LABELS: Map<String, String> = mapOf(RELATION_STAFF to "막료", RELATION_LIEUTENANT to "부장", RELATION_GUEST to "문객")

    /** 역할(ADR-LITE-017): 참모·호위·군수관·정찰·사신·NONE. 이 절편은 저장·표시만, 효과 없음. */
    const val ROLE_NONE = "NONE"
    val ROLES: Set<String> = setOf("STAFF", "GUARD", "QUARTERMASTER", "SCOUT", "ENVOY", ROLE_NONE)
    val ROLE_LABELS: Map<String, String> = mapOf(
        "STAFF" to "참모", "GUARD" to "호위", "QUARTERMASTER" to "군수관", "SCOUT" to "정찰", "ENVOY" to "사신", ROLE_NONE to "없음",
    )

    /** 임무(07 아트보드): 없음/내정 보좌/정찰/훈련. 훈련 부장이 지휘하는 부곡은 훈련 +2·피로 +10. */
    const val TASK_NONE = "none"
    const val TASK_TRAIN = "train"
    val TASKS: Set<String> = setOf(TASK_NONE, "domestic", "scout", TASK_TRAIN)
    val TASK_LABELS: Map<String, String> = mapOf(TASK_NONE to "없음", "domestic" to "내정 보좌", "scout" to "정찰", TASK_TRAIN to "훈련")

    const val RELEASE_MUTUAL = "MUTUAL"
    const val RELEASE_MASTER_ONLY = "MASTER_ONLY"

    const val REASON_INPUT = "올바르지 않은 입력입니다."
    /** NPC(npcState >= 2)는 휘하를 두지 않는다 — 결정성(인테이크만, PR 비평 S6). */
    const val REASON_NPC = "NPC 장수는 휘하를 둘 수 없습니다."
    const val REASON_RETAINERS_FULL = "가신이 가득 찼습니다."
    const val REASON_DUP_NAME = "같은 이름의 휘하가 있습니다."
    const val REASON_NO_GOLD = "자금이 부족합니다."
    const val REASON_NO_RETAINER = "휘하 인물이 없습니다."
    const val REASON_BUGOK_FULL = "부곡이 가득 찼습니다."
    const val REASON_NO_TROOPS = "병력이 부족합니다."
    const val REASON_NO_RICE = "군량이 부족합니다."
    const val REASON_NO_BUGOK = "부곡이 없습니다."
    const val REASON_CREW_TYPE = "병종이 다릅니다."
    const val REASON_NOT_LIEUTENANT = "부장만 배정할 수 있습니다."

    // ── 이름 정규화 (S8): NFC → trim → 내부 공백 거부 → 2~12 코드포인트 ──
    sealed interface NameOutcome {
        data class Ok(val name: String) : NameOutcome
        data class Denied(val reason: String) : NameOutcome
    }

    fun normalizeName(raw: String?): NameOutcome {
        if (raw == null) return NameOutcome.Denied(REASON_INPUT)
        val name = Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
        if (name.any { it.isWhitespace() }) return NameOutcome.Denied(REASON_INPUT)
        val length = name.codePointCount(0, name.length)
        if (length < 2 || length > 12) return NameOutcome.Denied(REASON_INPUT)
        return NameOutcome.Ok(name)
    }

    // ── 상태 게이트 (§4 순서 고정; ③ 입력은 호출자가 먼저 통과시킨다) ──

    /** 서약: 상한 → 중복 이름 → 자금. */
    fun pledgeDeny(retainerCount: Int, existingNames: Collection<String>, name: String, gold: Int): String? = when {
        retainerCount >= MAX_RETAINERS -> REASON_RETAINERS_FULL
        name in existingNames -> REASON_DUP_NAME
        gold < PLEDGE_COST_GOLD -> REASON_NO_GOLD
        else -> null
    }

    /** 편성: 상한 → 병력 → 군량. troops/rice 의 입력 검증(null·MIN·음수)은 [bugokFormInputDeny]. */
    fun bugokFormInputDeny(troops: Int?, rice: Int?): String? = when {
        troops == null || troops < MIN_BUGOK_TROOPS -> REASON_INPUT
        rice == null || rice < 0 -> REASON_INPUT
        else -> null
    }

    fun bugokFormDeny(bugokCount: Int, crew: Int, troops: Int, generalRice: Int, rice: Int): String? = when {
        bugokCount >= MAX_BUGOK -> REASON_BUGOK_FULL
        crew < troops -> REASON_NO_TROOPS
        generalRice < rice -> REASON_NO_RICE
        else -> null
    }

    /** 해산: 내 부곡 → 병종 일치. */
    fun bugokDisbandDeny(owned: Boolean, bugokCrewTypeId: Int, generalCrewTypeId: Int): String? = when {
        !owned -> REASON_NO_BUGOK
        bugokCrewTypeId != generalCrewTypeId -> REASON_CREW_TYPE
        else -> null
    }

    /** 지휘관 배정: 내 부곡 → (retainerId 있으면) 내 가신 → 부장. */
    fun assignCommanderDeny(bugokOwned: Boolean, retainerId: Int?, retainerOwned: Boolean, relation: String?): String? = when {
        !bugokOwned -> REASON_NO_BUGOK
        retainerId == null -> null
        !retainerOwned -> REASON_NO_RETAINER
        relation != RELATION_LIEUTENANT -> REASON_NOT_LIEUTENANT
        else -> null
    }

    // ── 월 정산 산식 (§5) ──

    fun payFor(troops: Int): Int = ceil(troops / 100.0).toInt() * PAY_GOLD_PER_100_TROOPS
    fun consumptionFor(troops: Int): Int = troops * PROVISION_PER_TROOP_MONTH
    fun provisionMonths(provisions: Int, troops: Int): Int = provisions / max(1, troops * PROVISION_PER_TROOP_MONTH)

    data class BugokSettleInput(
        val troops: Int,
        val provisions: Int,
        val morale: Int,
        val fatigue: Int,
        val training: Int,
        val masterGold: Int,
        /** 지휘 부장의 임무(없으면 null). */
        val commanderTask: String?,
    )

    data class BugokSettlement(
        val provisions: Int,
        val morale: Int,
        val fatigue: Int,
        val training: Int,
        /** 주인 장수 gold 에서 실제로 뺀 급여(전액 아니면 0 — 부분 지급 없음). */
        val goldPaid: Int,
        val shortProvisions: Boolean,
        val shortPay: Boolean,
    )

    fun settleBugok(i: BugokSettleInput): BugokSettlement {
        val consumption = consumptionFor(i.troops)
        val pay = payFor(i.troops)
        val shortProvisions = i.provisions < consumption
        val provisions = max(0, i.provisions - consumption)
        val shortPay = i.masterGold < pay
        val goldPaid = if (shortPay) 0 else pay
        val morale = max(0, i.morale - (if (shortProvisions || shortPay) MORALE_LOSS_UNPAID else 0))
        val training = if (i.commanderTask == TASK_TRAIN) min(100, i.training + TRAINING_GAIN) else i.training
        val fatigue = if (i.commanderTask == TASK_TRAIN) min(100, i.fatigue + FATIGUE_TRAIN) else max(0, i.fatigue - FATIGUE_REST)
        return BugokSettlement(provisions, morale, fatigue, training, goldPaid, shortProvisions, shortPay)
    }

    data class RetainerSettleInput(
        val loyalty: Int,
        val task: String,
        val origin: String,
        val masterGold: Int,
        val masterRice: Int,
    )

    sealed interface RetainerSettlement {
        /** 정산 시작 시 충성 0 — 떠난다. */
        data object Leave : RetainerSettlement
        data class Stay(val loyalty: Int, val goldPaid: Int, val ricePaid: Int, val upkeepPaid: Boolean) : RetainerSettlement
    }

    fun settleRetainer(i: RetainerSettleInput): RetainerSettlement {
        if (i.loyalty <= 0) return RetainerSettlement.Leave
        val needsUpkeep = i.origin == ORIGIN_RECRUITED
        val upkeepPaid = !needsUpkeep || (i.masterGold >= RETAINER_UPKEEP_GOLD && i.masterRice >= RETAINER_UPKEEP_RICE)
        val goldPaid = if (needsUpkeep && upkeepPaid) RETAINER_UPKEEP_GOLD else 0
        val ricePaid = if (needsUpkeep && upkeepPaid) RETAINER_UPKEEP_RICE else 0
        val delta = (if (i.task == TASK_NONE) LOYALTY_IDLE else LOYALTY_TASKED) + (if (upkeepPaid) 0 else LOYALTY_LOSS_UNPAID)
        return RetainerSettlement.Stay((i.loyalty + delta).coerceIn(0, 100), goldPaid, ricePaid, upkeepPaid)
    }
}
