# 캔버스 v3 · v3.1 — 화면 정본 원본

**화면 정본은 v3.1이다** — 2026-09-30 사용자가 전 페이지 설계 v3.1을 전체 승인했다(ADR-LITE-049 2026-09-30 「v3.1 전체 승인」).
캔버스는 claude.ai Design 아티팩트 `KCFDJTVgSGFa9N4qzrQ6By` 13판(보드 186장)이고, 이 폴더의 `v31system.py` · `boards_v31_k*.py` ·
`boards_v3_bundle1_rev.py` 와 `project/V31*.dc.html` 이 그 생성 원본이다. 승인 뒤 바꾸려면 ADR 개정으로만 한다.

v3(09-26 승인본)은 v3.1의 바탕이다.
- 정보 구조·내용: 2026-09-18 새 화면 캔버스를 최신 결정으로 고친 판
- 시각: 야전 사령부 19장 S1의 토큰·초상 3종

v3 보드는 claude.ai Design 아티팩트 「오픈삼국 새 게임 화면 시안」(`YU3AkStNuos4kXdMW5RJC2`)에 있다.

## 만드는 법

```sh
python3 boards_v3_shell.py   # project/V3*.dc.html 을 만든다
python3 v31system.py         # project/V31System*.dc.html
python3 boards_v31_k4.py     # 레인 보드(k4 … k8), boards_v3_bundle1_rev.py 도 같은 방식
python3 boards_v31_k6_battle_v2.py   # K6 전투 화면 개정(D24) — boards_v31_k6 부품을 가져다 쓴다
python3 boards_v31_k6_help.py   # K6 서신 「도움 요청」(D68) — boards_v31_k6 부품을 가져다 쓰고 이 보드만 굽는다
```

| 파일 | 내용 |
|---|---|
| `ui.py`, `names.py` | 2026-09-18 캔버스 공용 부품·표기 규칙. `names.py`는 저장소 루트의 `data/map/han-tiles.json`을 읽는다 |
| `v3map.py` | 작전실 지도 개략도. 국가색은 의미색과 겹치지 않는 예시 색이다 |
| `v3common.py` | v3 공용: 셸 하나, 메뉴 한 벌(`NAV`), 모바일 탭, 사유 시트, 자원색, 기록 5분류 |
| `boards_*.py` | 묶음별 보드 |
| `v31system.py` | **v3.1 디자인 시스템(전 페이지 설계 공용)**: 셸 v3.1 · 부품 · 상태 · 명령 흐름 · 지도 대상 고르기 · 내 위치 · 사람 고르기. 설계 레인은 `from v31system import *`. 부품 id 는 `PARTS`, 규칙은 `RULES`. `python3 v31system.py` → `project/V31System*.dc.html` |
| `v31assets.py` | v3.1 캔버스(KCFDJTVgSGFa9N4qzrQ6By) 그림 id(K0 관리). 없으면 v31system 이 그림 자리를 점선 상자로 그린다 |
| `boards_v31_k4.py` … `boards_v31_k8.py`, `boards_v3_bundle1_rev.py` | v3.1 페이지 보드(레인별). `from v31system import *` |

이미지(초상·카드 그림)는 아티팩트 저장소의 `/_blob/…` 참조라 이 저장소에서는 그려지지 않는다.

## 묶음

