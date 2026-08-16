# 에셋 라이선스 전수 감사 (2026-08-17)

- 범위: **메인 레포**(`peppone-choi/opensamguk`)에 커밋된 모든 에셋·에셋 생성 경로 + **별도 에셋 레포**(`peppone-choi/opensamguk-images`, jsDelivr CDN) 전량 + 두 레포를 잇는 참조 경로.
- 기준선: 메인 `origin/main` = `2db5ea06` / 에셋 레포 `main`(blob 11,666, 최종 push `2026-07-18T14:26:17Z`, 태그 `v2026.05.21` 1개).
- 목적: **라이선스 분리** — 자작 자산과 제3자 파생 자산의 경계를 파일 단위로 확정한다. 공개/비공개 전환, 삭제·교체, 대체 에셋 조달은 이 문서의 범위가 아니다.
- 판정 규칙: 레인 C의 CHGIS 검토(`docs/loops/opensam-37-evidence-contracts-2026-08-16/chgis-license-review.md`)와 동일. 라이선스 원문을 확보하지 못하면 `UNKNOWN`이며, `UNKNOWN`은 제품 자산 검증에서 차단된다(`logic/src/main/kotlin/opensamguk/logic/v2/evidence/EvidenceContracts.kt:86-96`).
- 본 감사는 문서화만 수행했다. 에셋을 추가·삭제·교체하지 않았다.

---

## 1. 요약

### 1-1. 라이선스 경계 (한 줄)

| 구획 | 파일 수 | 성격 |
|---|---|---|
| 자작 — 프로젝트가 만든 것 | 메인 레포 285 + 에셋 레포 3 | v2 전투 에셋 265, QA 스크린샷 20, 에셋 레포 루트 문서 3 |
| 자작 **변환** / 제3자 **콘텐츠** | 에셋 레포 7,015 | `portraits/rtk14/**` — 파이프라인·크롭·매니페스트는 자작, 원본 이미지는 RTK14 |
| 제3자 미러 (자작 아님) | 에셋 레포 4,648 + 메인 레포 44 | `devsam/image` 전량 미러(`game`/`icons`/`hook`) + 메인 레포에 복사된 아이콘·깃발 |
| 제3자 데이터 (허용 라이선스) | 메인 레포 82+ | `data/extracted/**` ← `legacy/devsam-core` MIT |

메인 레포 285 = v2 병종 원본 105 + 런타임 105 + 지형 40 + 이펙트 20(소스 18 + 아틀라스 2) − 중복 5(매니페스트류 제외) 로 계산한 이미지 파일 기준이며, 정확한 내역은 §3 표를 따른다.

### 1-2. UNKNOWN 목록 (원문 근거 미확보 → 차단)

| 항목 | 무엇을 확인 못 했나 |
|---|---|
| 에셋 레포 `icons/**` 4,167장 전체 | upstream `devsam/image`에 LICENSE 파일 없음. 서브디렉터리별 원저작권자는 **각기 다른 제3자**(§2-2) |
| 에셋 레포 `portraits/rtk14/**` 원본 1,000장 | RTK14 wikiwiki 첨부 이미지. wikiwiki 및 코에이 테크모의 재사용 허가 문서 미확인 |
| 에셋 레포 `game/**` 476장, `hook/*.php` 5개 | 같은 무LICENSE upstream |
| 메인 레포 `web/*/public/flags/*.png` 16장 | devsam `game/fFF0000.gif` 파생. 원본 무LICENSE → 파생물 권리 미확인 |
| `assets/battle/v2/units/source/*.png` 81장 (`origin: adopted-v1`) | `provider: null, model: null` — 생성기 기록 없음 |
| `assets/battle/v2/terrain/source/*.png` 8장 → tiles 32장 | `sourceProvenance.kind = "adopted-existing"`, provider/model `null` |
| `assets/battle/v2/effects/source/*.png` 18장 → atlases 2장 | 매니페스트에 provider/model 키 자체가 없음 |
| OpenAI 출력물 소유권 조항 (생성 24장에 적용) | `openai.com/policies/row-terms-of-use/`·`help.openai.com` 모두 **WebFetch HTTP 403**, verbatim 미확보 |
| Pretendard (`web/gateway/app/layout.tsx:13-16`) | 업스트림 SIL OFL 1.1로 알려짐. 이 레포에 원문·고지 파일 없음 |
| `game/3d/*.glb` 50개 (에셋 레포 미보유 — wiki 기록만) | Meshy 약관 원문 미인용 |
| `three` / `dompurify` / `@tiptap/*` | 선언·import 되나 `node_modules` 미설치 → 라이선스 파일 미확인 |

### 1-3. 근거 있는 허용 (OK)

