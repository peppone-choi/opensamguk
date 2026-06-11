# LEDGER — page-parity 루프

행 형식: `| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |`

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |
|---|---|---|---|---|---|
| 0 | (베이스라인) | BE 405/405 suites (2925 tests) green + FE tsc×2 clean + web/game 42/42 | gate.sh backend XML + pnpm (결정적) | 기준선 | main c75d0f9 (=be9916d+루프파일), 2026-06-10 12:06 KST |
| 1 | Nation/GetGeneralLog read API 포팅(§2G) — 4 reqType + 권한사슬 + id<reqTo 페이지네이션 | 405→406 suites green + 갭 1 닫힘 (신규 스위트 10 tests red→green 관찰) | fresh 서브에이전트(a1cbf2aa) — XML 판독 + PHP 대조, score: PASS | 채택 | 1차 게이트런 TC 기동실패 2건은 도커 OOM 플레이크(재실행 green으로 입증) |
| 2 | P0-01 예약명령 패널 하드코딩 '휴식' 위조 제거 — `GET /api/reserved-commands` 실제 소비 | 409→409 suites green + FE tsc clean + 갭 1 닫힘 | fresh 서브에이전트(ce-testing-reviewer) — 코드 리뷰 + gate/tsc 검증, score: PASS | 채택 | `PartialReservedCommand.tsx`: `useEffect`로 `api.reservedCommands()` 호출, `slotMap` 매핑, 빈 슬롯만 '휴식', 예약 후 `refreshKey` 증가 |

## 백로그 (바퀴 후보 — 가설 1개 = 바퀴 1개)

- read-api 미구현: `Nation/GetGeneralLog`(=General alias), `Global/ExecuteEngine`, `Global/GeneralListWithToken`, `InheritAction/GetMoreLog` (핸드오프 §2G)
- intake 미등록 6종: General/DieOnPrestart·DropItem·InstantRetreat, InheritAction/CheckOwner·ResetStat, Misc/UploadImage (§2F)
- statistic 골든 latent 3건: nations.all dict-vs-array / avg 키순서·pre-round / crewtype 3VL (2026-06-10 핫픽스 리뷰)
- (MISSING) battle-center 페이지 — 페이지 감사 워크플로 결과 대기
- 페이지 감사 P0 목록 — `PAGE_PARITY_AUDIT_2026-06-10.md` 산출 후 바퀴로 분해
- 빼섭 보급-동결 상류 버그 (doNPC구출발령 빈 supplyCities)
- General/GetGeneralLog self-view 변형 (checkPermission 무력화 — 재야 포함 본인 로그 열람, PHP General/GetGeneralLog.php:43-50) — 바퀴1 채점자 발견
- secretPermission 단일소스화 (GeneralLogController ↔ DiplomacyController 중복) + penalty/ambassador/auditor/secretlimit 분기 (schema BLOCKED)
- 페이지 감사 P0 54건 — PAGE_PARITY_AUDIT_2026-06-10.md (W0 파운데이션 8종 → W1 A~O 웨이브)

## 승인 대기

- 없음 (GOLDENSET.md 2026-06-10 승인·동결)
