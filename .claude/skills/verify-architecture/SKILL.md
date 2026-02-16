---
name: verify-architecture
description: 백엔드 TDD/DDD/클린-레이어드 아키텍처, 레포지토리 패턴 준수를 검증합니다. 백엔드 코드 추가/수정 후 사용.
---

# Architecture 검증

## Purpose

백엔드가 TDD, DDD, 클린-레이어드 아키텍처, 레포지토리 패턴을 올바르게 준수하는지 검증합니다:

1. **레이어 분리** — Controller → Service → Repository 계층이 올바르게 분리되어 있는지 확인
2. **의존성 방향** — 상위 레이어가 하위 레이어에만 의존하는지 확인 (Controller → Service → Repository, 역방향 금지)
3. **도메인 모델 독립** — Entity/Domain 모델이 프레임워크(Spring) 어노테이션 외 외부 의존성이 없는지 확인
4. **레포지토리 패턴** — DB 접근이 Repository 인터페이스를 통해서만 이루어지는지 확인
5. **서비스 레이어 로직** — 비즈니스 로직이 Service에 집중되고 Controller/Repository에 누출되지 않는지 확인
6. **테스트 구조** — 각 레이어에 대한 테스트가 존재하고 TDD 원칙을 따르는지 확인

## When to Run

- 새로운 Controller/Service/Repository를 추가한 후
- 기존 레이어 구조를 변경한 후
- 도메인 모델을 수정한 후
- PR 전 아키텍처 준수 여부를 점검할 때
- 새로운 기능 모듈을 구현한 후

## Related Files

| File                                              | Purpose                       |
| ------------------------------------------------- | ----------------------------- |
| `backend/src/main/kotlin/com/opensam/controller/` | 컨트롤러 레이어 (구현 대상)   |
| `backend/src/main/kotlin/com/opensam/service/`    | 서비스 레이어 (구현 대상)     |
| `backend/src/main/kotlin/com/opensam/repository/` | 레포지토리 레이어 (구현 대상) |
| `backend/src/main/kotlin/com/opensam/entity/`     | 도메인 엔티티                 |
| `backend/src/main/kotlin/com/opensam/dto/`        | DTO (구현 대상)               |
| `backend/src/main/kotlin/com/opensam/config/`     | 설정 (구현 대상)              |
| `backend/src/main/kotlin/com/opensam/command/`    | 게임 커맨드 (도메인 로직)     |
| `backend/src/main/kotlin/com/opensam/engine/`     | 게임 엔진 (도메인 로직)       |
| `backend/src/test/kotlin/com/opensam/`            | 테스트 루트 (구현 대상)       |

## Workflow

### Step 1: 패키지 구조 확인

**검사:** 백엔드가 클린-레이어드 아키텍처에 맞는 패키지 구조를 갖추고 있는지 확인합니다.

```bash
# 최상위 패키지 구조 확인
ls backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

기대 패키지 구조:

```
com.opensam/
├── controller/    # HTTP 요청/응답 처리
├── service/       # 비즈니스 로직
├── repository/    # 데이터 접근 추상화
├── entity/        # JPA 엔티티 (도메인 모델)
├── dto/           # 데이터 전송 객체
├── config/        # Spring 설정
├── command/       # 게임 커맨드 (도메인)
├── engine/        # 게임 엔진 (도메인)
└── ai/            # NPC AI (도메인)
```

**PASS:** 핵심 패키지(controller, service, repository, entity)가 모두 존재
**FAIL:** 패키지 누락 — 어떤 패키지가 없는지 기록

### Step 2: 의존성 방향 검증

**검사:** 상위 레이어가 하위 레이어에만 의존하는지 확인합니다.

**금지 패턴:**

- Repository가 Controller를 import
- Repository가 Service를 import
- Service가 Controller를 import
- Entity가 Controller/Service를 import

```bash
# Repository → Controller 역방향 의존 검사
grep -rn "import.*controller" backend/src/main/kotlin/com/opensam/repository/ 2>/dev/null
grep -rn "import.*controller" backend/src/main/kotlin/com/opensam/service/ 2>/dev/null

# Service → Controller 역방향 의존 검사
grep -rn "import.*controller" backend/src/main/kotlin/com/opensam/service/ 2>/dev/null

