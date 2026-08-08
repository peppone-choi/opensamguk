# U12 실측 — `SPRING_FLYWAY_LOCATIONS` env 오버라이드

- 티켓: OPENSAM-35 / 계획 §3 S0
- 측정일: 2026-08-08
- 측정자: agent `op35-s0-u12`
- 리포 상태: `main` @ `fb90eac1` (측정용 detached worktree에서 빌드)

## 판정

**PASS.** `SPRING_FLYWAY_LOCATIONS` 환경변수는 game-engine 부팅 시 `application.yml:14`의
하드코딩 `locations: classpath:db/migration`을 오버라이드하며, 지정된 `filesystem:` location의
마이그레이션이 실제로 적용되고 `flyway_schema_history`에 `success = t`로 기록된다.

**단, 오버라이드는 "추가"가 아니라 "치환"이다.** 리스트 프로퍼티 전체가 env 값으로 교체되므로
v1 location(`classpath:db/migration`)을 env 값에 **명시적으로 포함해야만** V1~V38이 함께 적용된다.
누락하면 v1이 통째로 사라진다(§ Run B 실증).

## 방법

**방법 A** (계획서 권장 = 진짜 OS 환경변수 경로). Testcontainers/`@SpringBootTest(properties=)`
우회 없이 실제 `java -jar` 프로세스에 셸 환경변수로 주입했다.

리포 워킹 트리에서 동시 gradle 빌드가 `app/game-engine/build/`를 클로버링해 3회 연속 실패했으므로,
스크래치패드에 `git worktree add --detach HEAD` 로 격리 트리를 만들어 거기서 bootJar를 빌드했다
(리포 추적 파일 변경 0, 측정 후 worktree 제거).

### 프로브 마이그레이션

`<scratch>/v2mig/V900__u12_probe.sql`:

```sql
CREATE TABLE u12_probe(id int primary key);
```

### 인프라

```bash
docker run -d --name u12pg -e POSTGRES_DB=sammo -e POSTGRES_USER=sammo \
  -e POSTGRES_PASSWORD=sammo -p 55432:5432 postgres:16-alpine   # docker-compose.yml:26과 동일 이미지
docker run -d --name u12redis -p 56379:6379 redis:7-alpine       # docker-compose.yml:47과 동일 이미지
docker exec u12pg psql -U sammo -d postgres -c "CREATE DATABASE sammo2"
```

### 빌드

```bash
git worktree add <scratch>/wt HEAD --detach
cd <scratch>/wt && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:bootJar
# BUILD SUCCESSFUL in 20s
```

### Run A — `classpath` + `filesystem` (핵심 측정)

```bash
env -i PATH=/usr/bin:/bin JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  GAME_DATABASE_URL="jdbc:postgresql://127.0.0.1:55432/sammo" \
  GAME_DB_USER=sammo GAME_DB_PASSWORD=sammo \
  REDIS_HOST=127.0.0.1 REDIS_PORT=56379 \
  OPENSAMGUK_WORLD_ID=1 SCENARIO_SEED_ENABLED=false GAME_ENGINE_PORT=58082 \
  SPRING_FLYWAY_LOCATIONS="classpath:db/migration,filesystem:<scratch>/v2mig" \
  $JAVA_HOME/bin/java -jar <scratch>/wt/app/game-engine/build/libs/game-engine-0.0.1-SNAPSHOT.jar
```

`env -i` 로 셸 환경을 비우고 필요한 변수만 주입했다 — 다른 경로(`application.yml`, 시스템 프로퍼티,
`.env`)로 값이 새어 들어올 여지가 없다.

### Run B — `filesystem` 단독 (치환 vs 추가 판별)

Run A와 동일하되 DB는 신선한 `sammo2`, `SPRING_FLYWAY_LOCATIONS="filesystem:<scratch>/v2mig"`.

## 원시 출력

### Run A — 부팅 로그 발췌