- `data/extracted/**` — `legacy/devsam-core` MIT (`legacy/devsam-core/LICENSE:1-3`, "Copyright (c) 2023 Hide_D, 62che").
- `assets/battle/v2/units/source/*.png` 중 24장 — `provider: openai`, `model: gpt-image-2` 영수증 보유(§1-2의 약관 원문 유보 포함).
- `web/*/package.json` 의존성 — **에셋을 동봉하는 패키지 0개**. 폰트 패키지·아이콘 세트·CSS 프레임워크 없음, 인라인 SVG 0개.
- `docker/`·`infra/nginx/`·`scripts/` — 이미지에 굽는 폰트·이미지 다운로드 없음.
- `tools/rtk14/`, `tools/rtk-faces/` — 빌더 스크립트만 커밋, 입력 원본·산출물 미커밋 + tracked 경로 fail-closed(§5).
- `'JetBrains Mono'`, `'Fira Code'` — CSS 이름 선언만, fetch·번들 없음.

---

## 2. 에셋 레포 `peppone-choi/opensamguk-images`

- visibility **PUBLIC**, default branch `main`, blob **11,666**, 태그 `v2026.05.21` 1개.
- 루트 `LICENSE` = **MIT, "Copyright (c) 2026 peppone-choi"** — 저장소 전체에 걸린 단일 표기이며, 아래 §2-2·§2-3의 제3자 저작물도 이 표기 아래 놓여 있다.
- 루트 `README.md`는 구조를 "`game/` — game assets · `icons/` — icon images · `hook/` — hook assets"로만 적고 출처를 적지 않는다. 출처 기록은 `portraits/rtk14/README.md`에만 있다.
- 배포 URL: `https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-images@<tag>/` (immutable 태그).

### 2-1. 디렉터리 전수

| 경로 | blob | 내용 | 출처 |
|---|---|---|---|
| (root) | 3 | `LICENSE`, `README.md`, `.gitignore` | 자작 |
| `game/` (하위 map 11 포함) | 427 | 깃발 `b<HEX>.png` 34, 도시아이콘 `cast_*.gif`, 상태 `event*.gif`, 배경 `back*.jpg`, `crewtype*.png`, 맵 타일 `map/{che,chess,cr,ludo_rathowm,pokemon_v1}` | `devsam/image` |
| `game/src/` | 49 | 병종 일러스트 `각궁병.jpg`·`기병.jpg`·`목우.jpg` 등 한글 병종명 | `devsam/image` (원저작자 UNKNOWN) |
| `hook/` | 5 | `hook.php`, `git_pull.php`, `InstallKey.php`, `HashKey.orig.php`, `gogs_key.orig.php` — 운영 PHP 스크립트 | `devsam/image` |
| `icons/` (루트) | 1,832 | 삼국지 장수 초상 `0.jpg`…`4000+.jpg` (번호 체계) | `devsam/image` |
| `icons/<프랜차이즈>/` 10개 | 2,335 | §2-2 | `devsam/image` |
| `portraits/rtk14/` | 7,015 | §2-3 | RTK14 wikiwiki |

**`game`·`icons`·`hook` = `devsam/image` 파일명 완전 일치 미러.** 로컬 클론 `legacy/devsam-image`와 대조 결과 `game` 476/476, `icons` 4,167/4,167, `hook` 5/5 — **차집합 0**(CDN 전용 0, 로컬 전용 0). 바이트 일치는 메인 레포에 복사된 14개 아이콘에서 md5로 확인했다(예: `cast_1.gif` = `6cd6c561e859285fb24f47b60dd7d3f1`, `event51.gif` = `f39e0a5c3d5ba5d7beaee5b89d7e714b`). `devsam/image`(origin `https://storage.hided.net/gitea/devsam/image.git`)에는 **LICENSE 파일이 없다** — MIT LICENSE가 있는 sibling `devsam-core`와 대조된다.

### 2-2. `icons/` 서브디렉터리 — RTK14 외 제3자 저작물

루트 1,832장(삼국지 초상) 외에, 프랜차이즈·실존인물별 서브디렉터리 10개 2,335장이 있다. 각각 **원저작권자가 서로 다르다**.

