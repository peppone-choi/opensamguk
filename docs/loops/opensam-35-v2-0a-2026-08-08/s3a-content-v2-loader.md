# S3-a — 0A-d `content/v2/` read-only 카탈로그 로더 (실측)

- 티켓: OPENSAM-35 / 계획 `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md` §3 S3
- 일자: 2026-08-08 · 브랜치 `op-35-v2-0a`
- 선행: S1(v2 location = `classpath:db/migration_v2`) · S2(게이트 규약 = 각 앱 `V2SandboxConfiguration` 안 `@Bean`)
- 병렬: S3-b(`web/game/app/game/v2-lab/**`) — 이 문서의 범위 밖, 파일 접점 0

---

## 1. 산출물 (전부 신규 파일 + S2 소유 신규 파일 1개에 `@Bean` 1개 추가)

| 경로 | 역할 |
|---|---|
| `infra/src/main/kotlin/opensamguk/infra/v2/V2ContentCatalog.kt` | 로더 본체(조회 2개, 쓰기 메서드 없음) |
| `infra/src/main/resources/content/v2/README.md` | 위치·규약 정본(빈 디렉터리 상태) |
| `infra/src/test/kotlin/opensamguk/infra/v2/V2ContentCatalogTest.kt` | 스코프·빈 카탈로그·"scan/seed 없음" 실측 7 test |
| `infra/src/test/resources/v2-catalog-fixture/content/**` | 테스트 픽스처 5개(양성 2 · 재귀 1 · 형제 1 · 비-JSON 1) |
| `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ContentCatalogBeanTest.kt` | 게이트 0/1 + startup runner 0개 실측 3 test |
| `app/game-engine/.../v2/V2SandboxConfiguration.kt` | `v2ContentCatalog()` `@Bean` 추가 (S2가 만든 **신규** 파일, origin/main에 없음 ⇒ 게이트 ③ 무영향) |

`git` 추적 기준 **기존 파일 수정·삭제 0건** (§5 게이트 출력 참조).

---

## 2. 위치 결정 — `infra/src/main/resources/content/v2/`

### 2-1. 후보와 판정

| 후보 | 판정 | 근거 |
|---|---|---|
| 리포 루트 `content/v2/` | **기각** | 컨테이너 내부 경로 실재성을 별도로 보장해야 한다(볼륨 마운트 또는 이미지 베이크 + Dockerfile 수정). S1 U3가 `filesystem:`을 기각한 것과 같은 이유. |
| `infra/src/main/resources/scenario/v2/` 등 v1 하위 | **기각** | S1 U1의 교훈(재귀 스캔)의 동형 위험. v1 `ScenarioCatalogService`는 `classpath*:scenario/scenario_*.json`, Flyway는 location을 재귀 스캔한다. |
| **`infra/src/main/resources/content/v2/`** | **채택** | 표준 Gradle 리소스 처리로 jar에 그대로 구워진다. 배포 형태와 무관하게 `classpath*:content/v2/…`가 동일 동작. |

"루트 `content/`"의 해석은 **클래스패스 루트**다 — 배포 산출물 안에서 경로가 정확히 `content/v2/`이므로
계획 §0.4의 규약 표기와 일치한다.

### 2-2. S1 교훈의 유효성 — **실제로 확인함** (추측 아님)

**(a) classpath가 jar에 구워지는가 — 확인됨.**

```text
$ ./gradlew :infra:jar && unzip -l infra/build/libs/infra-0.0.1-SNAPSHOT.jar | grep -E 'content/|db/migration_v2'
        0  content/
        0  content/v2/
     2394  content/v2/README.md
        0  db/migration_v2/
     3408  db/migration_v2/README.md
```

boot jar까지 도달하는지도 직접 확인했다(S1과 같은 경로):

