# S5 — 0A-e production 격리 + v2 스택 env 분리 (실측)

- 티켓: OPENSAM-35 / 0A-e (ADR-LITE-021 (i)), 계획 `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md` §3 S5
- 브랜치: `op-35-v2-0a`, 작성 2026-08-08
- 산출물: `docker-compose.v2-sandbox.yml` (**신규 1파일**). 기존 파일 수정 0건.
- 전제(재측정하지 않음): S0 치환 semantics · S1 형제 location + U4-d · S2 이중 게이트 · S3-b 런타임 읽기 · S4 빈 0개.

---

## 1. `docker-compose.v2-sandbox.yml` — 6개 env의 실제 값과 근거

C1 결정대로 `docker-compose.production.yml`·`tools/agent-system/check.py`·`application.yml`은
무수정이고 v2는 **별도 스택 파일**로 분리했다. `docker-compose.yml`(로컬 정본)도 무수정이다.

### 1.1 v1과 다른 값으로 명시 주입한 6개

컨테이너 안에서 `printenv`로 확인한 **실효값**이다(§3.1).

| # | env | v1 스택 값 | **v2 스택 값** | 근거 |
|---|---|---|---|---|
| ① | `GAME_DATABASE_URL` | `jdbc:postgresql://postgres:5432/sammo` | `jdbc:postgresql://postgres:5432/sammo_v2` (별도 postgres 컨테이너·별도 볼륨·별도 네트워크) | S1 U4-d — v2 행이 있는 DB에 v1이 붙어도 Flyway는 WARN만 내고 뜬다. **DB 분리가 유일한 1차 방어선** |
| ② | `OPENSAMGUK_WORLD_ID` | `1` | `9001` (`${V2_WORLD_ID:-9001}`) | `WorldIdConfig.kt:11` · `ScenarioSeedCoordinator.kt:46-48`이 world 동일성 불일치를 `error(...)`로 잡는다 |
| ③ | `SCENARIO_CODE` | `scenario_1010` | **기본값 없음** — `${V2_SCENARIO_CODE:?...}` fail-closed | 계획 §0.2 조용한 실패. 상속하면 `ignoreDefaultEvents=false` → `ScenarioImporter.kt:888` defaults 분기 |
| ④ | `SCENARIO_DIR` | `/data/scenarios` | `/data/scenarios-v2` + 호스트 마운트도 `${V2_SCENARIO_HOST_DIR:?...}` fail-closed | 동上. `EffectiveScenarioResolver`가 이 디렉터리를 classpath보다 우선한다 |
| ⑤ | `V2_ENABLED` | (미설정) | `"true"` 리터럴 (game-api·game-engine·**web-game**) | S2 게이트 조건 1 / S3-b 프론트 게이트 |
| ⑥ | `SPRING_PROFILES_ACTIVE` | (미설정 — 어느 compose에도 없음) | `v2-sandbox` 리터럴 | S2 게이트 조건 2 |

⑤⑥은 **리터럴**이다. 이 파일의 존재 이유가 "v2 게이트가 열린 스택"이므로 env로 끌 수 있게 두지 않았다.

### 1.2 함께 넣은 load-bearing 값 3개 (6개 외)

| env | 값 | 근거 |
|---|---|---|
| `SPRING_FLYWAY_LOCATIONS` | `classpath:db/migration,classpath:db/migration_v2` | S0 — 병합이 아니라 **치환**. v1 location 누락 시 V1~V38이 통째로 빠진다. S1 — 형제 경로여야 v1이 v2를 삼키지 않는다. **gateway-api에도 넣었다**: gateway-api도 같은 DB에 `classpath:db/migration`으로 Flyway를 돌린다(`app/gateway-api/src/main/resources/application.yml:12-14`) — 빼면 부팅 순서에 따라 U4-d의 "newer version" WARN이 난다 |
| `SCENARIO_SEED_ENABLED` | `true` (`${V2_SCENARIO_SEED_ENABLED:-true}`) | C1 (a). production compose의 `false` 불변식은 그 파일에만 걸리고 `check.py`는 무수정 |
| `V2_POSTGRES_PASSWORD` / `V2_JWT_SECRET` / `V2_ADMIN_PASSWORD` | 전부 `:?` fail-closed | v1 크리덴셜 재사용 금지. 로컬 compose의 기본값 관례를 따르지 않은 유일한 지점 |

