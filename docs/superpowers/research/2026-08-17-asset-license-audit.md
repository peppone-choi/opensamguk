# 에셋 라이선스 전수 감사 (2026-08-17)

- 범위: opensamguk 저장소에 **커밋된 모든 에셋**과 **에셋 생성 경로**, 런타임에 외부에서 당겨오는 이미지, 서드파티 번들.
- 기준선: `origin/main` = `2db5ea06`.
- 판정 규칙: 레인 C의 CHGIS 선례(`docs/loops/opensam-37-evidence-contracts-2026-08-16/chgis-license-review.md`)와 동일한 엄격도를 적용한다.
  라이선스 원문을 확보하지 못하면 `UNKNOWN`이며, `UNKNOWN`은 "아마 괜찮다"가 아니라 **차단 사유**다
  (`logic/src/main/kotlin/opensamguk/logic/v2/evidence/EvidenceContracts.kt:86-96`).
- 이 문서는 **감사와 문서화**다. 에셋을 추가·삭제·교체하지 않았다.

---

## 1. 요약

### 1-1. 즉시 조치 필요 (BLOCKED / NEEDS-REPLACEMENT)

| # | 항목 | 왜 즉시인가 |
|---|---|---|
| **A1** | **공개 CDN 저장소 `github.com/peppone-choi/opensamguk-images` 가 PUBLIC + MIT LICENSE 로 코에이 추정 장수 초상 ~4,167장을 재배포 중** | 이 저장소는 PRIVATE이지만 초상 자산은 이미 **공개 배포되고 있다**. 게다가 소유권이 없는 이미지에 MIT("sublicense, sell" 허용)를 붙였다 — 제3자에게 잘못된 권리를 부여하는 표기다. 저장소를 PRIVATE으로 돌려도 이 노출은 닫히지 않는다 |
| **A2** | **런타임 초상 fetch 경로가 A1 저장소를 기본값으로 하드코딩** (`web/game/lib/portrait.ts:17`, `web/gateway/lib/portrait.ts:3-19`) | 프로덕션이 켜지는 순간 코에이 추정 초상을 게임 화면에 서빙한다. `NEXT_PUBLIC_IMAGE_CDN` 오버라이드는 있으나 **기본값이 위험 경로** |
| **A3** | **`web/game/public/icons/*.gif`·`web/gateway/public/icons/*.gif` 14×2 = 28개 파일이 라이선스 없는 upstream(`devsam/image`)에서 verbatim 복사돼 커밋됨** | 저장소 자체 커밋 위반. 게다가 **코드에서 더 이상 참조하지 않는 고아 파일**(CDN으로 이전, 커밋 `1642bbaa`) — 위험만 남고 효용은 0 |
| **A4** | **저장소에 LICENSE / NOTICE / THIRD-PARTY 파일이 전혀 없다** | 서드파티 바이너리 에셋과 서드파티 폰트를 배포하면서 고지 파일이 없다 |

### 1-2. 안전한 것 (OK)

- `assets/battle/v2/**` 중 **생성 영수증이 있는 24개 병종 원본** — OpenAI `gpt-image-2` 생성물. 프롬프트에 "no Koei, no franchise imitation" 명시(§2 표 참조). 단 §1-3의 유보 참조.
- `data/extracted/**` (시나리오·상수·아이템 등 JSON) — 출처가 `legacy/devsam-core`(MIT, `legacy/devsam-core/LICENSE:1-3` "Copyright (c) 2023 Hide_D, 62che").
- `web/*/package.json` 의존성 전부 — **에셋을 동봉하는 패키지가 하나도 없다**. 폰트 패키지·아이콘 세트·CSS 프레임워크 0개.
- 인라인 SVG 0개 — 아이콘 세트 무단 복사 위험 없음.
- `docker/`, `infra/nginx/`, `scripts/` — 이미지에 굽는 폰트·이미지 다운로드 없음.
- `tools/rtk14/`, `tools/rtk-faces/` — **빌더 스크립트만 커밋**, 입력 원본·산출물 전부 미커밋 + fail-closed 가드. 설계상 안전(§4).

### 1-3. UNKNOWN (확인 못 함 — 사용 전 차단)

