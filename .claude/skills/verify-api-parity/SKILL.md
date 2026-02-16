---
name: verify-api-parity
description: 풀스택 1:1 메서드-레벨 패러티를 검증합니다. Controller-Service-Repository 체인, 프론트엔드 API 클라이언트-백엔드 엔드포인트 매칭, 페이지 스텁/데드 코드, 요청/응답 타입 호환성 확인.
---

# API Parity 검증

## Purpose

풀스택 전체에서 1:1 메서드-레벨 패러티를 검증합니다:

1. **Controller -> Service -> Repository 체인** -- 모든 Controller 엔드포인트 메서드가 대응하는 Service 메서드를 호출하고, 모든 Service 메서드가 대응하는 Repository 메서드를 호출하는지 확인. 어떤 Controller에서도 사용되지 않는 고아 Service/Repository 메서드 탐지
2. **프론트엔드 API 클라이언트 <-> 백엔드 엔드포인트** -- `gameApi.ts`의 모든 메서드가 매칭되는 백엔드 Controller 엔드포인트를 가지는지, URL 경로/HTTP 메서드/파라미터가 일치하는지 확인
3. **프론트엔드 페이지 -> API 클라이언트 사용** -- 백엔드 API가 존재하는 기능에 대해 플레이스홀더 텍스트가 남아있지 않은지, 페이지가 실제 API를 호출하는지, 사용되지 않는 데드 API 메서드가 없는지 확인
4. **요청/응답 타입 매칭** -- 프론트엔드 TypeScript 타입이 백엔드 JPA 엔티티/DTO 필드와 일치하는지, 요청/응답 구조가 호환되는지 확인

## When to Run

- 새로운 Controller 엔드포인트를 추가한 후
- `gameApi.ts`에 새로운 API 메서드를 추가한 후
- 프론트엔드 페이지에서 API 호출을 변경한 후
- 백엔드 DTO 구조를 변경한 후
- 프론트엔드 TypeScript 타입을 수정한 후
- 풀스택 기능 구현 완료 후 통합 점검

## Related Files

| File                                                  | Purpose                        |
| ----------------------------------------------------- | ------------------------------ |
| `backend/src/main/kotlin/com/opensam/controller/*.kt` | 모든 REST 컨트롤러             |
| `backend/src/main/kotlin/com/opensam/service/*.kt`    | 모든 서비스 클래스             |
| `backend/src/main/kotlin/com/opensam/repository/*.kt` | 모든 JPA 레포지토리            |
| `backend/src/main/kotlin/com/opensam/dto/*.kt`        | 요청/응답 DTO                  |
| `backend/src/main/kotlin/com/opensam/entity/*.kt`     | JPA 엔티티                     |
| `frontend/src/lib/gameApi.ts`                         | 모든 API 클라이언트 메서드     |
| `frontend/src/lib/api.ts`                             | Axios 인스턴스 (baseURL 설정)  |
| `frontend/src/types/index.ts`                         | TypeScript 타입 정의           |
| `frontend/src/app/(game)/*/page.tsx`                  | 모든 게임 페이지               |
| `frontend/src/stores/*.ts`                            | Zustand 스토어 (API 호출 포함) |

## Workflow

### Step 1: 백엔드 엔드포인트 카탈로그 구축

**파일:** `backend/src/main/kotlin/com/opensam/controller/*.kt`

**검사:** 모든 Controller 파일을 읽고, 클래스-레벨 `@RequestMapping` 프리픽스를 포함하여 모든 `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`의 전체 경로를 추출합니다.

```bash
# Controller 파일 목록
ls backend/src/main/kotlin/com/opensam/controller/ 2>/dev/null

# 클래스-레벨 @RequestMapping 추출
grep -rn "@RequestMapping" backend/src/main/kotlin/com/opensam/controller/ 2>/dev/null

# 메서드-레벨 매핑 추출
grep -rn "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping\|@PatchMapping" backend/src/main/kotlin/com/opensam/controller/ 2>/dev/null
```

각 엔드포인트에 대해 기록:

- 전체 URL 경로 (클래스 프리픽스 + 메서드 경로)
- HTTP 메서드 (GET/POST/PUT/DELETE/PATCH)
- 메서드 이름
- 파라미터 (`@PathVariable`, `@RequestParam`, `@RequestBody`)
- 반환 타입

**산출물:** 백엔드 엔드포인트 전체 목록 테이블

### Step 2: 프론트엔드 API 메서드 카탈로그 구축

**파일:** `frontend/src/lib/gameApi.ts`

**검사:** `gameApi.ts`를 읽고, 모든 API 메서드의 URL 경로, HTTP 메서드, 파라미터, 반환 타입을 추출합니다.

