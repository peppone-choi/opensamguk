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

## 4. 버전 번호 — **미확정 (사람 결정 대기)**

실측된 제약:

- v1과 v2는 **같은 `public.flyway_schema_history` 테이블**을 공유한다. 별도 테이블이 생기지 않고,
  버전 번호 순서대로 v1 행 사이에 끼어 정렬된다.
- 이미 마이그레이션된 DB에 **기존 최고 버전보다 낮은** v2 버전을 붙이면
  `Validate failed: Detected resolved migration not applied to database`로 **부팅 실패**(fail-closed, DB 무변경).
- **높은** 버전은 정상 append 된다.
- ⚠️ 반대로, v2 행이 있는 DB에 v1 스택이 붙으면 **WARN만 남기고 정상 부팅한다**
  (`Schema "public" has a version (902) that is newer than the latest available migration (38) !`).
  Flyway는 이 방향으로 게이트가 아니다 — **DB 분리(`GAME_DATABASE_URL`)가 1차 방어선이다.**

선택지 P1(DB 분리 + 고번호 대역 V900~) / P2(V39~ 이어붙이기) / P3(`spring.flyway.table` 분리, **미측정**)는
측정 문서 §U4 참조. **확정 전까지 이 디렉터리에 마이그레이션을 추가하지 마라.**

## 5. 현재 상태

**빈 디렉터리다(README만).** 실제 v2 마이그레이션(`v2_city_ledger` 등)은 **OPENSAM-150(R1)** 소관이며,
버전 번호 정책 확정 후에 추가한다.
