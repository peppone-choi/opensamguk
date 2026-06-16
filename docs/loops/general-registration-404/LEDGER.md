# LEDGER — general-registration-404

형식: `| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |`

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |
|------|------|------------|--------|------|------------|
| 0 | 베이스라인(소급) | 0/5 → — | oh-my-claudecode:verifier | 채택 | prod nginx rewrite만 동작하고, 로컬 `next dev`/`infra/nginx/nginx.conf`에선 `/game/smain/join`이 404. middleware가 path serverId를 인식하지 못함. |
| 1 | middleware에서 path serverId(`/game/smain/join`)를 `/game/join?server=smain`으로 rewrite + `sam_server` 쿠키 고정 | 0/5 → 5/5 | oh-my-claudecode:verifier | 채택 | 로컬 dev에서 `/game/smain/join` 200, 쿠키 `sam_server=smain` 확인. tsc green. |

| 2 | game-api FrontGlobalInfo에 `serverId` 노출 + CharacterClaim 링크를 `resolveServerGamePath`로 server-aware 변환 | 5/5 → 5/5 | oh-my-claudecode:verifier + tsc/build/test | 채택 | GOLDENSET 점수는 그대로이나 B2(인게임 등록 링크 serverId 노출) 해결. `/api/front-info` 쿠키→`serverId` 테스트 추가. |

| 3 | BottomNav/Sidebar/MainControlBar/MainControlDropdown/GlobalMenu/GameInfo/BackBar/join/select-pool/emperor 등 인게임 날개를 `resolveServerGamePath` + `useServerId`/`global.serverId`로 일관 server-aware 변환 | 5/5 → 5/5 | oh-my-claudecode:verifier | 채택 | `href="/game`와 `router.push('/game')` 리터럴 0개. tsc/build green. B3 해결. |

| 4 | 레거시 entrance.ts 3버튼 게이트/링크 패러티 감사 + CharacterClaim `npcMode` 폴백 정정 | — | 본인 검토 + tsc/build | 채택 | 3버튼 게이팅 수식은 lobby/CharacterClaim 모두 legacy와 동일. `npcMode ?? 1`은 BE 기본값 0과 달라 미기재 시 빙의 모드 오판 → `?? 0`으로 정정. select-pool 읽기/선택/수정은 미이식 placeholder로 별도 백로그에 둠. |

## 백로그

- B5: 코드/게임성/성능/기능 발전 아이템은 별도 루프로 분리(본 루프는 404 라우팅+entrance 감사에 집중).
- B6: `web/game/app/game/select-pool/page.tsx` 장수 풀 읽기/선택/수정 기능 이식(레거시 `select_general_from_pool.php`).