# Entity → Controller/Service 역방향 의존 검사
grep -rn "import.*controller\|import.*service" backend/src/main/kotlin/com/opensam/entity/ 2>/dev/null
```

**PASS:** 역방향 의존성 없음
**FAIL:** 역방향 import 발견 — 파일과 라인 번호 기록

### Step 3: Controller 레이어 검증

**검사:** Controller가 HTTP 요청/응답 처리만 담당하고 비즈니스 로직을 포함하지 않는지 확인합니다.

```bash
# Controller 파일 목록
ls backend/src/main/kotlin/com/opensam/controller/ 2>/dev/null

# Controller에서 비즈니스 로직 징후 검색
grep -rn "repository\.\|Repository\b" backend/src/main/kotlin/com/opensam/controller/ 2>/dev/null
grep -rn "\.save\b\|\.delete\b\|\.findBy" backend/src/main/kotlin/com/opensam/controller/ 2>/dev/null
```

**허용:** Controller가 Service를 주입받아 호출
**금지:** Controller가 Repository를 직접 사용하거나 DB 쿼리를 실행

**PASS:** Controller가 Service만 호출하고 비즈니스 로직 없음
**FAIL:** Controller에서 Repository 직접 접근 또는 비즈니스 로직 발견

### Step 4: Service 레이어 검증

**검사:** 비즈니스 로직이 Service에 집중되어 있는지 확인합니다.

```bash
# Service 파일 목록
ls backend/src/main/kotlin/com/opensam/service/ 2>/dev/null

# Service에서 @Service 어노테이션 확인
grep -rn "@Service" backend/src/main/kotlin/com/opensam/service/ 2>/dev/null

# Service에서 Repository 주입 확인
grep -rn "Repository" backend/src/main/kotlin/com/opensam/service/ 2>/dev/null
```

**허용:** Service가 Repository를 주입받아 데이터 접근
**금지:** Service가 HttpServletRequest/Response를 직접 처리

```bash
# Service에서 HTTP 관련 import 검사
grep -rn "import.*servlet\|import.*http\|@RequestMapping\|@GetMapping\|@PostMapping" backend/src/main/kotlin/com/opensam/service/ 2>/dev/null
```

**PASS:** Service가 비즈니스 로직만 포함하고 HTTP 관련 코드 없음
**FAIL:** Service에서 HTTP/Controller 관련 코드 발견

### Step 5: Repository 패턴 검증

**검사:** DB 접근이 Repository 인터페이스를 통해서만 이루어지는지 확인합니다.

```bash
# Repository 인터페이스 목록
grep -rn "interface.*Repository" backend/src/main/kotlin/com/opensam/repository/ 2>/dev/null

# JpaRepository/CrudRepository 상속 확인
grep -rn "JpaRepository\|CrudRepository" backend/src/main/kotlin/com/opensam/repository/ 2>/dev/null

# Service/Controller에서 EntityManager 직접 사용 검사
grep -rn "EntityManager\|@PersistenceContext\|em\.\|entityManager\." backend/src/main/kotlin/com/opensam/service/ backend/src/main/kotlin/com/opensam/controller/ 2>/dev/null
```

**허용:** Repository 내부에서 @Query, EntityManager 사용
**금지:** Service/Controller에서 EntityManager 직접 사용

**PASS:** 모든 DB 접근이 Repository를 통해 이루어짐
**FAIL:** Repository 외부에서 직접 DB 접근 발견

### Step 6: 도메인 모델 독립성 검증

**검사:** Entity/도메인 모델이 프레임워크 독립적인지 확인합니다.

```bash
# Entity에서 허용되는 import (JPA 어노테이션만)
grep -rn "^import" backend/src/main/kotlin/com/opensam/entity/ 2>/dev/null | grep -v "jakarta.persistence\|kotlin\|java.time\|java.util\|com.opensam.entity"

