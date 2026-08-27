# OpenSamguk

OpenSamguk은 삼국지 모의전투에서 출발해 독립적인 세계·작전·통치·전투 시스템으로 발전하는
웹 전략 게임입니다. Kotlin/Spring 기반의 결정론적 게임 엔진과 Next.js 클라이언트로
구성되며, 한 명의 장수에서 시작해 휘하 인물과 부곡, 국가, 작전, 전쟁을 운영합니다.

기존 PHP `devsam/core` 이식 결과는 호환성과 회귀 검증에 활용합니다. 새로운 기능은 OpenSamguk의
게임 방향에 맞춰 독립적으로 설계합니다.

## 지금 만드는 것

OpenSamguk이 지향하는 경험은 **비동기 작전실과 살아 있는 편년체**입니다.

```text
지난 사건 확인 → 개인·국가 명령 계획 → 이동·보급·건설·작전
→ WEGO 전투 → 점령·정치·관계 변화 → replay와 다음 판단
```

핵심 방향은 다음과 같습니다.

- `raster cell → tactical province → county/direct territory → commandery/kingdom` 세계 모델
- 한 번 구운 lossless RGB24 프로빈스 맵과 연도별 행정 계층
- 아이소메트릭 전략 지도와 빠른 평면 선택 지도
- 육로·방향성 하천·연안·검토된 외해·도하를 포함한 이동과 보급
- 개인턴, 사령턴, 다턴 계획, WEGO 전투 명령의 분리
- 막료·부장·문객 같은 휘하 인물과 장수 개인 사병인 부곡의 분리
- 국가회의, 정체성, 조정, 관직, 도독부, 봉토와 봉신
- 야전·공성·해전의 결정론적 WEGO 명령과 replay
- 새로운 시스템과 함께 제공되는 튜토리얼·도움말

전체 단계와 출시 관문은 [제품 로드맵](docs/design/roadmap.md)을 봅니다. 로드맵은 날짜 대신 검증 가능한
단계로 관리합니다.

## 공개 알파 원칙

공개 알파는 누구나 가입할 수 있는 공개 서버입니다. 안정적인 첫 경험을 제공할 수 있을 때 개장합니다.

- 게임에서 제공하는 모든 명령과 주요 시스템이 실제 플레이 흐름에 연결됨
- 사람이든 AI든 한 캠페인을 시작부터 통일·멸망까지 진행 가능
- 진행 중 이동·건설·작전·전투가 저장과 재시작을 견딤
- 가입부터 첫 명령·건설·작전·전투까지 실제 UI 튜토리얼로 완주 가능
- 공개 환경에서 백업·복원·롤백과 최종 월드 초기화 검증

알파 중 치명적인 데이터 또는 밸런스 문제가 있으면 게임 월드는 초기화할 수 있습니다. 계정·닉네임·인증
정보는 월드 초기화와 무관하게 보존합니다. 공개 베타부터는 예고 없는 월드 초기화를 원칙적으로 금지합니다.

## 프로젝트 상태

| 영역 | 상태 | 설명 |
|---|---|---|
| 기존 게임 기반 | 유지·개선 | 계정, 로비, 턴 진행, 명령, 국가 운영과 기록 시스템 |
| 명령 체계 | 재설계 중 | 기존 명령과 새로운 이동·정치·전투 명령을 하나의 흐름으로 통합 |
| Han 프로빈스 | 진행 중 | 정적 ID 맵과 공용 아이소메트릭 렌더러 기반을 확장 중 |
| 이동·작전 | 기획·구현 진행 | 프로빈스 topology, 보급, 호송, 출병, 요격과 다턴 계획 |
| 휘하·부곡·통치 | 공개 알파 필수 | 인물과 사병을 분리하고 회의·조정·관직·봉신까지 포함 |
| WEGO 전투 | 공개 알파 필수 | 실시간 명령 경쟁 대신 동시 계획·해결·재생 |
| 튜토리얼·도움말 | 함께 개발 | 새로운 기능을 처음 사용하는 흐름과 설명을 동시에 제작 |

세부 진행 상황과 검증 결과는 제품 로드맵과 공개 이슈를 따릅니다.

## 아키텍처

