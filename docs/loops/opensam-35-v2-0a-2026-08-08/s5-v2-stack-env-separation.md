# S5 — 0A-e production 격리 + v2 스택 env 분리 (실측)

- 티켓: OPENSAM-35 / 0A-e (ADR-LITE-021 (i), ADR-LITE-023, ADR-LITE-029), 계획 `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md` §3 S5
- 브랜치: `op-35-v2-0a`, 작성 2026-08-08
- ADR-LITE-023 보정 산출물: `docker-compose.v2-sandbox.yml` · `v2-sandbox.env.example` ·
  `infra/nginx/shared-gateway-relay.conf.template` · 이 S5 계약/증거. 런타임 소스는 수정하지 않았다.
- 전제(재측정하지 않음): S0 치환 semantics · S1 형제 location + U4-d · S2 이중 게이트 · S3-b 런타임 읽기 · S4 빈 0개.

---

## 1. ADR-LITE-023 — v2 world 격리와 shared gateway 경계

C1 결정대로 `docker-compose.production.yml`·`tools/agent-system/check.py`·`application.yml`은
무수정이고 v2는 **별도 스택 파일**로 분리했다. `docker-compose.yml`(로컬 정본)도 무수정이다.

### 1.1 현재 프로세스 계약

ADR-LITE-023의 "별도 DB"는 **게임 월드 DB에만** 적용한다. v2 Compose에는 `gateway-api` service가
없고, `shared-gateway-relay`는 계정 서비스가 아닌 relay다. relay만 required external
`SHARED_GATEWAY_NETWORK`에 붙고, required `SHARED_GATEWAY_UPSTREAM`으로 existing v1 gateway를 프록시한다.
tracked local Compose의 stable container DNS는 `opensamguk-gateway-api:8080`이다. `gateway-api:8080`는 relay의
**v2-network 전용 alias**라 upstream으로 쓰면 self-proxy가 된다. web-gateway·web-game·nginx는 v2 network에만
붙은 채 기존 `http://gateway-api:8080` URL로 relay를 사용한다. 이 방식은 account DB, admin seed, JWT issuer,
profile writer를 중복 생성하지 않는다.

| 대상 | 연결/소유 | JWT·프로필 경계 |
|---|---|---|
| 기존 v1 `gateway-api` | `SHARED_GATEWAY_UPSTREAM`의 unique existing DNS host:port로 relay에서만 해석. v2 Compose는 이 서비스를 build·seed·DB 연결하지 않는다 | 기존 v1 gateway가 account/JWT 발급/profile writer의 단일 소유자 |
| `shared-gateway-relay` | `shared-gateway`에는 unique service identity만, `opensamguk-v2`에는 `gateway-api` alias만 둔다. template은 required upstream을 Docker DNS로 프록시한다 | account DB·issuer·admin seed·profile writer 없음 |
| `web-gateway`·`web-game`·`nginx` | `opensamguk-v2`에만 attach하고 기존 `http://gateway-api:8080` 내부 URL을 그대로 사용 | `nginx`는 `SHARED_GATEWAY_PROFILE_ICONS_VOLUME`을 read-only mount해 기존 writer의 파일을 제공 |
| `game-api` | `jdbc:postgresql://postgres:5432/${V2_POSTGRES_DB:-sammo_v2}` + sandbox `redis`만 사용 | `${JWT_SECRET:?}`로 existing gateway가 발급한 토큰을 검증 |
| `game-engine` | `jdbc:postgresql://postgres:5432/${V2_POSTGRES_DB:-sammo_v2}` + sandbox `redis`만 사용 | JWT 미사용 |

`SPRING_FLYWAY_LOCATIONS`는 game-api와 game-engine에만
`classpath:db/migration,classpath:db/migration_v2`로 주며, shared gateway에는 v2 migration을 주지 않는다.
`ADMIN_USERNAME`/`ADMIN_PASSWORD`와 profile writer도 v2 서비스에는 존재하지 않는다. profile·닉네임·유산
포인트의 정책적 공유 범위를 새로 결정하지 않고, 이미 shared gateway가 관리하는 profile metadata와 파일만
같은 existing volume으로 일관되게 보이게 한다.

### 1.2 v1과 다른 값으로 명시 주입한 v2 world 값