### 1.3 설계 규약 — **스택 격리에 load-bearing한 값만 `V2_`-접두 치환 변수를 쓴다**

v1과 같은 호스트/같은 `.env`에서 나란히 뜨는 것이 이 파일의 목적이므로,
`${OPENSAMGUK_WORLD_ID}`처럼 v1과 같은 변수명을 쓰면 v2가 v1 값을 **조용히 상속**한다.
그래서 격리에 관여하는 값은 전부 `${V2_*}`로 바꿨고, 상속되면 무증상인 3·4는
기본값 자체를 주지 않았다.

**전수 적용이 아니다.** 스택 격리와 무관한 값 — 상속돼도 v1/v2가 서로를 오염시키지 않는 값 —
은 의도적으로 v1 이름을 공유한다. 실측(`grep -o '\${[A-Za-z_][A-Za-z0-9_]*'`)한 **예외 7종**:

| 변수 | 등장 위치 | 왜 v1 이름을 공유하는가 |
|---|---|---|
| `TZ` | 전 8서비스 (`:39`, `:61`, `:91`, `:139`, `:189`, `:248`, `:273` + `JAVA_TOOL_OPTIONS` 3곳) | 타임존은 호스트 단위 값이다. v1과 다르게 두면 오히려 로그 대조가 깨진다 |
| `NODE_ENV` | `web-gateway:249`, `web-game:274` | 빌드/런타임 모드. 스택 정체성이 아니다 |
| `JWT_ACCESS_EXPIRATION` | `gateway-api:100` | 만료 **시간**일 뿐이다. 격리는 `V2_JWT_SECRET`(서명키)이 하며 그건 `:?` fail-closed다 |
| `JWT_REFRESH_EXPIRATION` | `gateway-api:101` | 동上 |
| `GATEWAY_API_JAVA_OPTS` | `:111` | JVM 튜닝 플래그. v2 컨테이너 안에서만 작용하고 v1에 닿지 않는다 |
| `GAME_API_JAVA_OPTS` | `:162` | 동上 |
| `GAME_ENGINE_JAVA_OPTS` | `:219` | 동上 |

(계수 기준: `${…}` 치환으로 **실제 등장하는** 서로 다른 변수명. 헤더 주석 `:12`의
`${OPENSAMGUK_WORLD_ID}`는 설명용 예시 문자열이라 치환이 아니다.)

fail-closed 실측:

```
$ V2_SCENARIO_HOST_DIR=… V2_POSTGRES_PASSWORD=x V2_JWT_SECRET=x V2_ADMIN_PASSWORD=x \
  docker compose -f docker-compose.v2-sandbox.yml config
EXIT=1
error while interpolating services.game-engine.environment.SCENARIO_CODE:
  required variable V2_SCENARIO_CODE is missing a value:
  V2_SCENARIO_CODE required — inheriting v1 SCENARIO_CODE silently seeds the 12 v1 default events
```

### 1.4 `:?` fail-closed의 한계 — **값의 내용은 검증하지 않는다** (GATE-f Q2)

`V2_SCENARIO_HOST_DIR`(`:121`, `:171`, `:228`)·`V2_SCENARIO_CODE`(`:203`)의 `:?`가 막는 것은
**변수 미설정 하나뿐**이다. 값이 무엇을 가리키는지는 compose가 알지 못한다. 따라서:

- **콘텐츠 방향(v1 → v2)은 열려 있다.** 운영자가 `V2_SCENARIO_HOST_DIR`을 v1 시나리오 호스트
  디렉터리로 두고 `V2_SCENARIO_CODE`에 v1 시나리오 코드를 넣으면 **v1 콘텐츠가 v2 DB에 시드된다.**
  compose는 뜨고 부팅·시드·헬스체크가 전부 성공하므로 §1.1 ③④가 막으려던 "조용한 실패"와
  **같은 계열의 사고가 값 내용 쪽에는 남아 있다.**
