---
name: verify-command-parity
description: 레거시 PHP 93개 커맨드(55 장수 + 38 국가)가 백엔드/프론트엔드에 모두 구현되어 있는지 확인합니다. 커맨드 추가/수정 후 사용.
---

# Command Parity 검증

## Purpose

레거시 PHP 커맨드 카탈로그 대비 구현 상태를 추적합니다:

1. **장수 커맨드 존재** — 55개 장수 커맨드가 백엔드에 구현되어 있는지 확인
2. **국가 커맨드 존재** — 38개 국가 커맨드가 백엔드에 구현되어 있는지 확인
3. **프론트엔드 커맨드 UI** — 구현된 커맨드가 프론트엔드에서 선택/실행 가능한지 확인
4. **커맨드 레지스트리 정합** — 백엔드 커맨드 클래스가 레지스트리에 등록되어 있는지 확인
5. **docs 상태 동기화** — `legacy-commands.md`의 Ported/Planned 상태가 실제 구현과 일치하는지 확인

## When to Run

- 새로운 커맨드를 백엔드에 구현한 후
- 커맨드 레지스트리나 라우팅을 수정한 후
- 프론트엔드 커맨드 UI를 추가한 후
- 마일스톤 진행 상황을 점검할 때
- PR 전 패러티 상태를 확인할 때

## Related Files

| File                                                                  | Purpose                           |
| --------------------------------------------------------------------- | --------------------------------- |
| `docs/architecture/legacy-commands.md`                                | 레거시 커맨드 카탈로그 (93개)     |
| `legacy/hwe/sammo/Command/General/*.php`                              | 레거시 장수 커맨드 PHP 소스       |
| `legacy/hwe/sammo/Command/Nation/*.php`                               | 레거시 국가 커맨드 PHP 소스       |
| `core2026/packages/logic/src/command/general/*.ts`                    | core2026 장수 커맨드 (TS 참조)    |
| `core2026/packages/logic/src/command/nation/*.ts`                     | core2026 국가 커맨드 (TS 참조)    |
| `core2026/packages/logic/src/command/CommandRegistry.ts`              | core2026 커맨드 레지스트리 (참조) |
| `backend/src/main/kotlin/com/opensam/command/general/*.kt`            | 백엔드 장수 커맨드 (구현 대상)    |
| `backend/src/main/kotlin/com/opensam/command/nation/*.kt`             | 백엔드 국가 커맨드 (구현 대상)    |
| `backend/src/main/kotlin/com/opensam/command/CommandExecutor.kt`      | 커맨드 실행기 (라우팅/디스패치)   |
| `backend/src/main/kotlin/com/opensam/controller/CommandController.kt` | 커맨드 REST 엔드포인트            |
| `backend/src/main/kotlin/com/opensam/service/CommandService.kt`       | 커맨드 서비스 레이어              |
| `backend/src/main/kotlin/com/opensam/dto/CommandDtos.kt`              | 커맨드 요청/응답 DTO              |
| `frontend/src/components/command/*.tsx`                               | 프론트엔드 커맨드 UI (구현 대상)  |

## Workflow

### Step 1: 레거시 커맨드 카탈로그 로드

**파일:** `docs/architecture/legacy-commands.md`

**검사:** 레거시 커맨드 목록을 파싱하여 기준 목록을 구축합니다.

