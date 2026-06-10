package opensamguk.engine.config

import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.tick.CheckStatisticCalculator

/**
 * checkStatistic 결과를 `statistic` INSERT 컬럼 맵으로 변환한다.
 *
 * `aux`는 이종(heterogeneous) 중첩 맵이라 kotlinx 직렬화가 런타임에 실패한다
 * ("Serializer for class 'Any' is not found" — 연경계(새 달 == 1월) tick을 죽여 턴이 영구 동결).
 * 반드시 [MetaJson] (PHP `Json::encode` byte-faithful: compact, UNESCAPED_UNICODE,
 * insertion-order) 으로 인코딩한다.
 */
object StatisticInsertColumns {

    fun from(row: CheckStatisticCalculator.StatisticRow): LinkedHashMap<String, Any?> = linkedMapOf(
        "year" to row.year,
        "month" to row.month,
        "nation_count" to row.nationCount,
        "nation_name" to row.nationName,
        "nation_hist" to row.nationHist,
        "gen_count" to row.genCount,
        "personal_hist" to row.personalHist,
        "special_hist" to row.specialHist,
        "power_hist" to row.powerHist,
        "crewtype" to row.crewtype,
        "etc" to row.etc,
        "aux" to MetaJson.encode(row.aux),
    )
}
