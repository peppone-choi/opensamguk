# 캔버스 v3 — 화면 정본 원본

ADR-LITE-049 2026-09-26 개정의 화면 정본이다.
- 정보 구조·내용: 2026-09-18 새 화면 캔버스를 최신 결정으로 고친 판
- 시각: 야전 사령부 19장 S1의 토큰·초상 3종

캔버스는 claude.ai Design 아티팩트 「오픈삼국 새 게임 화면 시안」(`YU3AkStNuos4kXdMW5RJC2`)에 올리고, 이 폴더는 그 v3 보드의 생성 원본이다. 화면 묶음 단위로 사용자 승인을 받은 뒤 구현한다.

## 만드는 법

```sh
python3 boards_v3_shell.py   # project/V3*.dc.html 을 만든다
```

| 파일 | 내용 |
|---|---|
| `ui.py`, `names.py` | 2026-09-18 캔버스 공용 부품·표기 규칙. `names.py`는 저장소 루트의 `data/map/han-tiles.json`을 읽는다 |
| `v3map.py` | 작전실 지도 개략도. 국가색은 의미색과 겹치지 않는 예시 색이다 |
| `v3common.py` | v3 공용: 셸 하나, 메뉴 한 벌(`NAV`), 모바일 탭, 사유 시트, 자원색, 기록 5분류 |
| `boards_*.py` | 묶음별 보드 |
| `v31system.py` | **v3.1 디자인 시스템(전 페이지 설계 공용)**: 셸 v3.1 · 부품 · 상태 · 명령 흐름 · 지도 대상 고르기 · 내 위치 · 사람 고르기. 설계 레인은 `from v31system import *`. 부품 id 는 `PARTS`, 규칙은 `RULES`. `python3 v31system.py` → `project/V31System*.dc.html` |
| `v31assets.py` | v3.1 캔버스(KCFDJTVgSGFa9N4qzrQ6By) 그림 id(K0 관리). 없으면 v31system 이 그림 자리를 점선 상자로 그린다 |

이미지(초상·카드 그림)는 아티팩트 저장소의 `/_blob/…` 참조라 이 저장소에서는 그려지지 않는다.

## 묶음

| 묶음 | 보드 | 상태 |
|---|---|---|
| 1 셸·메뉴·작전실·시스템 | V3WarRoom, V3WarRoomTablet, V3MWarRoom, V3MSheet, V3MReason, V3MMenu, V3Nav, V3System | 승인(2026-09-26) |
| 시스템 v3.1(전 페이지 설계 공용) | V31SystemIndex · Nav · Shell · Tokens · Parts · MParts · Page · MPage · Command · MCommand · MCommandArgs · MapPick · MMapPick · MapModes · Marker · MMarker · People · MPeople · States · MStates · MNotFound · Banner · MBanner · MMaint · Season · MSeason | 잠금 초안(2026-09-30) — 전 페이지와 함께 한 번 승인 |

## 규칙

- 한글 우선: 縣→현, 郡→군, 城→성, 省→구역. 금·쌀을 쓰고, 화면에 사료·출처를 넣지 않는다. 화면 이름은 「계책 덱」이다.
- 보드의 지도는 배치를 보이는 개략도다. 실제 지도 그림은 와룡전풍 탑다운 타일이다(ADR-LITE-049 2026-09-26 개정 「지도 표시」).
- 데스크톱·태블릿·모바일은 같은 게임이다. 비활성은 점선 + 누르면 열리는 사유이고, 호버 전용 정보는 두지 않는다.
- 코에이(RTK14) 수치는 커밋하지 않는다. 인물 능력치 예시는 로컬 캐시가 있을 때 `V3_KOEI=1 python3 boards_v3_shell.py`로만 넣고(비공개 캔버스용), 저장소 사본은 「—」다. 적성은 확정 가중 평균식으로 계산한다. 설계에서 미정인 수치는 `[미정]`으로 둔다.