| 디렉터리 | 파일 | 파일명 예시 | 추정 원저작권자 | 판정 |
|---|---|---|---|---|
| `걸그룹` | 530 | `AOA 민아.png`, `ITZY 예지.png`, `CL.png` | **실존 인물 사진** — 사진 저작권 + 초상권(퍼블리시티권) | **UNKNOWN → 제거됨(2026-08-17, 부록 D)** |
| `루드라사움` | 464 | `가넷.png`, `가라샤.png`, `3G.png` | 출처 미확인 | **UNKNOWN → 제거됨(2026-08-17, 부록 D)** |
| `롤시나리오` | 439 | `가렌.png`, `갈리오.png`, `Faker.jpg` | Riot Games (League of Legends) + 실존 프로게이머 사진 | **UNKNOWN → 제거됨(2026-08-17, 부록 D)** |
| `스타1프로게이머` | 297 | `강민.jpg`, `강도경.jpg`, `hao lei.jpg` | **실존 인물 사진** | **UNKNOWN → 제거됨(2026-08-17, 부록 D)** |
| `포켓몬스터` | 291 | `갸라도스.png`, `강철톤.png` | The Pokémon Company / Nintendo / Game Freak | **UNKNOWN → 제거됨(2026-08-17, 부록 D)** |
| `환상향` | 176 | `구마리사.png`, `겐지.png` | 東方Project (上海アリス幻樂団) 및 2차 창작 | **UNKNOWN → 제거됨(2026-08-17, 부록 D)** |
| `강서유서월드` | 108 | `가마오.webp`, `니아드라.webp` | 출처 미확인 | **UNKNOWN → 제거됨(2026-08-17, 부록 D)** |
| `쿠키런킹덤` | 22 | `용감한쿠키.png`, `어둠마녀.png` | Devsisters | **UNKNOWN → 제거됨(2026-08-17, 부록 D)** |
| `삼모시네마틱유니버스` | 7 | `사스케.jpg`, `정승필.png` | 커뮤니티 창작 추정, 출처 미확인 | **UNKNOWN → 제거됨(2026-08-17, 부록 D)** |
| `삼국지6` | 1 | `헌제.jpg` | 코에이 테크모 (삼국지6) | **UNKNOWN → 제거됨(2026-08-17, 부록 D)** |

### 2-3. `portraits/rtk14/` — 자작 변환 / RTK14 콘텐츠

`portraits/rtk14/README.md` 원문:

> - 원본: **RTK14 wikiwiki** (`https://wikiwiki.jp/sangokushi14/`) 의 인물 일러스트.
>   각 인물 페이지의 첨부 이미지(`cdn.wikiwiki.jp/.../::attach/*.jpg`)를 취득한다.
> - 취득 URL은 매니페스트/리포트 파일에 인물명과 함께 기록된다.

| 하위 경로 | 파일 | 내용 |
|---|---|---|
| `original/` | 1,000 | 취득 원본 바이트 보존. 파일명 = 원본 SHA-256, 확장자 `.bin` |
| `full-frame-148x210/` | 1,000 | 원본 전체 프레임 148×210 PNG 리사이즈 |
| `face-crop-148x210/` | 1,000 (+`report.tsv`, `qc/` 2) | YuNet 얼굴 검출 기반 크롭(얼굴높이 ×2.1, face_y 37%) |
| `face-icon-96/` | 1,000 (+`report.tsv`) | 96×96 얼굴 아이콘(얼굴높이 ×2.0, y 50%) |
| `serving/portrait`·`serving/icon`·`serving/original` | 각 1,000 | id 키(10001–11000) 서빙 사본 |
| `manifest/` | 8 | `rtk14-name-file-map.tsv`(정본 1,000행, 인물명·mode·**source_url**·cache_path·output_path), `officer-id-registry.tsv`, `mismatch-judgment.txt`, famous20 리포트류 |
| `README.md`, `.gitattributes` | 2 | 문서 |

- 취득·가공 파이프라인은 메인 레포 `tools/rtk-faces/build_rtk14_faces.py`다(README 명시). 검출 파라미터·크롭 판정·QC 증적·id 레지스트리는 **프로젝트 자작 산출물**이고, 픽셀 콘텐츠는 RTK14 일러스트다.
- `manifest/rtk14-name-file-map.tsv`(653KB)에 인물별 **취득 URL이 그대로 기록**돼 있다. `mismatch-judgment.txt`에도 `https://cdn.wikiwiki.jp/to/w/sangokushi14/%E6%9D%8E%E8%A1%A1/::attach/…` 형태의 원본 URL이 남아 있다.
- **메인 레포·프로덕션 어디에서도 아직 참조되지 않는다**(`portraits/rtk14`, `serving/portrait`, `officer-id-registry` grep 결과 0). README도 "CDN 태깅은 **활성화 시점에** 별도로 부여한다", id 범위는 "라이브 컷오버 시 고정"이라고 적어 미활성 상태임을 밝힌다.

---

## 3. 메인 레포 커밋 자산

