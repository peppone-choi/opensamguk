# 게임 사건 모델 구현 기록

기준: ADR-LITE-069와 `docs/superpowers/specs/2026-09-25-game-log-model.md`.

첫 PR 범위: 중앙 사건 종류·구조화 refs·시각·수신 범위·공개 상태·중복 키의 순수 모델과 검증, 새 전진 DB 계약. 공유 턴·flush 쓰기 경로는 파일 소유 합의 뒤 별도 PR에서 연결한다.

## 첫 PR 계약

- 기존 `RecordKind` 31종은 전부 `EventKind`로 분류한다. 기존 `county.captured`/`county.lost`는 작성 불가 레거시 입력 종류로만 대응시키고, 새 공개 점령은 `county.ownerChanged` 한 건이다.
- `GameEvent`는 kind별 허용 audience·refs·facts를 검사한다. 참조는 식별자 타입만 받으며 이름·문장·색 태그는 저장할 필드가 없다. `EventPayloadCodec`의 JSONB wire는 역할명을 키로 하는 식별자/작은 값 객체다. 사용자가 작은 구조화 facts 저장을 추가로 확정했다.
- `income.monthly`의 숫자 역할은 `COUNTIES/MONEY/GRAIN/IRON/TIMBER/HORSES`, `yuedan.assessed`는 `RENOWN_BEFORE/RENOWN_AFTER/RENOWN_CHANGE`로 뜻을 고정한다. 감소는 부호 있는 변화량으로 저장한다. 임의의 `AMOUNT` 값은 쓰지 않는다.
- `RETINUE`/`COURT` 수신자 ID는 사건 시점 스냅샷을 저장한다. 조회 때는 현재 권한을 다시 검사해야 한다. `PUBLIC`만 즉시 `PUBLISHED`이고, 지연 공개 필드는 스키마에 예약하되 v1에서 NULL만 허용한다.
- V65는 `game_event`를 신설한다. `(world_id,event_key)` 및 `(world_id,occurred_year,occurred_month,occurred_phase,occurred_ordinal)` 중복을 막고 공개 사건의 종류·참조 역할을 제한한다. 기존 `log_entry`는 이 마이그레이션에서 복사하거나 삭제하지 않는다.
- 첫 PR은 아직 생산자·읽기 API를 연결하지 않는다. 새 월드 가드와 런타임 파일은 병행 작업의 소유 파일이므로 후속 PR에서 main 병합 뒤 연결한다. 운영 월드 리셋은 실행하지 않는다.

## 로컬 검증

- JDK 21 `:logic:compileKotlin` 성공, 최신 `GameEventTest` 6건 성공.
- `V65GameEventMigrationTest` 1건 성공: 실제 PostgreSQL 16에서 V64→V65 이행, 기존 `log_entry` 유지, 정상 수신 대상·공개 사건, 중복 키·순서·공개/수신 제약 이름, 인덱스 유효성을 확인했다. 첫 시도는 Docker 컨테이너 생성 HTTP 500으로 SQL 전 실패했으나 재시도에서 실행됐다.
