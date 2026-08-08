# S1 실측 — v2 Flyway location 격리

- 티켓: OPENSAM-35 / 계획 §3 S1
- 측정일: 2026-08-08
- 측정자: agent `op35-s1-flyway`
- 리포 상태: `op-35-v2-0a` @ `fb90eac1` (측정용 detached worktree에서 빌드)
- 선행: `u12-flyway-locations-measurement.md` (S0, 치환 semantics 확정)

## 결론 요약

| 미지 | 판정 |
|---|---|
| U1 재귀 스캔 | **재귀한다.** `classpath:db/migration` 하나만 지정한 v1 프로세스가 `db/migration/v2/V901`을 적용했다. **하위 경로 사용 불가.** |
| U2 형제 경로 격리 | **격리된다.** v1 = 38행(프로브 테이블 0개), v2 = 40행(v1 38 + 프로브 2개). |
| U3 classpath 도달성 | **jar에 함께 구워진다.** 볼륨 마운트·이미지 베이크 불필요. ⇒ **classpath 형제 경로 채택.** |
| U4 히스토리 테이블 | **`public.flyway_schema_history` 단일 공유.** 별도 테이블 아님. 버전 번호는 정책 선택지 3개 제시(§U4), 확정은 사람 몫. |

**확정 location: `classpath:db/migration_v2` (리포 경로 `infra/src/main/resources/db/migration_v2/`).**
v2 스택 env: `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/migration_v2`.

---

## 방법

S0 절차 재사용. `git worktree add --detach HEAD` 로 스크래치패드에 격리 트리를 만들고 거기서만
프로브 리소스를 넣고 `bootJar` 빌드. 실행은 `env -i`로 셸 환경을 비운 진짜 OS 환경변수 +
`java -jar` (Testcontainers/`@SpringBootTest(properties=)` 우회 없음).

### 프로브 (측정용 일회성, 리포에 남기지 않음)

| 프로브 | 위치 | 목적 |
|---|---|---|
| `V901__s1_probe_nested.sql` | `infra/src/main/resources/db/migration/v2/` | U1 재귀 스캔 |
| `V902__s1_probe_sibling.sql` | `infra/src/main/resources/db/migration_v2/` | U2 형제 격리 |
| `V10_5__s1_probe_ooo.sql` | `infra/src/main/resources/db/migration_v2/` | U4 out-of-order |

각 프로브 본문은 `CREATE TABLE <name>(id int primary key);` 한 줄.

### 인프라

```bash
docker run -d --name s1pg -e POSTGRES_DB=sammo_v1 -e POSTGRES_USER=sammo \
  -e POSTGRES_PASSWORD=sammo -p 55433:5432 postgres:16-alpine
docker run -d --name s1redis -p 56380:6379 redis:7-alpine
docker exec s1pg psql -U sammo -d postgres -c "CREATE DATABASE sammo_v2"
docker exec s1pg psql -U sammo -d postgres -c "CREATE DATABASE sammo_v1b"
```

포트 55433/56380 — S0(55432/56379)과 겹치지 않게 잡아 동시 세션 충돌을 피했다.

### 실행 (5 run)

| Run | DB | `SPRING_FLYWAY_LOCATIONS` | jar에 든 프로브 |
|---|---|---|---|
| 1 | `sammo_v1` (fresh) | **미설정**(application.yml 기본값) | V901(하위) + V902(형제) |
| 2 | `sammo_v1b` (fresh) | 미설정 | V902 + V10_5 (형제만, V901 제거 후 재빌드) |
| 3 | `sammo_v2` (fresh) | `classpath:db/migration,classpath:db/migration_v2` | V902 + V10_5 |
| 4 | `sammo_v1b` (Run2 결과 = V38) | 동일 | V902 + V10_5 |
| 5 | `sammo_v1b` (Run4 결과 = V902 포함) | 동일 | (V902를 `filesystem:`으로 분리 후) → 그 다음 **미설정**으로 v1 재부팅 |