| 항목 | 무엇을 확인 못 했나 |
|---|---|
| `web/*/public/flags/flag-cloth-*.png`, `flag-pole-*.png` (8×2) | devsam `game/fFF0000.gif`에서 PIL로 추출한 **파생물**이라고 커밋 메시지가 말하지만, 원본 `devsam/image`에 **LICENSE 파일이 없다**. 원본이 무권리면 파생물도 무권리 |
| `assets/battle/v2/units/source/*.png` 중 **81개** (`origin: "adopted-v1"`) | `provider: null, model: null` — 어떤 생성기로 만들었는지 **기록이 없다** |
| `assets/battle/v2/terrain/source/*.png` 8장 → 파생 tiles 32장 | `sourceProvenance.kind = "adopted-existing"`, `provider: null, model: null`. `assets/battle/v2/README.md`가 스스로 "최초 생성 공급자와 모델은 확인할 영수증이 없어"라고 적었다 |
| `assets/battle/v2/effects/source/*.png` 18장 → atlases 2장 | 매니페스트에 `provider`/`model` 키 자체가 없다(문자열 검색 0회) |
| OpenAI 출력물 소유권 조항 (24개 생성 스프라이트에 적용) | `openai.com/policies/row-terms-of-use/` 와 help.openai.com 문서 모두 **WebFetch HTTP 403**. 검색 인덱스 요약은 "OpenAI assigns to you all right, title, and interest in and to Output"이라 하나 **원문 verbatim 미확보** |
| Pretendard 폰트 (`web/gateway/app/layout.tsx:13-16`, jsDelivr CDN 런타임 로드) | 업스트림이 SIL OFL 1.1로 알려져 있으나 **이 저장소에 OFL 원문·고지 없음**. 라이선스 파일을 확인해 넣기 전까지 고지 의무 미이행 |
| `game/3d/*.glb` 50개 (Meshy.ai 생성, CDN 저장소에만 존재) | `docs/wiki/pages/design/3d-asset-generation-meshy.md:14,39`이 "Pro 플랜 commercial OK"라고만 적었다. **약관 원문 인용 없음** |
| `docs/loops/**/*.png` 스크린샷 20장 | 자체 QA 스크린샷이지만, 화면에 코에이 추정 초상이 찍혔는지 픽셀 검사는 하지 않았다 |

---

## 2. 전수 표

`판정` 열: `OK` / `BLOCKED` / `UNKNOWN` / `NEEDS-REPLACEMENT`.

### 2-1. 커밋된 이미지 자산

