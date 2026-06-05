# SESSION HANDOFF — 2026-06-05 (입구/맵 devsam 제전황 재설계 — 진행 중, 미커밋 + 로컬 테스트서버 가동)

컨텍스트 클리어 전 인수인계. **다음 세션은 이 문서부터.** 태스크는 TaskList #1~#7 (아래 §3).

> **바로**: 입구(로그인+로비) 맵을 devsam `https://sam.hided.net/sam/` "제 전황" 형태로 재설계 중. 프론트 대부분 완료(미커밋), **로컬 docker 스택 + pnpm dev 가동 중**으로 비주얼 반복. prod 턴동결/맵502는 지난 세션에 해결됨(§5).

---

## 0. 지금 돌아가는 것 (로컬 테스트 서버)
- **docker compose 8서비스 가동**(`docker compose ps`). fresh DB 시드 = **scenario_1010 황건적의난, 181년, 94도시(공백지 70 포함)**. postgres user/db=`sammo`, 호스트포트: game-api **8081**, gateway-api **8080**, web-gateway(컨테이너) 3100.
- **web-gateway는 컨테이너 말고 `pnpm dev`(:3000)로 가동 중**(빠른 반복용). env `GAME_API_ORIGIN=http://localhost:8081 GATEWAY_API_URL=http://localhost:8080`. dev script=`next dev -p 3000`(PORT env 무시). 접속 `http://localhost:3000/login`(맵 공개), admin `peppone/peppone`.
- **⚠️ 함정**: `pnpm build`(prod)와 `pnpm dev`를 **같은 `.next`에 교차 실행하면 청크 손상**(`Cannot find module './151.js'` → route 500 → 맵 스피너 무한). 빌드 검증은 dev 끈 뒤 or 별도. 손상 시 `rm -rf web/gateway/.next` + dev 재기동.
- **로컬 game-api = 2일전 이미지**(mapCode="scenario", **700×500 native**). 프론트가 `cdnMapCode()`로 bg는 "che"로 폴백. 이 700×500이 **php 정본 크기**(scale 1 → 아이콘 16~32px). **현재 main game-api는 che 1000×714(×10/7 확대)** — 그대로 prod 배포 시 아이콘 축소됨. **핵심 미결: 백엔드를 native 700×500 che로 맞춰야 php-fidelity**(아래 §2-A).
- 테스트로 로컬 DB `front_state` 1~5 세팅(업1 허창2 낙양3 장안4 성도5) — 상태아이콘(event1~5.gif/재난) 표출 확인용. **엔진 flush에 0으로 덮일 수 있음**(임시).

## 1. 이번 세션 한 일 (전부 미커밋, web/gateway 중심)
**입구 = devsam 제전황 형태로.** 레퍼런스 `sam.hided.net/sam/` = 헤더+로그인폼+세계지도(아이콘/깃발)+전황로그+푸터. **로그인+로비 둘 다** 적용.

신규/수정 파일:
- `web/gateway/components/MapPreview.tsx` — 점(circle)→**정적 마커**(성아이콘 cast_<lv>.gif + 국가색 깃발 4프레임 + 수도별 event51 + 오오라 + 상태 event<state>). **레거시 scss/map.scss 충실 재현**:
  - DETAIL_SIZES = `$detailMapCitySizes` 정확 일치(1~8).
  - **DOM 구조 = 레거시**: `.city-base(40×30) > [.city-aura(소유국만), .city-img(아이콘크기) > [cast, state{top5/left0}, flag{right:flagRight/top:flagTop}, name{left70%/bottom-10}]]`. 깃발/이름/상태가 **아이콘(city_img) 기준** 위치(이게 핵심 — base 기준이면 작은 도시서 어긋남).
  - **level→문자 = 레거시 `defs/index.ts CityLevelText`: 1수 2진 3관 4이 5소 6중 7대 8특**(촌 없음, 기존 매핑 틀렸었음).
  - 공백지(nationId 0)=오오라 없음. 오오라=radial-gradient `${col}cc 0%, ${col}66 40%, ${col}22 58%`.
  - **hover 툴팁**: 도시명 / **【region levelText】 nation**(region=cityRegions.json 룩업). canvas(overflow:hidden) **밖** `.map-preview`에 렌더(잘림 방지) + 도시 벗어나면 해제(onMouseLeave).
  - canvas aspect-ratio = data.w/h 동적. w/h 기본 700/500. mapCode "scenario"→"che" 폴백.
  - serverName prop(탭 이름으로 캡션).
- `web/gateway/config/cityRegions.json` — id→region_name(하북/중원/서북/서촉/초/오월/동이/남중), cities_1010.json서 추출. **툴팁 region용**(맵 라벨 아님 — 사용자가 라벨 말고 툴팁 원함).
- `web/gateway/components/ServerBoard.tsx` — 서버탭 + MapPreview + ServerLog(로그인+로비 공용).
- `web/gateway/components/ServerLog.tsx` — 전황로그, `/api/server-log/[id]` 프록시(**API 미구현** → 404 → '전황 보고 준비 중' graceful).
- `web/gateway/lib/flagTint.ts` + `public/icons/`(cast_*,event*) + `public/flags/`(cloth/pole) — web/game서 복사.
- `web/gateway/app/globals.css` — `.map-preview*`, 마커 `.city-*`, `.server-board/.server-tabs/.server-tab`, `.server-log*`, 툴팁.
- `web/gateway/app/login/page.tsx` · `app/lobby/page.tsx` — MapPreview→**ServerBoard**.
- `docker-compose.yml`(로컬) — web-gateway env에 **`GAME_API_ORIGIN: http://game-api:8081`** 추가(맵 프록시 502 방지, 누락돼 있었음).
- 계획서 `docs/superpowers/plans/2026-06-05-entrance-multiserver-redesign.md`.