공통 env (Run별로 `GAME_DATABASE_URL`·`GAME_ENGINE_PORT`·`SPRING_FLYWAY_LOCATIONS`만 다름):

```bash
env -i PATH=/usr/bin:/bin JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  GAME_DATABASE_URL="jdbc:postgresql://127.0.0.1:55433/<db>" \
  GAME_DB_USER=sammo GAME_DB_PASSWORD=sammo \
  REDIS_HOST=127.0.0.1 REDIS_PORT=56380 \
  OPENSAMGUK_WORLD_ID=1 SCENARIO_SEED_ENABLED=false GAME_ENGINE_PORT=<port> \
  $JAVA_HOME/bin/java -jar <wt>/app/game-engine/build/libs/game-engine-0.0.1-SNAPSHOT.jar
```

---

## U1 — Flyway는 location을 재귀 스캔하는가? **YES (하위 경로 사용 불가)**

Run 1. `SPRING_FLYWAY_LOCATIONS` **미설정** = `application.yml:14`의 `classpath:db/migration` 단독.
v1 location의 **하위**인 `db/migration/v2/`에 둔 V901이 그대로 적용됐다.

부팅 로그:

```text
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "38 - rtk14 npc lifecycle repair"
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "901 - s1 probe nested"
o.f.core.internal.command.DbMigrate : Successfully applied 39 migrations to schema "public", now at version v901 (execution time 00:01.919s)
```

`flyway_schema_history` (39행, 말미):

```text
             36 | 36      | diplomacy casualties               | SQL  | t
             37 | 37      | general owner claim request        | SQL  | t
             38 | 38      | rtk14 npc lifecycle repair         | JDBC | t
             39 | 901     | s1 probe nested                    | SQL  | t
(39 rows)
```

프로브 테이블 실재:

```text
 Schema |      Name       | Type  | Owner
--------+-----------------+-------+-------
 public | s1_probe_nested | table | sammo
(1 row)
```

같은 Run에서 형제 경로 V902는 **적용되지 않았다**(39행 중 902 없음, `s1_probe_sibling` 테이블 없음).

**판정: `classpath:db/migration/v2` 같은 하위 경로는 0A-c 목적과 정반대다.** v1 프로세스가
v2 마이그레이션을 통째로 집어삼킨다. 계획 §3 S1의 재귀 스캔 경고는 실측으로 확인됐다.
**형제 경로가 유일한 선택지.**

## U2 — 형제 경로가 실제로 격리되는가? **YES**

V901(하위 프로브)을 제거하고 재빌드해 하위 경로 오염 없이 측정했다.

### Run 2 — v1 컨텍스트 (`SPRING_FLYWAY_LOCATIONS` 미설정), fresh `sammo_v1b`

```text
o.f.core.internal.command.DbMigrate : Successfully applied 38 migrations to schema "public", now at version v38 (execution time 00:02.721s)
o.engine.GameEngineApplicationKt    : Started GameEngineApplicationKt in 36.556 seconds
```

```text
 count
-------
    38
(1 row)

 installed_rank | version |         description
----------------+---------+-----------------------------
             38 | 38      | rtk14 npc lifecycle repair
             37 | 37      | general owner claim request
             36 | 36      | diplomacy casualties
(3 rows)

Did not find any relation named "s1_probe*".
```

**v2 행 0건, v2 프로브 테이블 0개.** 형제 경로는 v1 프로세스에 보이지 않는다.

### Run 3 — v2 컨텍스트 (`classpath:db/migration,classpath:db/migration_v2`), fresh `sammo_v2`

```text
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "10.5 - s1 probe ooo"
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "902 - s1 probe sibling"
o.f.core.internal.command.DbMigrate : Successfully applied 40 migrations to schema "public", now at version v902 (execution time 00:05.481s)
```

`flyway_schema_history` 전문 (40행):