```bash
# gameApi.ts에서 API 호출 패턴 추출
grep -n "\.get\|\.post\|\.put\|\.delete\|\.patch" frontend/src/lib/gameApi.ts 2>/dev/null

# 함수/메서드 정의 추출
grep -n "export.*=.*async\|async.*(" frontend/src/lib/gameApi.ts 2>/dev/null
```

각 API 메서드에 대해 기록:

- 메서드 이름
- URL 경로
- HTTP 메서드
- 요청 파라미터/바디 타입
- 응답 타입

**산출물:** 프론트엔드 API 메서드 전체 목록 테이블

### Step 3: 엔드포인트 <-> API 메서드 교차 검증

**검사:** Step 1과 Step 2의 결과를 대조합니다.

각 백엔드 엔드포인트에 대해:

- 동일한 경로와 HTTP 메서드를 가진 프론트엔드 API 메서드가 존재하는가?
- 경로가 존재하지만 HTTP 메서드가 다른 경우 (GET vs POST 등) 플래그

각 프론트엔드 API 메서드에 대해:

- 동일한 경로와 HTTP 메서드를 가진 백엔드 엔드포인트가 존재하는가?
- 프론트엔드에 정의된 경로가 백엔드에 존재하지 않으면 플래그

**PASS:** 모든 프론트엔드 API 메서드에 대응하는 백엔드 엔드포인트 존재, 경로/메서드 일치
**FAIL:** 매칭되지 않는 메서드 발견

### Step 4: Controller -> Service 체인 검증

**파일:** `backend/src/main/kotlin/com/opensam/controller/*.kt`, `backend/src/main/kotlin/com/opensam/service/*.kt`

**검사:** 각 Controller의 모든 엔드포인트 메서드가 Service 메서드를 호출하는지 확인합니다.

```bash
# Controller 파일 각각을 읽어서 Service 호출 확인
for f in backend/src/main/kotlin/com/opensam/controller/*.kt; do
  echo "=== $(basename $f) ==="
  grep -n "Service\|service\." "$f" 2>/dev/null
done

# Service 파일의 public 메서드 목록
grep -rn "fun " backend/src/main/kotlin/com/opensam/service/ 2>/dev/null | grep -v "private\|internal"
```

각 Controller 메서드에 대해:

- Service 메서드 호출이 있는가?
- 호출 없이 직접 응답을 반환하는 경우 플래그

각 Service public 메서드에 대해:

- 하나 이상의 Controller에서 호출되는가?
- 어디서도 호출되지 않는 고아 메서드 플래그

**PASS:** 모든 Controller 메서드가 Service를 호출하고, 모든 Service 메서드가 사용됨
**FAIL:** Service 호출 없는 Controller 메서드, 또는 사용되지 않는 Service 메서드 발견

### Step 5: 프론트엔드 페이지 스텁/플레이스홀더 검사

**파일:** `frontend/src/app/(game)/*/page.tsx`

**검사:** 모든 게임 페이지에서 플레이스홀더 텍스트를 검색하고, 해당 기능에 백엔드 API가 이미 존재하는지 교차 확인합니다.

```bash
# 플레이스홀더 텍스트 검색
grep -rn "준비중\|TODO\|추가될 예정\|구현 예정\|mock\|placeholder\|FIXME\|HACK\|stub" frontend/src/app/ 2>/dev/null | grep -v node_modules
```

발견된 각 플레이스홀더에 대해:

- 해당 기능에 대한 백엔드 API가 이미 존재하는가?
- 존재하면 FAIL (API가 있는데 플레이스홀더를 표시)
- 존재하지 않으면 Exceptions 항목 확인 후 SKIP 가능

**PASS:** 백엔드 API가 존재하는 기능에 플레이스홀더 텍스트 없음
**FAIL:** 백엔드 API가 존재하는데 프론트엔드가 플레이스홀더/스텁을 표시

### Step 6: 데드 API 메서드 검사

**파일:** `frontend/src/lib/gameApi.ts`, `frontend/src/app/`, `frontend/src/stores/`

**검사:** `gameApi.ts`의 각 메서드가 실제로 어딘가에서 import되고 호출되는지 확인합니다.

```bash
# gameApi.ts에서 export된 함수/객체 추출
grep -n "export " frontend/src/lib/gameApi.ts 2>/dev/null

# 각 API 메서드가 다른 파일에서 사용되는지 검색
grep -rn "gameApi\." frontend/src/app/ frontend/src/stores/ frontend/src/components/ 2>/dev/null | grep -v "gameApi.ts" | grep -v node_modules
```

각 gameApi 메서드에 대해:

- 하나 이상의 페이지/스토어/컴포넌트에서 호출되는가?
- 호출되지 않는 메서드 플래그

**PASS:** 모든 API 메서드가 하나 이상의 곳에서 사용됨
**FAIL:** 사용되지 않는 데드 API 메서드 발견