| # | env | v1 스택 값 | **v2 world 값** | 근거 |
|---|---|---|---|---|
| ① | `GAME_DATABASE_URL` (game-api·game-engine만) | `jdbc:postgresql://postgres:5432/sammo` | `jdbc:postgresql://postgres:5432/sammo_v2` (별도 postgres 컨테이너·별도 볼륨·별도 네트워크) | S1 U4-d — v2 행이 있는 DB에 v1이 붙어도 Flyway는 WARN만 내고 뜬다. **world DB 분리가 1차 방어선** |
| ② | `OPENSAMGUK_WORLD_ID` | `1` | `9001` (`${V2_WORLD_ID:-9001}`) | `WorldIdConfig.kt:11` · `ScenarioSeedCoordinator.kt:46-48`이 world 동일성 불일치를 `error(...)`로 잡는다 |
| ③ | `SCENARIO_CODE` | `scenario_1010` | **기본값 없음** — `${V2_SCENARIO_CODE:?...}` fail-closed | 계획 §0.2 조용한 실패. 상속하면 `ignoreDefaultEvents=false` → `ScenarioImporter.kt:888` defaults 분기 |
| ④ | `SCENARIO_DIR` | `/data/scenarios` | `/data/scenarios-v2` + 호스트 마운트도 `${V2_SCENARIO_HOST_DIR:?...}` fail-closed | 동上. `EffectiveScenarioResolver`가 이 디렉터리를 classpath보다 우선한다 |
| ⑤ | `V2_ENABLED` | (미설정) | `"true"` 리터럴 (game-api·game-engine·**web-game**) | S2 게이트 조건 1 / S3-b 프론트 게이트 |
| ⑥ | `SPRING_PROFILES_ACTIVE` | (미설정 — 어느 compose에도 없음) | `v2-sandbox` 리터럴 | S2 게이트 조건 2 |

⑤⑥은 **리터럴**이다. 이 파일의 존재 이유가 "v2 게이트가 열린 스택"이므로 env로 끌 수 있게 두지 않았다.

### 1.3 함께 넣은 load-bearing 값

| env | 값 | 근거 |
|---|---|---|
| `SPRING_FLYWAY_LOCATIONS` | game-api·game-engine만 `classpath:db/migration,classpath:db/migration_v2` | S0 — 병합이 아니라 **치환**. v1 location 누락 시 V1~V38이 통째로 빠진다. S1 — 형제 경로여야 v1이 v2를 삼키지 않는다. existing shared gateway에는 v2 location을 주지 않는다 |
| `SCENARIO_SEED_ENABLED` | `true` (`${V2_SCENARIO_SEED_ENABLED:-true}`) | C1 (a). production compose의 `false` 불변식은 그 파일에만 걸리고 `check.py`는 무수정 |
| `V2_POSTGRES_PASSWORD` | `:?` fail-closed | v2 world postgres만의 비밀번호 |
| `SHARED_GATEWAY_NETWORK` | `:?` fail-closed external network name | relay만 attach하는 existing v1 gateway network |
| `SHARED_GATEWAY_UPSTREAM` | `:?` fail-closed unique host:port | relay의 existing v1 gateway target. `gateway-api:8080`는 v2 alias라 금지하고, render contract가 그 self value를 거부한다. 기본 hostname을 만들지 않는다 |
| `SHARED_GATEWAY_PROFILE_ICONS_VOLUME` | `:?` fail-closed external volume name | existing gateway writer와 nginx reader가 같은 profile icon 파일을 본다 |
| `JWT_SECRET` | game-api의 `:?` input | existing v1 gateway issuer와 같은 value여야 한다. cross-world runtime에서 검증할 계약이다 |

### 1.4 설계 규약 — world는 `V2_*`, shared gateway는 명시 입력

v1과 같은 호스트/같은 `.env`에서 나란히 뜨는 것이 이 파일의 목적이므로,
`${OPENSAMGUK_WORLD_ID}`처럼 v1과 같은 변수명을 쓰면 v2가 v1 값을 **조용히 상속**한다.
world 격리에 관여하는 값은 `${V2_*}`를 쓰고, 상속되면 무증상인 시나리오 값은 기본값 자체를
주지 않았다. 예외인 shared gateway는 `${SHARED_GATEWAY_NETWORK}`·`${SHARED_GATEWAY_UPSTREAM}`·
`${SHARED_GATEWAY_PROFILE_ICONS_VOLUME}`·`${JWT_SECRET}`을 **명시적으로 필수화**한다. 즉 `V2_*`
default나 ambient v1 환경값을 읽어 account topology나 JWT trust를 우연히 선택하지 않는다.
`TZ`·`NODE_ENV`·game-api/game-engine JVM 튜닝만 공통 이름을 쓴다.

