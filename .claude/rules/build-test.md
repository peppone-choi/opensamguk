---
paths:
  - "{common,logic,infra,app}/**"
  - "**/*.gradle.kts"
  - "gradle/**"
  - "web/**"
  - "tools/parity/**"
  - "tools/smoke.sh"
---

# 빌드·테스트와 결과 판정

[작업 참고](../../docs/development/agent-reference.md)에서 옮긴 경로별 규칙이다. 경로와 명령은 저장소 루트 기준이다. Codex는 자동으로 읽지 않으므로 작업 참고 표를 보고 직접 읽는다. 이 규칙은 명령 실행 승인을 대신하지 않는다.

## 빌드 · 테스트 명령

**전제**: JDK 21 LTS(Gradle 8.12는 Java 25+ 파싱 실패), Docker, Node 20 + pnpm(corepack). **Gradle 명령은 repo root에서, `JAVA_HOME`을 21로 고정.**

```bash
# 전체 빌드 + 테스트
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build

# 동결 회귀 백엔드 표준 게이트(XML 검증 포함, 명령명은 역사적으로 parity 유지)
tools/parity/gate.sh backend

# 단일 모듈
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test

# 광범위한 백엔드 변경에 필요한 풀 체크
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test :app:board-api:test

# Docker 스모크 (이미지 빌드 + 전체 스택 + health 단언)
./tools/smoke.sh

# 프론트 dev
cd web/gateway && corepack pnpm dev   # :3000
cd web/game    && corepack pnpm dev   # :3001
```

### Gradle 결과 판정

context-mode 래퍼를 사용하는 호스트에서는 `task-notification` exit 0만으로 성공을 판단하지 않는다. 실제 빌드 출력과 테스트 결과를 확인한다:

- 출력 tail로 검증: `... 2>&1 | tail -40` 후 `BUILD SUCCESSFUL` + 테스트 카운트 grep
- 또는 테스트 결과 XML: `**/build/test-results/test/*.xml`
- UP-TO-DATE false-green 의심 시 `--rerun-tasks`

### Testcontainers (macOS)

`infra`·`app:game-api`·`app:game-engine`의 `tasks.test`에 배선: `api.version=1.44`, `DOCKER_CONTEXT=default`, `TESTCONTAINERS_RYUK_DISABLED=true`. **Docker 미사용 시 통합 테스트는 fail이 아니라 skip.**
