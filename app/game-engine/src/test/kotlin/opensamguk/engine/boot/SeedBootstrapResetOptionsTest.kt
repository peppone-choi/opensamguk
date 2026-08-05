package opensamguk.engine.boot

import opensamguk.infra.seed.Scenario
import opensamguk.infra.seed.ScenarioImporter
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 턴 주기 외의 나머지 리셋 옵션(fiction / extend / block_general_create / npcmode / show_img_level)이
 * PHP 기본값과 허용 집합을 그대로 지키는지 고정한다.
 *
 * 배경: 어드민 리셋 옵션은 조용히 기본값으로 폴백하면 운영자가 고른 값이 말없이 버려진다.
 * 그래서 허용 집합 밖은 부팅 실패여야 하고, 기본값은 `.github/workflows/reset-game-server.yml`의
 * `env_or_default`와 정확히 같아야 한다 — 한쪽만 고치면 워크플로 리셋과 어드민 UI 리셋이
 * 서로 다른 월드를 만든다.
 */
class SeedBootstrapResetOptionsTest {

    /** (설정 이름, 허용 집합, PHP 기본값) — 아래 테스트들이 공통으로 도는 표. */
    private val options: List<Triple<String, List<Int>, Int>> = listOf(
        Triple("RESET_FICTION", SeedBootstrap.FICTION_VALUES, SeedBootstrap.PHP_DEFAULT_FICTION),
        Triple("RESET_EXTEND", SeedBootstrap.EXTEND_VALUES, SeedBootstrap.PHP_DEFAULT_EXTEND),
        Triple(
            "RESET_BLOCK_GENERAL_CREATE",
            SeedBootstrap.BLOCK_GENERAL_CREATE_VALUES,
            SeedBootstrap.PHP_DEFAULT_BLOCK_GENERAL_CREATE,
        ),
        Triple("RESET_NPCMODE", SeedBootstrap.NPC_MODE_VALUES, SeedBootstrap.PHP_DEFAULT_NPC_MODE),
        Triple(
            "RESET_SHOW_IMG_LEVEL",
            SeedBootstrap.SHOW_IMG_LEVEL_VALUES,
            SeedBootstrap.PHP_DEFAULT_SHOW_IMG_LEVEL,
        ),
    )

    @Test
    fun `미설정이면 PHP 기본값이 나온다`() {
        for ((name, allowed, default) in options) {
            // env 파일에서 오는 값이라 null / 빈 문자열 / 공백만 있는 값이 전부 미설정이다.
            for (unset in listOf(null, "", "   ")) {
                assertEquals(
                    default,
                    SeedBootstrap.resolveOption(name, unset, allowed, default),
                    "$name 미설정('$unset')이 PHP 기본값으로 풀리지 않았다",
                )
            }
        }
    }

    @Test
    fun `유효값은 그대로 통과하고 앞뒤 공백은 관용된다`() {
        for ((name, allowed, default) in options) {
            for (value in allowed) {
                assertEquals(value, SeedBootstrap.resolveOption(name, "$value", allowed, default))
                // 운영 env 파일에는 앞뒤 공백이 흔하다 — 값 자체가 유효하면 통과해야 한다.
                assertEquals(value, SeedBootstrap.resolveOption(name, " $value ", allowed, default))
            }
        }
        assertEquals(2, SeedBootstrap.resolveOption("RESET_NPCMODE", " 2 ", SeedBootstrap.NPC_MODE_VALUES, 0))
    }