| 항목 | 출처 | 라이선스 | 커밋 여부 | 판정 |
|---|---|---|---|---|
| `web/game/public/icons/*.gif` 14 + `web/gateway/public/icons/*.gif` 14 | `legacy/devsam-image/game/` md5 완전 일치 | 무LICENSE upstream | 예 (커밋 `80b4a47a`, `cd81083c`) | **UNKNOWN** — 코드 참조 0(커밋 `1642bbaa`가 CDN 단일출처로 이전) |
| `web/game/public/flags/*.png` 8 + gateway 8 | devsam `game/fFF0000.gif`에서 PIL 추출(커밋 `6017ad23`) | 원본 무LICENSE → 파생 권리 미확인 | 예 | **UNKNOWN** — 사용 중(`web/*/lib/flagTint.ts:64-65`) |
| `assets/battle/v2/units/source/*.png` 24 (`origin: generated`) | ppgen → `creation.tool = {provider: openai, model: gpt-image-2}` | OpenAI 약관(원문 403 미확보) | 예 | **OK (조건부)** |
| `assets/battle/v2/units/source/*.png` 81 (`origin: adopted-v1`) | provider/model `null` | 기록 없음 | 예 | **UNKNOWN** |
| `assets/battle/v2/units/sprites/*.png` 105 | 위 105장의 `sprite-gen` 결정적 컷아웃 | 원본 승계 | 예 | 24장분 OK(조건부) / 81장분 **UNKNOWN** |
| `assets/battle/v2/terrain/source` 8 + `tiles` 32 | `"adopted-existing"`, provider/model `null` | 기록 없음 | 예 | **UNKNOWN** |
| `assets/battle/v2/effects/source` 18 + `atlases` 2 | 매니페스트에 provider/model 키 없음 | 기록 없음 | 예 | **UNKNOWN** |
| `docs/loops/**/*.png` 20 | 자체 Playwright QA 스크린샷 | 자작 | 예 | **OK** (화면에 CDN 초상이 찍혔는지 픽셀 검사는 하지 않음) |
| `data/extracted/**` | `legacy/devsam-core/hwe/scenario/*` (`data/extracted/scenario/_meta.json`), 커밋 `895c4d85` | **MIT** | 예 | **OK** |
| `data/scenarios/scenario_*.json`, `data/scenario-source/`, `**/rtk14_stats.local.json` | RTK14 5스탯 divergence 산출물 | 코에이 IP | **아니오** — `.gitignore:96-102` 차단(`infra/src/main/resources/scenario/rtk14_stats.local.json`도 `git check-ignore` 확인) | **격리됨** |
| CHGIS / TGAZ | Harvard/Fudan | EULA §3/§5 상업·재배포 금지 | 아니오 | **BLOCKED** (기판정) |
| `content/v2/**` | 디렉터리 없음 | — | — | 해당 없음 |

**서드파티 코드 의존성**: `web/game/package.json:12-21`, `web/gateway/package.json:12-19`. `@sentry/nextjs` 10.66.0 / `next` 15.5.20 / `react`·`react-dom` 19.0.0 = MIT(설치본 확인). `three` 0.171.0 / `dompurify` 3.4.13 / `@tiptap/*` 3.29.2·3.30.0 = 미설치(§1-2). **에셋 동봉 패키지 0개.** 폰트는 Pretendard 1종만 CDN 런타임 로드(`web/gateway/app/layout.tsx:13-16`)이며 `web/game`은 CSS에 선언만 하고 로드하지 않는다(`web/game/app/globals.css:26`). `.woff`/`.ttf`/`.otf` 파일과 `@font-face`·`next/font`는 저장소에 0개.

---

## 4. 메인 레포·프로덕션 → CDN 참조 경로

### 4-1. 코드

| 경로 | 내용 |
|---|---|
| `web/game/lib/constants.ts:6-7` | `IMAGE_CDN_BASE = process.env.NEXT_PUBLIC_IMAGE_CDN ?? 'https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-images'` — **기본값 하드코딩** |
| `web/gateway/lib/constants.ts:11-12` | 동일 |
| `web/game/lib/constants.ts:10` | `MAP_CDN = ${IMAGE_CDN_BASE}/game/map` |
| `web/game/lib/constants.ts:15` | `ICON_CDN = ${IMAGE_CDN_BASE}/game` |
| `web/game/lib/portrait.ts:17,20` | `PORTRAIT_CDN = ${IMAGE_CDN_BASE}/icons`, `DEFAULT_PORTRAIT = .../icons/default.jpg` |
| `web/game/lib/portrait.ts:32-40` | `portraitUrl(picture, imageServer)` — `imageServer=0` → `${PORTRAIT_CDN}/${picture}`(확장자 없으면 `.jpg` 부착), truthy → `/d_pic/` |
| `web/gateway/lib/portrait.ts:3-19` | 동일 계약 |
| 초상 호출부 | `web/game/app/game/generals/page.tsx:254`, `my-generals/page.tsx:140`, `rankings/generals/page.tsx:132`, `select-pool/page.tsx:176`, `join/page.tsx:450,551`, `components/game/GeneralBasicCard.tsx:238` |
| 아이콘 호출부 | `web/game/components/game/MapViewer.tsx:606,630`, `web/gateway/components/MapPreview.tsx:362,369,385` |
| `app/gateway-api/src/main/resources/profile-icons/shared-manifest.json:18,21,37,40` | `source_repository: https://github.com/peppone-choi/opensamguk-images`, `delivery_url` = 커밋 SHA `1b6624d8…`로 핀 고정된 jsDelivr URL 2건(`icons/1001.jpg`, `icons/default.jpg`). 두 항목 모두 `"license_status": "unknown"`, `"redistribution_status": "unknown"`, `bundled_cleared: []` |
| `app/gateway-api/.../SharedProfileIconCatalog.kt:145` | `https://cdn.jsdelivr.net/gh/$repositorySlug@${entry.sourceRevision}/${entry.sourcePath}` 조립 |