```text
 installed_rank | version |            description             | type | success
----------------+---------+------------------------------------+------+---------
              1 | 1       | baseline                           | SQL  | t
              2 | 2       | p2 brief                           | SQL  | t
              3 | 3       | p2 nation env                      | SQL  | t
              4 | 4       | p3 calendar                        | SQL  | t
              5 | 5       | p3 event                           | SQL  | t
              6 | 6       | p4 war columns                     | SQL  | t
              7 | 7       | p6 messaging economy               | SQL  | t
              8 | 8       | nation power                       | SQL  | t
              9 | 9       | users table                        | SQL  | t
             10 | 10      | general owner                      | SQL  | t
             11 | 10.5    | s1 probe ooo                       | SQL  | t
             12 | 11      | admin member fields                | SQL  | t
             13 | 12      | select npc token                   | SQL  | t
             14 | 13      | statistic table                    | SQL  | t
             15 | 14      | server status                      | SQL  | t
             16 | 15      | inheritance kv table fix           | SQL  | t
             17 | 16      | add politics charm                 | SQL  | t
             18 | 17      | city disaster state                | SQL  | t
             19 | 18      | board author icon                  | SQL  | t
             20 | 19      | yearbook global logs               | SQL  | t
             21 | 20      | inheritance log date               | SQL  | t
             22 | 21      | world state current phase          | SQL  | t
             23 | 22      | log entry current phase            | SQL  | t
             24 | 23      | select pool                        | SQL  | t
             25 | 24      | city battle dead                   | SQL  | t
             26 | 25      | general access log                 | SQL  | t
             27 | 26      | npc lifecycle phase units          | JDBC | t
             28 | 27      | unification emperior               | SQL  | t
             29 | 28      | yearbook server id insert contract | SQL  | t
             30 | 29      | log entry year month index         | SQL  | t
             31 | 30      | profile icon changed at            | SQL  | t
             32 | 31      | world scope expand                 | SQL  | t
             33 | 32      | complete world scope expand        | SQL  | t
             34 | 33      | world version writer fence         | SQL  | t
             35 | 34      | command inbox                      | SQL  | t
             36 | 35      | command result outbox              | SQL  | t
             37 | 36      | diplomacy casualties               | SQL  | t
             38 | 37      | general owner claim request        | SQL  | t
             39 | 38      | rtk14 npc lifecycle repair         | JDBC | t
             40 | 902     | s1 probe sibling                   | SQL  | t
(40 rows)

 Schema |       Name       | Type  | Owner
--------+------------------+-------+-------
 public | s1_probe_ooo     | table | sammo
 public | s1_probe_sibling | table | sammo
(2 rows)
```

**v1 38개 + v2 프로브 2개 전부 `success = t`.** V26·V38 Kotlin(`JDBC`) 마이그레이션도 유실 없음.

**판정: 형제 classpath 경로는 양방향으로 정확히 동작한다** — v1은 못 보고, v2는 v1+v2 둘 다 본다.

## U3 — classpath vs filesystem, 컨테이너 도달성 **classpath 형제 경로 채택**

v2 리소스가 빌드된 jar에 실제로 들어갔는지 직접 확인했다. 마이그레이션은 부트 jar 루트가 아니라
**중첩 `BOOT-INF/lib/infra-0.0.1-SNAPSHOT.jar`** 안에 있다.

```bash
unzip -p game-engine-0.0.1-SNAPSHOT.jar 'BOOT-INF/lib/infra-0.0.1-SNAPSHOT.jar' > infra.jar
unzip -l infra.jar | grep -E 'db/migration/v2|db/migration_v2'
```

Run 1 빌드(하위+형제 둘 다):

```text
        0  08-08-2026 09:09   db/migration/v2/
       50  08-08-2026 09:09   db/migration/v2/V901__s1_probe_nested.sql
        0  08-08-2026 09:09   db/migration_v2/
       51  08-08-2026 09:09   db/migration_v2/V902__s1_probe_sibling.sql
```

Run 2·3 빌드(형제만):

```text
        0  08-08-2026 09:12   db/migration_v2/
       51  08-08-2026 09:12   db/migration_v2/V902__s1_probe_sibling.sql
       47  08-08-2026 09:12   db/migration_v2/V10_5__s1_probe_ooo.sql
```