```bash
# 장수 커맨드 키 추출
grep -E '^\| `(che_|cr_|휴식)' docs/architecture/legacy-commands.md | head -60
```

**기준:** 장수 55개, 국가 38개 (총 93개)

### Step 2: 백엔드 장수 커맨드 존재 확인

**파일:** `backend/src/main/kotlin/com/opensam/command/general/`

**검사:** 각 장수 커맨드 키에 대응하는 Kotlin 클래스가 존재하는지 확인합니다.

```bash
ls backend/src/main/kotlin/com/opensam/command/general/ 2>/dev/null | sort
```

장수 커맨드 키 목록 (55개):
`che_NPC능동`, `che_강행`, `che_거병`, `che_건국`, `che_견문`, `che_군량매매`, `che_귀환`, `che_기술연구`, `che_내정특기초기화`, `che_농지개간`, `che_단련`, `che_등용`, `che_등용수락`, `che_랜덤임관`, `che_모반시도`, `che_모병`, `che_무작위건국`, `che_물자조달`, `che_방랑`, `che_사기진작`, `che_상업투자`, `che_선동`, `che_선양`, `che_성벽보수`, `che_소집해제`, `che_숙련전환`, `che_요양`, `che_은퇴`, `che_이동`, `che_인재탐색`, `che_임관`, `che_장비매매`, `che_장수대상임관`, `che_전투태세`, `che_전투특기초기화`, `che_접경귀환`, `che_정착장려`, `che_주민선정`, `che_증여`, `che_집합`, `che_징병`, `che_첩보`, `che_출병`, `che_치안강화`, `che_탈취`, `che_파괴`, `che_하야`, `che_해산`, `che_헌납`, `che_화계`, `che_훈련`, `cr_건국`, `cr_맹훈련`, `휴식`

**PASS:** 구현된 커맨드 파일이 존재
**FAIL:** 커맨드 파일이 누락됨 — 구현 필요 또는 docs에 "Planned" 상태로 기록

### Step 3: 백엔드 국가 커맨드 존재 확인

**파일:** `backend/src/main/kotlin/com/opensam/command/nation/`

**검사:** 각 국가 커맨드 키에 대응하는 Kotlin 클래스가 존재하는지 확인합니다.

```bash
ls backend/src/main/kotlin/com/opensam/command/nation/ 2>/dev/null | sort
```

국가 커맨드 키 목록 (38개):
`che_감축`, `che_국기변경`, `che_국호변경`, `che_급습`, `che_몰수`, `che_무작위수도이전`, `che_물자원조`, `che_발령`, `che_백성동원`, `che_부대탈퇴지시`, `che_불가침수락`, `che_불가침제의`, `che_불가침파기수락`, `che_불가침파기제의`, `che_선전포고`, `che_수몰`, `che_의병모집`, `che_이호경식`, `che_종전수락`, `che_종전제의`, `che_증축`, `che_천도`, `che_초토화`, `che_포상`, `che_피장파장`, `che_필사즉생`, `che_허보`, `cr_인구이동`, `event_극병연구`, `event_대검병연구`, `event_무희연구`, `event_산저병연구`, `event_상병연구`, `event_원융노병연구`, `event_음귀병연구`, `event_화륜차연구`, `event_화시병연구`, `휴식`

**PASS:** 구현된 커맨드 파일이 존재
**FAIL:** 커맨드 파일이 누락됨

### Step 4: 커맨드 레지스트리 등록 확인

**파일:** 백엔드 커맨드 레지스트리 (구현 시 경로 확인 필요)

**검사:** 구현된 모든 커맨드 클래스가 레지스트리에 등록되어 있는지 확인합니다.

```bash
# 커맨드 등록 코드에서 커맨드 키 목록 추출
grep -rn "register\|commandMap\|CommandType" backend/src/main/kotlin/com/opensam/command/ 2>/dev/null
```

**PASS:** 모든 구현된 커맨드가 레지스트리에 등록됨
**FAIL:** 구현은 되어있으나 레지스트리에 누락된 커맨드 발견

### Step 5: 프론트엔드 커맨드 UI 확인

**파일:** `frontend/src/components/command/`, `frontend/src/types/`

**검사:** 구현된 백엔드 커맨드가 프론트엔드에서도 선택 가능한지 확인합니다.

```bash
# 프론트엔드에서 커맨드 키 참조 검색
grep -rn "che_\|cr_\|휴식" frontend/src/ 2>/dev/null | grep -v node_modules
```

**PASS:** 백엔드에 구현된 커맨드가 프론트엔드에서도 참조됨
**FAIL:** 백엔드에 구현되었으나 프론트엔드에서 참조되지 않는 커맨드 발견

### Step 6: 패러티 진행 상황 보고서

모든 검사 결과를 종합하여 진행 상황을 보고합니다:

```bash
# legacy-commands.md에서 Ported/Planned 상태 집계
grep -c "Ported" docs/architecture/legacy-commands.md
grep -c "Planned" docs/architecture/legacy-commands.md
```

## Output Format

```markdown
## Command Parity 검증 결과

### 장수 커맨드 (55개)

| 상태   | 수  | 비율 |
| ------ | --- | ---- |
| 구현됨 | X   | X%   |
| 미구현 | Y   | Y%   |

### 국가 커맨드 (38개)

| 상태   | 수  | 비율 |
| ------ | --- | ---- |
| 구현됨 | X   | X%   |
| 미구현 | Y   | Y%   |

### 상세 (미구현 커맨드)

| #   | 유형 | 커맨드 키  | 레거시 이름 | core2026 존재 | 백엔드 | 프론트엔드 |
| --- | ---- | ---------- | ----------- | ------------- | ------ | ---------- |
| 1   | 장수 | `che_강행` | 강행        | O             | X      | X          |
| 2   | 국가 | `che_급습` | 급습        | O             | X      | X          |
```

## Exceptions

다음은 **위반이 아닙니다**:

1. **docs에서 "Planned"로 표시된 커맨드** — 아직 미구현 상태는 이슈가 아닌 진행 상황으로 보고
2. **시나리오별 커맨드 (cr\_, event\_)** — 기본 시나리오(che\_)가 먼저 구현되는 것이 정상; cr\_, event\_ 커맨드가 후순위인 것은 허용
3. **프론트엔드 미구현** — 백엔드 구현이 선행하므로, 백엔드에만 존재하고 프론트엔드에 없는 것은 경고(warning)로 보고
4. **NPC전용 커맨드 (che_NPC능동)** — 플레이어 UI에 노출되지 않아도 정상
5. **커맨드 레지스트리 미구현** — 프로젝트 초기 단계에서 레지스트리 자체가 없을 수 있음; 이 경우 Step 4를 SKIP으로 보고