### 4-2. 설정·배포

| 경로 | 내용 |
|---|---|
| `.env.example:116-118` | `NEXT_PUBLIC_IMAGE_CDN=https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-images` (주석: "선택 — 미설정 시 기본값 사용") |
| `app/gateway-api/.../DeployService.kt:43` | `NEXT_PUBLIC_IMAGE_CDN`이 `sharedEnvKeys`에 포함 — 어드민 배포 경로가 이 값을 전 서버에 전파 |
| `README.md:11` | 관련 저장소로 `opensamguk-images`(jsDelivr CDN) 명시 |

즉 **미설정이 곧 CDN 사용**이다. 환경변수는 오버라이드 수단이지 차단 수단이 아니다.

### 4-3. `d_pic`와의 관계 — 별개 경로

`/d_pic/`는 CDN과 **무관한 same-origin 경로**다.

- `infra/nginx/default.conf:39-46`(및 `:196`, `nginx.conf:105`) — `location ~ "^/d_pic/(?<profile_icon>[0-9a-f]{8}\.(?:avif|webp|jpg|png|gif))$"` → `alias /var/lib/opensamguk/profile-icons/$profile_icon`.
- `infra/nginx/default.conf:49-51`(`:206`, `nginx.conf:116`) — 그 외 모든 `/d_pic/` 경로는 `return 404`.
- `docker-compose.yml:277-281`, `docker-compose.production.yml:244-247` — `profile-icons` named volume을 nginx에 **읽기 전용** 마운트, gateway-api가 유일 writer.

분기점은 DB `image_server` 컬럼이다(`infra/.../UserEntity.kt:62-63`). `0` = 공유 초상 → **CDN**, truthy = 사용자 업로드 → **`/d_pic/`**(canonical 8-hex 파일명이 아니면 기본 초상 폴백). 따라서 `/d_pic/`에 놓이는 것은 사용자가 올린 파일이고, 제3자 에셋 노출 경로는 CDN 쪽 하나다.

### 4-4. DB 시드 → CDN 경로 결합 (프랜차이즈 아이콘)

`infra/src/main/kotlin/opensamguk/infra/seed/ScenarioImporter.kt:516-533` `resolvedScenarioPicture()`가 시드 시 `picture` 컬럼 값을 만든다:

- 숫자 코드면 `storedIconByKey(".", n)` 조회, 아니면 `picturePath = "$iconPath/$picturePath"` (`:527-528`) — **시나리오의 `iconPath`가 경로 접두사가 된다**.
- `iconPath`는 시나리오 JSON의 필드다(`ScenarioJson.kt:74,313`).
- FE는 이 값을 `${IMAGE_CDN_BASE}/icons/<picture>`로 해석한다(`web/game/lib/portrait.ts:39`).

커밋된 `data/extracted/scenario/*.json` 82개 중 16개가 `iconPath`로 **§2-2의 프랜차이즈 디렉터리명과 정확히 같은 값**을 갖는다:

| iconPath | 시나리오 |
|---|---|
| `롤시나리오` | 2900, 2901, 2903, 2904 |
| `환상향` | 2130, 2131 |
| `걸그룹` | 2140, 2141 |
| `삼모시네마틱유니버스` | 2600, 2601 |
| `강서유서월드` | 2800, 2801 |
| `루드라사움` | 2171 |
| `스타1프로게이머` | 2200 |
| `포켓몬스터` | 2210 |
| `쿠키런킹덤` | 2300 |

나머지 65개는 `"."`(루트 `icons/`).

**현재 시딩 대상에는 포함되지 않는다.** 시드는 classpath 또는 `SCENARIO_DIR`에서 파일을 읽는데(`ScenarioSeedRunner`), 커밋된 classpath 리소스 `infra/src/main/resources/scenario/`에는 31개(0·1·2·1010~1120·900~914)만 있고 **2xxx 프랜차이즈 시나리오는 없다**. 프로덕션 기본값도 `SCENARIO_CODE: scenario_1010`(`docker-compose.production.yml:67`)이다. 즉 프랜차이즈 아이콘 경로는 **데이터로는 레포에 있고, 실행 경로로는 비활성**이었다.

**2026-08-17 갱신: 위 16개 시나리오는 제거됐다** — `data/extracted/scenario/`에서 삭제, `_meta.json`에서 해당 항목 제거. 실행 경로가 비활성이었다는 사실 자체가 제거 근거는 아니다(실행 경로 비활성 ≠ 저장소에 남겨도 안전) — 판정 근거는 §2-2와 동일(초상권·제3자 IP·출처 미확인). 상세: 부록 D.