`infra/src/main/resources/db/migration_v2/**` 는 표준 Gradle 리소스 처리로 **추가 빌드 설정 없이**
`infra` jar에 구워지고, Run 3에서 `classpath:db/migration_v2`로 실제 적용됐다.

**판정: classpath 형제 경로를 택한다.**
- 볼륨 마운트·이미지 베이크·Dockerfile 수정 **불필요** — S0의 미측정 이월 항목(컨테이너에서
  `filesystem:` 경로 실재성)이 **소멸**한다.
- v1과 동일한 아티팩트 유통 경로를 타므로 "이미지에는 있는데 마운트가 빠졌다" 류의 배포 사고면이 없다.
- `filesystem:`도 동작은 하지만(S0 Run A, 본 문서 Run 4) 컨테이너 안 경로 보장이라는 숙제를
  남기므로 채택하지 않는다.

## U4 — 버전 번호 정책 (**선택지 제시, 확정은 사람 몫**)

### 실측 사실

**(a) 히스토리 테이블은 단일 공유 `public.flyway_schema_history`다.** location을 늘려도 별도
테이블이 생기지 않는다 — Run 3에서 v1 38행과 v2 2행이 **같은 테이블에 `installed_rank` 연속으로**
기록됐고(위 40행 덤프), 버전 순서대로 **v1 사이에 끼어 정렬**됐다(`10 → 10.5 → 11`).

**(b) 같은 DB를 공유할 때 out-of-order 버전은 fail-closed로 부팅을 깨뜨린다.**
Run 4 — 이미 V38까지 적용된 `sammo_v1b`에 v2 location(V10_5 포함)을 붙여 부팅:

```text
Detected resolved migration not applied to database: 10.5.
ERROR o.s.boot.SpringApplication : Application run failed
... BeanCreationException: Error creating bean with name 'flywayInitializer' ...:
    Validate failed: Migrations have failed validation
Caused by: org.flywaydb.core.api.exception.FlywayValidateException: Validate failed: Migrations have failed validation
```

```text
 count
-------
    38
(1 row)

Did not find any relation named "s1_probe*".
```

DB 무변경(38행 유지), 프로브 테이블 0개. **조용히 깨지지 않는다.**

**(c) 기존 최고 버전보다 큰 번호는 정상 append 된다.**
같은 `sammo_v1b`에 V902만(`filesystem:`) 붙여 부팅:

```text
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "902 - s1 probe sibling"
o.f.core.internal.command.DbMigrate : Successfully applied 1 migration to schema "public", now at version v902 (execution time 00:00.021s)
o.engine.GameEngineApplicationKt    : Started GameEngineApplicationKt in 16.3 seconds
```

```text
 installed_rank | version |         description
----------------+---------+-----------------------------
             39 | 902     | s1 probe sibling
             38 | 38      | rtk14 npc lifecycle repair
```

**(d) ⚠️ 역방향은 조용히 통과한다 — 유일한 silent 경로.**
V902가 기록된 `sammo_v1b`에 **v1 프로세스**(env 미설정)를 붙이면 경고만 남기고 정상 부팅한다:

```text
WARN o.f.core.internal.command.DbMigrate : Schema "public" has a version (902) that is newer than the latest available migration (38) !
INFO o.engine.GameEngineApplicationKt    : Started GameEngineApplicationKt in 16.844 seconds
```

`ERROR` 0건. v1 스택이 v2 마이그레이션이 적용된 DB 위에서 **아무 저항 없이 뜬다** — v2 스키마가
production DB에 새어 들어갔을 때 부팅이 이를 잡아주지 못한다는 뜻이다. (DB 분리가 1차 방어선이고
Flyway는 이 방향으로는 게이트가 아니다.)

### 선택지

