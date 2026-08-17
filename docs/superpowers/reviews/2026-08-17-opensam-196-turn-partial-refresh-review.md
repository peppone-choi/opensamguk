# OPENSAM-196 — 턴 SSE 부분 갱신(페이지 리로드 제거) 자체 리뷰

Scope: web/game 프런트엔드 턴 갱신 경로 — web/game/components/Shell.tsx, web/game/lib/turnEvents.ts, web/game/hooks/useTurnRefresh.ts, web/game/hooks/useFrontInfo.ts, 그리고 web/game/app/game/** 화면 40여 개와 web/game/components/auction/** 및 web/game/__tests__/**.
Verdict: cleared

## 1. 무엇을 바꿨나

턴이 끝날 때마다 `Shell`이 `window.location.reload()`를 불렀다. 스크롤 위치, 입력 중이던 폼,
열려 있던 모달이 턴마다 전부 날아갔다. 대신:

- `lib/turnEvents.ts` — 턴 완료 신호 대기소(`deliverTurnCompleted` / `subscribeTurnCompleted`).
  이미 있는 `lib/commandResultEvents.ts`와 같은 모양이다.
- `hooks/useTurnRefresh.ts` — 화면이 자기 fetch 클로저를 넘겨 구독한다. 콜백은 ref로 최신 것을
  잡으므로 매 렌더 새로 만들어져도 재구독하지 않는다(`useSSE`와 같은 규약).
- `components/Shell.tsx` — SSE 하나가 신호를 받아 `deliverTurnCompleted()`로 나눠 준다. 리로드 제거.
- 화면 40여 개 + `components/auction/{AuctionResource,AuctionUniqueItem}.tsx`가 `useTurnRefresh`로
  자기 데이터만 다시 읽는다.

부수 효과 하나: 자체 `new EventSource('/api/game/sse/turn')`를 열던 7곳(betting, board, admin5,
admin8, nation-finance, 경매 컴포넌트 2개) + `useFrontInfo`가 공용 신호로 갈아탔다. 탭당 상시
연결이 화면 수만큼 늘던 것이 하나로 줄었다(오리진당 연결 한도).

로딩 플래시 방지는 기존 `nation-finance`의 `fetchData(background = false)` 규약을 그대로 따랐다.
`background=true`면 로딩 표시와 폼 상태 재설정을 건너뛴다.

## 2. 의도적으로 안 붙인 화면과 이유

- `join`, `select-pool`, `npc-control` — 사용자가 입력/선택 중인 폼이 화면의 본체다. 턴 갱신이
  입력을 덮어쓰는 쪽이 낡은 값보다 나쁘다.
- `admin1`(게임 설정), `register`, `coming-soon`, `rankings` 인덱스, `simulator` — 턴에 따라
  변하는 서버 데이터를 읽지 않는다.
- `auction/page.tsx` — 데이터는 하위 컴포넌트가 읽는다. 그 두 컴포넌트를 붙였다.
- `app/game/v2-lab/**` — v2 격리 대상. 건드리지 않았다.

Shell 밖에서 렌더되는 화면에는 신호가 오지 않는다 — 갱신이 늦어질 뿐 틀린 값을 보여 주지 않는다.

## 3. 증거

- `web/game`: `pnpm exec vitest run` → **Test Files 69 passed / Tests 389 passed**.
- `pnpm exec tsc --noEmit` → 출력 없음(에러 0).
- `pnpm exec next lint` → 에러 0(기존 `<img>`/exhaustive-deps 경고만 잔존, 이번 변경과 무관).
- `pnpm build` → exit 0, 전 라우트 빌드 성공.

새 테스트 `__tests__/turnRefresh.test.tsx` 3건:
1. 구독자에게 신호가 가고 언마운트 후에는 안 간다(리스너 누수 = 죽은 화면의 요청).
2. 콜백이 매 렌더 새로 만들어져도 재구독 없이 최신 것을 부른다.
3. **회귀 방지 핵심** — `Shell`을 실제로 렌더하고 SSE 프레임을 흘려, `window.location.reload`가
   불리지 **않고** 화면 구독자가 정확히 한 번 깨어남을 고정한다.

비-공허성: 이 3번 테스트는 예전 구현(`refresh = () => window.location.reload()`)에서 실패한다 —
`reload` 스파이가 불리고 구독자는 0회 호출된다.

기존 테스트도 새 구조로 조였다(약화 아님):
- `WorldLogPage.test.tsx` — 화면이 자기 EventSource를 **열지 않음**(`toHaveLength(0)`)을 단언하도록 강화.
- `nation-finance-editor.test.tsx` — EventSource 스텁 대신 공용 신호를 직접 흘린다. 20건 그대로 통과.

## 4. 스스로 공격해 본 것

- **신호를 놓치면?** 결과 폴링(`submitCommandAndAwaitResult`)은 이 신호와 무관하게 그대로다.
  턴 갱신 신호 유실은 화면이 낡을 뿐이며, 사용자가 새로고침 버튼을 눌러 복구할 수 있다.
- **리스너 누수?** `useTurnRefresh`가 언마운트에서 해제하고 테스트가 이를 고정한다.
- **mailbox의 서버 변경 부수효과(최신 열람 처리)가 턴마다 다시 나가나?** 예전에도 자체 SSE로
  턴마다 `fetchMessages()`를 불렀다. 호출 빈도·경로 모두 동일하며 새로 생긴 부수효과는 없다.
- **로딩 플래시로 화면이 깜빡이나?** `!loading` 게이팅이 있는 화면(vote, my, admin2, admin7,
  battle-center 등)은 `background=true` 경로에서 로딩 상태를 건드리지 않는다.

## 5. 이번 커밋에 함께 든 무관한 수정 1건

`__tests__/live-noop-closures.test.tsx`의 select-pool 테스트가 **main에서 이미 빨간불**이었다
(`stash` 후 재현 확인). `submitCommandAndAwaitResult`가 실제로 부르는 것은
`pollCommandResultResponse(requestId, signal)`인데 테스트 목이 `pollCommandResult`만 제공해
페이지가 목 누락 에러를 렌더하고 있었다. 목을 실제 호출 대상에 맞췄다 — 테스트 완화가 아니라
낡은 목의 정정이며, 프로덕션 코드는 건드리지 않았다.

## 6. 남긴 것

- 입력 중 폼이 본체인 화면(join, select-pool, npc-control)의 "충돌 없는 부분 갱신"은 별도 과제다.
  낙관적 병합 규칙이 필요하며 이번 범위 밖이다.
- 브라우저 실측(webapp-testing)은 하지 않았다. 근거는 단위 테스트 + 타입 + 빌드다.

## 7. CodeRabbit 지적 처리 (PR #433)

**고쳤다**
- `useTurnRefresh` — 최신 콜백을 passive effect가 아니라 **렌더 시점**에 ref에 넣는다. 커밋 후
  effect 전에 도착한 턴 신호가 낡은 클로저(방금 바뀐 필터·선택 상태를 모르는)를 부르던 창을 없앴다.
- **background 실패가 화면을 지우던 것** — `generals`·`nation`·`admin2`·`diplomacy`·`board`·
  `troop`·`nation-betting`: 턴 갱신 조회가 실패해도 보고 있던 데이터를 유지하고 차단용 error로
  전환하지 않는다(성공하면 stale error 해제). 일시적 네트워크 오류로 화면이 통째로 에러가 되는
  편이 낡은 목록보다 나쁘다.
- **턴 콜백이 foreground 경로를 쓰던 것** — `board`·`nation-betting`·`troop`·`diplomacy`를
  `background=true`로 바꿨다. 갱신 중 로딩 화면이 목록·댓글 draft를 덮지 않는다.
- `inherit` — 최초/수동/턴 조회가 겹칠 때 늦게 온 옛 응답이 새 값을 덮어쓰던 경합을 세대 번호로
  막았다(언마운트 후 commit도 함께 차단).

**안 고쳤다(이유)**
- "주석을 영어로" — 이 저장소의 프런트엔드 주석은 한국어가 관례다(기존 파일 다수, CLAUDE.md·
  리뷰 문서 모두 한국어). 일관성을 깨는 쪽이 손해다.
- "들여쓰기 2칸" — `web/game`의 기존 코드가 4칸이다. 새 줄만 2칸으로 바꾸면 파일 안에서 섞인다.
- `nation`의 응답 경합 — 같은 엔드포인트 집합을 통째로 다시 세팅하는 구조라 순서가 어긋나도 다음
  갱신에서 자가 치유되고 사용자 입력을 덮지 않는다. `inherit`과 달리 언마운트 누수도 없다.

## 8. 이번 변경과 무관한 기존 결함 1건 (미해결, 보고만)

`__tests__/GameChrome.main-map.test.tsx`의 "returns a successful explicit possession claim …"이
로컬에서 실패한다. **origin/main 내용으로 되돌려도 동일하게 실패**하므로 이 PR이 만든 결함이
아니다(같은 커밋으로 CI `web (game)`은 통과했다 — 환경/스케줄 의존 플레이크로 보인다).
`mockReturnValueOnce` 뒤 재렌더가 기본 목으로 떨어지며 `routerReplace('/game/s1')`가 불리는
구조라 렌더 횟수에 민감하다. 별도 티켓 감이며 여기서 손대지 않았다.