# 도메인 로직(command/engine)에서 Spring 의존 검사
grep -rn "import.*springframework" backend/src/main/kotlin/com/opensam/command/ backend/src/main/kotlin/com/opensam/engine/ 2>/dev/null
```

**허용:** Entity에서 JPA 어노테이션(`jakarta.persistence`), Kotlin 표준 라이브러리
**금지:** Entity에서 Spring 서비스, Controller, DTO import

**PASS:** 도메인 모델이 프레임워크 독립적
**FAIL:** 도메인 모델에서 프레임워크 의존성 발견

### Step 7: DTO 분리 검증

**검사:** API 요청/응답에 Entity를 직접 노출하지 않고 DTO를 사용하는지 확인합니다.

```bash
# Controller에서 Entity 직접 반환 검사
grep -rn "fun.*:.*Entity\|fun.*:.*General\b\|fun.*:.*Nation\b\|fun.*:.*City\b" backend/src/main/kotlin/com/opensam/controller/ 2>/dev/null

# DTO 존재 확인
ls backend/src/main/kotlin/com/opensam/dto/ 2>/dev/null
```

**PASS:** Controller가 DTO를 반환하고 Entity를 직접 노출하지 않음
**FAIL:** Controller에서 Entity를 직접 반환

### Step 8: 테스트 구조 검증 (TDD)

**검사:** 각 레이어에 대한 테스트가 존재하는지 확인합니다.

```bash
# 테스트 디렉토리 구조
ls backend/src/test/kotlin/com/opensam/ 2>/dev/null

# 레이어별 테스트 존재 확인
ls backend/src/test/kotlin/com/opensam/controller/ 2>/dev/null
ls backend/src/test/kotlin/com/opensam/service/ 2>/dev/null
ls backend/src/test/kotlin/com/opensam/repository/ 2>/dev/null
```

기대 테스트 구조:

- **Controller 테스트** — `@WebMvcTest` 또는 MockMvc 기반 통합 테스트
- **Service 테스트** — 단위 테스트 (Repository mock)
- **Repository 테스트** — `@DataJpaTest` 또는 실 DB 테스트

```bash
# 테스트 어노테이션 검사
grep -rn "@WebMvcTest\|@DataJpaTest\|@SpringBootTest\|@Test\|@MockBean" backend/src/test/kotlin/com/opensam/ 2>/dev/null | head -20
```

**PASS:** 각 레이어에 테스트 존재
**FAIL:** 테스트 없는 레이어 발견

## Output Format

```markdown
## Architecture 검증 결과

### 패키지 구조

| 패키지      | 존재 | 파일 수 |
| ----------- | ---- | ------- |
| controller/ | O/X  | N       |
| service/    | O/X  | N       |
| repository/ | O/X  | N       |
| entity/     | O/X  | N       |
| dto/        | O/X  | N       |
| command/    | O/X  | N       |
| engine/     | O/X  | N       |

### 아키텍처 규칙

| #   | 규칙                  | 상태      | 상세 |
| --- | --------------------- | --------- | ---- |
| 1   | 의존성 방향           | PASS/FAIL | ...  |
| 2   | Controller 책임 분리  | PASS/FAIL | ...  |
| 3   | Service 비즈니스 로직 | PASS/FAIL | ...  |
| 4   | Repository 패턴       | PASS/FAIL | ...  |
| 5   | 도메인 모델 독립성    | PASS/FAIL | ...  |
| 6   | DTO 분리              | PASS/FAIL | ...  |
| 7   | 테스트 구조 (TDD)     | PASS/FAIL | ...  |
```

## Exceptions

다음은 **위반이 아닙니다**:

1. **게임 커맨드/엔진의 Spring 어노테이션** — `command/`, `engine/` 패키지에서 `@Component`, `@Service` 사용은 DI를 위해 허용; 핵심은 HTTP/Controller 의존이 없는 것
2. **Config 클래스의 다층 접근** — `config/` 패키지의 설정 클래스는 여러 레이어에서 참조 가능
3. **프로젝트 초기 단계** — 아직 패키지가 생성되지 않은 초기 단계에서는 SKIP으로 보고하되, 기존 코드가 규칙을 위반하는지는 확인
4. **간단한 CRUD** — 단순 CRUD에서 Service가 Repository 호출만 위임하는 것은 정상; 불필요한 추상화 강제 아님
5. **도메인 이벤트** — Entity에서 Spring ApplicationEvent를 발행하는 것은 DDD 패턴으로 허용
6. **Projection/View DTO** — Repository에서 직접 DTO를 반환하는 Spring Data Projection은 허용
7. **테스트 커버리지 비율** — TDD 원칙 검증이지 100% 커버리지 강제가 아님; 핵심 로직에 테스트가 있으면 PASS
