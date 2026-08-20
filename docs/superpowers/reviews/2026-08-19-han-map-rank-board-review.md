# OPENSAM-105 — 후한 군현 맵·3축 작위·게시판 닉네임/전콘 교차 비평

Scope: PR #449 의 두 커밋 — `e143e4f3`(han 맵·시나리오·3축 등급·병종 세트), `5a7e2dc9`(게시판 소프트딜리트 비노출·닉네임·전콘)
Verdict: **fix-required → 수정 완료 (아래 반영표 참조), 잔여는 backlog**

두 개의 독립 레인이 각각 공격했다. 한 레인은 **PHP 패러티**(che 실행 경로가 흔들렸는가),
다른 레인은 **게시판·인증·마이그레이션 안전성**을 봤다. 작성 레인(구현)과 분리된 컨텍스트에서
돌았고, 자기 승인은 없다.

---

## 1. 패러티 레인 — che 경로가 흔들렸는가

무발견으로 확인된 것(레인이 명시적으로 검증):

- `Math.round`/`kotlin.math.round`/`roundToInt`/`HALF_UP`/`HALF_EVEN` 추가 **0건**
- `java.util.Random`/`ThreadLocalRandom`/`kotlin.random` 추가 **0건**, `rng.` 호출 추가·이동·삭제 **0건**
- game-engine 내 `entityManager`/`repository.save`/`@Transactional` 추가 **0건** (one-daemon-write-rule 무위반)
- `golden/**`·`tools/php-golden/**` 변경 **0건** (골든 무편집)
- `UnitSetTable.BY_SET["che"]` 는 `LinkedHashMap(GameUnitConst.all())` 에서 나와 **삽입 순서가 종전과 동일** — AI 후보 열거 draw 순서 안전
- `foundAssaultCrewCost(mapName != "han") == 0` 이라 건국 돌파 블록이 che 에서 전부 항등
- `isFoundableCityLevel` 은 che levelMap 1..8 에서 PHP `in_array($city['level'], [5,6])` 와 완전 동치
- `gateKeys` 는 che 에서 항상 빈 집합 → 기존 `regionIdByName` 경로로 단락

### 지적 → 반영

| # | 지적 | 판정 | 조치 |
| --- | --- | --- | --- |
| P1 | `UnitCatalog.byId` 가 id<1000 에서 조용히 null — PHP `GameUnitConst::byID()` 는 예외. che_첩보·통계 경로에서 로그 바이트가 갈릴 수 있다 | 유효 | che 대역 throw 계약 복원 |
| P1 | che 시드 패러티 단언(`ScenarioImporterIT`·`ScenarioBootIT`)이 han 값으로 **대체**됨 — che 커버리지 소멸 | 유효 | che 시나리오 픽스처를 테스트 리소스로 되살려 **병행** 복구(han 단언은 존치) |
| P2 | `BattleSimPreview` 의 `require` 메시지가 PHP 문구에서 바뀜 | 유효 | che 대역 메시지 복원 |
| P2 | `CrewTypeWarModule` 에 `System.err.println` 부수효과 — 순수 logic 층 오염 | 유효 | 제거 |
| P2 | `Presets.constructableCity()/recruitableCity()` 의 `requires()` 가 PHP `REQ_VALUES` 보다 넓어짐 | 유효(판정 무변, 페이로드만 확대) | han 분기에서만 넓히도록 조건화 |
| P2 | han 게임 상수(FOUND_ASSAULT_RATIO 2.0, `level>=10` 건국 가능, 郡治 문턱 1/13/28)가 CLAUDE.md 의 sanctioned-divergence 절에 미등재 | 유효(문서) | **backlog** — ADR-LITE 등재 후 CLAUDE.md 반영 |
| P1 | che 를 가리키는 시나리오가 런타임에 0개가 됨(라이브에서 che 실행 경로 소멸) | 사실이나 **의도된 것** | 사용자 지시(2026-08-19, "che/miniche/miniche_b 은퇴"). che 는 **테스트 전용 패러티 픽스처**로 강등한다. 코드·골든은 존치 |

