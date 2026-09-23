# 3-6 재분할 잔여와 엔진 밖 템포 실험 (2026-09-24)

기준: main `5a5176ebe3f94bc67732e07cb75924bdaf9cc82d`, han-tiles **1,653省·1,577城·1,447 관할**. 실행 입력은 이 PR의 변경 파일과 함께 `tools/sim/* --evidence`로 다시 고정한다. 아래는 게임 엔진 운영 판정이 아닌 지도·시나리오 입력의 재측정이다.

## 北海國 개시 보급

`python3 tools/scenario/audit_scenario_seed_supply.py --json`의 `BOTH_UNSUPPLIED`을 `han-world-v3`의 `city.meta.junCh` 및 각 시나리오 `nation[8]`와 ID로 결합했다.

| 시나리오 | 北海國 소유 城 | 北海國 절단 | 전체 절단 | 齊國·樂安國 소유 |
|---|---:|---:|---:|---|
| 1041 | 원소 18 | **0** | 0 | 두 國 모두 중립(6·9城) |
| 1120 | 공손찬 18 | **0** | 116 | 두 國 모두 중립(6·9城) |

따라서 #867에 적힌 1041의 15, 1120의 91은 현 판의 北海國 실측값이 아니다. 양 시나리오의 北海國 절단은 현 보급 그래프에서 해소됐다. 1120의 전체 116 절단은 별도 잔여다. 대조군은 `北海國` 소속 18城 전부와 1120의 실제 전체 절단 116건이다.

출전은 《後漢書》 卷112 郡國志四의 별도 항목 「樂安國…九城」, 「北海國…十八城」, 「齊國…六城」이다. 《三國志》 卷6 袁紹傳은 袁譚의 靑州 진출을, 卷1 武帝紀는 臧霸 등의 齊·北海 공격을 전한다. 이 출전은 세 國의 위치·당시 세력 관계를 대조하는 자료이며, 시나리오 1041/1120의 齊·樂安 중립 소유를 사료로 승인하는 근거는 아니다. 중립 설정의 역사적 주인 확정은 **사람 확인**으로 남긴다. 현재 보급 0건 판정은 시나리오 입력과 그래프에 한정된다.

## 영토 단절 결정표

`territory-disconnection-review-table-v1.json`은 partition 원장의 `pendingReview` **67행 전부**를 ID·조각 키로 옮긴 별도 초안이다. 각각에 이전 verdict, 출전, 현재 셀·구성원 비교를 기록했다. 현재 격자와 정확히 맞는 행은 52, 구성/칸 수가 달라진 행은 11, 같은 조각 키가 사라진 행은 4다. `NEW_UNVERIFIED` 11행과 기존 저신뢰 수정 판정 4행, 합계 15행은 `HUMAN_DECISION_REQUIRED`로 분리했다. 나머지 52행도 현재 기하 재검토 전에는 최종 판정으로 승격하지 않는다. `decision: null`은 결손을 추정으로 메우지 않았다는 뜻이다.

현재 `python3 tools/map/audit_territory_disconnections.py --check`는 실패한다. 옛 기준 원장의 `PARENT-0038@498:182`는 사라졌고, 현재 `PARENT-0038@456:178` 23칸이 무판정이다. 옛 검증표는 이전 격자에 대한 사료·지리 표결이므로 새 조각에 그대로 복사하지 않았다. 이 1건은 추가 재심사 대상이다.

재현: `python3 tools/map/refresh_territory_review_table.py --check`는 바이트 동일성 및 현재 격자 `PARENT-0038@456:178` 양성 대조군을 검사한다.

## 행군·보급 및 징세·징병·공성

현재 省 그래프에서 중원 간선은 **796개**, 중앙값 **29.9 km**다. `march-tempo-baseline.md`, `siege-supply-baseline.md`, `march-tempo-targets-v1.json`의 이전 판 수치를 갱신했다. 승인된 평지 30 km/순·험지 1.5·강행 1.5 및 일반 縣城 봉쇄 12–24순은 변경하지 않았다. 여덟 행군 경로 표와 보급 비교 표는 도구 출력과 일치한다.

`python3 tools/sim/yuzhou_campaign.py`는 현 豫州 **105縣**을 살아 있는 집합에서 읽어, 단독 공격군 조건을 만족한 102縣에 대해 각 4개 독립 시나리오를 계산했다. 모집·월별 세입·군량·행군·구원 수송·포위·함락을 같은 장부에서 정산한다. 기준 시나리오 102/102 함락, 포위 기간 22순으로 승인 12–24순 안이다. 구원 66/102 함락, 공격군 기아와 조우 시나리오는 각 0/102 함락이다. 평시 36순 순곡물 세입 대비 초기 모집·기준 군량 부담은 **40.61–40.63%**로 승인 25–50% 안이다. 단독 공격 병력이 부족한 3縣은 실험하지 않았다. 수치와 보존식은 스크립트의 assertion으로 검증하며 운영 엔진·전투 AI의 결과를 주장하지 않는다.

검증: `python3 -m unittest tools.sim.tests.test_march_tempo tools.sim.tests.test_siege_supply`; `python3 tools/sim/yuzhou_campaign.py`; `python3 tools/scenario/audit_scenario_seed_supply.py --check`; `python3 tools/map/refresh_territory_review_table.py --check`. 영토 단절 `--check`의 위 실패는 별도 잔여로 기록한다.
