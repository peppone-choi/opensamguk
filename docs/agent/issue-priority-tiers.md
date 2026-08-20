# 이슈 우선순위 티어 (2026-08-20)

열린 GitHub 이슈 130건을 **지금 코드 상태에 근거해** 티어링한 결과다.
Jira 의 `priority` 필드가 아니라 **코드를 읽어 확인한 사실**로 매겼다.

라벨로 조회한다:

```bash
gh issue list --label tier-0        # 지금 착수
gh issue list --label verified-fixed # 클로즈 후보
gh issue list --label stale-premise  # 본문 재작성 필요
```

## 티어의 뜻

| 라벨 | 뜻 |
| --- | --- |
| `tier-0` | 라이브가 틀리게 동작하거나, 다른 트랙 전체를 막는 것 |
| `tier-1` | **선행 결정** — 이게 안 정해지면 아래 작업이 헛일이 된다 |
| `tier-2` | 운영 안정 — 배포·가용성이 서로를 죽인다 |
| `tier-3` | 사용자 체감 품질 |
| `tier-4` | han 맵 트랙 본체 (tier-1 결정 뒤에 산다) |
| `tier-5` | v2 대공사 — 착수 조건 미충족 |
| `verified-fixed` | 코드 확인 결과 이미 해소. 클로즈 후보 |
| `stale-premise` | 본문 전제가 낡음. 재작성 전에는 착수 금지 |

---

## 판정을 지배한 사실 셋

### 1. han 맵 전환이 절반만 됐다

맵 데이터는 들어왔는데 **AI 도, 프론트도 그것을 못 본다.**
"돌아가는 것처럼 보이지만 내용이 틀린" 상태가 가장 나쁘다. 그래서 개별 버그보다
**han 전환 완결**이 상위 축이다.

### 2. 세계 단위가 안 정해졌다 — 세 숫자가 충돌한다

| 출처 | 단위 |
| --- | --- |
| `#464` (OPENSAM-204) 본문 | 후한 **161郡** |
| `#473` (OPENSAM-213) 본문 | han **780성 / 1778 간선** |
| `data/map/han-tiles.json` 실물 | `cities` **1,144** · `juns` **175** |

셋이 다르다. 이게 안 정해진 채로 `#473` 의 다턴 이동·Dijkstra 를 구현하면 노드 수부터
다시 짜야 한다. **`tier-1` 의 존재 이유가 이것이다.**

### 3. 패러티 은퇴(ADR-LITE-042)가 문서에 반영이 덜 됐다

`.ai/decisions.md` ADR-LITE-042 와 `CLAUDE.md` 는 갱신됐지만, **그 이전에 쓰인 티켓 본문**에는
폐기된 제약이 그대로 박혀 있다. 예: `#464` 본문의

> v1 che 패러티 코어(RNG draw 순서·로그 바이트·골든·DB 델타)는 **한 바이트도 바뀌지 않는다**.
> (NON-NEGOTIABLE)

이 문장은 2026-08-20 부로 무효다. 이걸 안 걷어내면 에이전트가 폐기된 규칙을 근거로
`#476` 같은 리팩터를 계속 "골든 깨진다"로 막는다. → `stale-premise`

---

## tier-0 — 지금 착수

### #476 (OPENSAM-216) AI 가 han 맵을 아예 못 본다

**han 맵 트랙의 실질 블로커.** 코드로 확인한 내용:

- `logic/src/main/kotlin/opensamguk/logic/ai/` 전체에서
  `CityConstVariant` / `CityConstRegistry` 참조 **0건**. 전역 `CityConst` 직결이다
  (`ai/bfs/AiDistance.kt:3`, `ai/families/GenFoundFamily.kt:3`,
  `NationDeployFamily.kt:3`, `GenWarMoveFamily.kt:3`).
- 그 전역은 `common/.../constants/CityConst.kt:207`
  `generateCities(cheInitCity)` — **che 94행 고정**.
