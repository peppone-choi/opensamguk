# `db/migration_v2` — v2 전용 Flyway location

OPENSAM-35 (0A-c) 산출물. **이 디렉터리는 v1 스택에 절대 보이지 않아야 한다.**
아래 규약은 전부 실측 근거가 있다 — 근거: `docs/loops/opensam-35-v2-0a-2026-08-08/s1-flyway-location-measurement.md`.

## 1. 왜 `db/migration/v2/`가 아니라 형제 경로인가

**Flyway는 location을 재귀 스캔한다.** `classpath:db/migration` 하나만 지정한 v1 프로세스가
`db/migration/v2/` 안의 마이그레이션까지 적용해버린다(S1 U1 실측: v1 `flyway_schema_history`에
하위 프로브 V901이 `success=t`로 기록됨).

⇒ v2 location을 v1 location의 **하위에 두면 격리가 정확히 반대로 깨진다.**
반드시 **형제 경로**여야 한다. 이 디렉터리를 `db/migration/` 밑으로 옮기지 마라.

## 2. env에 v1 location을 반드시 포함해야 하는 이유

`SPRING_FLYWAY_LOCATIONS` 오버라이드는 **추가가 아니라 치환**이다(S0 실측).
리스트 프로퍼티 전체가 env 값으로 교체되므로 v1 location을 빼면 V1~V38이 통째로 사라진다.

v2 스택 env는 **항상 두 location을 함께** 넣는다:

```
SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/migration_v2
```

v1 location을 빠뜨리면 JPA `ddl-auto: validate`가 `missing table [banned_member]`로
**fail-closed 부팅 실패**시킨다 — 조용히 깨지지는 않지만, 그렇다고 정상 동작도 아니다.

v1 스택은 `SPRING_FLYWAY_LOCATIONS`를 **설정하지 않는다**. `application.yml`의 기본값
`classpath:db/migration`이 그대로 유지되고, 이 디렉터리는 보이지 않는다(S1 U2 실측: v1 38행, v2 행 0건).

## 3. `classpath:`인 이유

이 디렉터리는 표준 Gradle 리소스 처리로 `infra` jar에 그대로 구워진다
(S1 U3 실측: `BOOT-INF/lib/infra-*.jar` 안에 `db/migration_v2/` 실재 확인).
따라서 **볼륨 마운트도, 이미지 베이크도, Dockerfile 수정도 필요 없다.**
`filesystem:` 경로는 컨테이너 내부 경로 실재성을 따로 보장해야 하므로 쓰지 않는다.

## 4. 버전·월드·롤백 규약

v2 마이그레이션은 v1의 최고 버전보다 높은 **`V900+__snake_case.sql`** 이름만 사용한다. `V900`은
`app/game-engine/src/test/resources/db/migration_v2/`의 isolation probe에 예약되어 있고, 실제 product leaf는
그 probe와 충돌하지 않는 다음 사용 가능 번호를 사용한다. 이 대역은 같은 `public.flyway_schema_history`에서
v1 이력 뒤에 append되므로, 이미 적용된 `V*.sql`은 절대 수정하거나 재사용하지 않는다.

v2 product leaf는 반드시 world-owned다. 새 행 테이블은 최소
`world_id integer NOT NULL REFERENCES world_state(id)`를 선언하고 primary/unique key와 조회 인덱스를
world scope로 구성한다. OPENSAM-43의 V900 probe도 같은 `world_id` foreign key와 world-scoped primary key로
이 계약을 실측한다.

Flyway는 down migration을 실행하지 않는다. 각 v2 SQL 파일의 첫 줄은 다음 계약 선언이어야 한다.

```sql
-- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
```

rollback이 필요하면 `flyway_schema_history`를 지우거나 과거 SQL을 고치지 말고, 새 `V900+` compensating
migration을 추가한다. 이 선언은 **물리적인 첫 줄과 정확히 일치**해야 한다. BOM, 빈 줄, 다른 주석, 또는
다른 텍스트가 앞에 오면 안 된다. `V2MigrationConventionTest`는 sibling directory와 test probe 모두에서
파일명과 이 첫 줄을 검사하고, `--`/`/* ... */` SQL 주석을 제거한 뒤에만 `world_id NOT NULL` 선언을 검사한다.
따라서 주석에만 적힌 `world_id integer NOT NULL`은 통과하지 못한다.

## 5. 현재 상태

production `db/migration_v2/`에는 아직 SQL이 없다. `V900__v2_sandbox_probe.sql`은 engine test resources에만
있으며, v1-only Flyway location은 이를 보지 못하고 explicit sibling location만 적용함을
실제 Spring Boot/Testcontainers context에서 검증한다. 기본 v1 context는 application.yml의
`classpath:db/migration`만 해석해 V900 history/table이 없고, `v2-sandbox` profile과 literal
`V2_ENABLED=true`, `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/migration_v2` 환경 source는
두 location을 해석해 V900 history/table을 만든다. 적용된 모든 v2 migration의 `CREATE TABLE`은 런타임에
`world_id NOT NULL`, world-scoped primary/unique key, 그리고 `world_state` foreign key를 PostgreSQL catalog로
검사한다. source SQL을 고유하게 찾을 수 없거나 지원하지 않는 `CREATE TABLE` 형식이면 이 검사는 fail-closed다.
실제 v2 schema/product leaf는 **OPENSAM-150** 소관이다.