```
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "1 - baseline"
...
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "38 - rtk14 npc lifecycle repair"
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "900 - u12 probe"
o.f.core.internal.command.DbMigrate : Successfully applied 39 migrations to schema "public", now at version v900 (execution time 00:02.725s)
o.engine.GameEngineApplicationKt     : Started GameEngineApplicationKt in 23.764 seconds (process running for 26.143)
```

### Run A — `flyway_schema_history` 전문

```
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
             11 | 11      | admin member fields                | SQL  | t
             12 | 12      | select npc token                   | SQL  | t
             13 | 13      | statistic table                    | SQL  | t
             14 | 14      | server status                      | SQL  | t
             15 | 15      | inheritance kv table fix           | SQL  | t
             16 | 16      | add politics charm                 | SQL  | t
             17 | 17      | city disaster state                | SQL  | t
             18 | 18      | board author icon                  | SQL  | t
             19 | 19      | yearbook global logs               | SQL  | t
             20 | 20      | inheritance log date               | SQL  | t
             21 | 21      | world state current phase          | SQL  | t
             22 | 22      | log entry current phase            | SQL  | t
             23 | 23      | select pool                        | SQL  | t
             24 | 24      | city battle dead                   | SQL  | t
             25 | 25      | general access log                 | SQL  | t
             26 | 26      | npc lifecycle phase units          | JDBC | t
             27 | 27      | unification emperior               | SQL  | t
             28 | 28      | yearbook server id insert contract | SQL  | t
             29 | 29      | log entry year month index         | SQL  | t
             30 | 30      | profile icon changed at            | SQL  | t
             31 | 31      | world scope expand                 | SQL  | t
             32 | 32      | complete world scope expand        | SQL  | t
             33 | 33      | world version writer fence         | SQL  | t
             34 | 34      | command inbox                      | SQL  | t
             35 | 35      | command result outbox              | SQL  | t
             36 | 36      | diplomacy casualties               | SQL  | t
             37 | 37      | general owner claim request        | SQL  | t
             38 | 38      | rtk14 npc lifecycle repair         | JDBC | t
             39 | 900     | u12 probe                          | SQL  | t
(39 rows)
```

프로브 테이블 실재 확인:

```
             Table "public.u12_probe"
 Column |  Type   | Collation | Nullable | Default
--------+---------+-----------+----------+---------
 id     | integer |           | not null |
Indexes:
    "u12_probe_pkey" PRIMARY KEY, btree (id)
```

### Run B — `filesystem` 단독

```
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "900 - u12 probe"
o.f.core.internal.command.DbMigrate : Successfully applied 1 migration to schema "public", now at version v900 (execution time 00:00.018s)
ERROR ... Failed to initialize JPA EntityManagerFactory: [PersistenceUnit: default]
  ... SchemaManagementException: Schema-validation: missing table [banned_member]
```

```
 installed_rank | version | description | success
----------------+---------+-------------+---------
              1 | 900     | u12 probe   | t
(1 row)
```

부팅 실패(`ddl-auto: validate`가 v1 테이블 부재를 잡음). **fail-closed** — 조용히 깨지지 않는다.

## v1 마이그레이션 동반 적용 여부 (별도 항목)

- **env 값에 `classpath:db/migration`을 포함한 경우(Run A): 동반 적용됨.**
  V1~V38이 전부 `success = t`로 기록됐고, 최고 버전 V38(`.kt`, `type = JDBC`)도 적용됐다
  (Kotlin 마이그레이션이 filesystem location 추가로 인해 유실되지 않음).
- **포함하지 않은 경우(Run B): 적용되지 않음.** `flyway_schema_history` 1행뿐.

⇒ 오버라이드는 **치환**이다. 0A-c 설계는 v2 스택 env에 **반드시 두 location을 함께** 넣어야 하고,
이 문자열이 한쪽만 갖게 되는 회귀를 막는 가드가 필요하다(누락 시 v1 소실 → JPA validate에서 부팅 실패).

## 이 측정이 증명하는 것 / 증명하지 못하는 것

**증명한다**
1. 실제 OS 환경변수 → Spring relaxed binding → `spring.flyway.locations` 오버라이드가 game-engine
   부팅 경로에서 동작한다 (`application.yml` 수정 0, 즉 계획 게이트 ⑤ 유지 가능).
