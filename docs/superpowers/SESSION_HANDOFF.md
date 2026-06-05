# SESSION HANDOFF — 2026-06-06 (parity-final 배포 + 양섭 재시딩 + 빼섭 보급-동결 fix)

다음 세션은 이 문서부터. 이전 핸드오프(입구 A·B·C·nginx 영구화)는 머지+배포 완료로 종료 — 핵심만 §6에 흡수.

> **바로**: `parity-final` 10+1커밋(W3 read-DTO·맵아이콘축소·reseed스크립트·빼섭 보급 fix) → main FF 배포. **본섭(1010)+빼섭(1030) 둘 다 외과적 재시딩 완료**(게임만 리셋, 로그인 보존). 빼섭 턴데몬 동결(`doNPC구출발령` 빈 supplyCities)은 **시드 소유 버그로 근본 fix**(`dd4e970`) — 배포 후 **빼섭 재시딩+엔진기동+턴전진 검증**이 마지막 잔여(아래 §1 끝).

---

## 1. 이번 세션 한 일 (전부 main 배포, 라이브 검증)

브랜치 `parity-final` → main FF 머지 push(자동 deploy.yml). 커밋:

| 커밋 | 내용 |
|------|------|
| `f2096e3` | **W3 read-DTO 인리치** — chief-center/getconst/generallist/frontinfo. game-api 6실패 마감: JavaBeans decapitalize 함정 2건(`isChief`→`@get:JsonProperty("isChief")`, `iAction`→`@get:JsonProperty("iAction")`), 구 /api/const→GetConstController 이관, 데드 GameConstResponse 제거. `:app:game-api:test` 175 green. |
| `4f2fc34` | **맵 도시 아이콘 ~28% 축소**(MapViewer `ICON_SCALE=0.72`, cast만, 아우라/깃발 비율 유지). 사용자 요청. MapViewer 15/15. |
| `23f3bf9` | **`scripts/reseed-prod.sh`** — 외과적 재시딩(users+flyway 제외 게임테이블 TRUNCATE→redis FLUSH→엔진재시작→game-api재시작). |
| `dd4e970` | **빼섭 보급-동결 근본 fix** — 도시 소유를 시나리오 `nation.cities`로 배정(아래 §2). ScenarioImporterIT 회귀게이트. **배포 후 빼섭 재시딩 필요.** |

(이전 배포 배치 — 같은 parity-final: W9머지/reconciled audit/mojibake/BuyHiddenBuff intake/nextRuler+deleteNation/W6·W5 REST 뮤테이션.)

**사용자 3요구 처리**: ① 아이콘 축소 ✅배포 ② 수도=국가당1 ✅(버그 아님 — "18국"은 방랑군 영지0 환상; 재시딩으로 청소) ③ prod 재시딩 ✅양섭.

**재시딩 결과(라이브)**: 본섭 319국→**2국·2수도**(업/낙양), year 182/3→181/1, 94도시·678장수, users 보존. 빼섭 21국·21수도(단 §2 동결 — fix 배포+재시딩 후 해소 예정).

**🔴 마지막 잔여(이 세션 끝나기 전/다음 세션 즉시)**: 빼섭 보급 fix(dd4e970) 배포 완료되면 →
```bash
ssh -i ~/.ssh/id_ed25519 ubuntu@3.37.232.176
# 빼섭만 재시딩(fixed importer가 21국 소유 복구) + 엔진 기동:
docker stop opensamguk-bbae-game-engine 2>/dev/null
docker exec -i opensamguk-bbae-db psql -U samguk -d samguk -v ON_ERROR_STOP=1 -c "DO \$\$ DECLARE r RECORD; BEGIN FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('users','flyway_schema_history') LOOP EXECUTE 'TRUNCATE TABLE public.'||quote_ident(r.tablename)||' RESTART IDENTITY CASCADE'; END LOOP; END \$\$;"
docker exec opensamguk-bbae-redis redis-cli FLUSHALL
docker start opensamguk-bbae-game-engine
# 검증: 모든 21국 도시 소유 + 월경계 크래시 없는지
sleep 30; docker exec opensamguk-bbae-db psql -U samguk -d samguk -tAc "SELECT count(*) FROM nation n WHERE NOT EXISTS(SELECT 1 FROM city c WHERE c.nation_id=n.id)"  # 0 기대
docker logs --tail 30 opensamguk-bbae-game-engine 2>&1 | grep -iE "Empty items|Exception|entering run loop"  # Empty items 없어야
```
**주의**: bbae-engine은 현재 stop 상태(완화 A). 배포(`$COMPOSE up -d bbae-engine`)가 부활시키나 재시딩 전엔 옛 깨진 소유라 재크래시 → 반드시 배포 후 재시딩.

## 2. 빼섭(1030) 보급-동결 — 근본 원인 + fix (dd4e970)

**증상**: 빼섭 턴데몬이 첫 월경계서 `RandUtil.choice "Empty items"`(RandUtil.kt:36) 크래시-루프(1초마다 backing off) → 동결+CPU. 스택: `rescueDeploy:140` ← `doNPC구출발령:625` ← GeneralAI.chooseNationTurn ← MonthBoundaryDriver.

**근본**: `scenario_1030`이 도시 리소스 `cities_1010.json` 재사용 → 도시 소유가 1010 baked nation_id(국가1·2만) → 1030 21국 중 **2국만 도시 소유, 19국 무소유**. 무소유국은 capital이 미소유 도시 가리킴 → UpdateCitySupply BFS가 capital seed 못함(`computeSuppliedCitiesOrdered:68-74` 미소유시 continue) → supplyCities 빔 → `doNPC구출발령`(capital!=0 가드만 통과)이 `choice(빈 보급)` throw. **PHP `RandUtil::choice`도 empty throw=패러티 정확 → PHP는 이 상태에 도달 안 함**(PHP 시나리오가 nation.cities로 소유 배정).

