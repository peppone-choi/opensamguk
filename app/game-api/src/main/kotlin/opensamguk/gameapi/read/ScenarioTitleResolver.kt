package opensamguk.gameapi.read

import opensamguk.infra.seed.ScenarioJson
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 시나리오 코드 → 표시 제목 해석. 로비/진입이 보여줄 이름은 코드(`scenario_1010`)가 아니라 시나리오
 * JSON 의 `title`(`【역사모드1】 황건적의 난`)이다 — legacy `ResetHelper`→`$scenarioObj->getTitle()`
 * 와 동일 출처(커밋된 scenario 리소스). `world_state.config["title"]` 가 시드돼 있으면 컨트롤러가 그걸
 * 쓰고, 미시드(라이브 s1)면 본 리졸버가 리소스에서 read-time 으로 채운다(재시드 불요).
 *
 * 코드별 1회만 파싱해 캐시(제목은 정적). 리소스 없음/파싱 실패면 null → 컨트롤러가 코드로 폴백.
 */
@Component
class ScenarioTitleResolver {
    private val cache = ConcurrentHashMap<String, String>()

    fun titleOf(scenarioCode: String?): String? {
        if (scenarioCode.isNullOrBlank()) return null
        val cached = cache.getOrPut(scenarioCode) {
            runCatching {
                javaClass.classLoader.getResourceAsStream("scenario/$scenarioCode.json")
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?.let { ScenarioJson.loadScenario(it).title }
                    ?: ""
            }.getOrDefault("")
        }
        return cached.takeIf { it.isNotBlank() }
    }
}
