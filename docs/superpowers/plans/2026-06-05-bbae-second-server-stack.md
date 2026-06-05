# 빼섭(bbae) 2번째 서버 스택 — 계획

2026-06-05. 사용자 결정: id=**bbae** / 표시명=**빼섭**, turnterm=**60분**, 시나리오=**scenario_1030 군웅할거(191, 21세력)**, 리소스=**t3.large 유지 + JVM 힙 튜닝**.

## 목표
공유 입구(gateway) 아래 2번째 독립 게임 월드 "빼섭". 로비에서 main(황건적 2세력)·빼섭(군웅할거 21세력) 탭 전환, 각자 맵/전황, 입장하면 해당 서버 인게임.

## 아키텍처 (공유 + 서버별)
- **공유**: gateway-api(인증), gateway-frontend(로비/입구), nginx, (선택) redis.
- **서버별(main, bbae)**: db(postgres) · engine(턴 데몬) · api(read/intake) · game-frontend(인게임) · redis 격리.

## 완료 (커밋 5b00a70)
- `infra/.../scenario/scenario_1030.json`(legacy verbatim) + ScenarioImporterIT 1030 시드 실DB 검증(21세력/94도시/491+무장 무에러). cities=che 풀맵 공용. SCENARIO_CODE=scenario_1030로 선택.

## 빌드 항목
1. **박스 compose 버전관리** — 라이브 `~/opensamguk/docker-compose.production.yml`(untracked) verbatim → 레포 정본화(nginx default.conf 선례) + deploy.yml이 scp 동기화. bbae 서비스 추가의 전제(untracked 편집은 또 유실).
2. **bbae 서비스 추가**(compose):
   - `bbae-db`(postgres) — 독립 볼륨.
   - **redis 격리** — 엔진이 Redis Stream(XADD) 사용 → main과 충돌 방지. 방안: (a) `bbae-redis` 별도 컨테이너, 또는 (b) 공유 redis + DB index/스트림 키 prefix 분리. 엔진 설정이 prefix 지원하는지 확인 필요 → 안전책 (a) 별도 컨테이너(메모리 ~30MB, 무난).
   - `bbae-engine` — `SCENARIO_CODE=scenario_1030`, DB→bbae-db, redis→bbae-redis, 힙 `-Xmx` 튜닝.
   - `bbae-api` — DB→bbae-db, redis→bbae-redis, 내부 18080(컨테이너망), 힙 튜닝.
   - `bbae-game-frontend` — 같은 web-game 이미지, `GAME_API_URL=http://bbae-api:18080`, `GATEWAY_API_URL=http://gateway-api:18081`(공유), `NEXT_PUBLIC_GATEWAY_URL=https://sam.peppone.dev`.
3. **리소스 예산(t3.large 8GB)** — 현재 main: postgres+redis+engine+api+gateway-api+2 frontend+nginx. 추가 bbae: postgres+redis+engine+api+frontend = JVM 3개(engine·api 2개 신규) + postgres + next 1개. **JVM 힙 축소** 필수: engine/api `-Xmx512m`, gateway-api `-Xmx384m` 등으로 8GB 안에. 배포 후 `free`/OOM 모니터.
4. **SERVER_REGISTRY_JSON**(gateway-frontend env) — `[{"id":"bbae","gameApiUrl":"http://bbae-api:18080"}]` → 로비 server-map/server-log가 bbae-api로 해석(이미 serverRegistry 구현됨). 로비 빼섭 탭 맵/전황 = DNS 불요(서버사이드 프록시).
5. **servers.json** — bbae public 항목 추가(name 빼섭, turnterm 60, status running, gameUrl=라우팅 결정에 따라).
6. **nginx 인게임 라우팅** ← **결정 필요(아래)**.
7. **시드** — bbae-engine 부팅 시 ScenarioSeedRunner가 bbae-db 비어있으면 scenario_1030 1회 시드(멱등). SCENARIO_SEED_ENABLED=true.
8. **배포 + 검증** — bbae 스택만 up(main 무중단), 로비 빼섭 탭 맵/전황 200, 입장 동작, bbae 턴 전진, 메모리 OK.

## 🔴 결정 필요: 인게임 라우팅
web/game는 app router `/game/` 세그먼트(basePath 없음) → main·bbae 두 인게임 모두 `/game/` 제공. 구분 방법:

| 방안 | 내용 | 장단 |
|---|---|---|
| **A. 서브도메인** | `bbae.sam.peppone.dev` → bbae-game-frontend. nginx server_name 분기. gameUrl=`https://bbae.sam.peppone.dev/game`. | ✅정석·N서버 확장·프론트 재빌드 무. ❌**DNS A레코드**(bbae→EC2 IP) + **TLS cert**(와일드카드 `*.sam.peppone.dev` 또는 SAN) 필요 — 사용자 DNS/cert 작업. |
| **B. 경로 + basePath 재빌드** | `/bbae/game/` → bbae-game-frontend(basePath `/bbae` 빌드). | ✅DNS 불요. ❌web-game 2번째 빌드(basePath 다른 이미지) + asset/api 경로 분리 — 빌드/CI 복잡. |
| **C. 단계적** | 1단계: 백엔드+로비 빼섭 탭(맵/전황)만(DNS 불요). 입장은 "준비 중". 2단계: 라우팅(A/B) 후속. | ✅즉시 가치(빼섭 현황 노출)·저위험. ❌입장 불가(반쪽). |

## 리스크
- t3.large 메모리(2스택). 힙 튜닝 + 모니터. 초과 시 인스턴스 업그레이드 폴백.
- 배포(main push)마다 엔진 recreate→턴 되감김(기존 갭2). bbae 추가는 main 엔진 무중단 가능(bbae 서비스만 up)이나, deploy.yml 경유 시 main도 recreate.
- DNS/cert(방안 A) = 사용자 인프라 작업.
- 박스 compose 버전관리 = compose 토폴로지 정합(서비스명 db/gateway-frontend/game-frontend) 동반.
