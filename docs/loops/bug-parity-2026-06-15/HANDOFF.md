# SESSION HANDOFF — bug-parity 루프 + 장기-시뮬 게이트 (2026-06-15)

> 다음 세션 재개용 정본. 짝: `GOLDENSET.md`(시험지 포인터) · `LEDGER.md`(바퀴 오답노트) ·
> `docs/superpowers/plans/2026-06-15-long-sim-parity-gate-plan.md`(게이트 4단계 계획).

## 1. 이번 세션 한 일 (요약)

**루프**: loop-engineering(측정→1가설→fresh 재채점→채택/원복). 브랜치 `loop-parity-2026-06-14-c`.

**버그 헌트→수정**: 2개 적대적-검증 워크플로(BE 10영역·FE 6영역)로 **40 확정버그**(26 오탐 기각).
그중 **15 수정·커밋**(바퀴 1–15). 10 커밋 `cde6abca..618aee4c`:
- map 회귀(MapPreview state=frontState→city.state, 두 맵뷰어 툴팁) · troop FE/BE 계약(크래시+dead버튼)
  · diplomacy 인테이크 가드 + FE 표시 8건 · login open-redirect · nationNotice 렌더(사이드루프)
  · RaiseDisaster trust 곱 · flush 3컬럼(power/officer_city/statisticInserts)
  · **CRITICAL turn-loop**(e8576473): runTick 장수 생애 꼬리(applyKillturnDecrement+updateTurnTime) 미실행 +
    dueGenerals strict-< 교정. = killturn 감소·kill/환생/유체이탈·turntime advance가 라이브에서 처음 작동.

**게이트(로컬, 결정적)**: 백엔드 **3115 tests / 0 fail**(common 192·logic 2148·infra 96·game-api 307·game-engine 372,
Docker Testcontainers IT 포함) + FE tsc(web/game·web/gateway) + web/game vitest 65/65.

**장기-시뮬 패러티 게이트(공백지→천하통일)** 착수: 리서치 2건 종합 → 계획 커밋(618aee4c). Phase 1(천하통일 탐지) 미완 → 원복(아래 §3).

## 2. 배포 상태 (확인 필요)

`deployer` 에이전트가 `loop-parity-2026-06-14-c`(10커밋) → main 머지 → auto-deploy → **turn-advance 검증** 중.
**재개 시 FIRST: 배포 결과 확인** — main 머지됐는지, world_state turn 전진하는지(turn-loop 변경이 라이브 데몬 동작 바꿈 — 동결 안 됐는지), health 200.
deployer 산출 transcript: `…/tasks/a1507236ed3fa133e.output`. 만약 머지/배포/검증 실패면 그 지점부터 재개.

## 3. 재개 즉시 할 일 — 장기-시뮬 게이트 Phase 1 (천하통일 탐지)

**미완 원복됨**(un-gated 였음). 스펙·진척 그대로:
- PHP grand truth: `legacy/devsam-core/hwe/func_gamerule.php:696-769` `checkEmperior()`, 호출 :430(postUpdateMonthly, refreshNationStaticInfo 뒤 / triggerTournament 앞). **no-rng**(draw 스트림 불변).
- 로직: 국가(level>0) 수==1 && 그 국가가 **전 도시 소유**(count(city nation=N)==count(CityConst.all)) → `isunited=2` + 전토통일 국가사 로그(`<D><b>{name}</b></>{조사이} 전토를 통일`).
- Kotlin 좌표: `PostUpdateMonthly.kt` Q14 슬롯(line ~397, isUnited 파라미터 unused 스텁) + 엔진 `MonthlyPostUpdateHook.kt`(Q14 소비처, 여기서 detection+mutation 수행 — applyDisaster류) + world 시seam `world.isunited()/setIsunited(2)`(참고 `InvaderEndingAction.kt`). 인메모리 city.size = CityConst 전체수(전 도시 시드됨).
- 직전 에이전트 진척: InMemoryTurnWorld.kt(seam) + PostUpdateMonthly.kt(Q14) 손댔다가 원복. MonthlyPostUpdateHook 배선 직전이었음.
- 격리(백로그, 위조금지): 통일시 1회성 부수효과(상속 unifier+2000, 유니크경매 종료, United 이벤트, refreshLimit*100, CheckHall)는 게이트가 최종턴 도달(Phase 4) 시 필요 — PHP라인 인용 주석+백로그로 격리. **isunited=2 + 전토통일 로그는 필수**.
- 테스트(no Docker): 1국 전도시→isunited 2+로그 / 2국→0 / 1국 비전도시→0 / 이미 !=0→no-op.
- 게이트: `:logic:test :app:game-engine:test`.

이후 Phase 2(PHP run_long_sim.php 캡처 하네스)·3(Kotlin LongSimReplayGateTest)·4(천하통일까지 윈도 확장). 계획문서 참조.

## 4. 백로그 (LEDGER에 정본)

**Docker 게이트 필요(골든 신규캡처/실DB IT):** BE 명령-패러티 — 선동 로그소수(number_format), 급습#3·이호경식#12 외교term 가산식,
약탈발동 float, 감축 2번째 제약, 집합 ReqTroopMembers 스텁. RNG-draw — do선전포고 게이트누락(여분draw)·preprocessCommand 미실행.
**FE BE-coupled:** 외교 서신 승인/거부(respond 엔드포인트 신설), my-boss 전섹션(read 컨트롤러 확장), vote 보상문구.
**Latent:** C3Strategic 비교역전(주입처0, P7 staging seam 시). bbae(1030) 보급동결 근본원인.

## 5. 환경 함정 (시간 절약)

- **한글경로 gradle 깨짐**: `개인프로젝트`→`uAC1C…` Kotlin 컴파일러 mangle(특히 --rerun-tasks/멀티데몬). **해결**: gradle 호출 앞에
  `export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 LC_CTYPE=en_US.UTF-8 JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"`.
  --rerun-tasks 금지, 동시 gradle 금지(빌드락 충돌로 에이전트 2회 stuck). gradle.properties는 안 건드림(에이전트가 넣은 in-process strategy 원복함).
- **빌드 호스트가 gradle을 ctx_execute로 리다이렉트**(Bash 차단) — `mcp__…ctx_execute(language:shell)`로 실행.
- **load 144 사망 위험**: devsam(hidche) 스택 `docker stop`함(복구: `docker start $(docker ps -aq --filter name=hidche-)`). opensamguk 스택은 유지. 동시 에이전트 다수+듀얼스택+Docker부팅=load폭발.
- **stale worktree 1.8GB**: `.claude/worktrees/agent-*`(w0/*·wf_*) 11개 — 죽은 세션 확인되면 `git worktree remove`로 회수. `git worktree prune`은 깨진것만 제거함.
- 게이트 검증: exit code 비신뢰 → `build/test-results/test/*.xml` 직접 파싱(failures/errors=0).

## 6. 검증 명령 (재개 시)
```
export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 LC_CTYPE=en_US.UTF-8 JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test :infra:test :app:game-api:test :app:game-engine:test
# 또는 tools/parity/gate.sh backend
```
</content>
