# 기능 안내

> 상태: route와 현재 구현을 기준으로 한 기능 지도
> 마지막 검토: 2026-08-20

## 정보와 기록

| 기능 | 경로 | 설명 |
|---|---|---|
| 내 정보 | `/game/my` | 내 장수 상태와 개인 정보 |
| 국가·도시·장수 | `/game/nation`, `/game/city`, `/game/generals` | 현재 월드의 기본 정보 |
| 내 국가·도시·장수 | `/game/my-nation`, `/game/my-cities`, `/game/my-generals` | 소속 관점의 정보와 권한 기능 |
| 지도 | `/game/map` | 현재 선택된 지도와 도시 정보 |
| 랭킹 | `/game/rankings/*` | 장수, 국가, NPC, 황제, 명예의 전당, 트래픽 |
| 기록 | `/game/history`, `/game/world-log` | 과거 기록과 월드 로그 |

기록 화면은 메인 화면과 갱신 시점이 다를 수 있습니다. 월 정산·시즌 종료 직후에는 화면의 상태 시각을 함께
확인합니다.

## 소통

- Gateway 게시판: `/board`
- 게임 게시판: `/game/board`
- 개인 서신: `/game/mailbox`

공개 글, 국가 범위 글, 개인 서신은 공개 범위가 다릅니다. 민감한 외교·국가 정보를 공개 게시판에 올리지
마세요. 운영자의 공지 고정·soft-delete 전체 기능은
[OPENSAM-81/#223](https://github.com/peppone-choi/opensamguk/issues/223)에서 추적 중이므로 구현 완료로
가정하지 않습니다.

## 부대·전투·외교

- 부대: `/game/troop`
- 전투 본부: `/game/battle-center`
- 국가 외교: `/game/diplomacy`
- 전역 외교 현황: `/game/global-diplomacy`
- 전투 시뮬레이터: `/game/simulator`

시뮬레이터 결과는 실제 월드 상태를 바꾸는 전투와 동일한 운영 행위가 아닙니다. 실제 출병은 예턴, 경로,
외교와 daemon 결과를 따릅니다.

## 기간형·부가 기능

| 기능 | 경로 | 확인할 상태 |
|---|---|---|
| 경매 | `/game/auction` | 열림, 입찰, 마감, 정산 |
| 일반 베팅 | `/game/betting` | 후보, 기간, 마감 |
| 국가 베팅 | `/game/nation-betting` | 이벤트가 연 서버인지, 마감 상태 |
| 투표 | `/game/vote` | 열린 안건, 참여 여부, 종료 |
| 유산 | `/game/inherit` | 보유 포인트와 사용 조건 |
| 토너먼트 | `/game/tournament` | 등록, 조별/본선 진행, 결과와 보상 |
| 장수 선택 | `/game/select-pool` | 현재 선택 가능 장수와 terminal 결과 |

메뉴가 있다고 항상 이벤트가 열린 것은 아닙니다. 시나리오와 서버 설정이 기능을 열지 않았다면 빈 상태 또는
사용 불가가 정상입니다.

## v2 실험 화면

`/game/v2-lab/*`은 garrison, ledger, space, transport 같은 v2 계약을 검증하는 실험 표면입니다. 일반 시즌의
완성 기능이나 운영 약속으로 보지 않습니다. v2 제품 방향은 [현재 로드맵](../design/roadmap.md)을 따릅니다.