| 항목 | 출처 | 라이선스 | 상업적 사용 | 재배포/번들 | 커밋 여부 | 판정 |
|---|---|---|---|---|---|---|
| `web/game/public/icons/cast_1..8.gif`, `event1..5.gif`, `event51.gif` (14) + `web/gateway/public/icons/` 동일 14 | `legacy/devsam-image/game/` **md5 완전 일치** (예: `cast_1.gif` = `6cd6c561e859285fb24f47b60dd7d3f1`, `event51.gif` = `f39e0a5c3d5ba5d7beaee5b89d7e714b`). upstream = `storage.hided.net/gitea/devsam/image` | **없음** — `devsam/image` 저장소에 LICENSE 파일 부재 (sibling `devsam-core`에는 있음) | 불명 | 불명 | **예** (커밋 `80b4a47a`, `cd81083c`) | **NEEDS-REPLACEMENT** — 코드 참조 0(커밋 `1642bbaa`가 CDN으로 이전), 삭제만 해도 위험 소멸 |
| `web/game/public/flags/flag-cloth-0..3.png`, `flag-pole-0..3.png` (8) + gateway 동일 8 | devsam `game/fFF0000.gif`에서 PIL 추출(커밋 `6017ad23` 본문). devsam에 동일 바이트 파일 없음 = 파생물 | 원본 라이선스 없음 → 파생물 권리 **미확인** | 불명 | 불명 | **예** | **UNKNOWN** — 사용 중(`web/*/lib/flagTint.ts:64-65`)이므로 §5 정책 결정 필요 |
| `assets/battle/v2/units/source/*.png` 24장 (`origin: generated`) | ppgen → `provider: openai`, `model: gpt-image-2` (`source-receipt-ledger.v1.json` `creation.tool`) | OpenAI 이용약관(출력물 소유권 이용자 귀속) — **원문 403으로 verbatim 미확보** | 약관상 허용으로 보고됨 | 프롬프트에 "no Koei, no franchise imitation" 고정 | 예 | **OK (조건부)** — §5-1 조치로 약관 원문 캡처 후 확정 |
| `assets/battle/v2/units/source/*.png` 81장 (`origin: adopted-v1`) | `provider: null, model: null` | **기록 없음** | 불명 | 불명 | 예 | **UNKNOWN** |
| `assets/battle/v2/units/sprites/*.png` 105장 | 위 105장을 `sprite-gen` 결정적 추출로 컷아웃 | 원본 상태를 그대로 승계 | 승계 | 승계 | 예 | 24장분 OK(조건부) / 81장분 **UNKNOWN** |
| `assets/battle/v2/terrain/source/*.png` 8장, `terrain/tiles/*.png` 32장 | `"adopted-existing"`, provider/model `null` (`terrain/manifest.json` `sourceProvenance`) | **기록 없음** | 불명 | 불명 | 예 | **UNKNOWN** |
| `assets/battle/v2/effects/source/*.png` 18장, `effects/atlases/*.png` 2장 | 매니페스트에 provider/model 키 없음. 컴파일러(`sprite-gen`) 정보만 존재 | **기록 없음** | 불명 | 불명 | 예 | **UNKNOWN** |
| `docs/loops/**/*.png` 20장 (board-editor, opensam-86, opensam-90) | 자체 Playwright QA 스크린샷 | 자체 제작 | OK | 저장소 내부 | 예 | **OK** (단 §1-3 유보) |

### 2-2. 런타임에 외부에서 가져오는 자산 (커밋 안 됨 ≠ 안전)

| 항목 | 출처 | 라이선스 | 상업적 사용 | 재배포/번들 | 커밋 여부 | 판정 |
|---|---|---|---|---|---|---|
| 장수 초상 `icons/<id>.jpg` (~4,167) | `cdn.jsdelivr.net/gh/peppone-choi/opensamguk-images/icons/` — `web/game/lib/portrait.ts:17,20,32-40`, `web/gateway/lib/portrait.ts:3-19`, 기본값 `web/game/lib/constants.ts:7` / `web/gateway/lib/constants.ts:12` | 해당 저장소는 **MIT를 표방**(`LICENSE`: "Copyright (c) 2026 peppone-choi") 하나, 내용물은 **코에이 테크모 삼국지 시리즈 초상으로 추정**(`docs/wiki/pages/design/image-asset-pipeline.md:84-86`, git-ignored 로컬 문서) | **불가로 보아야 함** | **현재 공개 재배포 중** | 아니오(fetch) | **BLOCKED** |
| 도시/이벤트 아이콘 `game/cast_*.gif`, `event*.gif` | 같은 CDN (`ICON_CDN`, `web/game/lib/constants.ts:15`) — 사용처 `web/game/components/game/MapViewer.tsx:606,630`, `web/gateway/components/MapPreview.tsx:362,369,385` | upstream `devsam/image` 무LICENSE | 불명 | 공개 재배포 중 | 아니오 | **UNKNOWN → 사실상 BLOCKED** |
| `app/gateway-api/src/main/resources/profile-icons/shared-manifest.json` 2개 항목(`1001.jpg`, `default.jpg`) | 위와 같은 CDN, 커밋 SHA로 핀 고정 | 매니페스트가 **스스로** `"license_status": "unknown"`, `"redistribution_status": "unknown"` 이라 적음 | 불명 | 불명 | 매니페스트만 커밋(바이너리 아님) | **UNKNOWN** — 정직한 기록. `bundled_cleared: []`로 번들은 0개 |
| 3D `game/3d/*.glb` 50개 | Meshy.ai 생성, CDN 저장소 | Meshy Pro 플랜 약관(원문 미인용) | "commercial OK"로만 기록 | 불명 | 아니오 | **UNKNOWN** |
| Pretendard 폰트 | `cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/...` (`web/gateway/app/layout.tsx:13-16`) | SIL OFL 1.1(업스트림) — 저장소에 원문·고지 없음 | OFL은 허용 | CDN 로드(번들 아님) | 아니오 | **UNKNOWN(고지 미비)** — 라이선스 자체는 허용적, **고지 의무 미이행** |
| `'JetBrains Mono'`, `'Fira Code'` (`web/game/app/globals.css:27`, `web/gateway/app/globals.css:28`) | 이름만 선언, fetch·번들 없음 | 해당 없음 | — | — | 아니오 | **OK** |

