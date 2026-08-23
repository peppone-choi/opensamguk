# PR #530 (issue #514) — SSE 프록시 401 passthrough 독립 비평

- 대상: PR https://github.com/peppone-choi/opensamguk/pull/530, 이슈 #514
- 브랜치: `work/opensamguk/sse-proxy-401-passthrough` (worktree)
- 대조 기준: `git diff origin/main...HEAD` (5 files, +190/-22)
- 리뷰어: 독립 리뷰 레인 (구현자와 다른 컨텍스트). 구현자 요약을 신뢰하지 않고 diff/코드/실행으로 재검증했다.

## 판정: **fix-required**

서버측 핵심 수정(A 케이스)은 정확하고 이슈의 완료 조건을 충족한다. 그러나 **클라이언트측
`useSSE.ts` 변경이 언마운트 이후 EventSource 를 누수시키는 신규 회귀를 도입했고, 이것을
실행으로 증명했다.** 아래 H1 을 고치기 전에는 머지하면 안 된다.

### 반드시 고쳐야 할 것

1. **H1** — `useSSE.ts` 언마운트 레이스: `aliveRef` 류의 취소 플래그를 `.then` 안에서 검사할 것.
2. **H2** — `useSSE.ts:53-54` 주석의 "AuthGate가 재로그인으로 보낸다"는 사실이 아니다.
   재검증 경로를 실제로 만들거나, 최소한 주석을 사실에 맞게 고칠 것.

나머지(M1/M2/H3/L1-L4)는 권고이며 머지를 막지 않는다.

---

## 검증한 증거

### E1. 뮤테이션 체크 — 직접 재현 (구현자 주장 확인됨)

`git stash` 는 이 브랜치에서 no-op 이다(변경이 이미 커밋되어 워킹트리가 깨끗하다). 구현자가 보고한
절차 그대로는 아무것도 되돌리지 않는다. 실제로 되돌리려면 `git checkout origin/main -- <route paths>`
가 필요하다. 그렇게 revert 한 뒤 실행한 결과:

```
# web/game
× game API proxy SSE (/api/game/sse/turn) — #514 401 passthrough
  > returns upstream 401 plainly instead of opening a text/event-stream (200 + {})
  → expected 200 to be 401 // Object.is equality
  Tests  1 failed | 10 passed (11)

# web/gateway
× (동일 테스트) → expected 200 to be 401
  Tests  1 failed | 12 passed (13)
```

fix 복원 후 양쪽 GREEN. **RED→GREEN 은 양쪽 프록시 모두에서 사실이다.** 대조군인
"passes an ok upstream through as a live event-stream" 은 pre-fix 에서도 통과하므로
"401 이 401 로 나간다"가 SSE 를 통째로 깨뜨린 결과와 구분된다 — 이슈 완료 조건이 요구한 그대로다.

### E2. 전체 스위트 / 타입체크 (구현자 주장 확인됨)

- `web/game`: 78 files / 445 tests 전부 통과
- `web/gateway`: 24 files / 174 tests 전부 통과
- `npx tsc --noEmit`: 양쪽 exit 0

### E3. 호출부 감사

- `streamEventSource` 호출부는 파일당 1곳뿐: `web/game/.../route.ts:137`, `web/gateway/.../route.ts:168`
- `/api/game/sse/turn` 을 여는 브라우저 소비자는 `web/game/hooks/useSSE.ts:27` 하나뿐
- `web/game/e2e/v1-core-live.spec.ts:681` 은 EventSource 를 프록시로 감싸 `open` 만 관찰한다 —
  헤더 지연(M2)이 30초 폴링 한도 안이면 영향 없음

---

## 발견 사항

### [HIGH] H1 — 언마운트 이후 좀비 EventSource 누수 (신규 회귀, 실행으로 증명)

`web/game/hooks/useSSE.ts:55-66`

재연결 스케줄링이 `/api/auth/me` 라는 **비동기 홉 뒤로 밀렸다.** `useEffect` cleanup
(`:72-75`)은 `reconnectRef.current` 만 clearTimeout 한다 — cleanup 시점에 타이머가 아직
존재하지 않으면 지울 것이 없다. 그 뒤 pending promise 가 resolve 되면서 **새 타이머를 걸고
`connect()` 를 불러 아무도 소유하지 않는 EventSource 를 만든다.** cleanup 은 이미 지나갔으므로
그 연결은 영원히 닫히지 않는다.

