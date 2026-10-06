# OpenSamguk

OpenSamguk(오픈삼국)은 후한 말을 무대로 한 비동기 웹 전략 게임입니다. 장수 한 명으로 시작해 주공을 섬기거나
스스로 주공이 되어 휘하(화면 이름 「부」), 세력, 작전, 전쟁을 운영합니다. Kotlin/Spring 기반의 결정론적 게임 엔진과
Next.js 클라이언트로 이루어져 있고, 소스는 [MIT](LICENSE)로 공개합니다.

- 지금(2026-10): 공개 서버 개장을 준비하고 있습니다. 휘하(부)가 유일한 제품 규칙이고(ADR-LITE-065),
  옛 삼모 명령 체계는 동결 회귀 기준선으로만 남아 있습니다.
- 출발점: HideD 님의 MIT 프로젝트 `devsam/core`(삼국지 모의전투)입니다. 이식 자료는 개발 이력으로 보관하고,
  현행 규칙과 검증은 OpenSamguk 의 독립적인 제품 계약을 기준으로 합니다.

## 지금 만드는 것

OpenSamguk이 지향하는 경험은 **비동기 작전실과 살아 있는 편년체**입니다.

```text
지난 순 확인 → 장수 행동·배치·방침·공사·계책 예약
→ 자기 턴 시각에 행군·조우·공성, 순 경계에서 보급·내정 정산 → 점령·정치·관계 변화 → replay와 다음 판단
```

핵심 방향은 다음과 같습니다.

- `raster cell → tactical province → county/direct territory → commandery/kingdom` 세계 모델
- 한 번 구운 lossless RGB24 프로빈스 맵과 연도별 행정 계층
- 휘하 2D 전략 지도(메인 지도 통일 작업 진행 중)
- 육로·방향성 하천·연안·검토된 외해·도하를 포함한 이동과 보급
- 장수마다 자기 턴 시각에 행동하고 세계는 순 경계에서 정산하는 입력 체계(장수 행동·배치·방침·공사·계책·조정 결정)
- 인물·부대·계책·보물 카드로 이루어진 휘하와 혈연·향당·은의·결의 같은 결속
- 지형과 거리에서 나오는 이동·보급, 전·곡·철·목재·말 다섯 가지 실물 자원
- 발령·관직·봉신 계약·국가회의(정치), 조정·황실·세력 정체성·제도(깊이)
- 야전·공성·해전의 결정론적 전투: 공격 측 계획과 방어 측 대응을 함께 공개해 해결하고 replay로 재생
- 새로운 시스템과 함께 제공되는 튜토리얼·도움말

전체 단계와 출시 관문은 [제품 로드맵](docs/design/roadmap.md)을 봅니다. 로드맵은 날짜 대신 검증 가능한
단계로 관리합니다.

## 공개 알파 원칙

공개 알파는 누구나 가입할 수 있는 공개 서버입니다. 안정적인 첫 경험을 제공할 수 있을 때 개장합니다.

- 사람이든 AI든 한 캠페인을 시작부터 통일·멸망까지 진행 가능
- 진행 중 이동·건설·작전·전투가 저장과 재시작을 견딤
- 기반·정치·깊이 세 층의 모든 입력이 실제 플레이 흐름에 연결됨
- 가입부터 첫 출사·발령·공사·등용·행군·전투까지 실제 UI 튜토리얼로 완주 가능
- 공개 환경에서 백업·복원·롤백과 최종 월드 초기화 검증

알파 중 치명적인 데이터 또는 밸런스 문제가 있으면 게임 월드는 초기화할 수 있습니다. 계정·닉네임·인증
정보는 월드 초기화와 무관하게 보존합니다. 공개 베타부터는 예고 없는 월드 초기화를 원칙적으로 금지합니다.

## 프로젝트 상태

단계별 진행과 출시 관문은 [제품 로드맵](docs/design/roadmap.md)과 GitHub 이슈 · PR 에서 봅니다. 이 README 에는
빠르게 낡는 진행 수치를 적지 않습니다.

## 기술 구성

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

## 로컬 실행

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

## 배포

운영 배포 구성(compose · nginx · 배포 사이드카)은 별도 저장소
[opensamguk-docker](https://github.com/peppone-choi/opensamguk-docker)에 있습니다.

- 이 저장소의 CI 가 서비스 이미지를 빌드해 GHCR(`ghcr.io/peppone-choi/opensamguk`)에 게시합니다.
- 공유 스택(게이트웨이 · 게시판 · nginx)과 게임 서버 스택(서버마다 game-api · game-engine · web-game)은 따로 올립니다.
  게임 서버는 서버별 이미지 핀으로 고정되며 승격 절차를 거쳐서만 바뀝니다.
- 설치 · 운영 절차는 opensamguk-docker 의 README 와 [관리자 매뉴얼](docs/admin/README.md)을 따릅니다.

## 문서

- [문서 포털](docs/README.md)
- [제품 로드맵](docs/design/roadmap.md)
- [기존 코어와 현재 설계의 경계](docs/design/architecture-boundary.md)
- [사용자 매뉴얼](docs/user/README.md)
- [관리자 매뉴얼](docs/admin/README.md)
- [기여 안내](CONTRIBUTING.md) · [문서 쓰는 법](docs/CONTRIBUTING.md)
- [보안 정책](SECURITY.md)
- [개발자·에이전트 안내](AGENTS.md)

## 보안

- 취약점은 공개 이슈 대신 [SECURITY.md](SECURITY.md)의 메일로 알려 주세요.
- `.env`, 키, 토큰, 운영 DB와 비공개 원본 데이터는 커밋하지 않습니다.
- `legacy/`는 참고 전용이며 커밋하지 않습니다.
- 런타임은 LLM API에 의존하지 않습니다.

## 라이선스와 자산 고지

- 소스 코드 · 문서 · 프로젝트가 직접 만든 자산: [MIT](LICENSE)
- 글꼴: SIL Open Font License 1.1([`web/licenses/`](web/licenses/README.md))
- **MIT 범위 밖**: 「제갈공명 와룡전」 파생 지도 · 전장 자료, CHGIS 파생 역사 지리 자료, 「삼국지 14」 기준 시나리오 자료,
  git 이력에만 남은 옛 devsam 그림 파생물. 이 자료에 대해 이 저장소는 재배포 권리를 부여하지 않으며 원저작권을 주장하지 않습니다.
- 받은 MIT 코드의 원 고지와 범위 밖 자료의 경로 · 출처는 [NOTICE.md](NOTICE.md)에 있습니다.

OpenSamguk은 HideD님의 MIT 라이선스 프로젝트 `devsam/core`에서 출발했습니다. 역사적 기반을 공개한
원작자와 삼모 커뮤니티에 감사드립니다.
