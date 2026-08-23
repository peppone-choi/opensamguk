# board-admin-proxy-fix — docs-drift 확인 노트

## 이 문서의 목적

`tools/agent-system/check.py`의 `docs-drift` 게이트가 요구하는 evidence 아티팩트다.
이 파일은 리뷰(cross-agent critique)가 아니다 — `Scope:`/`Verdict:` 형식을 쓰지 않는다.
`docs/superpowers/reviews/*.md` 형태의 별도 크리틱은 다른 에이전트(critic-504)가
맡는다.

## 변경

`web/gateway/components/admin/BoardControl.tsx`의 세 fetch 호출(load, togglePin,
deletePost)을 `/api/proxy/board/posts...` → `/api/board/posts...`로 교체.
`web/gateway/__tests__/admin-board-control.test.tsx`의 기대 URL을 동일하게 갱신.

## docs가 이미 갱신돼 있는지 확인 (c8507bfa, OPENSAM-208)

`git show c8507bfa -- AGENTS.md CLAUDE.md README.md`로 직접 확인했다. 세 문서 모두
board-api(:8083) 분리와 `/api/board/` → web-gateway 프록시 → board-api 라우팅을
이미 정확히 기술한다:

- `AGENTS.md`: `app:board-api` 행 추가, nginx 라우팅에
  `/api/board/`→web-gateway Next 프록시→board-api` 명시.
- `CLAUDE.md`: 모듈 목록에 `app/board-api` (:8083) 추가.
- `README.md`: 서비스 표에 `board-api` 행 추가, 동일한 nginx 라우팅 문구.

즉 **인프라/라우팅 수준 문서는 c8507bfa 당시 정확히 갱신됐고 지금도 정확하다** —
`/api/board`가 board-api로 가는 정본 경로라는 서술은 참이다. 이번 회귀는 문서가
틀려서가 아니라, `web/gateway/components/admin/BoardControl.tsx`라는 개별 호출부
파일이 c8507bfa의 변경 범위(`web/gateway/app/api/board/[...path]/route.ts`,
`web/gateway/lib/server-api.ts`, 관련 테스트)에서 빠졌기 때문에 생긴 코드 누락이다
(`git show c8507bfa --stat`으로 web/gateway 변경 파일 3개만 확인, BoardControl.tsx
미포함).

## 이번 PR이 문서 갱신을 요구하는지

`AGENTS.md`/`CLAUDE.md`/`README.md`/`docs/agent/architecture.md`/
`docs/agent/project-overview.md` 어디에도 "관리자 컴포넌트별로 어느 게이트웨이
프록시 라우트를 호출하는가"라는 개별 프론트엔드 호출부 수준의 서술은 없다(다른
페이지/컴포넌트에 대해서도 이 수준까지는 문서화하지 않는다 — `grep -rn
"api/proxy\|BoardControl" docs/` 결과 없음, UNKNOWN이 아니라 직접 확인). 이번
수정은 이미 문서화된 라우팅(`/api/board` → board-api)에 코드를 다시 맞춘 것뿐이며,
새로운 서비스·엔드포인트·아키텍처를 도입하지 않는다.

**결론: 인프라 문서(AGENTS.md/CLAUDE.md/README.md/docs/agent/architecture.md 등)는
갱신 불필요 — 이미 정확함.** 이 파일 자체가 그 판단의 근거(evidence)이자
`docs-drift` 게이트가 요구하는 `docs/superpowers/` 갱신이다.