| # | 정책 | 장점 | 위험 | 실측 근거 |
|---|---|---|---|---|
| **P1** | **DB 분리 + 고번호 대역**(예: V900~). v2 스택은 `GAME_DATABASE_URL`이 v1과 다르고(계획 S5가 이미 요구), v2 버전은 V900대 사용 | 히스토리 충돌 구조적으로 불가. 실수로 같은 DB를 가리켜도 (c)처럼 append 되어 부팅은 유지 | v1이 V900대에 도달하면 충돌(현실적으로 먼 미래). (d)의 silent 경로는 남음 | (a)(c)(d) |
| **P2** | DB 분리 + v1과 같은 번호 공간에서 이어붙이기(V39~) | 번호 체계 단일, 특별 규칙 없음 | v1이 V39를 쓰는 순간 **같은 버전 두 개** — 같은 DB를 가리키면 checksum/중복 충돌. v1 개발 속도와 경합 | (a)(b) |
| **P3** | 별도 히스토리 테이블(`spring.flyway.table` 오버라이드, 미측정) | 히스토리 자체가 분리 | **본 S1에서 미측정.** env 하나가 더 늘고, v1 location을 함께 나열하는 구조와 상호작용 불명(v1 38개가 v2 테이블에 재적용될 소지) | 없음 — **UNKNOWN** |

**권고(구속력 없음): P1.** DB 분리는 계획 §3 S5가 이미 `GAME_DATABASE_URL` 명시 주입으로 요구하고
있어 추가 비용이 0이고, 고번호 대역은 (c)로 실측된 안전한 append 성질을 유지한다. P3는 측정 전이라
근거가 없다.

---

## UNKNOWN (측정하지 않음 — 정직하게 남김)

1. **P3(`spring.flyway.table` 분리)의 실제 거동.** env 이름·상호작용 모두 미측정.
2. **game-api에서의 동작.** S0와 동일하게 game-engine만 측정했다. `application.yml:14`가 동일하고
   같은 Spring Boot 메커니즘이라 동일할 것으로 예상되나 **실측하지 않았다.**
3. **`docker compose` env 전달 경로.** classpath 채택으로 마운트 문제는 사라졌지만, compose가
   `SPRING_FLYWAY_LOCATIONS`를 컨테이너에 실제로 전달하는지는 v2 스택 파일(S5)이 생긴 뒤 측정 대상이다.
4. **v1 location 누락 회귀 가드.** S0가 fail-closed임을 보였으나, env 문자열이 한쪽만 갖게 되는
   회귀를 막는 자동 가드는 아직 없다(S5/S6 몫).
5. **실제 v2 마이그레이션 내용.** 본 S1은 프로브만 썼다. `v2_city_ledger` 등 실제 스키마는
   OPENSAM-150 소관이며 지금 만들면 날조다.

## 정리 (측정 후) / deletion authorization boundary

이전 measurement write-up은 scratch containers/worktree가 정리됐다고 기록하지만, 이 artifact에는
별도의 target-specific deletion approval record가 없다. 따라서 아래 destructive cleanup은 **BLOCKED**다:
명시적 별도 삭제 승인 없이는 실행·반복·재현 명령으로 사용하면 안 된다.

```text
# BLOCKED — do not run without separately approved exact targets
# pkill -f game-engine-0.0.1-SNAPSHOT.jar
# docker rm -f s1pg s1redis
# git worktree remove --force <scratch>/wt
```

프로브 마이그레이션이 당시 격리 worktree에만 있었다는 것은 historical inventory이며, 위 문장을
current deletion authorization으로 해석하지 않는다. 리포에 남는 intended artifact는 이 문서와
`infra/src/main/resources/db/migration_v2/README.md`다.

## 재현 명령

