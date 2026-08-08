# `content/v2` — v2 전용 read-only 콘텐츠 카탈로그

OPENSAM-35 (0A-d) 산출물. 로더는 `infra/src/main/kotlin/opensamguk/infra/v2/V2ContentCatalog.kt`.
근거 문서: `docs/loops/opensam-35-v2-0a-2026-08-08/s3a-content-v2-loader.md`.

## 1. 왜 리포 루트 `content/`가 아니라 `infra/src/main/resources/content/v2/`인가

S1이 `db/migration_v2`에서 실측한 것과 같은 이유다 — **표준 Gradle 리소스 처리로 jar에 그대로
구워지므로 볼륨 마운트도, 이미지 베이크도, Dockerfile 수정도 필요 없다.** 리포 루트에 두면
컨테이너 내부 경로 실재성을 따로 보장해야 한다(= `filesystem:` 경로의 문제, S1 U3에서 기각된 쪽).
`classpath*:content/v2/*.json`은 배포 형태와 무관하게 동일하게 동작한다.

여기서 "루트"는 **클래스패스 루트**다. 배포된 산출물에서 이 디렉터리의 경로는 정확히
`content/v2/`이며, 계획 §0.4가 말한 `content/v2/` 규약과 일치한다.

## 2. 왜 `scenario/`나 `db/migration/`의 하위가 아닌가

S1의 교훈(재귀 스캔)이 여기서도 유효하다. v1 로더 두 개가 각각 자기 트리를 훑는다 —
`ScenarioCatalogService`는 `classpath*:scenario/scenario_*.json`, Flyway는 location을 **재귀**
스캔한다. v2 콘텐츠를 그 하위에 두면 v1이 집어삼킨다.

⇒ **형제 경로여야 한다.** 이 디렉터리를 `scenario/`나 `db/migration/` 밑으로 옮기지 마라.

단, `V2ContentCatalog` 자신은 재귀하지 않는다(패턴에 `**`가 없다). 하위 디렉터리에 넣은
파일은 **조용히 무시된다** — 콘텐츠는 이 디렉터리 직속에 둔다.

## 3. 규약

- 확장자 `*.json`만 읽는다(이 README는 카탈로그에 잡히지 않는다).
- **읽기 전용.** 로더에 쓰기 메서드가 없고, 부팅 시 어떤 시드도 돌지 않는다.
- 로더 빈은 게이트 안에서만 산다 — `V2_ENABLED=true` **AND** 프로파일 `v2-sandbox`
  (`app/game-engine/.../v2/V2SandboxConfiguration.kt`). 게이트 밖에 v2 빈을 만들면 0A-b 위반이다.

## 4. 현재 상태

**빈 디렉터리다(README만).** 실제 v2 콘텐츠 파일은 이 티켓의 범위가 아니다 — 내용을 지어내면
날조다. 파일 0개일 때 `names()`가 빈 목록을 반환하는 것이 현재의 정상 동작이며 테스트로 고정돼 있다.