**fix**: ScenarioImporter가 도시 소유를 **시나리오 `nation[].cities`(도시명)** 에서 배정(baked nation_id 무시). 1010 무변(동일집합), 1030 21국 소유 복구. 회귀게이트 ScenarioImporterIT(1010 14/10 보존 + 1030 무소유국0·수도자국소유). 메모리 `project_bbae_supply_freeze_bug.md`.

## 3. 남은 패러티 작업 (reconciled §3 티어순 — 현 상태 반영)

**✅ 닫힘(이 배치)**: Tier0 전부 · Tier1 #5 nextRuler · Tier3 #9 메시지/#10 경매개설/#11 외교서신/#13 명령큐 · Tier5 #16 read-DTO(W3) · 빼섭 보급 fix.

**⬜ 잔여**:
| 우선 | 항목 | 무게 | 게이팅 |
|------|------|------|--------|
| Tier4 #15 | **24 미포팅 명령** — 8×`event_*연구`(극병/무희/상병/대검병/화시병/음귀병/산저병/화륜차/원융노병) · che_계략(화계/파괴/탈취/선동/첩보/반계) · misc(강행/접경귀환/숙련전환/전투태세/모반시도/특기초기화×2/단련/등용수락/cr_인구이동) | **최대** | 각 PHP 골든(Docker `tools/php-golden`), `/parity-wave` 팬아웃 |
| Tier2 #7·#8 | W8 토너먼트 — `processTournament` state machine(pending→fill→qualify→prelim→bet→16/8/4/2/finals) + `TournamentStart/Advance/Reset` admin(현 tournament-admin FE silent no-op) | 중-대 | draw-for-draw 골든(`func_tournament.php` 1393줄) |
| Tier3 #12·#14 | 입국·건국(거병→건국 candidate) · NPC 선택풀 pick/update | 소-중 | 골든(현 deny-stub) |
| Tier5 #17 | W4 FE 렌더 — 게이지 now/max, 로그/기록 페이지(`/game/battle-records` 등), 설정패널, generals sort | 중-대 | 골든 불요(W3 read 위에) — 병렬 가능 |
| Tier1 #4·#6 | checkStatistic 훅 + Q14 checkEmperior | 소 | **의도적 디퍼**(표시전용 연감, 최저 ROI) |

**대략**: 백엔드 파러티 배관(foundation/REST/read/데몬후계/시드) 거의 닫힘 ≈ **60% 완료**. 남은 ~40%는 #15 24명령이 절반(기계적·병렬) + W8 토너먼트 + W4 FE. 골든 캡처(Docker)가 Tier2·4·3잔여의 처리율 게이팅. W4 FE(#17)+checkStatistic은 골든 불요 병렬.

## 4. 인프라/배포 실측 (이 세션 검증)

- **EC2** `3.37.232.176`, `ssh -i ~/.ssh/id_ed25519 ubuntu@…`. (sam.peppone.dev=Cloudflare 프록시 → SSH 불가, HTTP만.)
- **라이브 컨테이너명(compose와 다름)**: 본섭 DB=`opensamguk-db`(user/db=**samguk**, sammo 아님!), engine=`opensamguk-game-engine`, api=`opensamguk-game-api`, redis=`opensamguk-redis`, web=`opensamguk-{game,gateway}-frontend`. 빼섭=`opensamguk-bbae-{db,game-engine,game-api,redis}`(db samguk). **전 앱 단일 DB 공유 → 볼륨 wipe=유저소실 → 외과적 truncate(users 보존) 필수.**
- **배포**: main push → `deploy.yml`(`gradlew build` 풀테스트 → GHCR 이미지 `:svc-latest`(본섭+빼섭 공용) → SSH 롤링 `up -d`(upstreams→engine→bbae→nginx force-recreate)). 단일 push가 양섭 동시. DB 영속 → 배포만으론 재시드 안됨(world_state 비어야).
- **prod 맵 라우트** = `/api/map/preview`(NOT `/api/game/map/preview`=404). 자율 머지+배포 OK([[feedback_auto_merge_deploy]]) — CI green 선결.

## 5. 작업 원칙/함정

- **main push = deploy.yml 자동발화 → 엔진 recreate → DB스냅샷 rehydrate로 턴 ~수년 되감김**. doc-only는 main push 금지(이 핸드오프도 parity-final 로컬커밋만). 코드 배포는 큰 배치로.
- 빌드: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew ...`, gradle은 `ctx_execute(language:"shell")` 경유. 검증=출력 tail+XML(exit0 불신, `--rerun-tasks`).
- 로컬/박스 fetch는 Bash(host) — ctx_execute는 host localhost 미공유. 비주얼=`/browse`(gstack).
- 주석 한글, 식별자/wire/패러티 로그 영문. 커밋 끝 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- 패러티: RNG draw-for-draw + PhpRound(half-away) + 한글 로그 byte + flush-delta + fabricate 금지. 골든은 real PHP 캡처만(Docker scenario_1010).

## 6. 이전 세션 흡수 (입구/nginx — 완료)
입구 A·B·C(제전황 재설계·맵 native 700×500·멀티서버 라우팅·world-log) 배포 완료. nginx default.conf는 infra/nginx 정본화+scp 동기화로 영구화(#35 머지). 라이브=untracked `~/opensamguk`(이전 핸드오프 §3 토폴로지 불일치는 정리됨). 상세는 git log.