UNKNOWN(레인이 스스로 표시): `UpdateNationLevel.php` 원본 라인 대조는 코틀린 측에서만 확인했고 PHP 재대조 미실시.

---

## 2. 게시판·인증 레인

### 지적 → 반영

| # | 지적 | 판정 | 조치 |
| --- | --- | --- | --- |
| MAJOR | `includeDeleted=true` 가 무의미 — 어드민 테이블이 클라이언트에서 제목을 다시 가림 | 유효 | `BoardControlTable` 이 제목을 그대로 보여주고 삭제는 취소선으로 표시 |
| MAJOR | V42 중복 해소가 기존 값과 또 부딪히면 인덱스 생성 실패 → 게이트웨이 부팅 불가 | 유효 | 수렴 보장 루프로 재작성(1바퀴 `_<id>`, 이후 `user_<id>`, 10바퀴 초과 시 명시적 예외) |
| MAJOR | 백필을 먼저 해서 **사용자가 직접 정한 닉네임이 밀릴 수 있음** | 유효 | 백필 대상을 임시 테이블로 먼저 기록하고, 중복 시 백필된 쪽만 이동 |
| MAJOR | V42 마이그레이션 테스트 부재 | 유효 | `V42UsersNicknameMigrationTest` 신설(실 Postgres, 사전 상태 심고 결과 검증) |
| MAJOR | 중복 닉네임 경쟁 시 500 노출(`DataIntegrityViolationException` 핸들러 없음) | 유효 | 409 + 한글 메시지 핸들러 추가 |
| MINOR | `postResponse` 의 마스킹 분기가 죽은 코드 | 유효 | `mask`/`reveal`/`DELETED_*` 상수 제거 |
| MINOR | 삭제된 글 존재가 댓글 작성 409 로 새어나감 | 유효 | 404 로 통일 |
| MINOR | `updatePost`/`updatePin` 응답이 작성자 대신 호출자 기준으로 이름을 품 | 유효 | 글 작성자 id 로 해석 |
| MINOR | `@Size(min=2)` 가 trim 전 값을 봄 → 1자 닉네임 저장 가능 | 유효 | trim 후 길이 재검사 |
| MINOR | 유일 인덱스가 대소문자 구분 → 사칭 방지 미달 | 유효 | `ON users(lower(nickname))` + `existsByNicknameIgnoreCase` |
| MINOR | `left(nickname,40) || '_' || id` 가 VARCHAR(50) 초과 가능 | 유효 | 30자로 자름 |
| MINOR | 고아 작성자 테스트가 계정 삭제가 아니라 `authorAccountId=null` 을 테스트 | 유효 | 계정 행을 실제로 지우도록 수정 |
| MINOR | `portraitUrl` 의 `imageServer=0` 분기는 화이트리스트가 없고, 주석은 있다고 말함 | 유효(현재 도달 불가) | **backlog** — 주석/화이트리스트 정합화 |
| 확인 | 기존 테스트를 약화시키지 않았는가 | 무발견 | `board-detail-content.test.tsx` 의 img 단언은 아바타 `<img>` 도입으로 **의도를 좁힌** 것(비-아바타 img 0개 + script 0개는 그대로) |

### 게이트 판정으로 남긴 것(backlog)

- V42 이전에 발급된 JWT 에 `nickname` 클레임이 없을 때 game-api 경로 전수 확인(현재는 `username` 폴백 확인됨)
- `AdminSeeder` 가 시드하는 닉네임이 기존 사용자와 충돌하는 라이브 케이스
- 닉네임 중복 400 이 입력값을 되돌려주는 열거 오라클(아이디·이메일과 동일한 기존 패턴)

---

## 3. 게이트 증거

수정 후 재실행 결과는 PR 본문의 표를 정본으로 본다. 실행 명령:

```
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test :infra:test \
  :app:game-engine:test :app:game-api:test :app:gateway-api:test
cd web/gateway && npx vitest run && npx tsc --noEmit -p tsconfig.json
```

판정은 종료코드가 아니라 **출력 tail + `build/test-results/test/*.xml` 집계**로 한다.
