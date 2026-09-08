# UI 리디자인 Phase 6 마감 — 접근성·죽은 CSS·브라우저 매트릭스 (2026-09-08)

계획: `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md` §Phase 6.
선행: 디자인 일체화 1/4(#678) · 2/4(#680) · 3/4(#681), 작전실 지도 전면 스테이지(#677).

## 1. 접근성 스모크 — axe critical 0 (5화면)

`web/game/e2e/a11y-smoke.spec.ts`. 라이브 스택이 필요하고, 스택이 없으면 **조용히 넘어가지 않고 실패한다**
(0건이 「검사가 안 돌았다」를 뜻하면 안 되기 때문이다).

| 화면 | critical | serious |
|---|---|---|
| 로그인 (게이트웨이 `/login`) | 0 | 0 |
| 로비 (게이트웨이 `/`) | 0 | 0 |
| 작전실 (게임 `/game`) | 0 | 0 |
| 커뮤니티 (게임 `/game/board`) | 0 | 0 |
| 운영 콘솔 (게이트웨이 `/admin`) | 0 | 0 |

**빨간 프로브로 게이트가 실제로 도는지 확인했다.** 로그인 화면에 `alt` 없는 `<img>` 를 주입하니
`critical 1` 로 빨개지고 테스트가 실패했다(`image-alt`). 프로브 제거 후 다시 0.

실행:

```
E2E_ADMIN_USERNAME=<ADMIN 계정> E2E_ADMIN_PASSWORD=... \
  pnpm exec playwright test e2e/a11y-smoke.spec.ts
```

ADMIN 계정을 주지 않으면 콘솔 화면은 「건너뜀 — UNKNOWN, 0건 아님」으로 로그에 남는다.

## 2. 모바일 시트 포커스 트랩 · 390px · reduced-motion

`web/game/e2e/mobile-focus-trap-live.spec.ts` 3건 통과.

- 390px 부서 시트(`.dept-sheet`, `role=dialog aria-modal`): 포커스 가능한 요소 수보다 많이 Tab 을 눌러도
  포커스가 시트를 벗어나지 않고, Shift+Tab 도 마찬가지이며, Escape 는 시트를 닫고 「더보기」 트리거로 되돌린다.
- 390px 로그인 화면 가로 넘침 0px.
- `prefers-reduced-motion: reduce` 에서 `--motion-turn` 이 `0ms`.

**빨간 프로브:** `BottomNav` 의 Tab 트랩을 임시로 무력화하니 「Tab 11회 뒤 포커스가 시트 안에 있어야 한다」로
실패했다. 복원 후 다시 통과. 트랩 코드가 있다는 사실이 아니라 **키보드가 실제로 갇히는지**를 잰다.

## 3. 죽은 CSS 제거

| 앱 | 이전 | 이후 | 제거 규칙 |
|---|---|---|---|
| `web/game/app/globals.css` | 2,772줄 | 2,629줄 | 40 (3/4 PR #681) |
| `web/gateway/app/globals.css` | 1,599줄 | 1,480줄 | 22 (이 PR) |

옛 DOM 기반 지도가 캔버스로 바뀌며 남은 `.city-*`·`.map-world`/`.map-road`, 그리고 쉘 재편 뒤 쓰이지 않는
`.auth-page`/`.auth-panel`·`.board-topbar`/`.board-nav`·`.server-grid`/`.server-card`·`.admin-tabs` 등이다.

**동적으로 조립되는 클래스는 남겼다** — `status-${variant}` · `toast-${type}` · `war-card__v--${tone}` ·
`portrait-editor__preview--${key}` 는 소스에 문자열로 나타나지 않아 「미사용」으로 보이지만 살아 있다.
규칙 제거가 앞 셀렉터를 삼키는 사고(`.map-bg,` 가 다음 규칙을 먹은 자리)를 실제로 한 건 잡아 고쳤고,
이후 제거기는 앞 줄이 콤마로 끝나면 건드리지 않는다.

## 4. 브라우저 매트릭스

| 브라우저 | 결과 |
|---|---|
| macOS Chromium (Playwright) | 위 두 스펙 전부 통과 |
| macOS WebKit(Safari 엔진) | UNKNOWN — Playwright 브라우저 바이너리 설치가 이 환경에서 완료되지 않았다 |
| macOS Firefox | UNKNOWN — 같은 사유 |
| Windows Chrome | UNKNOWN — 이 환경에 Windows 호스트가 없다 |

UNKNOWN 은 「통과」가 아니다. 세 칸은 브라우저를 갖춘 환경에서 같은 두 스펙을 그대로 돌리면 채워진다.

## 5. 남은 것

- `docs/user/**` 화면 안내와 README 스크린샷 교체 — 프로덕션 배포 뒤 실화면으로 찍는 게 맞다(로컬은
  시나리오가 `che` 라 지도 타일이 없어 「지도 데이터 준비 중」이 찍힌다).
- 교차 비평(다른 에이전트) 1회.