- **DB 방향(v2 → v1)은 막혀 있다.** S1 U4-d 대응은 `GAME_DATABASE_URL` 분리이며 이건 DB 방향
  전용이다. 별도 postgres 컨테이너 + 별도 볼륨 + 별도 DB명 + 자기 네트워크의 `postgres`
  서비스만 참조 ⇒ 이 compose 안에 v1 DB로 붙는 경로는 없다(GATE-f 리뷰 재확인).

**두 방향을 헷갈리지 말 것**: DB는 격리, 콘텐츠는 운영자 신뢰. 값 내용 검증(예: 마운트 경로가
v1 `SCENARIO_DIR`과 같은지 비교)은 이 티켓 범위 밖이고 compose에 가드를 넣지 않았다 —
현재 결론은 **한계 명시**다.

격리 나머지: 프로젝트명 `opensamguk-v2-sandbox` · 컨테이너 `opensamguk-v2-*` ·
볼륨 `v2-pgdata`/`v2-redisdata`/`v2-profile-icons` · 네트워크 `opensamguk-v2` ·
포트 전부 비표준(55432/56379/58080-58082/53000/53001/58090) — 다른 세션·v1 스택과 충돌 없음.

---

## 2. (b) DB 실측 — 계획 §3 S5 판정

### 2.1 판정 문구를 문자 그대로는 충족할 수 없다 (정직하게 남김)

판정은 "**v2 leaf 이벤트 행이 존재**하고 v1 기본 12행이 적재되지 않음"이다.
그런데 계획 §0.1대로 리포에 **v2 콘텐츠·v2 시나리오·v2 이벤트 leaf가 0건**이다.
`infra/src/main/resources/db/migration_v2/`와 `content/v2/`는 `README.md`뿐이다.
없는 v2 leaf를 만들어 "존재한다"고 쓰면 날조다.

**대신 측정한 것**: 일회성 프로브 시나리오(§2.2)를 써서 "**v2 스택은 자기 시나리오가 준 이벤트만
적재하고 v1 기본 12행은 적재하지 않는다**"를 DB로 실측했다. 판정의 두 번째 절(핵심 방어)은 충족,
첫 번째 절은 **v2 콘텐츠가 생기기 전까지 UNKNOWN**이다 — 약화된 대체 판정을 진짜 판정으로 부르지 않는다.

### 2.2 프로브 (리포에 남기지 않음)

`scenario_1010.json`을 스크래치패드로 복사해 `title` 교체 + `ignoreDefaultEvents: true` +
`events`를 식별 가능한 2행으로 치환한 `scenario_9001.json`.
경로: `<scratchpad>/scenarios-v2/scenario_9001.json`. **리포 워킹트리 밖**이며 §5에서 제거를 확인했다.

프로브 이벤트 2행(`NoticeToHistoryLog "<S>S5-V2-PROBE-EVENT-A|B</>"`)은 v1 leaf 액션을 쓴다 —
**v2 leaf가 아니다.** 그래서 §2.1의 UNKNOWN이 남는다.

**부수 발견(구속 조건):** 첫 시도의 코드 `scenario_s5v2probe`는 부팅을 깨뜨렸다 —
`SCENARIO_CODE must be canonical scenario_<number>` (`ScenarioSeedRunner.kt:150`).
⇒ **v2 시나리오 코드도 `scenario_<숫자>` 정규형이어야 한다.** OPENSAM-151(R2)이 이 제약을 받는다.

### 2.3 실행

동일 `game-engine` 이미지 1개(`docker/game-engine.Dockerfile` 빌드)를 두 스택이 공유한다 —
차이는 **env뿐**이다(S3-b가 프론트에서 증명한 성질을 백엔드에서도 유지).

