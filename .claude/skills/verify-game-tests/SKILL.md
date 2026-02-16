---
name: verify-game-tests
description: 백엔드 게임 엔진 테스트가 작성되어 있고 모두 통과하는지 확인합니다. core2026 테스트를 참조. 엔진 로직 구현/수정 후 사용.
---

# Game Tests 검증

## Purpose

백엔드 게임 엔진의 테스트 커버리지와 통과 여부를 검증합니다:

1. **커맨드 테스트 존재** — 구현된 각 커맨드에 대한 단위 테스트가 존재하는지 확인
2. **엔진 테스트 존재** — 턴 실행, 전투, 경제 등 엔진 모듈에 대한 테스트가 존재하는지 확인
3. **엣지케이스 커버** — core2026 테스트에서 다루는 엣지케이스가 새 테스트에도 포함되는지 확인
4. **테스트 통과** — 모든 테스트가 통과하는지 확인
5. **테스트 네이밍** — 테스트가 무엇을 검증하는지 명확한 이름을 가지는지 확인

## When to Run

- 새로운 커맨드나 엔진 로직을 구현한 후
- 기존 로직을 수정한 후
- 테스트를 추가한 후
- PR 전 테스트 상태를 확인할 때
- 마일스톤 테스트 커버리지를 점검할 때

## Related Files

| File                                                         | Purpose                        |
| ------------------------------------------------------------ | ------------------------------ |
| `core2026/packages/logic/src/ai/__tests__/GeneralAI.test.ts` | core2026 AI 테스트 (참조)      |
| `core2026/docs/testing-policy.md`                            | core2026 테스트 정책 (참조)    |
| `backend/src/test/kotlin/com/opensam/`                       | 백엔드 테스트 루트 (구현 대상) |
| `backend/src/test/kotlin/com/opensam/command/general/`       | 장수 커맨드 테스트 (구현 대상) |
| `backend/src/test/kotlin/com/opensam/command/nation/`        | 국가 커맨드 테스트 (구현 대상) |
| `backend/src/test/kotlin/com/opensam/engine/`                | 엔진 테스트 (구현 대상)        |

## Workflow

### Step 1: core2026 테스트 정책 확인

**파일:** `core2026/docs/testing-policy.md`

**검사:** core2026의 테스트 정책을 읽어 참조 기준을 파악합니다.

```bash
cat core2026/docs/testing-policy.md 2>/dev/null || echo "testing-policy.md not found"
```

### Step 2: 구현된 커맨드 대비 테스트 존재 확인

**검사:** 백엔드에 구현된 각 커맨드에 대응하는 테스트 파일이 존재하는지 확인합니다.

```bash
# 구현된 커맨드 목록
GENERAL_CMDS=$(ls backend/src/main/kotlin/com/opensam/command/general/ 2>/dev/null | sed 's/.kt$//')
NATION_CMDS=$(ls backend/src/main/kotlin/com/opensam/command/nation/ 2>/dev/null | sed 's/.kt$//')

# 각 커맨드에 대한 테스트 존재 확인
for cmd in $GENERAL_CMDS; do
  if ls backend/src/test/kotlin/com/opensam/command/general/${cmd}Test.kt 2>/dev/null; then
    echo "PASS: ${cmd}"
  else
    echo "MISSING: ${cmd}"
  fi
done
```

**PASS:** 모든 구현된 커맨드에 테스트 파일 존재
**FAIL:** 테스트 없는 커맨드 발견

### Step 3: 엔진 모듈 테스트 존재 확인

**검사:** 턴 실행, 전투, 경제 등 핵심 엔진 모듈에 대한 테스트가 존재하는지 확인합니다.

```bash
# 엔진 테스트 파일 검색
ls backend/src/test/kotlin/com/opensam/engine/ 2>/dev/null
```

필수 테스트 대상 모듈:

- 턴 실행 (TurnExecution)
- 전투 엔진 (Battle/War)
- 경제 엔진 (Economy)
- 제약조건 (Constraint)
- NPC AI

**PASS:** 핵심 모듈에 테스트 존재
**FAIL:** 핵심 모듈 테스트 누락

### Step 4: 엣지케이스 커버리지 확인

**검사:** core2026 테스트에서 다루는 엣지케이스가 새 테스트에도 포함되는지 확인합니다.

```bash
# core2026 테스트에서 엣지케이스 패턴 추출
grep -rn "it\(\|describe\(\|test\(" core2026/packages/logic/src/**/__tests__/ 2>/dev/null

# 백엔드 테스트에서 유사 패턴 검색
grep -rn "@Test\|fun.*test\|should" backend/src/test/kotlin/com/opensam/ 2>/dev/null
```

주요 엣지케이스 카테고리:

- 자원 부족 시 커맨드 실패
- 권한 없는 장수의 국가 커맨드 시도
- 전투 중 특수 상황 (0명 병사, 사기 0 등)
- 동시 실행 충돌 (같은 도시에 복수 커맨드)
- 국가 멸망/장수 사망 경계 조건

**PASS:** 주요 엣지케이스가 테스트에 포함됨
**FAIL:** 누락된 엣지케이스 발견

### Step 5: 테스트 실행 및 통과 확인

**검사:** 모든 테스트가 통과하는지 확인합니다.

```bash
cd backend && ./gradlew test 2>&1 | tail -20
```

**PASS:** 모든 테스트 통과 (BUILD SUCCESSFUL)
**FAIL:** 실패한 테스트 존재 — 실패 내용을 상세히 기록

### Step 6: 테스트 커버리지 보고서

모든 검사 결과를 종합합니다.

## Output Format

```markdown
## Game Tests 검증 결과

### 테스트 실행 결과

| 항목        | 수  |
| ----------- | --- |
| 전체 테스트 | N   |
| 통과        | X   |
| 실패        | Y   |
| 건너뜀      | Z   |

### 커맨드 테스트 커버리지

| #   | 유형 | 구현된 커맨드  | 테스트 존재 | 엣지케이스 | 상태    |
| --- | ---- | -------------- | ----------- | ---------- | ------- |
| 1   | 장수 | `che_농지개간` | O           | 2개        | PASS    |
| 2   | 장수 | `che_모병`     | X           | -          | MISSING |

### 엔진 모듈 테스트 커버리지

| #   | 모듈     | 테스트 존재 | 테스트 수 | 상태      |
| --- | -------- | ----------- | --------- | --------- |
| 1   | 턴 실행  | O/X         | N         | PASS/FAIL |
| 2   | 전투     | O/X         | N         | PASS/FAIL |
| 3   | 경제     | O/X         | N         | PASS/FAIL |
| 4   | 제약조건 | O/X         | N         | PASS/FAIL |
| 5   | NPC AI   | O/X         | N         | PASS/FAIL |
```

## Exceptions

다음은 **위반이 아닙니다**:

1. **미구현 커맨드의 테스트 없음** — 아직 구현되지 않은 커맨드에 대한 테스트는 불필요; verify-command-parity에서 구현 상태 추적
2. **통합 테스트 vs 단위 테스트** — 커맨드별 단위 테스트 대신 시나리오 기반 통합 테스트로 커버하는 것도 허용
3. **core2026 테스트 없음** — core2026에 테스트 파일이 거의 없으므로, core2026 테스트 부재가 새 프로젝트의 테스트 부재를 정당화하지는 않음
4. **테스트 실행 환경** — Docker(DB/Redis)가 실행 중이 아니어서 통합 테스트가 실패하는 경우, 단위 테스트만 실행하여 보고
5. **프로젝트 초기** — 백엔드 소스가 아직 없는 초기 단계에서는 전체를 SKIP으로 보고하되, 테스트 디렉토리 구조가 준비되어 있는지는 확인