```text
$ ./gradlew :app:game-engine:bootJar
$ unzip -o -q game-engine-0.0.1-SNAPSHOT.jar 'BOOT-INF/lib/infra-*.jar'
$ unzip -l BOOT-INF/lib/infra-0.0.1-SNAPSHOT.jar | grep 'content/'
        0  content/
        0  content/v2/
     2394  content/v2/README.md
```

⇒ 마운트·베이크·Dockerfile 수정 **불필요**. S1 U3의 결론이 이 디렉터리에도 그대로 성립한다.

**(b) "형제 vs 하위" 교훈은 유효하나, 방향이 다르다.**

Flyway와 달리 이 로더는 **자기가 재귀하지 않는다**(패턴에 `**` 없음, §3-2 실측). 따라서 "v2가 v1을
집어삼킨다"는 위험은 없다. 남는 위험은 반대 방향 — **v1 로더가 v2 콘텐츠를 집어삼키는 것**이다.
`ScenarioCatalogService`가 `classpath*:scenario/scenario_*.json`으로 스캔하므로 v2 콘텐츠를
`scenario/` 하위에 두면 v1 시나리오 목록에 섞인다. ⇒ **형제 경로 `content/`가 맞다.**

**(c) 빈 디렉터리는 git이 추적하지 않는다.** README.md 1개가 디렉터리를 존재시키며, 로더 패턴이
`*.json`이라 README는 카탈로그에 잡히지 않는다(§3-2 `ignored.txt` 케이스가 같은 성질을 고정).

---

## 3. "classpath scan·startup seed 금지"를 무엇으로 강제했는가

선언(주석·규약)이 아니라 **실행되는 검증**으로 고정했다. 네 겹이다.

### 3-1. 코드 형태 자체

- `V2ContentCatalog`는 **평범한 클래스**다. `@Component`·`@Service`가 없어 컴포넌트 스캔으로
  등록될 수 없고, 오직 게이트 안 `@Bean`으로만 생성된다(S2 규약 준수).
- `ApplicationRunner`/`CommandLineRunner`를 **구현하지 않는다** ⇒ 부팅 시 자동 호출되는 진입점이 없다.
  `names()`/`read()`는 호출자가 부를 때만 클래스패스를 읽는다. v1 `ScenarioSeedRunner` 경로와 접점 0.
- 공개 API가 조회 2개뿐이다. **쓰기 메서드가 존재하지 않는다.**
- 패턴이 `classpath*:$location/*.json` — `**` 없음, location 한 디렉터리 직속으로 한정.

### 3-2. 실행되는 실측 (`V2ContentCatalogTest`, infra, 7 test)

픽스처 `infra/src/test/resources/v2-catalog-fixture/content/` 아래에 의도적으로 함정을 깔았다.

| 픽스처 | 기대 | 테스트 |
|---|---|---|
| `content/v2/alpha.json`, `beta.json` | 잡힌다 | `lists only the direct json entries of its own location` |
| `content/v2/nested/deep.json` | **잡히지 않는다** (재귀 금지) | `does not recurse into subdirectories` |
| `content/v2-decoy/decoy.json` | **잡히지 않는다** (형제 스코프 밖) | `does not read a sibling directory outside its scope` |
| `content/v2/ignored.txt` | **잡히지 않는다** (비-JSON) | `reads the content of a listed entry and nothing else` |
| 운영 기본 위치(콘텐츠 0개, README만) | **빈 목록, 예외 없음** | `empty catalog returns an empty list instead of throwing` |

`read("../v2-decoy/decoy.json")` = `null`도 같은 테스트가 고정한다 — `read`가 이름 대조로만 동작하므로
경로 탈출이 성립하지 않는다.