### 2-3. 데이터 에셋

| 항목 | 출처 | 라이선스 | 상업적 사용 | 재배포/번들 | 커밋 여부 | 판정 |
|---|---|---|---|---|---|---|
| `data/extracted/**` (시나리오 80+, 상수, 아이템, 성격, 맵, 제약) | `legacy/devsam-core/hwe/scenario/*` (`data/extracted/scenario/_meta.json` `source`), 커밋 `895c4d85` | **MIT** (`legacy/devsam-core/LICENSE:1-3`) | OK | OK | 예 | **OK** (단 §3-3 유보) |
| `data/scenarios/scenario_*.json`, `data/scenario-source/`, `data/scenarios/refined|reports/` | RTK14 5스탯 divergence 산출물 | 코에이 IP | 불가 | 불가 | **아니오** — `.gitignore:97-102`가 차단 | **OK (격리 성공)** |
| CHGIS / TGAZ 사료 지리 데이터 | Harvard/Fudan | EULA §3/§5 상업·재배포 금지, Dataverse CC0 표기와 충돌 | 불가 | 불가 | 아니오 | **BLOCKED** (기판정, `chgis-license-review.md`) |
| `content/v2/**` | **존재하지 않음** (디렉터리 없음) | — | — | — | — | 해당 없음 |

### 2-4. 서드파티 코드 의존성 (에셋 동봉 여부)

`web/game/package.json:12-21`, `web/gateway/package.json:12-19`. **에셋을 동봉하는 패키지 0개.**

| 패키지 | 버전 | 라이선스 | 에셋 동봉 | 판정 |
|---|---|---|---|---|
| `@sentry/nextjs` | 10.66.0 | MIT (설치본 `package.json` 확인) | 없음 | OK |
| `next` | 15.5.20 | MIT (설치본 확인) | 없음 | OK |
| `react`, `react-dom` | 19.0.0 | MIT (설치본 확인) | 없음 | OK |
| `three` | 0.171.0 | 미설치 — 선언·import만(`web/game/components/v2/SpaceProof3D.tsx:15`) | `three/examples` 미사용 | **UNKNOWN(미검증)** |
| `dompurify` | 3.4.13 | 미설치 | 없음 | **UNKNOWN(미검증)** |
| `@tiptap/*` | 3.29.2 / 3.30.0 | 미설치 | 없음 | **UNKNOWN(미검증)** |

`three`/`dompurify`/`@tiptap/*`는 업스트림이 허용적 라이선스로 알려져 있으나 **이 체크아웃에서 파일로 확인하지 못했다**. `pnpm install` 후 재확인 대상.

### 2-5. 에셋 생성 도구

| 도구 | 입력 | 출력 | 커밋되는 것 | 판정 |
|---|---|---|---|---|
| `tools/rtk14/build_rtk14_stats.py` | RTK14 무장 스탯 xlsx (코에이 IP) | `scenario_*.json` tuple 14/15 | **스크립트+테스트만** | **OK** (격리 성공) |
| `tools/rtk14/build_rtk14_hexmap.py` | RTK14 지도 원본 PNG (코에이 IP) | 헥스 지형 JSON | **스크립트+테스트만**. 헤더 `:6-9`가 "`RIGHTS WARN`… 원본 이미지도 산출 JSON도 커밋하지 않는다", repo-tracked 경로 fail-closed | **OK** (격리 성공) |
| `tools/rtk-faces/build_rtk14_faces.py` | **wikiwiki.jp / cdn.wikiwiki.jp** 에서 관측된 RTK14 무장 초상 URL (`:154-155`, `:179-180`) | 리사이즈된 초상 이미지 | **스크립트+테스트만**. `assert_safe_path()` `:257-265`가 tracked 경로 거부, 네트워크 경로 없음(캐시 미스 = FAIL) | **OK (도구)** / 산출물은 **BLOCKED** — §3-2 |
| `tools/assets/generate-v2-roster-static-sprites.mjs` | 병종 카탈로그 md + 프롬프트 | ppgen(OpenAI gpt-image-2) 1024px 원본 | 원본 PNG + 영수증 원장 | 24장 OK(조건부) / 81장 UNKNOWN |
| `tools/assets/compile-v2-{unit-static-sprites,terrain-core,battle-effects}.mjs` | 위 원본 + 지형/이펙트 소스 시트 | 런타임 스프라이트·타일·아틀라스 | 예 | 입력 상태 승계 |

