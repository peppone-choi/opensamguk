# LEDGER — 5-stat divergence + 패러티 재정렬 (2026-06-13)

오답 노트 + 백로그. 바퀴마다 1줄. 실패도 반드시 기록.
행 형식: `| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |`

## 바퀴 기록

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |
|---|---|---|---|---|---|
| 0 | (베이스라인) | common 192 + logic 2123 = 2315 green / fail 0 | gate.sh (결정적, --rerun-tasks) | 기준 | Docker-down IT FAILED는 환경탓·패러티 회귀 아님. 커널 green 확정. |
| 1 | General 도메인에 politics/charm inert 필드(기본0, meta 뒤 append) | 2315 → 2315 green / fail 0 | fresh 서브에이전트 a37a5613 (--rerun-tasks 실행) | 채택 | 무회귀(D1) 충족. 골든 무수정. 중간삽입 대신 끝append로 positional caller 보존. divergence 라벨. |
| 2 | 영속화+import: V16 마이그레이션 + GeneralRowMapper(read/write) + generalUpdate SQL + ScenarioImporter RTK14 룩업 | infra 89 + logic 2123 green / fail 0 | fresh 서브에이전트 a890ad9c (infra+logic 재실행) | 채택 | 정치/매력 seed→DB→메모리→flush 왕복 생존. generalCreateMany 무손상(DEFAULT 50). 골든 무수정. |
| 2b | 동명이인 보정: RTK14 다중행에 통무지 지문 1:1 greedy 최적배정(오프라인) → exact 이름 keying, 로더 strip 제거 | infra 89 green / fail 0 | fresh 서브에이전트 a772d010 (infra 재실행) | 채택 | 유저 지적(중복이름). 마충1≠마충2 distinct(644 distinct/2 붕괴/32 fallback). 재현 스크립트 tools/rtk14/build_rtk14_stats.py 커밋(데이터는 IP gitignore). |

## 백로그 (가설 후보 — 한 바퀴당 1개씩만)

5스탯 divergence 프로그램을 disjoint 바퀴로 분해. Foundation-first.

- **W1 (foundation)** — General 도메인(logic)에 `politics`/`charm` inert 필드 추가(기본값). 게이트: common+logic green 무회귀 + 필드 존재 + 패러티 경로 unchanged.
- **W2** — 값 소스: scenario seed(infra) + MakeGeneral(logic)이 politics/charm 부여. ⚠️값 산출 규칙은 유저 승인 필요(레거시 없음). 게이트: +Docker IT.
- **W3** — 영속화: Flyway 마이그레이션 컬럼 + JdbcFlushExecutor 매퍼 + JPA read 엔티티. 게이트: infra flush IT green (Docker).
- **W4** — UI: web/game(myGenInfo·랭킹·장수카드) + web/gateway 정치/매력 표시. 게이트: tsc + 비주얼.
- **W5 (패러티 재정렬)** — `AvailableCommandsControllerTest` 실패 조사·수정 + 드리프트 감사. 게이트: 전 backend green(Docker up).
- **W6 (docs)** — CLAUDE.md/README/AGENTS + 메모리에 5스탯 divergence + 버전 정책 반영. 게이트: divergence 채점(스펙 일관성).

## W2 결정 (유저 2026-06-13) — 해소됨

- 값 소스 = 삼국지14 무장정보.xlsx (RTK14 955무장). 코에이 IP → git-ignored `rtk14_stats.local.json`(955건 생성). 미커밋 확인.
- 스탯값: devsam 통무지 유지 + 정치·매력만 RTK14 추가.
- 이름매칭 89%(정규화 ~95%+), 잔여 fallback.
- 효과: 기존 로직 일부 대체(내정→정치, 등용/임관·민심/인구→매력, 외교→정치/매력) = Track B.
- 시퀀스: 둘 같이.
- 스펙: `docs/superpowers/specs/2026-06-13-five-stat-rtk14-divergence.md`.

## 승인 대기 (현재)

1. **Track B 골든 재기준선 방식** — devsam 골든을 quarantine 보존+신규 divergence 골든 신설(권장) vs 기존 재생성. **규칙 변경 = 유저 승인 필수.** 승인 전 B1~B4 공식 rewrite 착수 금지(fabricate 위반).
2. **Track B 영역별 신규 공식 계수** — 정치/매력을 지력/통솔 자리 1:1 치환? 가중? 영역별 스펙 필요.
3. **코에이 IP prod 사이드로드** 방식(미커밋 데이터를 prod에 전달).
4. **CLAUDE.md divergence carve-out 문구**(W6) — 패러티 규율에 5스탯 예외 명문화 = 규칙 변경.

## 백로그 갱신

- W2(import 로더)·W3(영속화)·W4(UI) = Track A, 승인 불요, 기존 게이트 green이 측정. 순차 실행.
- B1~B4 = Track B, 승인 게이트 뒤. 각 1바퀴.

## 빼기 주기

- 다음 삭제 바퀴: W3 이후(더하기 3연속 시).