---

## 5. 자작 / 제3자 파생 경계

라이선스 분리 시 그어야 할 선.

### 5-1. 자작 (프로젝트 저작물)

| 자산 | 근거 |
|---|---|
| `assets/battle/v2/**` 전량(265 이미지 + 매니페스트·영수증) | 프롬프트·카탈로그·컴파일러 모두 프로젝트 산출. 전 105행 프롬프트에 `No text, no fantasy, no modern gear, no Total War, no Koei, and no franchise imitation.` 고정. `assets/battle/v2/README.md` "출처와 IP 경계" 절이 타사 이미지 참조·복사를 금한다. **단 생성기 출처가 기록된 것은 24장뿐**(§1-2) |
| `docs/loops/**/*.png` 20 | 자체 QA 스크린샷 |
| `tools/rtk14/*.py`, `tools/rtk-faces/*.py`, `tools/assets/*.mjs` + 테스트 | 알고리즘·파이프라인 코드 |
| 에셋 레포 `portraits/rtk14/`의 **파생 레이어**: 크롭 파라미터(v3 얼굴높이×2.1·face_y 37% / 아이콘 ×2.0·y 50%), `report.tsv`, `qc/`, `officer-id-registry.tsv`, `manifest/*` | 검출·판정·정본화 작업 결과 |
| 에셋 레포 루트 `README.md`, `.gitignore` | 자작 문서 |
| `web/*/lib/flagTint.ts` 캔버스 틴팅 로직 | `docs/superpowers/gap/_full_audit_2026-06-07.raw.json:2768` — "국기 컬러 틴팅(Canvas 기반, tintFlag). **legacy에 없음.** 신규 추가" |

### 5-2. 제3자 파생 (자작 아님)

| 자산 | 원천 | 자작 부분 |
|---|---|---|
| 에셋 레포 `game/` 476, `icons/` 4,167, `hook/` 5 | `devsam/image` (무LICENSE) | 없음 — 파일명 완전 일치 미러 |
| 에셋 레포 `portraits/rtk14/original/` 1,000, `serving/original/` 1,000 | RTK14 wikiwiki 첨부 이미지 | 없음 — 원본 바이트 보존 |
| 에셋 레포 `portraits/rtk14/{full-frame,face-crop,face-icon,serving/portrait,serving/icon}` 5,001 | 위 원본의 리사이즈·크롭 | 크롭·검출 파라미터는 자작, **픽셀 콘텐츠는 원본 파생** |
| 메인 레포 `web/*/public/icons/` 28 | `devsam/image` md5 일치 | 없음 |
| 메인 레포 `web/*/public/flags/` 16 | devsam `fFF0000.gif` PIL 추출 | 추출 스크립트는 자작, 픽셀은 파생 |
| `data/extracted/**` | `legacy/devsam-core` (MIT) | 추출·정본화 |

### 5-3. 경계가 판단 불가한 지점 (UNKNOWN)

1. **`assets/battle/v2` 81+8+18 = 107장의 소스** — 자작이라고 부르려면 생성기·모델·프롬프트가 필요한데 그 기록이 없다. "누가 만들었는지 모른다"는 자작의 반대편도 아니고 파생의 반대편도 아니다. `origin: "adopted-v1"` / `"adopted-existing"`이 곧 이 미확정 상태의 라벨이다.
2. **`portraits/rtk14`의 크롭본** — 변환 강도가 파생물성을 어디까지 희석하는지는 이 감사가 판단할 문제가 아니다. 파일 단위 경계만 §5-2에 기록한다.
3. **`game/src/*.jpg` 49장(병종 일러스트)** — `devsam/image` 유래인 것만 확인했고, devsam이 이를 어디서 얻었는지는 확인하지 못했다.
4. **`icons/루드라사움`·`강서유서월드`·`삼모시네마틱유니버스`** — 프랜차이즈명이 알려진 IP와 매칭되지 않아 원저작권자 자체가 미상이다.
5. **`data/extracted/**`의 인물 데이터** — devsam-core는 MIT지만, devsam이 그 데이터를 어디서 얻었는지는 이 감사의 범위 밖이다.

### 5-4. 분리 단위

라이선스를 파일 트리에 반영한다면 경계는 **디렉터리 단위로 이미 정렬돼 있다**:

- 에셋 레포: `game/`·`icons/`·`hook/` = devsam 유래 / `portraits/` = RTK14 유래 / 루트 문서 = 자작. 현재 루트 `LICENSE`(MIT) 하나가 세 구획 전부를 덮고 있다.
- 메인 레포: `assets/battle/v2/**` = 자작 / `web/*/public/**` = devsam 파생 / `data/extracted/**` = devsam MIT.
- 코드 계약에는 이미 라벨 자리가 있다 — `LicenseBundling`(`EvidenceContracts.kt:86-96`), `SharedProfileIconEntry.licenseStatus`(`SharedProfileIconCatalog.kt:31`, 현재 기본값 `"unknown"`). 후자는 `String`이라 enum 게이트를 타지 않는다.