| 묶음 | 보드 | 상태 |
|---|---|---|
| 1 셸·메뉴·작전실·시스템 | V3WarRoom, V3WarRoomTablet, V3MWarRoom, V3MSheet, V3MReason, V3MMenu, V3Nav, V3System | 승인(2026-09-26) |
| 시스템 v3.1(전 페이지 설계 공용) | `V31System*` 35장 — 셸 · 부품 · 상태 · 명령 흐름 · 지도 대상 고르기 · 내 위치 · 사람 고르기 · 게이트웨이 · 걸음 입력 · 시간 막대 · 서랍 | 승인(2026-09-30) |
| v3.1 K4 — 작전실 · 부 · 영지 | `V31K4*` 29장 | 승인(2026-09-30) |
| v3.1 K5 — 입장 · 로비 · 계정 · 커뮤니티 · 기록 · 운영 콘솔 | `V31K5*` 58장 | 승인(2026-09-30) |
| v3.1 K6 — 명령 흐름 · 계책 · 군단 · 전투 · 외교 · 서신 | `V31K6*` 29장 | 승인(2026-09-30) |
| v3.1 K6 개정 — 전투 화면(D-BATTLE 2C · 1A) | `V31K6v2*` 6장(`boards_v31_k6_battle_v2.py`) — `V31K6BattleJoin` · `BattleLive` · `BattleLiveUnits` 와 모바일 셋을 대신한다 | 승인(2026-10-01, D24 · ADR-LITE-049 개정) |
| v3.1 K7 — 도움말 · 튜토리얼 | `V31K7*` 11장 | 승인(2026-09-30) |
| v3.1 K8 — 2 · 3층(관직 · 조정) | `V31K8*` 19장 | 승인(2026-09-30) |
| v3.1 K0 — 1묶음 개정 · 표지 · 기준선 | `V31Map*` 3장 · `V31Cover` · `V31Baseline` — 5장 | 승인(2026-09-30) |
| v3.1 K2 — 새 지도 군단 표지 세 상태(`boards_v31_k2.py`) | `V31K2CorpsStates` · `V31K2MCorpsStates` — 2장 | 승인(2026-10-02, ADR-LITE-049 개정 · 원장 §1 D34) |
| v3.1 K4 — 작전실 성 찾기(`boards_v31_k4.py`) | `V31K4WarRoomSearch` · `V31K4WarRoomSearchNone` · `V31K4MWarRoomSearch` — 3장 | 승인(2026-10-03, ADR-LITE-049 개정 · 원장 §1 D46) |
| v3.1 K5 — 입장 역할로 들어가기(`boards_v31_k5.py`) | 바꾼 승인본 11장(`V31K5Entry` · `MEntry` · `EntryStates` · `Historical` · `MHistorical` · `MHistoricalSheet` · `Create` · `MCreate1` · `MCreate2` · `MCreate4` · `EnlistEmpty`) + 새 `V31K5MCreate0` — 12장 | 승인(2026-10-03, ADR-LITE-049 개정 · 원장 §1 D83 · D84) |
| v3.1 K5 — 로비 펼친 지도 · 모바일 가입 띠 · 첫걸음 카드 걷기(`boards_v31_k5.py`) | 새 `V31K5LobbyOpen` + 바꾼 승인본 `V31K5MJoin` · `Lobby` · `MLobby` · `LobbyStates` — 5장 | 승인(2026-10-03, ADR-LITE-049 개정 · 원장 §1 D87 · D88 · D89) |
| v3.1 K5 — 게임 관리 세력 개요 열 이름(`boards_v31_k5.py`) | 바꾼 승인본 `V31K5GameAdminNations` — 1장(「수도 창고 금 · 쌀」 → 「창고 합 금 · 쌀」) | 승인(2026-10-04, ADR-LITE-049 개정 · 원장 §1 D95) |
| v3.1 K6 — 서신 「도움 요청」(D68, `boards_v31_k6_help.py`) | 새 `V31K6HelpRequest` · `V31K6MHelpRequest` · `V31K6MHelpStatus` — 3장 | 승인(2026-10-05, ADR-LITE-049 개정 · 원장 §1 D111) — [결정 대기] 다섯 칸은 그대로 |
| v3.1 직속 명령 · 상관 건의(`boards_v31_direct.py`, K4 주관 · K6 · K8 절) | `V31Direct*` 13장 — 부 편성 직속 관계 · 인물 상세 관계 · 직속 명령 흐름 · 수신함 받은 것/보낸 것 · 건의 폼 · 판단 · 고친 기록 · NPC 판단 대기 | 승인(2026-10-06, ADR-LITE-049 개정 · 원장 §1 D127 — 「[결정 대기]」 칸을 D128–D135로 문구만 채움, 보류 P03 · P04 · P14 · P15 · P16 · H01은 [값] · [미정]) |

2026-10-03 대비 개정(ADR-LITE-049 개정 · 원장 §1 D73–D76a · D86): 승인본 210장(main 병합 뒤 K5 입장 역할 · K8 제안 보드 포함)을 다시 굽지 않고 글자 대비에 걸린 색 바이트만 바꿨다 — 흐린 글자 #8a8477 → `--muted` #8e8879, 밝은 바탕 위 흐린 글자는 `ui.py` `CONTRAST_CSS` 한 줄로 `--text-2`, 이끼 · 정보 칩 글자, 계책 카드 글자색, 흐린 묶음 둘(반투명 → `--muted`). 생성기도 같은 값을 낸다.

v3.1 정본 커밋(ADR 표와 같다): 시스템 `d64c11596` · K4 `890f1a0bc` · K5 `2d73c14cf` · K6 `1ed54d26c` · K7 `cd751c13c` · K8 `6b42da61a` · K0 `91dfa45b6`.
이 폴더의 v3.1 파일은 그 커밋들의 `docs/design/ui-v3/` 를 바꾸지 않고 모은 것이다.
정본은 커밋된 `project/V31*.dc.html` 자체다. K4 · K6 · K7 · K8 보드는 그 레인이 쓰던 앞 판 `v31system` 으로 구워서, 지금 판(3.1.6)으로
다시 구우면 알림 띠 CSS 등이 달라진다. 보드를 고칠 때만 다시 굽고, 그때는 ADR 개정으로 한다. `boards_v3_bundle1_rev.py` 는 정본 5장 밖의
`V31WarRoom` · `V31MWarRoom` 도 만든다 — 그 둘은 정본이 아니다.

## 규칙

- 한글 우선: 縣→현, 郡→군, 城→성, 省→구역. 금·쌀을 쓰고, 화면에 사료·출처를 넣지 않는다. 화면 이름은 「계책 덱」이다.
- 보드의 지도는 배치를 보이는 개략도다. 실제 지도 그림은 와룡전풍 탑다운 타일이다(ADR-LITE-049 2026-09-26 개정 「지도 표시」).
- 데스크톱·태블릿·모바일은 같은 게임이다. 비활성은 점선 + 누르면 열리는 사유이고, 호버 전용 정보는 두지 않는다.
- 코에이(RTK14) 수치는 커밋하지 않는다. 인물 능력치 예시는 로컬 캐시가 있을 때 `V3_KOEI=1 python3 boards_v3_shell.py`로만 넣고(비공개 캔버스용), 저장소 사본은 「—」다. 적성은 확정 가중 평균식으로 계산한다. 설계에서 미정인 수치는 `[미정]`으로 둔다.
