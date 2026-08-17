package opensamguk.gameapi.v2

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.v2.V2SandboxGate
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * OPENSAM-155 (v2 R6) — v2 도시 원장(`v2_city_ledger`) 열람. **read-only, JPA 미사용.**
 *
 * R1~R5는 전부 백엔드였고 유저는 자기 도시의 금·병량·도시병사를 어디서도 볼 수 없었다. 보이지 않는
 * 원장 위에서는 "어느 도시에 무엇을 둘까"라는 결정이 성립하지 않는다(설계안 §8) — 이 컨트롤러가 그
 * 공백을 닫는다.
 *
 * **JPA를 쓰지 않는 이유는 취향이 아니다.** `GameApiApplication.kt:9-10`의 `@EntityScan`/
 * `@EnableJpaRepositories` 화이트리스트가 진짜 등록점이고 `application.yml`의 `ddl-auto: validate`가
 * 걸려 있어, 화이트리스트를 넓히는 순간 **v1 부팅이 깨진다**. 그래서 v2 read는 [NamedParameterJdbcTemplate]
 * 직접 조회다(선례: game-api의 기존 JDBC read 4파일).
 *
 * 게이트: `@Profile` AND `@ConditionalOnProperty` — 둘 중 하나만으로는 절대 열리지 않으며, 닫혀 있으면
 * 빈 자체가 없어 엔드포인트가 404다(0A-b). 월드 스코프는 [GameApiProcessWorld]에서 오고 SQL은 항상
 * `world_id`로 좁힌다(월드 간 누수 금지).
 *
 * 쓰기는 이 경로에 없다 — v2 원장의 유일한 쓰기 경로는 데몬의 `ChangeRecorder` → `JdbcFlushExecutor`
 * 배치다(one-daemon-write rule).
 */
@RestController
@Profile(V2SandboxGate.PROFILE)
@ConditionalOnProperty(name = [V2SandboxGate.PROPERTY], havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/v2/city-ledger")
class V2CityLedgerReadController(
    private val jdbc: NamedParameterJdbcTemplate,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId

    /** 도시 한 곳의 원장. 행이 없으면 0/0/0 — 엔진 `V2CityLedgerEntry.EMPTY`와 같은 시멘틱. */
    data class CityLedgerView(val cityId: Int, val gold: Long, val rice: Long, val garrison: Int)

    data class CityLedgerListResponse(val entries: List<CityLedgerView>)

    /** `GET /api/v2/city-ledger` — 월드 전체, **city_id 오름차순**(결정적 정렬). */
    @GetMapping
    fun list(): CityLedgerListResponse = CityLedgerListResponse(
        jdbc.query(
            "SELECT city_id, gold, rice, garrison FROM v2_city_ledger WHERE world_id = :world_id ORDER BY city_id",
            MapSqlParameterSource("world_id", worldId.value),
        ) { rs, _ ->
            CityLedgerView(
                cityId = rs.getInt("city_id"),
                gold = rs.getLong("gold"),
                rice = rs.getLong("rice"),
                garrison = rs.getInt("garrison"),
            )
        },
    )

    /**
     * `GET /api/v2/city-ledger/{cityId}` — 한 도시.
     *
     * 원장에 행이 없는 도시는 **404가 아니라 0/0/0**이다: R1 이후 아직 아무 델타도 받지 못한 도시는
     * "존재하지 않는 도시"가 아니라 "원장이 비어 있는 도시"이고, 엔진 쪽 `entry()`도 같은 값을 준다.
     * 여기서 404를 내면 화면이 실재하는 도시를 없는 것처럼 보여주게 된다.
     */
    @GetMapping("/{cityId}")
    fun one(@PathVariable cityId: Int): CityLedgerView =
        jdbc.query(
            "SELECT city_id, gold, rice, garrison FROM v2_city_ledger WHERE world_id = :world_id AND city_id = :city_id",
            MapSqlParameterSource("world_id", worldId.value).addValue("city_id", cityId),
        ) { rs, _ ->
            CityLedgerView(
                cityId = rs.getInt("city_id"),
                gold = rs.getLong("gold"),
                rice = rs.getLong("rice"),
                garrison = rs.getInt("garrison"),
            )
        }.firstOrNull() ?: CityLedgerView(cityId, 0, 0, 0)
}