---

## 6. 비주얼 트랙이 의존하는 자산군

각 트랙이 어느 구획에 붙어 있는지만 기록한다.

| 트랙 | 의존 자산 | 현재 상태 |
|---|---|---|
| 장수 얼굴 | 에셋 레포 `icons/`(4,167, §2-2) — **현재 런타임 경로** / `portraits/rtk14/serving/*`(3,000) — 준비됐으나 **미배선** | 런타임 경로의 라이선스 = UNKNOWN. `portraits/rtk14`는 §1-2 원본 권리 미확인 |
| 병종 스프라이트 (2D) | 메인 레포 `assets/battle/v2/units/**` 210 | 24장 영수증 보유 / 81장 UNKNOWN. **컴파일러 외 코드 참조 0 — 미배선** |
| 지형·이펙트 (2D) | `assets/battle/v2/terrain/**` 40, `effects/**` 20 | 소스 26장 UNKNOWN → 파생 34장이 이에 종속. 미배선 |
| 3D | `game/3d/*.glb` 50 (Meshy 생성, wiki 기록. 에셋 레포 현재 트리에는 **없음**) | 약관 원문 미확보. 메인 레포 커밋 0 |
| 맵·도시 아이콘 | 에셋 레포 `game/`(`cast_*`, `event*`, `b<HEX>`, `map/`) | 런타임 사용 중(`MapViewer.tsx:606,630`). UNKNOWN |
| 폰트 | Pretendard(CDN 런타임 로드) | 라이선스는 허용적으로 알려짐, 고지 파일 부재 |

---

## 부록 A. 확인 실패 기록

| URL | 결과 |
|---|---|
| `https://openai.com/policies/row-terms-of-use/` | **HTTP 403** (WebFetch) |
| `https://help.openai.com/en/articles/5008634-…` | **HTTP 403** (WebFetch) |

CHGIS 검토와 동일한 우회(브라우저 취득)가 필요하다.

## 부록 B. 확인한 것 / 확인하지 않은 것

**확인함**: 두 레포의 전 파일 목록(메인 `git ls-files` 확장자 스캔 / 에셋 레포 `git/trees?recursive=1` 11,666 blob, `truncated: false`), 에셋 레포 visibility·LICENSE·README·태그·브랜치, `portraits/rtk14/README.md`·`.gitattributes`·`mismatch-judgment.txt` 원문, 에셋 레포 ↔ `legacy/devsam-image` 파일명 차집합, 메인 레포 복사본 md5 대조, `assets/battle/v2` 매니페스트·영수증의 provider/model 분포, 메인 레포 전 CDN 참조 경로(코드·env·nginx·compose·배포 env 키), `ScenarioImporter` picture 해석과 시나리오 `iconPath` 분포, classpath 시드 목록, `web/*` 의존성·폰트·SVG, Dockerfile 에셋 다운로드, `.gitignore` 커버리지(`git check-ignore` 확인), 생성 도구 4종의 입출력·fail-closed 가드.

**확인하지 않음**: 에셋 레포 이미지의 픽셀 단위 원본 대조(원본 미보유), `icons/` 서브디렉터리 파일 개별 바이트 다운로드, `docs/loops/**` 스크린샷 내 초상 픽셀 검사, devsam이 각 자산을 취득한 경로, 미설치 npm 3종의 라이선스 파일, Meshy·Pretendard·OpenAI 약관 원문.

## 부록 C. 후속 조치 — 메인 레포 제3자 파일 44개 (2026-08-17)

브랜치 `fix-thirdparty-assets-main-repo`에서 §4 표의 메인 레포 두 행을 닫았다.

- `web/{gateway,game}/public/icons/*.gif` 28장 — 코드 참조 0(전 경로가 `ICON_CDN` 단일 출처)이라 **삭제**했다. CDN 쪽 `game/cast_*.gif`·`event*.gif`의 UNKNOWN 판정은 그대로 남는다(이 조치의 범위 밖).
- `web/{gateway,game}/public/flags/*.png` 16장 — devsam `fFF0000.gif` 파생본을 버리고 `tools/assets/build_flag_assets.py`가 입력 이미지 없이 그리는 자작 12x12 픽셀아트로 **교체**했다. 런타임 계약(`flagTint.ts`의 cloth/pole 2레이어 · 4프레임)은 불변.

표의 나머지 UNKNOWN 행(에셋 레포 전체, `assets/battle/v2/**`)은 손대지 않았다 — 여전히 UNKNOWN이다.

## 부록 D. 옵션 IP 초상 세트 전량 제거 — 메인 레포 쪽 (2026-08-17)