```text
Browser
  ├─ web/gateway (:3000) ── app/gateway-api (:8080)
  └─ web/game    (:3001) ── app/game-api    (:8081)
                                  │ durable intake / Redis wake
                                  ▼
                         app/game-engine (:8082)
                         InMemoryTurnWorld
                                  │ ChangeRecorder
                                  ▼
                         JdbcFlushExecutor
                                  │
                                  ▼
                              PostgreSQL
```

게임 엔진의 권위 상태는 `InMemoryTurnWorld`에 있습니다. 변경은 `ChangeRecorder`가
`created`·`dirty`·`deleted` 델타로 수집하고 `JdbcFlushExecutor`가 JDBC batch로 저장합니다.
game-engine에서 JPA write를 사용하는 것은 금지됩니다.

같은 snapshot, 입력 순서, seed는 같은 결과와 replay hash를 만들어야 합니다.

## 모듈

| 경로 | 책임 |
|---|---|
| `common` | 결정론적 RNG, 수치·로그 공통 계약 |
| `logic` | 순수 게임 규칙, 커맨드, AI, 전투, 이벤트 |
| `infra` | JDBC flush, Flyway, Redis, read repository, 시나리오 적재 |
| `app/gateway-api` | 계정, 인증, 프로필, 운영자 기능 |
| `app/board-api` | 게시판 |
| `app/game-api` | read, precheck, durable command intake, SSE |
| `app/game-engine` | 턴 daemon과 권위 월드 |
| `web/gateway` | 로그인, 가입, 로비, 관리자 UI |
| `web/game` | 게임 UI, 지도, 명령, replay, 도움말과 튜토리얼 |
| `data/map` | 커밋 가능한 지도 정본과 생성 산출물 |
| `tools/map` | 결정론적 지도 생성·검증 도구 |

아이콘 원본·생성기·큐레이션·미리보기의 정본은 별도
[opensamguk-images](https://github.com/peppone-choi/opensamguk-images) 저장소입니다. 이 저장소에는
웹 배포용 deterministic export만 둡니다.

## 빠른 시작

필요한 도구:

- JDK 21
- Docker와 Docker Compose
- Node.js 20 이상과 Corepack/pnpm

```bash
git clone git@github.com:peppone-choi/opensamguk.git
cd opensamguk
cp .env.example .env
docker compose up -d --build
```

`.env`에는 최소한 JWT 키와 월드 ID, 관리자 계정 값을 직접 설정해야 합니다. 실제 비밀값을 저장소에
커밋하지 마세요.

- Gateway: `http://localhost:3000`
- Game: `http://localhost:3001/game`
- nginx 통합 진입점: `http://localhost/`

## 개발과 검증

```bash
# 백엔드 전체
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build

# 동결 회귀 포함 표준 백엔드 게이트
tools/parity/gate.sh backend

# 프론트엔드
cd web/gateway && corepack pnpm test && corepack pnpm typecheck
cd web/game && corepack pnpm test && corepack pnpm typecheck

# 로컬 통합 스모크
./tools/smoke.sh
```

호스트의 Gradle wrapper가 종료 코드를 왜곡할 수 있으므로 `BUILD SUCCESSFUL`과 테스트 XML의
failure/error 수를 함께 확인합니다. Docker가 없어 Testcontainers 통합 테스트가 skip되면 전체 통합 검증을
통과했다고 주장하지 않습니다.

## 문서

- [문서 포털](docs/README.md)
- [제품 로드맵](docs/design/roadmap.md)
- [기존 코어와 현재 설계의 경계](docs/design/architecture-boundary.md)
- [사용자 매뉴얼](docs/user/README.md)
- [관리자 매뉴얼](docs/admin/README.md)
- [기여 안내](docs/CONTRIBUTING.md)
- [개발자·에이전트 안내](AGENTS.md)

## 보안과 라이선스

- `.env`, 키, 토큰, 운영 DB와 비공개 원본 데이터는 커밋하지 않습니다.
- `legacy/`는 참고 전용이며 커밋하지 않습니다.
- 런타임은 LLM API에 의존하지 않습니다.
- 원작 및 외부 데이터·자산의 라이선스와 출처는 각 manifest와 관련 문서를 따릅니다.

OpenSamguk은 HideD님의 MIT 라이선스 프로젝트 `devsam/core`에서 출발했습니다. 역사적 기반을 공개한
원작자와 삼모 커뮤니티에 감사드립니다.