---

## 3. 코에이 IP 노출 위험 항목

### 3-1. 🔴 최상위 — 공개 CDN 저장소가 코에이 추정 초상을 MIT로 재배포 중

- 저장소: `https://github.com/peppone-choi/opensamguk-images` — `gh repo view` 결과 **`"visibility": "PUBLIC"`, `"licenseInfo": {"key": "mit"}`**.
- 내용: `icons/`(초상 ~4,167장, `0.jpg`…`4000+.jpg`), `portraits/`, `game/`, `hook/`.
- LICENSE 원문: `MIT License / Copyright (c) 2026 peppone-choi / … rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell …`
- 이 저장소의 자체 문서가 이미 코에이 유래를 지목한다 — `docs/wiki/pages/design/image-asset-pipeline.md:84-86`(git-ignored 로컬):
  > `icons/` 4,400+ 파일 = 코에이 테크모(KOEI TECMO) 삼국지 시리즈 portrait로 추정
  > **M1 외부 공개 (5~10개월차) 전 라이선스 재검토 필수 TODO**
- **왜 최상위인가**: (a) opensamguk 저장소를 PRIVATE으로 유지하는 보호가 여기서 무력화된다. (b) 소유하지 않은 저작물에 MIT를 붙이는 것은 단순 무단 사용보다 **더 나쁜 표기 문제**다 — 제3자에게 재배포·판매 권리를 부여한 것처럼 보인다. (c) jsDelivr는 태그별 불변 캐시라 원본을 지워도 캐시가 남는다.

### 3-2. 🔴 RTK14 초상 수집 파이프라인 (`tools/rtk-faces/`)

- 소스 호스트가 `wikiwiki.jp` / `cdn.wikiwiki.jp`로 하드코딩(`build_rtk14_faces.py:154-155,179-180`) — RTK14 팬위키의 **무장 초상 이미지**다. 원저작권자는 코에이 테크모.
- 도구 자체는 모범적이다: 네트워크 경로 없음, 오퍼레이터가 캐시를 직접 공급, tracked 경로 fail-closed, 헤더가 "A manifest or cache entry does not itself establish reuse rights. Source/reuse rights still require separate clearance."라고 명시.
- **그러나 산출물에는 사용 권리가 없다.** 이 파이프라인의 출력은 어떤 형태로도 제품에 들어갈 수 없다. 현재 저장소에 산출물 커밋은 **0건**(확인함).

### 3-3. 🟡 RTK14 스탯·지도 (기존 격리, 유지 확인)

`tools/rtk14/` 두 빌더 모두 원본·산출물을 커밋하지 않고 스크립트만 버전 관리한다. `.gitignore:97-102`가 `**/rtk14_stats.local.json`, `data/scenarios/scenario_*.json`, `data/scenario-source/` 등을 차단한다. **현행 격리는 유효하다.** 다만 `data/extracted/scenario/*.json`(커밋됨)에는 devsam 유래 장수 이름·통무지가 들어 있다 — devsam은 MIT지만 **devsam 자체가 코에이 데이터를 어디까지 포함하는지는 이 감사 범위 밖이며 UNKNOWN**이다.

### 3-4. 🟡 devsam/image 유래 커밋 자산 (§2-1 icons/flags)