    @Test
    fun `허용 집합 밖은 조용한 폴백이 아니라 부팅 실패다`() {
        // "١"은 아라비아-인도 숫자 1이다. kotlin toIntOrNull은 이걸 1로 파싱하지만 워크플로의
        // 셸 case 문은 리터럴 ASCII만 매치한다 — 백엔드가 더 관대하면 두 리셋 경로가 갈라진다.
        val bad = listOf("-1", "3", "abc", "1.0", "١", " ١ ", "١٢٠", "+1", "01x")
        for ((name, allowed, default) in options) {
            for (value in bad) {
                val trimmed = value.trim()
                // "3"은 fiction에선 범위 밖이지만 show_img_level에선 유효값이다 — 그 옵션에선 건너뛴다.
                if (trimmed.all { it in '0'..'9' } && trimmed.toInt() in allowed) continue
                val e = assertFailsWith<IllegalArgumentException>("$name 이 허용값 아닌 '$value'를 통과시켰다") {
                    SeedBootstrap.resolveOption(name, value, allowed, default)
                }
                assertTrue(
                    e.message!!.contains(name),
                    "실패 사유가 어떤 설정 때문인지 말해야 한다: ${e.message}",
                )
            }
        }
    }

    /**
     * 기본값이 리셋 워크플로와 **정확히** 같은지 워크플로 파일에서 직접 읽어 대조한다.
     * 문서 주석이 아니라 실행되는 검사로 둔다.
     */
    @Test
    fun `기본값이 reset-game-server 워크플로와 일치한다`() {
        val workflow = generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
            .map { java.io.File(it, ".github/workflows/reset-game-server.yml") }
            .firstOrNull { it.isFile }
        checkNotNull(workflow) { "reset-game-server.yml을 찾지 못했다 — 테스트 작업 디렉터리를 확인할 것" }

        // 형태: RESET_FICTION="$(env_or_default RESET_FICTION 1)"  (마지막 토큰이 기본값)
        val line = Regex("""^(RESET_[A-Z_]+)="\$\(env_or_default \1 (\S+)\)"$""")
        val fromWorkflow = workflow.readLines()
            .mapNotNull { line.matchEntire(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }

        for ((name, _, default) in options) {
            val raw = fromWorkflow[name]
            checkNotNull(raw) {
                "워크플로에서 $name 의 env_or_default 라인을 찾지 못했다 — 형태가 바뀌었는지 확인할 것 " +
                    "(찾은 키: ${fromWorkflow.keys})"
            }
            assertEquals(
                default,
                raw.toInt(),
                "백엔드 기본값과 워크플로 기본값이 갈라졌다($name). 한쪽만 고치면 워크플로 리셋과 " +
                    "어드민 UI 리셋이 서로 다른 월드를 만든다.",
            )
        }
    }

    /**
     * 시드 자체의 기본값도 같은 PHP 오라클을 따라야 한다. ScenarioImporter가 옛 0을 그대로 들고
     * 있으면 옵션 미지정 리셋이 PHP와 다른 월드를 만든다 (`hwe/func.php:1820`에서 실제로 쓰인다).
     */
    @Test
    fun `ScenarioImporter fiction 기본값이 PHP 기본값과 같다`() {
        val ctor = checkNotNull(ScenarioImporter::class.primaryConstructor)
        val fictionParam = checkNotNull(ctor.parameters.firstOrNull { it.name == "fiction" }) {
            "ScenarioImporter에 fiction 파라미터가 없다 — 이름이 바뀌었는지 확인할 것"
        }
        assertTrue(fictionParam.isOptional, "fiction은 기본값을 가진 파라미터여야 한다")

        val emptyScenario = Scenario(
            title = "t",
            startYear = 180,
            nations = emptyList(),
            generals = emptyList(),
            diplomacy = emptyList(),
        )
        val required = ctor.parameters.filterNot { it.isOptional }
            .associateWith { p ->
                when (p.name) {
                    "scenario" -> emptyScenario
                    "cities" -> emptyList<Nothing>()
                    else -> error("ScenarioImporter에 예상치 못한 필수 파라미터: ${p.name}")
                }
            }
        val importer = ctor.callBy(required)

        val field = ScenarioImporter::class.java.getDeclaredField("fiction").apply { isAccessible = true }
        assertEquals(
            SeedBootstrap.PHP_DEFAULT_FICTION,
            field.getInt(importer),
            "ScenarioImporter의 fiction 기본값이 PHP install.php:98 기본값과 다르다",
        )
    }
}
