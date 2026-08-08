package opensamguk.engine.v2

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * OPENSAM-35 GATE-f2 F1 — 계획서 §4-2 규약 1(**v2 런타임 코드는 `opensamguk.*.v2.*` 패키지에 둔다**)의
 * 이름 휴리스틱 층. `V2ProductionContextBeanGateIT`의 누출 탐지는 빈의 **타입 이름에 `.v2.`가 들어
 * 있을 때만** 동작하므로, `opensamguk.engine.ledger.V2CityLedgerStore` 같은 선언은 게이트를 조용히
 * 빠져나간다. 이 테스트가 그 구멍의 이름-규약 절반을 소스 스캔으로 막는다.
 *
 * 실측(2026-08-08): `class|object|interface V2…` 선언은 main 소스 전체에서 6건이고 그중 5건은 이미
 * `opensamguk.*.v2.*`, 나머지 1건은 Flyway `V26__npc_lifecycle_phase_units`(`V2` 뒤가 숫자라 비대상).
 * 즉 오탐 0.
 *
 * **천장(정직하게):** 이건 이름 규약만 강제한다. `V2`로 시작하지 **않는** 이름의 v2 코드
 * (`SandboxCityLedger`, `LedgerV2Store` 등)는 여전히 못 잡는다 — 그건 리뷰가 잡아야 한다.
 * 또한 소스 텍스트 스캔이라 문자열 리터럴/주석 안의 같은 패턴은 구분하지 않는다(현재 0건).
 */
class V2NamingConventionGuardTest {

    /** `class V2X` / `object V2X` / `interface V2X` — `V2` 뒤가 대문자인 선언만. 숫자 접미(`V26__`)는 비대상. */
    private val declaration = Regex("""\b(class|object|interface)\s+(V2[A-Z]\w*)""")

    private val scannedRoots = listOf(
        "app/game-engine/src/main/kotlin",
        "app/game-api/src/main/kotlin",
        "app/gateway-api/src/main/kotlin",
        "infra/src/main/kotlin",
        "common/src/main/kotlin",
        "logic/src/main/kotlin",
    )

    /** 테스트 작업 디렉터리는 러너에 따라 모듈 루트 또는 프로젝트 루트다 — `settings.gradle.kts`까지 올라간다. */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        fail("repo root (settings.gradle.kts) not found from ${File("").absolutePath}")
    }

    @Test
    fun `V2-prefixed declarations live in an opensamguk v2 package`() {
        val root = repoRoot()
        val sourceDirs = scannedRoots.map { File(root, it) }.filter { it.isDirectory }
        if (sourceDirs.size != scannedRoots.size) {
            fail("expected all scanned source roots to exist under ${root.absolutePath}, got $sourceDirs")
        }

        val violations = sourceDirs
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .flatMap { file ->
                val text = file.readText()
                val pkg = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
                    .find(text)?.groupValues?.get(1).orEmpty()
                val segments = pkg.split('.')
                val inV2Package = segments.firstOrNull() == "opensamguk" && "v2" in segments
                if (inV2Package) {
                    emptyList()
                } else {
                    declaration.findAll(text).map { m ->
                        "${file.relativeTo(root).path}: ${m.groupValues[2]} (package '$pkg')"
                    }.toList()
                }
            }
            .sorted()

        assertEquals(
            emptyList(), violations,
            "V2-prefixed declarations must live in an opensamguk.*.v2.* package (계획서 §4-2 규약 1)",
        )
    }
}