```bash
S=<scratch>
git worktree add $S/wt HEAD --detach

mkdir -p $S/wt/infra/src/main/resources/db/migration/v2 $S/wt/infra/src/main/resources/db/migration_v2
printf 'CREATE TABLE s1_probe_nested(id int primary key);\n'  > $S/wt/infra/src/main/resources/db/migration/v2/V901__s1_probe_nested.sql
printf 'CREATE TABLE s1_probe_sibling(id int primary key);\n' > $S/wt/infra/src/main/resources/db/migration_v2/V902__s1_probe_sibling.sql

docker run -d --name s1pg -e POSTGRES_DB=sammo_v1 -e POSTGRES_USER=sammo \
  -e POSTGRES_PASSWORD=sammo -p 55433:5432 postgres:16-alpine
docker run -d --name s1redis -p 56380:6379 redis:7-alpine
docker exec s1pg psql -U sammo -d postgres -c "CREATE DATABASE sammo_v2"
docker exec s1pg psql -U sammo -d postgres -c "CREATE DATABASE sammo_v1b"

(cd $S/wt && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:bootJar)

# U1 — v1 컨텍스트, 하위 경로 프로브가 딸려오는지
env -i PATH=/usr/bin:/bin JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  GAME_DATABASE_URL="jdbc:postgresql://127.0.0.1:55433/sammo_v1" \
  GAME_DB_USER=sammo GAME_DB_PASSWORD=sammo REDIS_HOST=127.0.0.1 REDIS_PORT=56380 \
  OPENSAMGUK_WORLD_ID=1 SCENARIO_SEED_ENABLED=false GAME_ENGINE_PORT=58083 \
  $(/usr/libexec/java_home -v 21)/bin/java -jar \
  $S/wt/app/game-engine/build/libs/game-engine-0.0.1-SNAPSHOT.jar
docker exec s1pg psql -U sammo -d sammo_v1 \
  -c "select installed_rank, version, description, type, success from flyway_schema_history order by installed_rank;"

# U2 — destructive removal is BLOCKED unless separately approved for this exact scratch target.
# Do not copy/run without that approval:
# rm -rf $S/wt/infra/src/main/resources/db/migration/v2
# After an approved scratch cleanup, rebuild and compare v1/v2 contexts.
printf 'CREATE TABLE s1_probe_ooo(id int primary key);\n' > $S/wt/infra/src/main/resources/db/migration_v2/V10_5__s1_probe_ooo.sql
(cd $S/wt && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:bootJar)
#   → sammo_v1b 에 env 미설정으로 1회, sammo_v2 에
#     SPRING_FLYWAY_LOCATIONS="classpath:db/migration,classpath:db/migration_v2" 로 1회

# U3
unzip -p $S/wt/app/game-engine/build/libs/game-engine-0.0.1-SNAPSHOT.jar \
  'BOOT-INF/lib/infra-0.0.1-SNAPSHOT.jar' > $S/infra.jar
unzip -l $S/infra.jar | grep -E 'db/migration/v2|db/migration_v2'

# U4 — sammo_v1b(V38 상태)에 v2 location 부착 / 고번호 단독 부착 / 그 뒤 v1 재부팅
```

## 다음 단계에 주는 결론

- 0A-c의 v2 location은 **`classpath:db/migration_v2`** 로 확정. 리포 경로
  `infra/src/main/resources/db/migration_v2/`. 신규 디렉터리이므로 게이트 ⑤(`--diff-filter=MD`) 무관.
- v2 스택 env는 **반드시** `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/migration_v2`
  (S0 치환 semantics).
- **하위 경로(`db/migration/v2`)는 금지.** v1 프로세스가 삼킨다(U1 실측).
- S5의 `GAME_DATABASE_URL` 분리는 U4 (d)의 silent 경로 때문에 **더 강한 요구사항이 된다** —
  Flyway는 v1 스택이 v2 스키마 DB에 붙는 것을 막아주지 않는다(WARN만).
- 버전 번호 정책은 **미확정**. §U4의 P1/P2/P3 중 사람이 택한다.

## PHP golden scope boundary

This Flyway-location measurement is isolation/build-only evidence. It did not run and does not claim a PHP
golden capture/draw-for-draw replay: T1/parity behavior is unchanged. A3 records only that scope/inventory;
it is not a replay pass and does not replace the current backend gate. A future ticket that changes T1/parity
must run the relevant PHP oracle capture/replay.
