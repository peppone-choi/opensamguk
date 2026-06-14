package opensamguk.gameapi.read

import opensamguk.infra.entity.NationEnvEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * V3 `nation_env`(int-namespace KV, namespace = nation id) READ 전용 뷰.
 *
 * 데몬 flush가 유일 writer(`JdbcFlushExecutor.nationEnvKvWrite`); game-api는 절대 write 안 한다.
 * W1-O 백엔드 데이터 채널: NF-P1-B(nationMsg=nationNotice.msg / scoutMsg=scout_msg) +
 * NF-P0-C(warSettingCnt.remain=available_war_setting_cnt)의 read 경로. 종전 `GameKvReadRepository`는
 * string-namespace V7 `game_kv` 테이블만 보므로 nation_env KV를 읽지 못한다(테이블 불일치).
 */
interface NationEnvReadRepository : JpaRepository<NationEnvEntity, Int> {

    /** (namespace = nationId, key) 단건 — 부재 시 null. */
    fun findByNamespaceAndKey(namespace: Int, key: String): NationEnvEntity?
}