**"DB 쓰기 없음"은 클래스파일 상수풀 스캔으로 판정한다** (`references no persistence write type and no
startup runner type`). 기존 `DaemonNoEntityManagerTest`(`app/game-engine/.../flush/`)의 패턴 재사용이다 —
어떤 타입을 참조하면 슬래시 형식 내부 이름이 상수풀에 남으므로, 컴파일 산출물로 판정된다. 금지 목록:
`javax/sql/DataSource` · `org/springframework/jdbc` · `org/springframework/transaction` ·
`jakarta/persistence` · `org/springframework/data/repository` · `org/springframework/boot/ApplicationRunner` ·
`org/springframework/boot/CommandLineRunner` · `opensamguk/engine/turn/ChangeRecorder` ·
`opensamguk/infra/flush` · `opensamguk/infra/seed`.
스캔이 실제로 클래스 내용을 보고 있음을 고정하는 양성 assertion 2개
(`PathMatchingResourcePatternResolver`·`content/v2` 상수 존재)를 같이 넣었다.

### 3-3. 게이트 실측 (`V2ContentCatalogBeanTest`, game-engine, 3 test)

S2와 같은 `ApplicationContextRunner`.

| 조건 | 로더 빈 수 |
|---|---|
| 둘 다 미충족 / 프로퍼티만 / 프로파일만 | **0** |
| `v2.enabled=true` + 프로파일 `v2-sandbox` | **1** (그리고 `names()` = 빈 목록) |

게이트가 열린 컨텍스트에서 `ApplicationRunner`·`CommandLineRunner` 빈 수 **0**을 assert한다.

**측정 범위 (GATE-f Q5 — 과대 진술 축소).** 이 0은 `V2SandboxConfiguration` **하나만** 등록한
맨몸 `ApplicationContextRunner`에서 잰 값이다. 정확히 말하면:

- **잰 것**: `V2SandboxConfiguration`이 **자기 자신**은 startup runner를 하나도 등록하지 않는다.
  즉 게이트가 열려도 **이 설정 클래스로 인해** 새로 도는 부팅 훅은 없다. P-2 프로브(§3-4)가
  이 assert의 비공허성을 확인했다.
- **재지 않은 것**: **실제 앱 컨텍스트**의 runner 개수. 실 부팅에는 `ScenarioSeedRunner` 등
  v1 runner가 존재하므로 이 0은 "게이트가 열린 **앱** 컨텍스트에 seed runner가 없다"를
  뜻하지 **않는다.** 그런 컨텍스트에서의 v2 빈 실측은 S4(0A-f)의 `@SpringBootTest` 소관이고,
  S4도 v2 **빈**을 세지 runner 총수를 세지 않는다.

`V2ContentCatalog` 자체가 두 인터페이스를 구현하지 않는다는 사실은 별도로 infra 쪽
클래스파일 상수풀 스캔(§3-2)이 고정한다 — 컨텍스트 모양과 무관하게 성립하는 층이다.

### 3-4. 비공허성(non-vacuity) 뮤테이션 프로브 2회

"전부 PASS"가 검증이 작동해서인지 확인했다. 프로브는 측정 후 원본 복원(`diff` 동일 확인: `RESTORED-IDENTICAL`).

| 프로브 | 조작 | 결과 | 뒤집힌 케이스 |
|---|---|---|---|
| P-1 | 패턴을 `$location/**/*.json`(재귀)로 변경 | infra `tests="7" failures="2" errors="0"` | `does not recurse into subdirectories`(`expected: [alpha.json, beta.json] but was: [alpha.json, beta.json, deep…]`) + `lists only the direct json entries…` |
| P-2 | `V2ContentCatalog`가 `CommandLineRunner`를 구현하도록 변경 | infra `tests="7" failures="2"` / engine `tests="3" failures="1"` | infra `is not a startup runner` + `references no persistence write type…` / engine `gate open - registers no startup runner` |

⇒ 재귀 금지·startup seed 금지가 **각각 독립적으로 실측되고 있음**이 확인됐다.

> P-2 최초 실행에서 engine 모듈만 `failures="0"`으로 나왔다. 같은 Gradle 호출 안에서 engine 테스트가
> 이전 infra jar로 실행된 순서 아티팩트였고, engine 테스트만 단독으로 `--rerun-tasks` 재실행하니
> `failures="1"`로 뒤집혔다. 문서에는 재실행 결과를 싣는다.