```
# v2:  docker compose -f docker-compose.v2-sandbox.yml up -d --no-build postgres redis game-engine
# v1:  docker compose -p opensamguk-s5-v1 -f docker-compose.yml up -d --no-build postgres redis game-engine
#      (v1은 SCENARIO_HOST_DIR을 빈 디렉터리로 두어 classpath scenario_1010으로 폴백)
```

부팅: v2 `health=healthy` (t=40s 이후 재실행에서 정상), v1 `health=healthy` (t=80s).

```
v2: Seeding fresh world 'scenario_9001' — map=che nations=2 generals=678 cities=94 turnTerm=60
    Scenario seed complete — world_state=1 … event=79
v1: Seeding fresh world 'scenario_1010' — map=che nations=2 generals=678 cities=94 turnTerm=60
    Scenario seed complete — world_state=1 … event=90
```

### 2.4 쿼리 원문과 결과

쿼리는 `<scratchpad>/probe.sql` 전문이며 두 DB에 **동일하게** 실행했다.

```sql
SELECT current_database();
SELECT id FROM world_state ORDER BY id;
SELECT count(*) FROM flyway_schema_history;
SELECT target_code, priority, count(*) FROM event GROUP BY 1,2 ORDER BY 1,2;

-- v1 기본 12행 지문 (EventStore.kt:157-251의 DEFAULT_EVENTS와 1:1)
SELECT count(*) AS v1_default_rows FROM event
 WHERE (target_code = 'pre_month' AND priority = 9000)
    OR (target_code = 'month'     AND priority IN (9000, 2000))
    OR (target_code = 'united'    AND priority = 5000)
    OR (target_code = 'month'     AND priority = 1000 AND action::text LIKE '%UpdateNationLevel%');

SELECT count(*) AS probe_event_rows FROM event WHERE action::text LIKE '%S5-V2-PROBE-EVENT%';
SELECT count(*) AS scenario1010_event_rows FROM event WHERE target_code = 'destroy_nation';
SELECT count(*) AS event_rows_total FROM event;
```

| 항목 | **v1 스택** | **v2 스택** | 판정 |
|---|---|---|---|
| `current_database()` | `sammo` | `sammo_v2` | **DB 분리 확인** |
| `world_state.id` | `1` | `9001` | **world_id 분리 확인** |
| `flyway_schema_history` 행수 | 38 (최신 V38) | 38 (최신 V38) | v2 location이 비어 있어 추가 행 없음(§4 UNKNOWN 1) |
| `v1_default_rows` | **12** | **0** | **판정 핵심 — v2는 v1 기본 12행을 적재하지 않는다** |
| `probe_event_rows` | 0 | **2** | v2는 자기 시나리오 이벤트만 적재 |
| `scenario1010_event_rows` (`destroy_nation`) | **1** | 0 | v1 시나리오 고유 이벤트가 v2로 새지 않음 |
| `event` 총행수 | **90** (12 + 1 + 77 deferred) | **79** (0 + 2 + 77 deferred) | 산술 일치 |

`event` 분포 원문:

```
v1                                    v2
 target_code    | priority | count     target_code | priority | count
 Month          |     1000 |    77     Month       |     1000 |    77
 destroy_nation |     1000 |     1     month       |     1000 |     2
 month          |     1000 |     1
 month          |     2000 |     5
 month          |     9000 |     4
 pre_month      |     9000 |     1
 united         |     5000 |     1
```

`Month/1000 = 77`은 양쪽 동일한 deferred general 등장 이벤트다(같은 장수 로스터를 복사한 프로브라
당연한 결과이며, 이 값이 같다는 사실이 나머지 차이가 **이벤트 병합 분기에서만** 나왔음을 보인다).

### 2.5 v1 회귀 확인

**v1 스택은 멀쩡히 뜬다.** 같은 이미지·현재 브랜치 소스(S1~S4의 v2 신규 파일 전부 포함)로:

