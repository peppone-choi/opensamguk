# OPENSAM-223 test resource shadowing review

Scope: `app/`의 gateway-api와 board-api 테스트 설정을 테스트 전용 프로필로 분리하고 shadow guard를 추가했으며, 검증 기록은 `docs/`에 남겼다.

Verdict: cleared
- Red/green evidence: 두 모듈의 shadow guard가 기존 `src/test/resources/application.yml`을 감지해 각각 1건 실패했고, 파일을 `application-test.yml`로 옮긴 뒤 각각 1건 통과했다.
- Full verification: 격리된 단일 Gradle 프로세스에서 `:app:board-api:test :app:gateway-api:test --rerun-tasks --no-parallel --no-daemon`이 1분 48초에 성공했다. board-api는 53 tests, 0 failures, 0 errors, 1 skipped이고 gateway-api는 201 tests, 0 failures, 0 errors, 8 skipped이다.
- Review: 독립 소스 리뷰에서 설정 파일의 내용 보존, 모든 Spring context 테스트의 `test` 프로필 활성화, V2 sandbox 프로필 조합, 동적 프로퍼티 우선순위를 확인했고 차단 사항이 없었다.
- Invalid evidence excluded: 같은 worktree에서 겹쳐 실행된 Gradle 프로세스의 클래스패스 충돌 결과는 검증 근거에서 제외했다.
- Residual risk: guard는 현재 모듈 작업 디렉터리를 기준으로 동작하며, 미래의 새 Spring context 테스트가 `@ActiveProfiles("test")`를 빠뜨리는 경우까지 직접 강제하지는 않는다.
