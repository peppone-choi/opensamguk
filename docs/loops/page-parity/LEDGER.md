# LEDGER — page-parity 루프

행 형식: `| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |`

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |
|---|---|---|---|---|---|
| 0 | (베이스라인) | BE 405/405 suites (2925 tests) green + FE tsc×2 clean + web/game 42/42 | gate.sh backend XML + pnpm (결정적) | 기준선 | main c75d0f9 (=be9916d+루프파일), 2026-06-10 12:06 KST |

## 백로그 (바퀴 후보 — 가설 1개 = 바퀴 1개)

- read-api 미구현: `Nation/GetGeneralLog`(=General alias), `Global/ExecuteEngine`, `Global/GeneralListWithToken`, `InheritAction/GetMoreLog` (핸드오프 §2G)
- intake 미등록 6종: General/DieOnPrestart·DropItem·InstantRetreat, InheritAction/CheckOwner·ResetStat, Misc/UploadImage (§2F)
- statistic 골든 latent 3건: nations.all dict-vs-array / avg 키순서·pre-round / crewtype 3VL (2026-06-10 핫픽스 리뷰)
- (MISSING) battle-center 페이지 — 페이지 감사 워크플로 결과 대기
- 페이지 감사 P0 목록 — `PAGE_PARITY_AUDIT_2026-06-10.md` 산출 후 바퀴로 분해
- 빼섭 보급-동결 상류 버그 (doNPC구출발령 빈 supplyCities)

## 승인 대기

- 없음 (GOLDENSET.md 2026-06-10 승인·동결)
