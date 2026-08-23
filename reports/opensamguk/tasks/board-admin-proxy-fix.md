# board-admin-proxy-fix

## 버그
어드민 "게시판 관리" 탭이 항상 실패(401/404).

## 근본 원인
2026-08-21 커밋 c8507bfa (OPENSAM-208)가 게시판 서비스를 gateway-api → board-api(:8083)로
분리하면서 `web/gateway/app/api/board/[...path]/route.ts`는 `BOARD_API_URL`로 정확히
갱신됐지만, `web/gateway/components/admin/BoardControl.tsx`는 여전히 범용 프록시
`/api/proxy/board/posts...`를 호출하고 있었다. 범용 프록시(`/api/proxy/[...path]/route.ts`)는
`GATEWAY_API_URL`(gateway-api:8080)로만 포워딩하는데 gateway-api에는 더 이상 `/board/**`
매핑도 permitAll 규칙도 없어 SecurityConfig의 authenticationEntryPoint가 401을 뱉거나
핸들러 없음으로 404가 났다.

## 수정
- `web/gateway/components/admin/BoardControl.tsx`의 세 fetch 호출(load, togglePin,
  deletePost) URL을 `/api/proxy/board/posts...` → `/api/board/posts...`로 교체.
- 수정 전 대상 프록시(`/api/board/[...path]/route.ts`)를 직접 읽어 확인: admin이 쓰는
  PATCH `.../pin`, DELETE `.../posts/{id}`, GET `includeDeleted` 쿼리가 모두
  `accessMode: 'required'` 경로로 이미 지원됨을 확인. 프록시 쪽 변경 불필요.
- `web/` 전체에서 `/api/proxy/board` 를 grep — `BoardControl.tsx` 외 다른 회귀 없음 확인.
- `web/gateway/__tests__/admin-board-control.test.tsx`의 기대 URL도 동일하게
  `/api/board/...`로 갱신.

## 범위 밖 (하지 않음)
- 범용 프록시(`/api/proxy/[...path]`)에 401 refresh 재시도 추가 — 지시에 따라 스코프 밖으로 제외.

## 검증
`web/gateway`에서 (pnpm install 후, 출력으로 판정):
- `pnpm test` — 24 files / 172 tests passed (admin-board-control.test.tsx 포함)
- `pnpm typecheck` — clean, 에러 없음
- `pnpm lint` — 기존에 있던 무관한 경고 3건(img 엘리먼트, useEffect deps)만 있고 에러 없음

## PR
https://github.com/peppone-choi/opensamguk/pull/504
(work/opensamguk/board-admin-proxy-fix → main, 커밋 a8b835da)
CI: 푸시 직후 agent-system/jvm/web(game)/web(gateway) 워크플로 pending 상태로 확인.
푸시/PR까지만 진행, main 머지는 하지 않음.