**사용자 맵 피드백 전부 반영**(레거시 대조): level 문자 / php크기 / 이름 항상+아이콘기준 / 깃발·상태·이름 위치 / 동적aspect / 공백지 오오라X / 오오라 강화 / 깃발 코너부착 / 툴팁 잘림·벗어나면해제 / 상태아이콘 / **region 툴팁**.

## 2. 미결 (사용자 결정/다음 작업)
- **A. ⚠️ 백엔드 맵 좌표 native 700×500 che (php-fidelity 핵심)** — 현 main `MapPreviewController`=che 1000×714(×10/7 확대, "마커겹침완화" opensamguk 분기). php=700×500. **prod 배포 전** controller가 native 700×500 + mapCode="che" 반환하게 수정(che.json 좌표 ÷(10/7) or native 맵json). 인게임 MapViewer(web/game, zoom 있음)도 동일 적용 검토.
- **B. "빼섭" 2번째 서버 스택** (TaskList #7) — opensamguk-docker compose에 bbae-db/api/engine + SERVER_REGISTRY_JSON + nginx, fresh 시드(공백지 자동). t3.large 2스택 리소스 확인. **route.ts per-server 버그**: `process.env.GAME_API_ORIGIN ?? server.gameApiUrl` — env가 per-server URL 덮음 → 멀티서버 전 `server.gameApiUrl` 우선으로 수정 + servers.json 환경별 내부URL. servers.json은 빌드타임 baked.
- **C. 전황 로그 API** (TaskList #7) — game-api 월드 최근이벤트 read + 게이트웨이 `/api/server-log/[id]` 프록시.
- **D. 자잘**: ① 정적 맵JSON(che.json 좌표) ↔ 동적 소유상태 분리(devsam식, 선택) · ③ 1000px 디스플레이 확대 여부(현 700=php) · ④ 가장자리 도시명 clip(레거시도 clip, 패딩 여부).
- **E. 배포 경로**: 입구 프론트는 단일 서버(통일)로 먼저 배포 가능(빼섭 백엔드 후속). 배포 전 A(좌표) 필수.

## 3. 태스크 (TaskList 복원 — clear해도 유지 안 되면 재생성)
- #2 CI 헬스체크 fix(toothless + nginx stale-DNS 영구수정 deploy.yml `--force-recreate nginx`) — §5.
- #3 서버 생성 모델(무서버+admin 수동, ServerRegistry).
- #4 betting/auction flush enum 버그(scope=action/category 밖, 월틱 아님).
- #5 PHP 구조 문서화 + W9브랜치 분리 + 커버리지 갭.
- #6 (in_progress) 입구 프론트 프레임 — **이 세션 주작업**, §1.
- #7 "빼섭" 2번째 스택 + route 멀티서버 + 전황 API — §2-B/C.

## 4. 작업 원칙 (이번 세션 교훈)
- **pnpm build ↔ pnpm dev 같은 .next 교차 금지**(청크 손상). 손상=`rm -rf .next`.
- 맵 마커 = **레거시 scss/map.scss + MapCityBasic.vue + defs/index.ts 정본** 대조(in-game MapViewer.tsx도 참고하나 레거시가 grand truth).
- 비주얼 검증 = `/browse`(gstack, `~/.claude/skills/gstack/browse/dist/browse`). hover는 React onMouseEnter←native `mouseover` 디스패치 + canvas `mousemove`(cursor)로 트리거.
- ctx_execute는 격리 샌드박스(host localhost 미공유) — 로컬 서버 fetch는 docker exec 내부 or /browse.
- 주석 한글, 식별자/wire/패러티 로그 영문. 커밋 끝 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## 5. prod 상태 (지난 세션, 해결됨)
- PR #30/31/32(턴동결 3단 fix) + #33(맵502 fix+브랜드 오픈삼국) **머지+배포**. **턴 진행 = year 183**(전진 확인). 맵 프리뷰 502→200.
- **nginx stale-DNS 502 재발**(deploy `up -d`가 nginx 무재생성 → app 새IP, nginx 옛IP) → **이번 세션 수동 `docker compose -f docker-compose.production.yml up -d --force-recreate --no-deps nginx`로 복구**(`/game/` 502→200). **영구수정 미적용**(TaskList #2: deploy.yml에 nginx force-recreate 스텝 + 헬스체크 toothless fix).
- EC2 `3.37.232.176`, ssh `~/.ssh/id_ed25519` ubuntu. box `~/opensamguk`=opensamguk-docker 클론. compose `docker-compose.production.yml`, 서비스명 `gateway-frontend`/`game-frontend`(repo는 web-gateway/web-game), game-api 내부 **18080**, gateway-api **18081**. box gateway-frontend엔 `GAME_API_ORIGIN: http://game-api:18080` 이미 설정됨. repos: `peppone-choi/opensamguk`(코드)·`opensamguk-docker`(배포)·`opensamguk-images`(자산, 맵 CDN=jsdelivr che/bg_*.jpg·che_road.png 실재).

## 6. 재개 순서
1. 이 문서 → 로컬 서버 살아있나(`docker compose ps`, dev :3000) 확인. 죽었으면: `docker compose up -d` + (web) `rm -rf web/gateway/.next` 후 `GAME_API_ORIGIN=http://localhost:8081 GATEWAY_API_URL=http://localhost:8080 pnpm dev`.
2. §2 미결 — **A(백엔드 native 700×500 che) 먼저**(배포 게이트), 그 다음 사용자 지시(빼섭/전황/배포).
3. 입구 작업 커밋(미커밋 다수) → 배포는 사용자 go 후.