- `opensamguk-game-engine` `health=healthy`, `Started GameEngineApplicationKt in 57.206 seconds`
- `SPRING_FLYWAY_LOCATIONS` **미설정** → `application.yml`의 `classpath:db/migration` 그대로.
  `flyway_schema_history` 38행, `ERROR` 0건, "newer than the latest available migration" WARN 0건
- 시드 결과가 v2 도입 전과 동일(`event=90`, 기본 12행 정상 적재)
- 프론트 `/game/rankings`·`/game/pep/rankings` 200 (§3.2)

---

## 3. (c) M2 잔여 폐쇄 — standalone 404 재관측

S3-b가 남긴 구속 요구: `next start`가 아니라 **실제 배포 형태**에서 재관측할 것.

### 3.1 standalone임을 먼저 확인

```
$ docker inspect -f '{{.Config.Cmd}}' opensamguk-v2-web-game
[node server.js]                    # docker/web-game.Dockerfile 최종 CMD = standalone 서버
$ docker exec opensamguk-v2-web-game printenv V2_ENABLED SERVER_ID
true
v2s
$ docker exec opensamguk-web-game    printenv SERVER_ID      → pep
$ docker exec opensamguk-web-game    printenv V2_ENABLED     → (unset)
```

`node server.js` = `.next/standalone/server.js`. `next start` 경고(S3-b UNKNOWN 3) 소멸.
**S3-b UNKNOWN 2("컨테이너에서 `V2_ENABLED`가 Next 프로세스에 실제로 주입되는지")도 여기서 닫힌다.**

### 3.2 실측 표 — 두 스택 모두 nginx 경유, **동일 이미지**

v1 = `opensamguk-s5-v1` 프로젝트(`docker-compose.yml`, `SERVER_ID=pep`, `V2_ENABLED` 미설정) nginx `:58091`
v2 = `docker-compose.v2-sandbox.yml`(`SERVER_ID=v2s`, `V2_ENABLED=true`) nginx `:58090`.
`web-game` 이미지는 **같은 태그 하나를 두 스택에 태깅**한 것이라 차이는 env뿐이다.

| 경로 | v1 스택 (nginx, `V2_ENABLED` 미설정) | v2 스택 (nginx, `V2_ENABLED=true`) |
|---|---|---|
| `/game/v2-lab` | **404** | **200** (`v2 실험 네임스페이스` 렌더 확인) |
| `/game/pep/v2-lab` (v1의 rewrite 우회 경로) | **404** | 404 (v2의 serverId가 아님 — 라우트 부재) |
| `/game/v2s/v2-lab` (v2의 rewrite 우회 경로) | 404 (v1의 serverId가 아님) | **200** (렌더 확인) |
| `/game/rankings` (정상 라우트 대조군) | 200 | 200 |
| `/game/pep/rankings` (**v1 rewrite 생존 대조군**) | **200** | 404 |
| `/game/v2s/rankings` (**v2 rewrite 생존 대조군**) | 404 | **200** |

nginx 없이 standalone 포트 직접(v1 `:53003`, v2 `:53001`)도 동일: `/game/v2-lab` v1 404 / v2 200,
`/game/pep/v2-lab` v1 404, `/game/v2s/v2-lab` v2 200. **nginx가 상태코드를 바꾸지 않는다.**

**요구된 2경로 판정**: `/game/v2-lab` 404 ✅ / `/game/<SERVER_ID>/v2-lab` 404 ✅
(v1 스택에서 `SERVER_ID=pep`이므로 우회 경로는 `/game/pep/v2-lab`이고, 같은 스택의
`/game/pep/rankings`가 200이라는 것이 **rewrite 분기가 실제로 살아 있다는 대조군**이다 —
즉 404는 "라우트가 없어서"가 아니라 게이트가 잡은 것이다).
**양성 대조군**: v2 스택에서 두 경로 모두 200 + 실제 렌더.

### 3.3 콘텐츠 유출 0건

```
v1 /game/v2-lab      leak_hits=0  body_bytes=0
v1 /game/pep/v2-lab  leak_hits=0  body_bytes=0
v2 /game/v2-lab      → "v2 실험 네임스페이스"
v2 /game/v2s/v2-lab  → "v2 실험 네임스페이스"
```