`legacy/devsam-image`에는 LICENSE 파일이 없다(`devsam-core`에는 MIT LICENSE가 있는 것과 대조). 같은 저장소의 `icons/`가 코에이 추정 초상이라는 점을 감안하면, **같은 저장소의 `game/` 아이콘도 출처 심사 대상**으로 봐야 한다.

### 3-5. 🟢 위험 아님으로 판정한 것

v2 병종 스프라이트의 프롬프트는 **모든 105행에 동일하게** `No text, no fantasy, no modern gear, no Total War, no Koei, and no franchise imitation.` 을 포함한다(`source-receipt-ledger.v1.json`). `assets/battle/v2/README.md` "출처와 IP 경계" 절도 "Total War, Koei 또는 다른 게임의 이미지·UI·실루엣을 복사하거나 참조 에셋으로 사용하지 않는다"고 명시한다. 코에이 IP 관점에서는 **깨끗한 트랙**이다 — 남은 문제는 코에이가 아니라 **생성기 출처 기록 누락**(§1-3)이다.

---

## 4. 비주얼 트랙 선행조건

각 트랙을 진행하려면 무엇이 먼저 닫혀야 하는가, 그리고 에셋 조달 경로 후보와 제약.

### 4-1. 장수 얼굴 (초상) — **가장 막혀 있다**

**선행조건 (순서대로)**
1. `portraitUrl()`의 기본 CDN을 코에이 추정 초상에서 떼어낸다 (`web/game/lib/portrait.ts:17`, `web/gateway/lib/portrait.ts`). 기본값이 위험 경로인 한 나머지 조치는 의미가 없다.
2. `peppone-choi/opensamguk-images` 를 PRIVATE 전환하거나 `icons/`·`portraits/`를 제거하고, **MIT LICENSE 표기를 내린다**(소유하지 않은 자산에 대한 권리 부여 표기 제거).
3. 대체 초상 세트를 조달한다(아래).
4. `SharedProfileIconCatalog`의 `license_status: "unknown"` 항목을 대체본으로 교체하고 `BUNDLED_CLEARED` 스코프로 올린다. 이 계약은 이미 존재하므로 **새로 만들 것은 없다**.

**조달 경로**

| 경로 | 제약 |
|---|---|
| **AI 0-shot 생성** (참조 이미지 없음) | 현실적 1순위. 이미 병종 스프라이트에서 검증된 파이프라인(ppgen/OpenAI). 제약: **생성기·모델·프롬프트를 영수증에 반드시 기록**해야 한다(현재 81장이 이걸 안 해서 UNKNOWN이 됐다). 약 4,000장 규모면 비용·시간이 실질 제약. 인물 동일성 유지가 어렵다 |
| **img2img 스타일 변환** (기존 코에이 초상 기반) | **권장하지 않는다.** 로컬 wiki 문서가 "변환 강도 ≥0.7이면 transformative 주장 가능"이라 적었지만, 한국·일본 저작권법에서 2차적저작물은 원저작자 권리에 종속된다. 이건 법률 의견이 아니라 **위험 등급이 UNKNOWN이라는 사실 기술**이다. 원본 접근 자체가 §3-2 문제를 재발시킨다 |
| **직접 제작 / 발주** | 권리 명확. 비용·리드타임이 제약. 4,000장 전량은 비현실적 — 등장 빈도 상위 N명만 우선 |
| **CC0·퍼블릭도메인** | 삼국지 인물 초상에 쓸 만한 CC0 세트가 사실상 없다. 청대 판화 등 PD 자료는 화풍·수량이 안 맞는다 |
| **초상 없이 출시** | 가장 싼 선행조건 해제. 이니셜/실루엣/색상 뱃지로 대체하고 초상 트랙을 나중에 연다 |

### 4-2. 병종 스프라이트 (2D)