`opensamguk-images` 레포에서 §2-2의 10개 프랜차이즈/실존인물 세트(2,335장)를 히스토리까지 제거하는 결정과 짝을 이뤄, 메인 레포에서 그 세트를 참조하는 시나리오 데이터를 제거했다. 브랜치 `chore-drop-optional-ip-scenarios`.

- **삭제한 시나리오 16개** (전수 스캔 `grep -rl <세트명> data/extracted/scenario/` 근거, 삼국지 시나리오의 참조 0건 확인): `scenario_2130`·`2131`(환상향), `scenario_2140`·`2141`(걸그룹), `scenario_2171`(루드라사움), `scenario_2200`(스타1프로게이머), `scenario_2210`(포켓몬스터), `scenario_2300`(쿠키런킹덤), `scenario_2600`·`2601`(삼모시네마틱유니버스), `scenario_2800`·`2801`(강서유서월드), `scenario_2900`·`2901`·`2903`·`2904`(롤시나리오).
- `data/extracted/scenario/_meta.json`의 `scenarios` 배열에서 위 16개 항목 제거, `count` 81→65로 갱신.
- `삼국지6`(1장)은 어떤 시나리오도 참조하지 않았다 — 메인 레포 쪽에 삭제할 시나리오가 없다(이미지 레포 쪽 삭제만 해당).
- **시나리오 코드 참조는 0건.** 코드(`ScenarioImporter`/`ScenarioSeedRunner`/테스트) 전수 grep 결과 이 16개 코드에 대한 실행 경로 참조는 0건 — classpath 시드 목록(`infra/src/main/resources/scenario/`)에 애초에 없었고 `SCENARIO_CODE` 기본값도 `scenario_1010`이다. `docs/loops/opensam-35-v2-0a-2026-08-08/baseline/a2-scenario-seed-sha256.txt`에 과거 sha256 스냅샷이 남아 있으나 그 루프는 이미 닫힌 정적 기록이고, 그 파일의 sha256이 같은 디렉터리 `MANIFEST.md:34`에 고정돼 있어 편집하면 MANIFEST가 깨진다 — 손대지 않았다. 대신 `MANIFEST.md`가 "tracked 시드 소스 82개"라고 단언하는 자리에 이 삭제를 가리키는 각주를 달았다(트리 실제값 66 = 시나리오 65 + `_meta.json`).
- **단, 역방향 고아가 두 건 있었다** — 시나리오 코드 grep만으로는 안 잡히는 축이다.
  - `SharedProfileIconCatalogTest.kt:46`이 프로덕션 매니페스트의 `delivery_url`에 박힌 **옛 리비전**을 assert하고 있어 매니페스트 갱신만으로 `:app:gateway-api:test`가 깨졌다. `df8068e7`로 핀을 갱신해 종결(200 tests / 0 failures). 교훈: `shared-manifest.json`을 건드리는 변경은 항상 `:app:gateway-api:test`를 동반해야 한다.
  - `scenario_2210` 삭제로 `pokemon_v1` 맵의 소비 시나리오가 0이 됐다. §2-2 `포켓몬스터` 행과 **동일한 IP 등급**인 관동지방 지명 데이터가 남아 있었으므로 함께 제거했다: `data/extracted/map/pokemon_v1.json`·`infra/src/main/resources/map/pokemon_v1.json` 삭제, `web/game/components/game/MapViewer.tsx`·`web/gateway/components/MapPreview.tsx`의 `CDN_MAPS`에서 `'pokemon_v1'` 제거(빠지면 `cdnMapCode()`가 `che`로 폴백). 이미지 레포의 `game/map/pokemon_v1/` 타일은 이미 삭제돼 404였다. `game/map/ludo_rathowm/`은 시나리오 **2180**이 계속 쓰므로 **남긴다**.
- `app/gateway-api/src/main/resources/profile-icons/shared-manifest.json`의 핀 고정 SHA는 이미지 레포 히스토리 재작성에 맞춰 `1b6624d8…` → `05842c61…`로 갱신했다. 두 URL을 jsDelivr·raw 양쪽에서 실제로 내려받아 매니페스트의 `sha256`·64x64 치수와 4/4 일치를 확인했다(파일 바이트 불변 → `sha256`/`portrait_asset_id`/치수 필드는 불변).
- ⚠️ **IP 노출은 아직 닫히지 않았다.** 이미지 레포 force-push 후에도 옛 리비전 `1b6624d886c1b326a2feeda449288b41231df5ef`로 삭제 대상 2,335장이 **여전히 공개 접근된다**(실측 2026-08-17: `raw.githubusercontent.com/.../1b6624d8…/icons/포켓몬스터/강철톤.png` → `200`; 신규 SHA에서는 404). GitHub가 unreachable 객체를 GC 전까지 보관하기 때문이다. 닫는 경로는 GitHub Support의 unreachable-object purge 또는 레포 삭제 후 재생성뿐이며, 그 조치가 **검증되기 전까지 이 항목을 "제거 완료"로 기록하면 안 된다.**