`:?`는 값의 내용을 검증하지 않는다. `V2_SCENARIO_HOST_DIR`이 v1 시나리오를 가리키면 v1 콘텐츠가
v2 world DB에 시드될 수 있다. 반대로 game-api/game-engine은 이 파일의 `postgres`만 참조하므로
v2 world schema가 v1 **world** DB에 연결되는 경로는 없다. shared gateway service는 relay 하나만 external
network에서 소비하고, world 서비스는 그 network나 account DB에 붙지 않는다.

### 1.5 Compose render + mutation evidence (2026-08-08)

실제 `.env`나 시크릿은 읽지 않았다. `env -i`로 만든 complete placeholder input과 `--env-file /dev/null`만
`docker compose … config --format json`에 사용했다.

회귀 대상은 v2가 gateway identity 또는 profile storage를 복제하지 않으면서, duplicate `gateway-api` DNS
alias 없이 existing shared gateway를 유일하게 해석하는 path를 갖는지다.

수정 후 render는 다음 JSON 단언이 `true`/exit 0이었다.

| 단언 | 관측 |
|---|---|
| services 목록에 local gateway service·local profile-icons volume 없음 | `true` |
| `shared-gateway-relay`만 `shared-gateway` external network에 attach | `true` |
| `web-gateway`·`web-game`·`nginx`는 `opensamguk-v2` network만 사용 | `true` |
| relay의 `gateway-api` alias는 `opensamguk-v2`에만 있고 shared network에는 custom alias가 없음 | `true` |
| relay `SHARED_GATEWAY_UPSTREAM` = `opensamguk-gateway-api:8080`, v2 alias/self `gateway-api:8080`와 다름 | `true` |
| `game-api`·`game-engine`은 `opensamguk-v2` network만 사용 | `true` |
| nginx profile-icons mount는 named external shared volume이며 read-only | `true` |
| game-api `JWT_SECRET`은 supplied placeholder value를 받음 | `true` |
| game-api DB = game-engine DB = `jdbc:postgresql://postgres:5432/sammo_v2` | `true` |
| game-api/game-engine `REDIS_HOST` = `redis` | `true` |
| web/nginx의 stable gateway target은 `http://gateway-api:8080` (v2 relay alias) | `true` |
| nginx image entrypoint renders relay template and `nginx -t` succeeds | `true` |

`--env-file /dev/null`과 `env -i`로 만든 complete placeholder 입력에서 각 필수 shared 변수를 실제로
unset하는 mutation도 모두 exit 1로 fail-closed했다.

| unset mutation | exit | Compose 오류에 포함된 required 변수 |
|---|---:|---|
| `SHARED_GATEWAY_NETWORK` | 1 | `SHARED_GATEWAY_NETWORK required` |
| `SHARED_GATEWAY_UPSTREAM` | 1 | `SHARED_GATEWAY_UPSTREAM required` |
| `SHARED_GATEWAY_PROFILE_ICONS_VOLUME` | 1 | `SHARED_GATEWAY_PROFILE_ICONS_VOLUME required` |
| `JWT_SECRET` | 1 | `JWT_SECRET required` |

render는 external network/volume의 **이름과 wiring** 및 relay alias/upstream 구분만 검증한다. 실제 one-account
v1 gateway → relay → v2 game-api JWT 검증, 로그인 후 `/d_pic` 동일 파일 관측은 실재 shared network/volume과
같은 JWT를 준비한 뒤 실행할 최종 integration gate이며, 이 placeholder render의 통과로 주장하지 않는다.

격리 나머지: 프로젝트명 `opensamguk-v2-sandbox` · 컨테이너 `opensamguk-v2-*` ·
볼륨 `v2-pgdata`/`v2-redisdata` + external shared profile-icons · 네트워크 `opensamguk-v2` + external
shared gateway · 포트는 v2 world/web/nginx만 비표준(55432/56379/58081/58082/53000/53001/58090)이다.

---

## 2. (b) DB 실측 — 계획 §3 S5 판정

### 2.1 ADR-LITE-029 수용 기준 정정

ADR-LITE-029는 이 티켓의 수용 기준을 **v2 전용 probe 이벤트 2행 존재 + v1 기본 이벤트 12행 미적재**로
확정했다. 실제 v2 schema/content leaf 행은 OPENSAM-150의 필수 수용 기준으로 이관했다. 따라서 리포에
v2 leaf가 0건인 것은 S5의 미충족이 아니라 명시된 비범위다. 없는 leaf를 0A에 만들어
"존재"라고 쓰지 않는다.

S5가 측정한 것은 일회성 프로브 시나리오(§2.2)가 준 이벤트만 v2 world DB에 적재하고 v1 기본 12행은
적재하지 않는다는 격리 능력이다. OPENSAM-150은 같은 DB에서 실제 v2 schema/content leaf와 기본 12행
0을 함께 재측정해야 한다.