`leak_hits` = 응답 바디의 `v2 실험 네임스페이스` 출현 횟수. S3-b §2.2의 soft 404에서는 이 문자열이
페이로드에 실려 나갔다. standalone + nginx에서도 **0**이다. 404 바디는 0바이트(흰 화면) —
S3-b §4.3과 동일하며 범위를 넓히지 않았다.

⇒ **M2("production-shape 스택 실요청 404")는 이 측정으로 충족**한다. 0A-a의 잔여 구속 요구 폐쇄.

---

## 4. UNKNOWN (정직하게 남김)

1. **"v2 leaf 이벤트 행 존재"는 미충족.** 리포에 v2 콘텐츠 0건이라 실재하는 v2 leaf가 없다(§2.1).
   `db/migration_v2/`·`content/v2/`도 `README.md`뿐이라 v2 스택의 `flyway_schema_history`가
   v1과 같은 38행이다 — **v2 location이 비어 있다는 뜻이지 안 읽힌다는 뜻이 아니다**(S1이 프로브
   V900/V901/V902로 이미 적용을 실측했다). OPENSAM-150(R1)이 실제 마이그레이션을 넣으면 재측정 대상.
2. **컨테이너 안에서 v2 **빈** 0/1은 재측정하지 않았다.** `/actuator/beans`가 노출돼 있지 않고
   (`:58082/actuator/beans` → 404) 노출하려면 `application.yml` 수정이 필요해 게이트 ⑤ 위반이다.
   빈 게이트는 S4가 `@SpringBootTest` 8칸 + 비공허성 프로브 3회로 실측했고, S5는 그 게이트를 여는
   **두 env가 컨테이너에 실제로 도달함**(§3.1의 `printenv`, 프로파일 활성 로그
   `The following 1 profile is active: "v2-sandbox"`)까지만 확인했다.
3. **장기 동작·턴 진행 미측정.** 부팅·시드·헬스체크까지다. v2 스택에서 턴이 도는지, v1/v2가
   동시에 오래 떠 있을 때의 자원 경합은 범위 밖.
4. **`gateway-api`의 v2 게이트 없음은 그대로다.** S2가 game-api·game-engine 양쪽에만 게이트를
   설치했으므로 v2 스택의 gateway-api에는 `V2_ENABLED`/프로파일을 주지 않았다(Flyway location만 정렬).
   gateway-api가 v2 빈을 갖게 되면 이 파일도 함께 고쳐야 한다.
5. **sibling `opensamguk-docker` 리포 미반영** — C2 (a) 결정에 따른 후속 티켓 몫.
6. **실제 프로덕션(`gcp-prod`) 미접속.** 전부 로컬 측정이다.

---

## 5. 정리(cleanup)

```
$ docker compose -f docker-compose.v2-sandbox.yml down -v        # v2 스택 + 볼륨
$ docker compose -p opensamguk-s5-v1 -f docker-compose.yml down -v   # v1 스택 + 볼륨
$ docker rmi (측정용 이미지 10 태그)
$ docker ps -a --format '{{.Names}}\t{{.Status}}' | grep opensamguk   # 무출력
```

- 컨테이너 0 · 볼륨 0 · 네트워크 0 · 측정용 이미지 0.
- 프로브 시나리오 `scenario_9001.json`은 스크래치패드에만 존재했고 리포에 **없다**
  (`git status --short`에 흔적 0건).
- 비표준 포트만 썼다(55432/55433/56379/56380/58080-58085/53000-53003/58090/58091) — 표준 포트 미점유.
- 브랜치 변경·stash·커밋·푸시 **0건**.

## 6. 게이트 출력 (§7 보고와 동일)

`git diff --name-only --diff-filter=MD origin/main -- …` 4종 전부 **빈 출력**.
`docker-compose.v2-sandbox.yml`은 신규 파일이라 `--diff-filter=MD`에 걸리지 않는다(계획 §2 C1 예상대로).
