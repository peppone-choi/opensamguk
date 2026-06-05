# SESSION HANDOFF — 2026-06-05 (입구 A·B·C 배포 완료·라이브 / nginx 영구화 결정 대기)

다음 세션은 이 문서부터. 이전 핸드오프(입구 재설계 미커밋)는 모두 **머지+배포 완료**로 종료.

> **바로**: 입구(로그인/로비) devsam '제 전황' 재설계 + 맵 native 700×500(가로1000 비례확대) + 멀티서버 라우팅 기반 + 전황 read API를 **prod 라이브 배포 완료**(PR#34, main `b729e00`). 라이브 검증 끝. **유일 미결 = 박스 nginx 핫픽스의 버전관리 영구화 결정**(§3) — 인프라 토폴로지 3중 불일치 발견.

---

## 1. 이번 세션 한 일 (전부 main 머지 + prod 배포 + 라이브 검증)

**입구 A·B·C** (브랜치 `entrance-multiserver-redesign` → PR#34 → main `b729e00`, CI green 골든 포함):

| | 커밋 | 내용 |
|---|---|---|
| A | `cd81083` | 입구 devsam 제전황 재설계(ServerBoard/MapPreview/ServerLog/cityRegions/flagTint/아이콘) + **맵 native 700×500**(infra `che.json` ×10/7 1000×714 폐기 → php 정본 native, 프론트 transform이 컬럼폭 max-width 1000에 균일확대 → php비율 그대로 ~1000폭, 아이콘 미축소). lobby+in-game(MapViewer) 양쪽 native 정렬. MapPreviewController/Dto/Test native 단언. docker-compose GAME_API_ORIGIN. |
| B | `697c2af` | `lib/serverRegistry.ts` per-server origin 해석(SERVER_REGISTRY_JSON→GAME_API_ORIGIN(기본서버)→servers.json). 단일 env가 모든 서버를 한 game-api로 강제하던 버그 제거. server-map route 교체. 클라=servers.json public만, 내부주소=서버사이드. |
| C | `d05e43d` | game-api `WorldLogController` `GET /api/world-log`(log_entry SYSTEM history/summary, enum ::text 캐스트, 최신30) + gateway `/api/server-log/[id]` 프록시 + ServerLog devsam 태그 표시용 제거. |

**라이브 검증** (sam.peppone.dev, 박스 3.37.232.176):
- 맵 `/api/server-map/main` → 200 **width=700 native**, 로그인 페이지 맵 canvasW≈998(1000폭) 24도시 렌더.
- 전황 `/api/world-log` → 200 + nginx location 추가 후 `/api/server-log/main` 404→**200**, 전황 패널 실데이터 표출(182年6月 등).
- 턴 전진 정상(181/4→182/x), nginx 502 없음, game-api/gateway healthy, 에러로그 0.

## 2. 박스 nginx 핫픽스 (라이브 적용됨, 미영구화)

전황 패널이 라이브서 404였던 원인: 박스 nginx에 `/api/server-log/` location 부재(맵 `/api/server-map/`는 존재). **박스 `~/opensamguk/docker/nginx/default.conf`에 `/api/server-log/` location을 server-map과 1:1(proxy_pass `http://gateway-frontend:3000`, http·https 두 블록) 추가 → `nginx -t` ok → reload → 200 확인.** 백업: `~/opensamguk/docker/nginx/default.conf.bak.1780663639`. **이 변경은 어떤 git에도 미추적 → 박스 재클론/재셋업 시 유실.**

## 3. 🔴 미결: nginx 영구화 결정 (인프라 토폴로지 3중 불일치 — 사용자 택1)

배포 중 발견(핸드오프의 repo 정보가 stale):
- **라이브 prod 스택은 비-git 디렉터리 `~/opensamguk`에서 구동**(`docker-compose.production.yml` + `docker/nginx/default.conf`, 서비스 `gateway-frontend`/`game-frontend`/`db`, game-api 내부 18080). **어느 git에도 미추적.**
- 박스 git 레포 `opensamguk-deploy` = **obsolete**(레이아웃 `nginx/nginx.conf`, upstream `frontend`/`gateway`, `opensam-nginx`, blue/green — 현 토폴로지와 무관, server-map 선례 없음). ← 메모리/이전핸드오프의 "opensamguk-docker"와 불일치.
- 코드레포 `peppone-choi/opensamguk`의 `infra/nginx/nginx.conf` = **또 다른 토폴로지**(upstream 블록, `web-gateway:3000`, `/api/game/`, 8081) — 박스 미사용, server-map/server-log 없음.
→ 라이브 박스 nginx를 대표하는 버전관리 파일 부재. stale 레포 푸시=divergent/fabricate 위반이라 deployer가 보류(백업 sibling만).

**결정 옵션(택1):**
1. **(권장·근본)** 코드레포 `infra/nginx/nginx.conf`를 박스 실 토폴로지(server-map/server-log 포함 default.conf)로 일치 + deploy 파이프라인이 박스에 sync. TaskList #2와 합류.
2. 라이브 `~/opensamguk` 설정 디렉터리를 신규 전용 repo로 버전관리(`.env`/secrets/tar/db백업 제외).
3. 박스 핫픽스+백업으로만 운영(재셋업 시 수동 재적용) — 비권장.

## 4. ⚠️ 후속: 턴 되감김 (배포마다)

엔진 컨테이너 recreate 시 in-memory 턴(~185)이 마지막 DB 스냅샷(~181, 5분 주기)에서 rehydrate → **배포마다 ~수년 손실**(이번 read-only PR 무관, 메모리=source-of-truth 아키텍처 trade-off). 동결 아님(catch-up). 후속: 부팅/종료 전 강제 스냅샷(메모리 project_in_memory_crud "종료/시작 강제"와 정합) 또는 graceful 엔진 교체.

## 5. 남은 작업 (사용자 리스트 "차례대로" 잔여 + TaskList)

- **빼섭 2nd 서버 스택** — opensamguk-docker(미클론) + 2번째 db/api/engine + `SERVER_REGISTRY_JSON` + nginx + fresh 시드. **§3 영구화/토폴로지 정리 선결 권장.** 결정 필요: 서버명/시나리오/t3.large 2스택 리소스. (멀티서버 코드 기반 B 완료 — 추가 = servers.json public + SERVER_REGISTRY_JSON env + 박스 스택.)
- TaskList #2 — CI 헬스체크 toothless(`/api/game/actuator/health` 박스에 없는 경로 30회 재시도) + nginx stale-DNS 영구수정(deploy.yml `--force-recreate nginx`) + **§3 nginx VCS 정합**(합류).
- TaskList #4 — betting/auction flush enum 버그(scope=action/category 밖, 월틱 아님).
- TaskList #5 — PHP 구조 문서화 + W9 분리 + 커버리지 갭(`docs/superpowers/gap/WAVE_COVERAGE_REVIEW.md` untracked, 무관 — 별도).
- 입구 polish: 전황 색 렌더(현재 태그 제거 평문), 1000px 디스플레이 도시명 clip 등.

## 6. 작업 원칙/함정 (이번 세션 교훈)

- **main push = deploy.yml 자동 발화 → 엔진 recreate → 턴 되감김.** doc-only 변경은 main에 push 금지(또는 deploy 무발화 경로). 이 핸드오프도 로컬 커밋만(미push).
- 맵 좌표 = php 정본 native 700×500이 정답(표시 전용, 골든 패러티 무관). 프론트 transform 1겹이 캔버스폭에 균일확대 — coords/icons/font 한 번에. che.json에 스케일 굽지 말 것(아이콘 비율 깨짐).
- 비주얼 검증 = `/browse`(gstack). 로컬 dev :3000(`pnpm build`↔`pnpm dev` 같은 `.next` 교차 금지=청크손상, `rm -rf .next`).
- ctx_execute는 host localhost 미공유 — 로컬/박스 fetch는 Bash(host) or /browse or docker exec.
- 주석 한글, 식별자/wire/패러티 로그 영문. 커밋 끝 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## 7. 박스/repo 정보 (업데이트됨)

- EC2 `3.37.232.176`, ssh `~/.ssh/id_ed25519` ubuntu. **라이브 스택 = 비-git `~/opensamguk`**(`docker-compose.production.yml`, 서비스 `gateway-frontend`/`game-frontend`/`db`/`nginx`, game-api 내부 18080, gateway-api 18081, gateway-frontend에 `GAME_API_ORIGIN: http://game-api:18080`). nginx config = `~/opensamguk/docker/nginx/default.conf`(미추적, §3).
- repos: `peppone-choi/opensamguk`(코드·CI deploy.yml·GHCR 빌드) · `opensamguk-deploy`(박스 git, **obsolete**) · `opensamguk-images`(자산, 맵 CDN jsdelivr che/bg_*.jpg·che_road.png). 메모리의 "opensamguk-docker"는 라이브와 불일치 — §3 정리 대상.
- 배포 흐름: opensamguk main push → `.github/workflows/deploy.yml`이 GHCR 이미지 빌드+푸시 → 박스 pull+recreate. CI 게이트 `ci.yml`(jvm 골든 + web×2).