**선행조건**
1. **81개 `adopted-v1` 원본의 생성 출처를 확정한다.** `build/perfectpixel/v2-battle/roster-static/` 캐시 영수증에 provider/model이 남아 있으면 원장에 승격, 없으면 **`--force --only <slug>`로 재생성**한다(README에 절차 있음). 재생성이 UNKNOWN을 닫는 가장 확실한 방법이다.
2. OpenAI 이용약관 출력물 소유권 조항을 **브라우저로 캡처해 원문 인용**한다(WebFetch 403 — CHGIS 때와 같은 우회: Playwright).
3. 그 다음에야 런타임 배선. 현재 `assets/battle/v2/**`는 **컴파일러 외에 어떤 코드도 참조하지 않는다**(확인함) — 즉 지금 배선 전에 닫으면 비용 0.

**조달 경로**: 현행 절차적 생성(ppgen + sprite-gen 결정적 컷아웃)이 그대로 최적. 프롬프트에 프랜차이즈 배제 문구가 이미 고정돼 있다. 제약은 "영수증 없는 에셋을 원장에 넣지 않는다" 규율뿐.

### 4-3. 지형·이펙트 (2D 타일 / 아틀라스)

**선행조건**: 8장의 지형 소스 시트와 18장의 이펙트 소스가 `adopted-existing` = **출처 불명**이다. 파생물인 32 타일 + 2 아틀라스가 전부 여기에 종속된다. 소스 26장을 **기록된 파이프라인으로 재생성**하면 40장 가까이가 한 번에 UNKNOWN에서 빠져나온다. 이게 2D 비주얼 트랙에서 가장 레버리지 큰 단일 조치다.

**조달 경로**: 재생성(1순위) / CC0 타일셋(예: Kenney, OpenGameArt CC0 — 단 삼국시대 톤과 안 맞아 리터치 필요) / 직접 제작.

### 4-4. 3D 지형·유닛

**선행조건**
1. Meshy 약관 원문에서 **출력물 소유권 + 상업 재배포 조항**을 인용해 기록한다. 현재 근거는 wiki 한 줄("Pro 플랜 commercial OK")뿐이다. 플랜별로 다르면 **어느 플랜으로 생성했는지**도 영수증에 남아야 한다.
2. `.glb` 50개가 §3-1의 같은 공개 저장소에 얹혀 있다 — 저장소 정리와 함께 이동한다.
3. 이 저장소에는 3D 자산이 **한 개도 커밋돼 있지 않다**. 배선 전에 닫으면 비용 0.

**조달 경로**: Meshy(약관 확인 후) / Poly Haven·Kenney 등 CC0 3D / 직접 제작 / 절차적 생성(지형 메시는 하이트맵에서 절차적 생성 가능 — 라이선스 문제 자체가 없다. **지형에 한해 1순위**).

### 4-5. 폰트

**선행조건**: Pretendard OFL 1.1 원문을 저장소에 두고(`licenses/` 또는 `NOTICE`) 고지한다. `web/game`은 Pretendard를 CSS에서 부르지만 실제로 로드하지 않아 폴백 중이므로(`web/game/app/layout.tsx`에 링크 없음), **고지와 로드를 같이 정리**한다. 오프라인·차단 환경을 고려하면 self-host(OFL은 허용)가 낫다.

---

## 5. 권고 정책

재발을 막기 위해 `CLAUDE.md`(또는 계약 코드)에 못박을 규칙.

### 5-1. 즉시 (이번 주)

| # | 조치 | 대상 |
|---|---|---|
| P0-1 | `peppone-choi/opensamguk-images` PRIVATE 전환 + **MIT LICENSE 표기 제거**. 최소한 LICENSE를 "코드에만 적용, 이미지 자산은 제3자 권리" 로 정정 | 사용자 본인 결정 필요 |
| P0-2 | `web/*/public/icons/**` 28개 파일 삭제 (코드 참조 0) | 별도 티켓 |
| P0-3 | `portraitUrl()` 기본 CDN에서 코에이 추정 초상 경로 제거 또는 기본값을 `DEFAULT_PORTRAIT`로 강등 | 별도 티켓 |
| P0-4 | 저장소 루트에 `LICENSE` + `NOTICE`(서드파티 고지: Pretendard OFL, devsam-core MIT) 추가 | 별도 티켓 |

### 5-2. `CLAUDE.md`에 추가할 하드 룰 (제안 문구)