### 2.2 프로브 (리포에 남기지 않음)

`scenario_1010.json`을 스크래치패드로 복사해 `title` 교체 + `ignoreDefaultEvents: true` +
`events`를 식별 가능한 2행으로 치환한 `scenario_9001.json`.
경로: `<scratchpad>/scenarios-v2/scenario_9001.json`. **리포 워킹트리 밖**이며 §5에서 제거를 확인했다.

프로브 이벤트 2행(`NoticeToHistoryLog "<S>S5-V2-PROBE-EVENT-A|B</>"`)은 v1 leaf 액션을 쓴다 —
**v2 leaf가 아니다.** 이는 ADR-LITE-029가 OPENSAM-150으로 이관한 실제 leaf 수용 기준을 대체하지 않는다.

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
| `flyway_schema_history` 행수 | 38 (최신 V38) | 38 (최신 V38) | v2 location이 비어 있어 추가 행 없음. 실제 v2 migration/leaf는 ADR-LITE-029에 따라 OPENSAM-150에서 측정 |
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

ADR-LITE-029가 실제 v2 leaf를 OPENSAM-150의 수용 기준으로 이관했으므로, 그것은 이 목록의 미확정
항목이 아니다(§2.1).

1. **컨테이너 안에서 v2 **빈** 0/1은 재측정하지 않았다.** `/actuator/beans`가 노출돼 있지 않고
   (`:58082/actuator/beans` → 404) 노출하려면 `application.yml` 수정이 필요해 게이트 ⑤ 위반이다.
   빈 게이트는 S4가 `@SpringBootTest` 8칸 + 비공허성 프로브 3회로 실측했고, S5는 그 게이트를 여는
   **두 env가 컨테이너에 실제로 도달함**(§3.1의 `printenv`, 프로파일 활성 로그
   `The following 1 profile is active: "v2-sandbox"`)까지만 확인했다.
2. **장기 동작·턴 진행 미측정.** 부팅·시드·헬스체크까지다. v2 스택에서 턴이 도는지, v1/v2가
   동시에 오래 떠 있을 때의 자원 경합은 범위 밖.
3. **sibling `opensamguk-docker` 리포 미반영** — C2 (a) 결정에 따른 후속 티켓 몫.
4. **실제 프로덕션(`gcp-prod`) 미접속.** 전부 로컬 측정이다.
5. **one-account cross-world integration은 미실행.** 실재 shared network/volume과 existing unique gateway
   upstream, 같은 `JWT_SECRET`이 준비된 뒤, v1 gateway 로그인 → relay → v2 game-api Bearer 검증 → `/d_pic`
   동일 파일 응답을 함께 관측해야 한다. §1.5 render는 이 runtime 동작을 주장하지 않는다.

---

## 5. 정리(cleanup)

```
$ docker compose -f docker-compose.v2-sandbox.yml down -v        # v2 리소스만; external shared network/volume은 보존
$ docker compose -p opensamguk-s5-v1 -f docker-compose.yml down -v   # v1 스택 + 볼륨
$ docker rmi (측정용 이미지 10 태그)
$ docker ps -a --format '{{.Names}}\t{{.Status}}' | grep opensamguk   # 무출력
```

- 위 원래 S5 측정의 컨테이너·볼륨·네트워크·측정용 이미지는 정리했다.
- 프로브 시나리오 `scenario_9001.json`은 스크래치패드에만 존재했고 리포에 **없다**
  (`git status --short`에 흔적 0건).
- 비표준 v2 포트만 썼다(55432/55433/56379/56380/58081/58082/53000-53003/58090/58091) — 표준 포트 미점유.
- ADR-LITE-023 보정은 `docker compose config`와 relay nginx template syntax check만 실행했다. 컨테이너·DB·볼륨을
  새로 만들지 않았고, checkout·stash·커밋·푸시도 하지 않았다.

## 6. ADR-LITE-023 보정 게이트 출력

- scoped `git diff --check -- docker-compose.v2-sandbox.yml v2-sandbox.env.example
  infra/nginx/shared-gateway-relay.conf.template docs/loops/opensam-35-v2-0a-2026-08-08/s5-v2-stack-env-separation.md` exit 0.
- temporary placeholder env의 render 및 필수 shared 입력 4종 unset mutation, relay alias/upstream assertion은 §1.5에 기록했다.
- 이 보정에서는 스택을 기동하지 않았고 broad backend gate도 재실행하지 않았다. 이는 Compose interpolation과
  topology 계약만 바뀐 범위이며, one-account cross-world runtime gate와 full gate 재실행은 상위 작업의 책임이다.