---

## 4. 최종 판정 — 테스트 XML (exit code 아님)

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :infra:test --tests '*V2ContentCatalogTest' \
  :app:game-engine:test --tests '*V2ContentCatalogBeanTest' --tests '*V2SandboxConfigurationTest' \
  --rerun-tasks 2>&1 | tail -4
```

출력 tail: `BUILD SUCCESSFUL in 1m` / `20 actionable tasks: 20 executed`.

```text
infra/build/test-results/test/TEST-opensamguk.infra.v2.V2ContentCatalogTest.xml
  tests="7" skipped="0" failures="0" errors="0"
app/game-engine/build/test-results/test/TEST-opensamguk.engine.v2.V2ContentCatalogBeanTest.xml
  tests="3" skipped="0" failures="0" errors="0"
app/game-engine/build/test-results/test/TEST-opensamguk.engine.v2.V2SandboxConfigurationTest.xml
  tests="6" skipped="0" failures="0" errors="0"   ← S2 회귀 없음
```

---

## 5. 하드 제약 / PHP boundary

S3-a 당시 `origin/main` + wildcard transcript의 blank output은 historical stage evidence만이다.
Wildcard pathspec은 current proof가 아니므로, current canonical merge-base glob commands와 PR Round 1
disposition은 `s6-gates-and-baseline.md` §15 및 review ledger를 따른다.

```text
$ git diff --name-only --diff-filter=MD origin/main -- …
(historical blank output; no current PASS claim)
```

T1/parity paths are unchanged. This loader/isolation ticket neither ran nor claims a PHP golden
capture/draw-for-draw replay; A3 only inventories that scope. The local loader/bean assertions are not a
replacement for A4 or the current exact-SHA backend gate. Commit·push 없음; `.env*`·키·토큰 미접근.

---

## 6. UNKNOWN (측정하지 않은 것 — 추측으로 메우지 않음)

1. **실제 v2 콘텐츠 파일의 스키마·내용.** 만들지 않았다. 지어내면 날조다. 현재 카탈로그는 빈 목록이
   정상 동작이고, JSON 파싱은 로더가 하지 않는다(원문 문자열 반환). 파싱·검증은 콘텐츠 스키마가
   확정되는 후속 티켓 소관.
2. **game-api 등록.** 로더 빈을 **game-engine에만** 등록했다. game-api에 v2 콘텐츠 소비자가 아직
   0건이라 등록할 근거가 없다(YAGNI). infra에 두었으므로 소비자가 생기면 game-api
   `V2SandboxConfiguration`에 `@Bean` 한 줄로 붙는다. game-api 게이트 자체는 S2가 이미 설치했다.
3. **풀 `@SpringBootTest` + Testcontainers 컨텍스트에서의 v2 빈 0개.** `ApplicationContextRunner`로만
   쟀다. 실 부팅 실측은 **S4(0A-f)** 소관 — S4의 조회 타입에 `V2ContentCatalog`도 포함시켜야 한다.
4. **jar 밖(전개된 디렉터리·devtools·다중 클래스패스 루트) 동작.** `classpath*:`는 여러 루트를
   합치므로 다른 모듈이 같은 `content/v2/`를 제공하면 합쳐진다. 현재 제공자는 infra 하나뿐이며
   충돌 상황은 미측정.
5. **파일명 중복.** 서로 다른 클래스패스 루트에 같은 파일명이 있을 때 `read`가 무엇을 고르는지
   미측정(현재 루트 1개라 발생 불가).
6. **대용량 콘텐츠 성능.** `names()`는 호출마다 클래스패스를 스캔한다(캐시 없음). 콘텐츠 0개 기준이라
   측정 의미가 없어 캐시를 넣지 않았다. 실제 파일이 들어온 뒤 필요하면 그때 측정해서 붙인다.
