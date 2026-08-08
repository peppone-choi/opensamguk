package opensamguk.infra.v2

/**
 * OPENSAM-35 0A-b — v2 빈 등록 게이트의 **이름 정본**.
 *
 * v2 빈은 `V2_ENABLED=true`(프로퍼티 [PROPERTY])와 프로파일 [PROFILE]이 **둘 다** 참일 때만 등록된다.
 * 한쪽만으로는 절대 켜지지 않는다. 조건은 각 앱의 컴포넌트 스캔 루트 안에 있는
 * `opensamguk.engine.v2.V2SandboxConfiguration` / `opensamguk.gameapi.v2.V2SandboxConfiguration`이 건다.
 *
 * 두 앱이 같은 상수를 쓰도록 여기 한 곳에만 정의한다 — 철자가 갈리면 게이트가 조용히 열린다.
 */
object V2SandboxGate {
    /**
     * Spring 프로퍼티 키. 컨테이너 환경변수 `V2_ENABLED`가 Spring relaxed binding으로 이 키에 매핑된다
     * (`SystemEnvironmentPropertySource`: `_` → `.`, 소문자화).
     *
     * `havingValue = "true"` + `matchIfMissing = false`(기본값)이므로 **미설정 = 비활성**이다.
     */
    const val PROPERTY: String = "v2.enabled"

    /** `SPRING_PROFILES_ACTIVE=v2-sandbox`로 활성화되는 프로파일. */
    const val PROFILE: String = "v2-sandbox"
}

/**
 * 게이트가 열렸을 때만 존재하는 마커 빈.
 *
 * 게임 로직·DB 접근·스케줄러를 **넣지 않는다.** 존재 자체가 게이트의 상태이고,
 * 0A-f(S4)의 실측 테스트가 v1 컨텍스트에서 이 타입의 빈 수 0을 assert하는 대상이다.
 *
 * 실제 v2 빈(v2 원장 store, v2 커맨드 핸들러, v2 read/intake 컨트롤러 …)은 나중에
 * 각 앱의 `V2SandboxConfiguration` **안으로** 들어온다 — 게이트 밖에 새 v2 빈을 만들지 마라.
 */
class V2SandboxMarker