### Step 7: 요청/응답 타입 호환성 검증

> **상세 검증:** 이 단계의 심층 검증은 `verify-type-parity` 스킬이 담당합니다. 여기서는 기본 구조 호환성만 확인합니다.

**파일:** `frontend/src/types/index.ts`, `backend/src/main/kotlin/com/opensam/dto/*.kt`, `backend/src/main/kotlin/com/opensam/entity/*.kt`

**검사:** 프론트엔드 TypeScript 타입과 백엔드 DTO/엔티티 필드 구조가 호환되는지 확인합니다.

```bash
# 프론트엔드 타입 정의 추출
grep -n "interface\|type " frontend/src/types/index.ts 2>/dev/null

# 백엔드 DTO 클래스 필드 추출
grep -rn "val \|var " backend/src/main/kotlin/com/opensam/dto/ 2>/dev/null

# 백엔드 @RequestBody DTO 사용 확인
grep -rn "@RequestBody" backend/src/main/kotlin/com/opensam/controller/ 2>/dev/null
```

주요 확인 사항:

- 프론트엔드가 보내는 요청 바디 필드명이 백엔드 `@RequestBody` DTO 필드명과 일치하는가?
- 백엔드가 반환하는 응답 필드가 프론트엔드 TypeScript 타입에 정의되어 있는가?
- 필드 타입이 호환되는가? (예: Kotlin `Long` <-> TypeScript `number`, Kotlin `LocalDateTime` <-> TypeScript `string`)
- Loose 타입(`Record<string, unknown>`, `any`), 인라인 DTO, 인라인 API 응답 타입 → **`verify-type-parity`에서 상세 검증**

**PASS:** 요청/응답 타입 구조가 호환
**FAIL:** 필드명 불일치, 누락된 필드, 또는 타입 비호환 발견

## Output Format

```markdown
## API Parity 검증 결과

### 요약

| 카테고리                       | 상태      | 이슈 수 |
| ------------------------------ | --------- | ------- |
| 엔드포인트 <-> API 메서드 매칭 | PASS/FAIL | N       |
| Controller -> Service 체인     | PASS/FAIL | N       |
| 고아 Service/Repository 메서드 | PASS/FAIL | N       |
| 프론트엔드 스텁/플레이스홀더   | PASS/FAIL | N       |
| 데드 API 메서드                | PASS/FAIL | N       |
| 요청/응답 타입 호환성          | PASS/FAIL | N       |

### 상세

| #   | Category                  | Issue                                           | File:Line                     | Fix                                 |
| --- | ------------------------- | ----------------------------------------------- | ----------------------------- | ----------------------------------- |
| 1   | Endpoint mismatch         | GET /api/foo exists in backend, missing in FE   | XxxController.kt:42           | Add gameApi method for GET /api/foo |
| 2   | Dead API method           | gameApi.fetchBar() not called from any page     | gameApi.ts:120                | Remove or wire up to a page         |
| 3   | Stub with backend support | "준비중" shown but GET /api/baz exists          | page.tsx:55                   | Replace stub with API call          |
| 4   | Orphan service method     | FooService.doSomething() never called           | FooService.kt:30              | Remove or add Controller usage      |
| 5   | Type mismatch             | FE sends `nationId` but backend expects `ntnId` | gameApi.ts:80, NationDto.kt:5 | Align field names                   |
```

## Exceptions

다음은 **위반이 아닙니다**:

1. **백엔드 미지원 기능의 플레이스홀더** -- 경매(auction), 베팅(betting), 토너먼트(tournament), 계승 포인트(inherit) 등 백엔드 API가 아직 없는 기능의 프론트엔드 플레이스홀더 텍스트는 허용
2. **NPC 전용 커맨드** -- NPC/AI가 내부적으로만 사용하는 커맨드는 프론트엔드 API 메서드가 불필요
3. **WebSocket 엔드포인트** -- STOMP/WebSocket 엔드포인트는 REST가 아니므로 이 검증 범위에 포함되지 않음 (별도 검증 대상)
4. **Admin 전용 엔드포인트** -- 관리자 전용 엔드포인트는 프론트엔드 게임 페이지에 대응하지 않을 수 있음
5. **내부 Service-to-Service 호출** -- Service에서 다른 Service를 호출하는 것은 정상; Controller에서만 호출되지 않더라도 다른 Service에서 호출되면 고아가 아님
6. **프론트엔드 추가 필드** -- TypeScript 타입에 백엔드에 없는 computed/display 전용 필드(예: `nationName`, `power`)가 있는 것은 허용
7. **Kotlin camelCase <-> JSON snake_case 변환** -- Jackson의 자동 변환으로 인해 Kotlin `crewType`이 JSON `crewType`으로 직렬화되는 것은 정상; 변환 설정이 일관되면 PASS