> **에셋 권리 규율 (하드 룰, 에이전트가 약화 불가)**
> 1. **에셋은 영수증 없이 커밋하지 않는다.** 저장소에 들어오는 모든 이미지·폰트·3D·오디오는 같은 커밋에 provider/model/prompt(생성물) 또는 출처 URL+라이선스 원문 인용(외부물)을 담은 매니페스트 행을 동반한다. `provider: null` / `origin: adopted-*` 는 **UNKNOWN이며 머지 차단 사유**다.
> 2. **`legacy/` 유래 바이너리는 커밋 금지.** 코드·데이터 포팅은 허용, 바이너리 자산 복사는 금지. `legacy/devsam-image`는 검증용 참조일 뿐 소스가 아니다.
> 3. **런타임 fetch도 배포다.** 외부 CDN에서 당겨오는 자산은 "커밋 안 했으니 안전"이 아니다. 기본값 URL은 커밋된 자산과 동일한 권리 심사를 받는다.
> 4. **소유하지 않은 자산에 라이선스를 붙이지 않는다.** 미러 저장소에 MIT/Apache 등 권리 부여 표기를 붙이는 것은 무단 사용보다 무겁다.
> 5. **UNKNOWN은 차단이다.** 라이선스 원문(URL + 인용문)을 확보하지 못하면 `LicenseBundling.UNKNOWN`이며, 추측으로 `BUNDLING_ALLOWED`나 `RESEARCH_ONLY`로 올리지 않는다. 접근 실패(403 등)는 "확인됨"이 아니라 UNKNOWN이다.
> 6. **레퍼런스 이미지 금지.** 생성 프롬프트에 특정 프랜차이즈(코에이·Total War 등)를 참조·모방시키지 않으며, 타사 에셋을 img2img 입력으로 쓰지 않는다.

### 5-3. 계약 코드로 강제할 것 (기존 자산 재사용)

이미 있는 두 계약을 **에셋에도 확장**하면 새 인프라가 필요 없다:

- `LicenseBundling` / `SourceLicense` (`EvidenceContracts.kt:86-118`) — 지금은 사료 데이터 전용이다. `SharedProfileIconEntry.licenseStatus: String = "unknown"`(`SharedProfileIconCatalog.kt:31`)를 **String에서 `LicenseBundling` enum으로 바꾸면** 아이콘 카탈로그가 같은 게이트를 탄다.
- `tools/agent-system/check.py` — 새 바이너리 에셋이 커밋되는데 대응 매니페스트 행이 없으면 finding을 내는 체크를 추가하면 5-2의 1번이 자동 강제된다.

### 5-4. 감사 재실행

이 문서는 `origin/main` = `2db5ea06` 시점의 스냅샷이다. 새 에셋 도입 PR마다 §2 표에 행을 추가하고, 비주얼 트랙 착수 전 전체 재실행한다.

---

## 부록 A. 확인 실패 기록 (재검증하는 사람 주의)

| URL | 결과 |
|---|---|
| `https://openai.com/policies/row-terms-of-use/` | **HTTP 403** (WebFetch). 브라우저 필요 |
| `https://help.openai.com/en/articles/5008634-…` | **HTTP 403** (WebFetch). 브라우저 필요 |

CHGIS 검토와 동일한 우회(Playwright로 브라우저 취득)가 필요하다.

## 부록 B. 본 감사에서 확인한 것 / 확인하지 않은 것

**확인함**: 커밋된 전 에셋 파일 목록(git ls-files 확장자 스캔), 각 파일의 매니페스트/영수증 provenance 필드, devsam-image와의 md5 대조, 공개 CDN 저장소의 visibility·LICENSE·디렉터리 구조(gh api), `web/*` 의존성 전수와 폰트 로드 경로, Dockerfile 에셋 다운로드 유무, `.gitignore`/`.claudeignore` 커버리지, 에셋 생성 도구 4종의 입출력과 fail-closed 가드.

**확인하지 않음**: 코에이 추정 초상의 실제 코에이 원본 대조(원본 미보유), `docs/loops/**` 스크린샷 내 초상 픽셀 검사, devsam/core MIT 저작권자가 코에이 데이터를 어떤 근거로 배포했는지, 미설치 npm 패키지 3종의 라이선스 파일, Meshy·Pretendard 약관 원문.
