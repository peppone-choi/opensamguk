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
 * **S5 카탈로그 등재 완료 (OPENSAM-189).** 이 파일은 `HotColdCatalog.runtimeDirectSqlBoundaries`에,
 * `engine/v2` 디렉터리는 `runtimeSourceDirectories`에 등재돼 있다. 따라서 아래 `load()`의 `jdbc.query`는
 * `HotColdWorldCatalogGuardTest`의 `assertEquals`에 묶여 있고, 등재를 지우거나 이 패키지에 새 JDBC
 * 수신자를 들이면 그 가드가 빨개진다. SQL **본문**(world-scoped·`SELECT *` 금지·결정적 정렬·직접 쓰기
 * 금지)은 카탈로그가 보지 않으므로 [V2CityLedgerReadBoundGuardTest]가 별도로 고정한다.
 *
 * **이름이 `Store`인 이유**: R1 원본 KDoc은 `*Repository`/`*Reader` 접미사를 "소비자에서 가드를 깨므로"
 * 피했다고 적었다 — 즉 수신자-이름 탐지의 회피였다. OPENSAM-189이 정식 등재로 그 회피의 필요를 없앴고,
 * 이제 이름은 실체를 그대로 가리킨다: 이 클래스는 v1의 `InMemoryTurnWorld`와 같은 **메모리 보유자**이지
 * 조회 리포지터리가 아니다(읽기는 lazy 1회, 이후 모든 접근은 메모리). 대신 R2가 이 store를
 * `DaemonLoopConfig`에 주입할 때 수신자-이름 탐지는 이름 기반이라 자동으로 걸리지 않는다는 점을 기억할 것 —
 * 그 호출들은 `HotColdCatalog.runtimeReadSeams`에 **손으로** 등재해야 한다.
 *
 * **U10(부팅 순서) 대응 — lazy 초기화.** 첫 접근 시점에 한 번만 적재하므로 Flyway/시드/스냅샷 로더와의
 * 부팅 순서에 의존하지 않는다(`@DependsOn`·`ApplicationRunner` 순서 선언 불필요). 데몬 프로세스는 한
 * 월드를 담당하므로 캐시는 월드 하나 분량이며, 다른 `worldId`로 접근하면 다시 적재한다.
 *
 * **아직 `@Bean`이 아니다 — 그러나 더 이상 막혀 있지 않다 (OPENSAM-184).** R1 시점에는
 * `V2BothConditionsBeanGateIT`가 **모든** `opensamguk.*.v2.*` 빈 이름 집합을 인라인 리터럴과
 * `assertEquals`해서 신규 v2 빈이 무조건 실패했다. 그 단언은 이제 명시 allowlist
 * (`V2ProductionContextBeanGateIT.kt`의 `APPROVED_V2_BEAN_NAMES`) 부분집합 검사다. R2가 이 store를
 * [V2SandboxConfiguration]의 `@Bean`으로 올릴 때 그 allowlist에 빈 이름 한 줄을 **의도적으로** 추가하면
 * 된다(그 편집 = 리뷰 지점). 프로덕션 컨텍스트에서 v2 빈 0을 요구하는 같은 파일의 `assertNoV2Beans()`는
 * allowlist와 무관하게 그대로이므로 0A-b 게이트 밖 등록은 여전히 불가능하다. 그때까지 이 클래스는
 * 직접 생성해 쓴다(IT가 그 경로를 증명).
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