수정 전에는 `setTimeout` 이 `onerror` 안에서 동기적으로 걸렸기 때문에 cleanup 의 clearTimeout 이
항상 잡아냈다. 즉 이 PR 이 만든 회귀다.

증명 — 임시 프로브 테스트(제출 안 함, 삭제 완료). onerror 발생 → 언마운트 → 그 다음
`/api/auth/me` resolve → 타이머 전진:

```
post-fix (HEAD)     : EventSource instances after unmount: 2   ← 좀비 생성
pre-fix (origin/main): EventSource instances after unmount: 1   ← 누수 없음
```

실패 시나리오: 턴 경계/게이트웨이 재시작/탭 스로틀로 SSE 가 끊긴 직후 사용자가 페이지를
벗어나면(`/api/auth/me` 왕복 시간 안에), 열린 SSE 연결이 탭당 하나씩 남고 그 연결에는
멈출 주체가 없는 재연결 루프가 붙는다. #514 가 없애려던 바로 그 "주인 없는 영구 재연결"이
다른 경로로 되살아난다.

수정(3줄):

```ts
const aliveRef = useRef(true);
// ...
useEffect(() => {
    aliveRef.current = true;
    connect();
    return () => { aliveRef.current = false; /* ...기존... */ };
}, [connect]);
// .then 안:
.then((sessionAlive) => {
    if (!aliveRef.current || !sessionAlive) return;
    // ...
});
```

### [MEDIUM] H2 — "세션 만료 확정" 후 복구 경로가 실제로 없다

`web/game/hooks/useSSE.ts:53-54` 주석은 재연결을 멈춰도 "AuthGate가 재로그인으로 보낸다"고
주장한다. **`web/game/components/AuthGate.tsx:16-25` 을 읽어보면 사실이 아니다.** Gate 는
`AuthProvider` 의 `user` 값만 보고, 그 값은 마운트 시 `/api/auth/me` 로 한 번 확정된 뒤
재폴링되지 않는다. 세션이 게임 중간에 만료되면:

- `user` 는 여전히 truthy → 리다이렉트 없음
- SSE 는 영구 침묵 → 턴 갱신 없음
- 사용자에게 보이는 것: 아무 에러 없이 멈춘 화면

#514 가 지적한 해악은 "복구 경로가 없다"였다. 이 변경은 무한 루프를 **조용한 정지**로 바꿨을
뿐 복구 경로를 만들지 않았다. 둘 중 하나가 필요하다:

- (권장) 만료 확정 시 auth context 를 재검증시켜 AuthGate 가 실제로 리다이렉트하게 한다, 또는
- 최소한 주석에서 일어나지 않는 리다이렉트를 주장하지 않도록 고치고, 후속 이슈를 남긴다.

### [MEDIUM] H3 — 클라이언트 변경에 테스트가 0건

`useSSE.ts` diff 전체가 무커버리지로 나간다. `web/game/__tests__/useSSE.test.tsx` 는 손대지
않았다(1 test, onerror 를 건드리지 않음). 이슈의 완료 조건은 프록시 테스트만 요구했으므로
계약 위반은 아니지만, **H1 이 발견된 곳이 바로 이 무커버리지 코드다.** 기존
`FakeEventSource` 하네스로 세 가지를 그대로 고정할 수 있다(내 프로브가 그 증거다):

1. onerror + `/api/auth/me` !ok → 재연결 스케줄 안 됨
2. onerror + `/api/auth/me` ok → backoff 후 재연결
3. 확인 도중 언마운트 → 새 EventSource 없음 (= H1 회귀 방지)

### [MEDIUM] M1 — 연결 수립 중 클라이언트 이탈을 더 이상 abort 할 수 없다

`web/game/.../route.ts:48-51`, `web/gateway/.../route.ts:69-75`

`upstreamAbort` 는 fetch 앞에서 만들어지지만 abort 되는 곳은 `stream.cancel()`
(game:95-100 / gateway:122-127) 뿐이고, **그 스트림은 fetch 가 resolve 되기 전에는 존재하지도
않는다.** 수정 전에는 fetch 가 `start()` 안에 있어서 연결 수립 중 클라이언트가 끊으면
cancel → abort 로 전파됐다. 지금은 game-api 가 느릴 때 클라이언트가 포기해도 upstream fetch 가
계속 살아 있고, 돌아온 body 는 읽히지도 취소되지도 않는다.

