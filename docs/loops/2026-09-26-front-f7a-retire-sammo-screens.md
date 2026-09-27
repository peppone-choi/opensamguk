# 2026-09-26 프론트 F7a — 대체 없이 지우는 삼모 화면

## 범위

ADR-LITE-049 2026-09-26 개정 「삼모 화면」(#971) 중 **대체 없이 삭제**하는 화면의 웹 쪽을 지운다. 순서는 화면 → API다. 이 PR이 들어가면 #917이 해당 백엔드 API를 지울 수 있다. 백엔드·운영 도구는 바꾸지 않는다.

| 항목 | 이 PR |
|---|---|
| 빙의·장수 선택 풀 | `select-pool` 화면·`CharacterClaim` 삭제. 게임 입구(`GameEntry`)와 로비는 장수가 없으면 가입 하나로 보낸다. `/game/select-pool`은 404 |
| 삼모 랭킹 4종(황제·황제 상세·NPC·명예의 전당·접속 통계) | 화면 삭제, 기록 탭·랭킹 로비·옛 PHP 경로 대응에서 제거, `/game/rankings/<그 4종>`은 404 |
| 사령턴 | 고아 `ChiefCommandReserve` 삭제, `CommandModal`의 국가 명령 제출 분기(`isNationCommand`) 제거 |
| 경매·베팅·토너먼트·유산·설문·NPC 정책·모의전투 | 화면은 이미 없었다. 남은 API 클라이언트·타입·상수·CSS·FrontInfo 필드(`npcMode`·`auctionCount`·`isBettingActive`·토너먼트·설문 등)를 지웠다 |
| 전역 메뉴 | web이 쓰지 않던 `api.globalMenu`·`menu-types.ts`·타입 삭제 |

감찰부는 `battle-center`와 같은 화면인데, 전면 감사 §6에서 기록 화면(F6)으로 대체하기로 했으므로 이 PR에서는 지우지 않았다.

## 백엔드에 남은 것(#917 몫, 이제 웹 소비자 0)

`SelectPoolController`·`PossessionController`·`RankingController`의 npcs/hall-of-fame/traffic/emperor/emperor/{id}·`AuctionController`·`BettingController`·`TournamentController`·`InheritPointController`·`InstantActionController`·`VoteController`·`NpcPolicyController`·`ChiefCenterController`·`SimulatorController`·`GlobalMenuController`, `CommandController`의 `nation/{bulk,push,repeat}`·`selectPoolPick/Update`·경매·베팅 wire, FrontInfo의 위 필드.

## 일부러 남긴 것

- 게이트웨이 관리 화면 서버 리셋 옵션(NPC 빙의·토너먼트 자동 시작·자동 행동). 리셋 워크플로·엔진 기본값과 함께 #917 백엔드 제거 때 정리한다.
- 예약 서버 ID 목록의 `select-pool` 등 이름(서버 ID 충돌 방지).
- 아이콘 `hub-emperor`·`hub-npcs`·`hub-traffic`: opensamguk-images 정본·스프라이트 2개·`icons.ts`를 함께 바꿔야 해서 따로 한다.
- CommandModal의 삼모 카탈로그 분기 전체: #917 알파 카탈로그 슬라이스와 화면 교체에서 한다.

## 검증

- web/game: typecheck, vitest 742/742(지운 화면 테스트 제외, 입구 테스트 `game-main-entry` 4건 새로), `next lint` 오류 0.
- web/gateway: typecheck, vitest 262/262, `next lint` 오류 0. web/shared vitest 141/141.
- 라우트 적색 프로브: `v2-lab-route.test.tsx`가 `select-pool`과 랭킹 4종 경로의 page 부재·404를 확인하고, 남은 랭킹 3종은 통과시킨다.
- `naming_lint.py`: retired_reference 5864→5850(기준선 갱신).
- 로컬 `next build` 미실행(디스크). CI가 돌린다.