2. `filesystem:` prefix location의 `V***__*.sql`이 적용되고 이력에 기록된다.
3. classpath location을 함께 나열하면 V1~V38(`.sql` + `.kt` 양쪽)이 그대로 적용된다.
4. 리스트 프로퍼티가 병합이 아니라 치환된다.
5. v1 location 누락은 조용히 통과하지 않고 부팅을 깨뜨린다(fail-closed).

**증명하지 못한다**
1. **game-api**(`app/game-api/.../application.yml:14`)에서의 동작 — 측정 대상은 game-engine 뿐이다.
   동일 Spring Boot 메커니즘이므로 동일할 것으로 예상되나 실측하지 않았다.
2. **compose/GHCR 이미지 경로**에서의 주입 — `docker compose` env 전달과 컨테이너 내부
   filesystem location 마운트는 미검증. `filesystem:` 경로는 **컨테이너 안의 경로**여야 하므로
   v2 마이그레이션 디렉터리를 이미지에 굽거나 볼륨 마운트하는 별도 결정이 남는다.
   (컨테이너에서는 `classpath:db/migration,classpath:db/migration/v2` 형태가 더 단순할 수 있으나 미측정.)
3. **v1 프로세스에서 v2 마이그레이션이 적용되지 않음** — 이는 S1의 판정 항목이며 여기서는
   v1 전용 컨텍스트를 띄우지 않았다.
4. 두 스택이 **같은 DB**를 공유할 때의 `flyway_schema_history` 충돌 거동 — 본 측정은 스택당 별도 DB.
5. v2 마이그레이션 **버전 번호 정책**(V900 대역 vs 별도 스키마/히스토리 테이블) — 프로브는 충돌 회피용
   임의값 V900이며 정책 결정이 아니다.

## 재현 명령

```bash
S=<scratch>
mkdir -p $S/v2mig
printf 'CREATE TABLE u12_probe(id int primary key);\n' > $S/v2mig/V900__u12_probe.sql

docker run -d --name u12pg -e POSTGRES_DB=sammo -e POSTGRES_USER=sammo \
  -e POSTGRES_PASSWORD=sammo -p 55432:5432 postgres:16-alpine
docker run -d --name u12redis -p 56379:6379 redis:7-alpine

git worktree add $S/wt HEAD --detach
(cd $S/wt && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:bootJar)

env -i PATH=/usr/bin:/bin JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  GAME_DATABASE_URL="jdbc:postgresql://127.0.0.1:55432/sammo" \
  GAME_DB_USER=sammo GAME_DB_PASSWORD=sammo \
  REDIS_HOST=127.0.0.1 REDIS_PORT=56379 \
  OPENSAMGUK_WORLD_ID=1 SCENARIO_SEED_ENABLED=false GAME_ENGINE_PORT=58082 \
  SPRING_FLYWAY_LOCATIONS="classpath:db/migration,filesystem:$S/v2mig" \
  $(/usr/libexec/java_home -v 21)/bin/java -jar \
  $S/wt/app/game-engine/build/libs/game-engine-0.0.1-SNAPSHOT.jar

docker exec u12pg psql -U sammo -d sammo \
  -c "select installed_rank, version, description, type, success from flyway_schema_history order by installed_rank;"

# 정리
pkill -f game-engine-0.0.1-SNAPSHOT.jar
docker rm -f u12pg u12redis
git worktree remove --force $S/wt
```

## 다음 단계에 주는 결론

계획 §3 S0의 PASS 분기 그대로: **0A-c는 env 오버라이드 경로로 간다.** `application.yml` 무수정
(게이트 ⑤ 유지). 단 §3 S1 설계에 아래 두 조건을 추가해야 한다.

- v2 스택 env의 `SPRING_FLYWAY_LOCATIONS`는 **v1 location을 반드시 포함**한다(치환 semantics).
- 컨테이너 배포에서 `filesystem:` 경로가 실재하는지(이미지 굽기/볼륨 마운트) 별도 확정 — 미측정 항목.