- han 도시 id 는 `byId` 에서 null 이 되고, 호출부가 전부 조용히 삼킨다:
  `GenWarMoveFamily.kt:473` `?: emptyList()`,
  `:750` `?: continue`, `:768-769`,
  `NationDeployFamily.kt:267` `?: emptySet()`,
  `GenFoundFamily.kt:409` `?: continue`, `:525`,
  `AiDistance.kt:83` `?: continue`, `:210`.

**예외가 안 난다.** 빈 리스트와 `return null` 로 떨어져 NPC 가 **무증상으로 아무것도 안 한다**.

부수 결함: 건국 가능 레벨이 AI 안에 하드코딩돼 있다
(`GenFoundFamily.kt:400,410`, `GenWarMoveFamily.kt:731,751,770` 의 `5..6`).
정본 `isFoundableCityLevel`(`logic/.../world/CityConstRegistry.kt:37`)을 쓰는 곳은
`constraints/Presets.kt:407`, `ReservedTurnHandler.kt:1518`, `ProcessNationCommand.kt:664`
**3곳뿐이고 AI 는 0곳**이다.

미검증(UNKNOWN): 인접 1칸 가정, `AiDistance.kt:132-148` 의 O(m³) Floyd-Warshall 성능.

### #454 (OPENSAM-2) v1 패러티 백로그 — 재작성

**코드 0줄, 가장 싼 언블록.** 에픽의 전제("PHP 가 grand truth", "골든은 실캡처만")가
ADR-LITE-042 로 통째로 폐기됐다. 살아남는 축은 넷뿐이다(ADR `.ai/decisions.md:560-575`):
거짓 완료 금지 · 골든을 **frozen-baseline 회귀 게이트**로 재해석 · 리플레이 결정론 ·
one-daemon-write-rule/삽입순서/flush 델타.

→ 에픽을 "패러티 백로그"에서 **"회귀 게이트 재해석 + 미결 테스트 처분"**으로 재작성한다.
ADR `:578-580` 이 남긴 "순수 패러티 단언만 있는 테스트의 처분 미결"이 이 에픽의 새 내용이다.

---

## tier-1 — 선행 결정

착수 전에 **사람이 정해야 하는 것**들이다. 코드보다 결정이 먼저다.

| 이슈 | 정해야 하는 것 |
| --- | --- |
| **#464** | 세계 단위 — 161郡 / 780성 / 1,144城 중 무엇인가 (위 §2) |
| **#469** | han 월드에서 무엇을 그리는가 — `MapViewer` 소비처를 `HanMapCanvas` 로 분기할지 |
| **#473** | 다턴 이동·3페이즈 전투 — #464 가 정해진 뒤에만 설계 가능 |
| **#474** | han 게임 상수를 ADR-LITE 에 등재 (지금 근거 없이 코드에만 있다) |

**#469 는 "한 줄 고침"이 아니다.** `MapViewer.tsx:108` 의 `CDN_MAPS` 에 `'han'` 을 추가하는 것은
**오답이다** — han 은 CDN 배경 이미지가 없어 404 가 난다. 실제 구조:

- `web/game/app/game/map/page.tsx:79-81` 은 **이미 `HanMapCanvas` 를 기본**으로 쓰고
  terrain 404 일 때만 `MapViewer` 로 폴백한다 → `/game/map` 은 이미 옳다.
- che 가 뜨는 표면은 (a) `web/game/app/game/global-diplomacy/page.tsx:320`,
  (b) 게이트웨이 로비/로그인 `web/gateway/components/MapPreview.tsx`,
  (c) terrain 404 시 폴백.
- 백엔드 폴백도 **로그 없이 조용하다**: `GameConst.kt:14 mapName = "che"`,
  `GetConstController.kt:62-63`, `CityConstRegistry.kt:369-374`
  `activeMapName(...) ?: DEFAULT_MAP_NAME`.

---

## tier-2 — 운영 안정

`#466` 게임 서버 생성·삭제가 게이트웨이를 죽인다 / `#467` deployer 재기동 목록 / `#468` board-api 분리.