`req.signal` 은 수정 전후 모두 배선된 적이 없다(기존 결함). 이번 변경이 남은 한 가지 취소
경로마저 없앤 것이므로, `streamEventSource(target, init, req.signal)` 로 넘겨
`AbortSignal.any([req.signal, upstreamAbort.signal])` 을 쓰는 정도가 적절하다. 작은 diff다.

### [MEDIUM] M2 — 헤더 선전송이 사라졌다 (수정에 내재된 대가, 문서화 필요)

수정 전에는 브라우저가 즉시 200 + `: proxy-connected` 를 받았고 25초 heartbeat 가 느린
upstream 을 덮었다. 지금은 upstream 헤더가 올 때까지 응답 자체를 보류한다. game-api 가
헤더를 늦게 주면 EventSource 는 heartbeat 보호 없이 CONNECTING 에 머물고, 플랫폼 응답
타임아웃에 걸리면 브라우저는 네트워크 에러로 보고 backoff 경로를 탄다.

(A) 를 고치면서 헤더를 미리 flush 하는 것은 원리적으로 불가능하므로 이건 **수용해야 하는
대가**이고 이슈도 그렇게 지시했다. 다만 실제 동작 변화이므로 PR 본문 또는 라우트 주석에
명시하는 편이 좋다 — 지금은 어디에도 안 적혀 있다.

### [LOW] L1 — 주석이 낡았다

`web/game/app/api/game/[...path]/route.ts:25`
"SSE(/api/game/sse/turn): 프록시가 **즉시** event-stream을 열고..." — 더 이상 즉시가 아니다.
upstream 이 ok 일 때만 연다. (`web/gateway` 쪽엔 대응 주석이 없다.)

### [LOW] L2 — 두 파일이 미묘하게 갈라졌다

`web/game:137` 은 `return await streamEventSource(...)`, `web/gateway:168` 은
`return streamEventSource(...)`. 기능은 동일하다. #516 이 이 두 파일을 dedup 하려는 상황이므로
불필요한 diff 는 만들지 않는 편이 낫다.

### [LOW] L3 — `!upstream.ok` 응답이 `content-type` 외 헤더를 버린다

`WWW-Authenticate`, `Retry-After` 등이 사라진다. **다만 JSON 경로도 정확히 같다**
(game:151-155 / gateway:182-188). 계약 동등성이라는 이슈의 목표는 지켜졌으므로 이번 PR 의
결함이 아니다. 기존 동작으로 기록해 둔다.

### [LOW] L4 — onerror 가 두 번 겹치면 backoff 가 두 배 더 뛴다

첫 `/api/auth/me` 확인이 끝나기 전에 두 번째 onerror 가 들어오면 `delayRef` 가 두 번 배가된다.
`clearTimeout` 이 타이머는 하나만 남기므로 이중 연결은 없다. 1초→4초 정도의 영향.

---

## 범위 검토 — 통과

diff 는 5 파일뿐이다. 두 프록시 dedup(#516) 은 건드리지 않았고, (B) mid-stream payload
재설계도 하지 않았으며(주석으로 범위 밖임을 명시), JSON 경로와 기존 테스트는 그대로다.
이슈가 그은 (A)/(B) 경계를 정확히 지켰다.

## 잘한 점

- (A) 를 (B) 처럼 이벤트로 때우지 않았다 — 이슈가 명시적으로 경고한 함정을 피했다.
- 두 테스트 모두 **status 단언**을 포함한다. 이벤트 내용만 단언했다면 pre-fix 도 통과했을 것이고,
  실제로 pre-fix 에서 실패하는 것은 status 단언이라는 걸 뮤테이션 체크로 확인했다.
- 정상 경로 대조군 테스트가 있어 "401 이 401" 과 "SSE 를 깨뜨림" 이 구분된다.
- 프로덕션 트래픽을 받는 `web/gateway` 와 dev 전용 `web/game` 을 동일하게 고쳤다.
- 코드 주석이 (A)/(B) 구분과 그 이유를 코드 옆에 남겨, 다음 사람이 (B) 를 버그로 오인하지 않게 했다.
