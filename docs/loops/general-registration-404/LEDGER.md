# LEDGER — general-registration-404

형식: `| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |`

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |
|------|------|------------|--------|------|------------|
| 0 | 베이스라인(소급) | 0/5 → — | oh-my-claudecode:verifier | 채택 | prod nginx rewrite만 동작하고, 로컬 `next dev`/`infra/nginx/nginx.conf`에선 `/game/smain/join`이 404. middleware가 path serverId를 인식하지 못함. |
| 1 | middleware에서 path serverId(`/game/smain/join`)를 `/game/join?server=smain`으로 rewrite + `sam_server` 쿠키 고정 | 0/5 → 5/5 | oh-my-claudecode:verifier | 채택 | 로컬 dev에서 `/game/smain/join` 200, 쿠키 `sam_server=smain` 확인. tsc green. |

| 2 | game-api FrontGlobalInfo에 `serverId` 노출 + CharacterClaim 링크를 `resolveServerGamePath`로 server-aware 변환 | 5/5 → 5/5 | oh-my-claudecode:verifier + tsc/build/test | 채택 | GOLDENSET 점수는 그대로이나 B2(인게임 등록 링크 serverId 노출) 해결. `/api/front-info` 쿠키→`serverId` 테스트 추가. |

## 백로그

- B3: 전체 인게임 URL 체계를 서버 식별자로 일관 노출(path vs query 결정).
- B4: 레거시와의 entrance 플로우 패러티 갭(미등록 3버튼, 장수생성/빙의/선택) 추가 검증.
- B5: 코드/게임성/성능/기능 발전 아이템은 별도 루프로 분리(본 루프는 404 라우팅에 집중).