`#466` 실측: `app/gateway-api/.../service/ServerRegistry.kt:28` 이
`@Value("\${SERVER_REGISTRY_JSON:}")` 를 생성자에서 **한 번만** 파싱한 불변 리스트다(`:37`).
재로딩 진입점(`@Scheduled`/`refresh()`/`@RefreshScope`)이 없고, DB 경로도 없다
(`game_server` 문자열이 `app/`·`infra/` 의 `.sql`/`.kt` 에 **0건**).
파싱 실패 시 fail-closed 로 전체를 비운다(`:53`, `:92`).

`#467` 은 sibling 저장소(`opensamguk-docker`) 소관이라 `#466` 과 **동시 착수**가 필요하다 — 비용이 크다.

---

## tier-3 — 사용자 체감

`#471` 브랜드 / `#470` 공유 UI 패키지 / `#472` 디자인 완성도 / `#480` JWT / `#479` 닉네임 변경.

**`#471` 이 이 티어에서 가장 싸다.** `web/game/public/` 에 로고 파일이 **여전히 없고**
(`.gitkeep`, `city/`, `flags/`, `map/` 뿐), 텍스트 브랜드가 3곳에 하드코딩돼 있다
(`web/game/components/Header.tsx:36`, `web/game/app/page.tsx:27`,
`web/gateway/components/board/BoardShell.tsx:8`). 공유 `Brand` 컴포넌트는 `web/` 전체에 없다.
→ `tools/assets/build_brand_assets.py` 1회 실행 + 컴포넌트 1개 + 호출부 5곳. 위험 0.
`#470` 의 첫 조각으로 쓰기 좋다.

---

## tier-5 — v2 대공사 (약 108건)

`BATTLE-F0~F13`, `V2-0`~`V2-8`, 계약동결 P-1~P-15, C-track 콘텐츠 승격 등.

**지금 착수하면 안 된다.** 이유는 하나다 — tier-1 의 세계 단위가 안 정해졌다.
`BATTLE-F2`(#335)에서 `BattleTicketV1` 과 레지스트리 이름이 **동결**되는데,
그 위에 설 세계가 161郡인지 780성인지 모르는 상태다.

---

## verified-fixed — 클로즈 후보

| 이슈 | 근거 |
| --- | --- |
| **#459** 서신함 500 | `infra/.../entity/MessageEntity.kt:37-40` 이 이미 `@Convert` + `@JdbcType(PostgresValueEnumJdbcType)` + `columnDefinition = "message_type"`. `@Enumerated` 없음. 커밋 `b589559d`, IT `MessageRepositoryIT` 존재 |
| **#458** 서신 시각 9시간 | `app/game-engine/.../intake/MessageHandler.kt:574-578` 이 UTC 오프셋 보존 포맷. 삭제 게이트(`:178`)는 그대로 — 원인만 고치고 게이트는 안 건드린 올바른 형태. 회귀 테스트 `MessageHandlerTest.kt:607`. 커밋 `55170838` |
| **#477** 로컬 OOM | `docker-compose.yml` 에 restart·메모리·힙 정책 반영, 커밋 `4ccc9aef` (PR #450) |
| **#478** 패러티 종료 문서화 | 커밋 `60178962` (PR #451) |

**중요한 정정:** 2026-08-20 라이브에서 "서신이 안 불러와진다"의 실제 원인은
`#459`/`#458` 이 아니라 **컨테이너 OOM**(`game-api`/`game-engine` 이 exit 137,
`RestartPolicy=no` 로 45시간 정지)이었다. 재기동 후 `GET /api/mailbox/recent` 는 200 을 반환했다.

---

## 이 문서를 갱신하는 법

티어는 **코드 판정의 스냅샷**이다. 코드가 바뀌면 판정도 바뀐다.
근거로 인용한 `path:line` 이 아직 그대로인지 확인하지 않고 티어를 믿지 마라.
`verified-fixed` 를 클로즈할 때는 해당 커밋이 **main 에 있는지** 먼저 확인해라 —
지금은 브랜치에만 있는 것들이 섞여 있다.
