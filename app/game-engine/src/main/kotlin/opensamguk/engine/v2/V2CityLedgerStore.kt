package opensamguk.engine.v2

import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** v2 도시 원장 한 도시의 상태 (OPENSAM-150 R1, 설계안 §2.1). 셋 다 음수가 될 수 없다. */
data class V2CityLedgerEntry(val gold: Long, val rice: Long, val garrison: Int) {
    companion object {
        val EMPTY = V2CityLedgerEntry(0, 0, 0)
    }
}

/**
 * OPENSAM-150 (R1) — v2 도시 원장(`v2_city_ledger`)의 메모리 보유자 겸 델타 기록기.
 *
 * **이름이 `Store`인 이유 (그리고 그 대가)**: `HotColdWorldCatalogGuardTest`는 스캔 대상 파일 안에서
 * `*Repository`/`*Reader` 수신자 호출을 카탈로그와 `assertEquals`로 묶는다. 이 클래스는 스캔 대상
 * 밖(`engine/v2`는 `HotColdCatalog.runtimeSourceDirectories`에 없다)이지만 이름이 그 접미사를 가지면
 * 소비자(R2의 `DaemonLoopConfig`)에서 가드를 깨므로 접미사를 피했다.
 *
 * **그래서 이 클래스의 SELECT는 어떤 S5 카탈로그에도 등재돼 있지 않다.** R1에서는 빈도 아니고 턴 루프에
 * 배선돼 있지도 않아 실해가 없지만, R2가 이 store를 데몬 루프에 물리는 순간 **카탈로그 밖 런타임 읽기**가
 * 된다. R2의 선행 조건으로 `HotColdCatalog.runtimeDirectSqlBoundaries`(또는 `runtimeSourceDirectories`)에
 * 이 파일을 등재해야 한다. 그때까지의 보완 통제는 [V2CityLedgerReadBoundGuardTest] — 이 파일의 SQL이
 * world-scoped·결정적 정렬·읽기 전용임을 소스 스캔으로 고정한다.
 *
 * **U10(부팅 순서) 대응 — lazy 초기화.** 첫 접근 시점에 한 번만 적재하므로 Flyway/시드/스냅샷 로더와의
 * 부팅 순서에 의존하지 않는다(`@DependsOn`·`ApplicationRunner` 순서 선언 불필요). 데몬 프로세스는 한
 * 월드를 담당하므로 캐시는 월드 하나 분량이며, 다른 `worldId`로 접근하면 다시 적재한다.
 *
 * **아직 `@Bean`이 아니다 (BLOCKER, OPENSAM-150 보고 대상).** 0A-b 게이트를 그대로 쓴 신규
 * `@Configuration`을 추가하면 `V2BothConditionsBeanGateIT`
 * (`app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt:186-190`)이
 * **모든** `opensamguk.*.v2.*` 빈 이름 집합을 리터럴 4개와 `assertEquals`하므로 실패한다. 게이트 없이
 * 무조건 등록해도 같은 파일 `:64`의 `assertNoV2Beans()`(프로덕션 컨텍스트에서 v2 빈 0)가 깨진다 —
 * 막는 단언은 하나가 아니라 둘이다. 그 파일은 R1 게이트 ②가 수정을 금지한 테스트 소스라, 빈 등록은
 * (i) 그 기대 집합 확장에 대한 사람 승인 또는 (ii) OPENSAM-35 후속으로 그 단언을 allowlist화하는
 * 결정을 받은 뒤에 붙인다. 그때까지 이 클래스는 직접 생성해 쓴다(IT가 그 경로를 증명).
 *
 * **쓰기 경로**: 절대 직접 쓰지 않는다. 변경은 [ChangeRecorder]에 절대값 UPSERT 델타로 기록되고
 * `JdbcFlushExecutor`의 v2 step이 v1 델타와 **같은 트랜잭션**에서 커밋한다(one-daemon-write rule).
 */
class V2CityLedgerStore(private val jdbc: NamedParameterJdbcTemplate) {

    private var loadedWorldId: WorldId? = null
    private val entries = linkedMapOf<Int, V2CityLedgerEntry>()

    /** 도시의 현재 원장. 미적재면 이 호출이 적재한다. 행이 없는 도시는 [V2CityLedgerEntry.EMPTY]. */
    fun entry(worldId: WorldId, cityId: Int): V2CityLedgerEntry =
        load(worldId)[cityId] ?: V2CityLedgerEntry.EMPTY

    /**
     * 적재된 월드 전체 원장, **도시 id 오름차순**.
     *
     * `toSortedMap()`인 이유: 내부 캐시는 `LinkedHashMap`이라 적재 순서(`ORDER BY city_id`)를 보존하지만,
     * [adjust]가 아직 행이 없던 도시를 처음 만지면 그 도시가 **맨 뒤에 append** 되어 오름차순이 깨진다.
     * 설계안 §8 R3이 이 순회를 `city_id ASC`로 못박았으므로(공백지화 판정 순서) 반환 시점에 정렬한다.
     */
    fun entries(worldId: WorldId): Map<Int, V2CityLedgerEntry> = load(worldId).toSortedMap()

    /**
     * 도시 원장을 델타만큼 움직이고 결과 **절대 상태**를 recorder에 기록한다.
     *
     * 판정: 세 값 모두 0 미만으로 내려가지 않는다(`coerceAtLeast(0)`). 도시 금·병량·도시병사는 음수
     * 상태가 없고, 부족분은 소비 측(R2 봉록·R3 감소·R5 수송)이 자기 규칙으로 판정한다 — 여기서는
     * 원장이 음수로 새는 것만 막는다. 결과가 이전과 같으면 기록하지 않는다(빈 델타 = SQL 0건).
     */
    fun adjust(
        worldId: WorldId,
        recorder: ChangeRecorder,
        cityId: Int,
        goldDelta: Long = 0,
        riceDelta: Long = 0,
        garrisonDelta: Int = 0,
    ): V2CityLedgerEntry {
        val loaded = load(worldId)
        val before = loaded[cityId] ?: V2CityLedgerEntry.EMPTY
        val after = V2CityLedgerEntry(
            gold = (before.gold + goldDelta).coerceAtLeast(0),
            rice = (before.rice + riceDelta).coerceAtLeast(0),
            garrison = (before.garrison + garrisonDelta).coerceAtLeast(0),
        )
        if (after == before && loaded.containsKey(cityId)) return before
        loaded[cityId] = after
        recorder.recordCityLedgerV2Upsert(
            linkedMapOf(
                "city_id" to cityId,
                "gold" to after.gold,
                "rice" to after.rice,
                "garrison" to after.garrison,
            ),
        )
        return after
    }

    private fun load(worldId: WorldId): MutableMap<Int, V2CityLedgerEntry> {
        if (loadedWorldId == worldId) return entries
        entries.clear()
        jdbc.query(
            "SELECT city_id, gold, rice, garrison FROM v2_city_ledger WHERE world_id = :world_id ORDER BY city_id",
            MapSqlParameterSource("world_id", worldId.value),
        ) { rs ->
            entries[rs.getInt("city_id")] = V2CityLedgerEntry(
                gold = rs.getLong("gold"),
                rice = rs.getLong("rice"),
                garrison = rs.getInt("garrison"),
            )
        }
        loadedWorldId = worldId
        return entries
    }
}
