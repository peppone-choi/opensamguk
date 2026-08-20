# web/gateway ↔ web/game UI 일원화 감사 (2026-08-20)

감사 전담, 코드 미수정. 사용자 결정: 공유 UI 패키지까지 간다. 근거는 전부 실제 파일을 열어 확인한 path:line — 확인 못 한 항목은 UNKNOWN으로 표기.

## 0. 이미 측정된 전제 (재측정 안 함)

- 둘 다 Tailwind 없음. 각자 `app/globals.css` 하나에 전부(gateway 1564줄 / game 2039줄).
- `:root` 토큰 33/37 동일. 다른 건 `--font-sans` 값 하나 + 공백 차이 3개. gateway 전용 `--text-inverse --text-3xl --radius-lg --shadow-lg`, game 전용 `--bottom-nav-height`.
- CSS 클래스 gateway 214 / game 305, 이름 겹침 39개, 그중 13개는 규칙이 갈림(`.city-base` `.spinner` `.game-table-wrap` `.center-screen` 등).
- 브랜드가 4곳에서 제각각 — 아래 1절에서 근본 원인까지 확인.

## 1. 요소별 비교

### 브랜드/헤더

| 위치 | 구현 |
|---|---|
| `web/gateway/app/login/page.tsx:99-106`, `join/page.tsx:93` | `<Image src="/logo-wordmark.png" width={86} height={32}>` — 실제 로고 이미지 |
| `web/gateway/components/board/BoardShell.tsx:8` | 텍스트 "오픈삼국" (`.board-brand` 클래스) |
| `web/game/components/Header.tsx:35` | 텍스트 "오픈삼국" (`.game-header-brand` 클래스, `Header.tsx:36`) |
| `web/game/app/page.tsx:27` | 텍스트 "오픈삼국" (인라인 `<h1>`) |

**근본 원인 확인**: `web/game/public/`에 `logo-*` 파일이 **아예 없다** (`find web/gateway/public web/game/public -iname "*logo*"` → gateway만 히트). game이 텍스트를 쓰는 건 게으름이 아니라 **자산 자체가 없어서**다. gateway 내부에서도 로그인/가입(이미지)과 게시판(텍스트)이 갈려 일관성이 없다.
**기준**: `logo-wordmark.png`를 공유 자산으로 승격해 두 앱 헤더 전부에 적용.

### 버튼

- gateway: `web/gateway/app/globals.css:117-144` — `.btn-primary`/`.btn-ghost`/`.btn-danger`/`.btn-block` 변형 시스템 + hover 규칙. 실제 재사용 컴포넌트는 없고 클래스만 씀(`ConfirmModal.tsx:66,72` 등).
- game: `web/game/app/globals.css`에 `.btn-*` 계열 클래스가 **전혀 없음**(grep 무결과). 대신 컨텍스트 전용 클래스가 난립: `.claim-btn`(`CharacterClaim.tsx:157`), `.msg-send-btn`(`MessagePanel.tsx:111`), `.control-btn`(`MainControlBar.tsx:80`), `.global-menu-btn`(`GlobalMenu.tsx:98,109,116`), `.back-bar-btn`(`BackBar.tsx:23,26`), `.rcp-edit-btn`, `.main-refresh-btn` 등.
- **기준**: gateway의 변형 시스템(디자인은 있음). **이관 우선순위 최상위** — game은 버튼 스타일이 파편화돼 새 패키지 도입 효과가 가장 크다.

### 카드

- gateway: `.server-card`(`app/globals.css:945-958`) — `padding: space-lg`, flex-column. 컴포넌트 없이 각 페이지에서 `<div className="server-card">` 직접 사용.
- game: `.game-card`(`app/globals.css:222-228`) + 실제 컴포넌트 `GameCard.tsx`. `padding: space-md`만, 나머지(bg-card/border-subtle/radius-md, hover→border-accent+shadow-gold)는 gateway와 거의 동일.
- **기준**: game의 컴포넌트화 + gateway의 padding/token을 합성. `CityBasicCard.tsx`/`NationBasicCard.tsx`(game)는 `GameCard` 위에 얹은 특화 카드라 그대로 game 전용으로 남겨도 됨.

### 표

- game: `GameTable.tsx` — headers/rows props를 받는 진짜 제네릭 컴포넌트. `.game-table-wrap`/`.game-table`(`app/globals.css:235-256`), zebra+hover, 정렬·페이지네이션은 없음.
- gateway: 제네릭 테이블 컴포넌트 없음. `BoardList.tsx`는 리스트 형태(표 아님). admin 쪽 실제 표 사용 여부는 **UNKNOWN**(범위 밖, 미확인).
- **기준**: game의 `GameTable`이 이관 기준.

### 폼 입력

- gateway: `input,select,textarea`(`app/globals.css:97-108`) — `padding: space-sm space-md` + 명시적 `font-size: text-base`.
- game: 동일 셀렉터(`app/globals.css:73-81`) — `padding: space-sm`만(비대칭 패딩 없음), font-size 미지정.
- 폼 검증: `BoardPostForm.tsx:29-38`처럼 gateway는 trim+길이체크를 페이지마다 손으로 반복. 공용 검증 헬퍼는 **양쪽 다 없음**.
- **기준**: 규칙 차이가 작아 병합 쉬움(패딩 대칭 + font-size 명시로 통일). 공용 `<TextField>` 신설 여지 있으나 검증 로직까지 묶는 건 범위 확장 — 이번 이관에서는 스타일만.

### 배지/칩

- 클래스 컨벤션은 **이미 완전 동일**: 둘 다 `.status-badge status-{gold|crimson|jade|muted}`, 같은 rgba/변수. 유일한 차이는 game만 `text-transform: uppercase` 한 줄 추가(`app/globals.css:266`).
- game만 컴포넌트화(`StatusBadge.tsx:8`). gateway는 6곳에서 `<span className="status-badge status-gold">`를 손으로 반복(`ServerBoard.tsx:62`, `lobby/page.tsx:213`, `admin/page.tsx:303,346,361,446,1157,1160,1605`).
- gateway `.board-category`/`.board-pin`(`app/globals.css:741-754`)은 게시판 전용 별도 배지, 컴포넌트 없이 `BoardList.tsx:17` 인라인.
- **기준**: game의 `StatusBadge`. **이관 난이도 가장 낮음** — CSS 이미 동일, gateway는 컴포넌트로 바꿔 끼우기만 하면 됨. `uppercase` 한 줄만 결정 필요(적용 여부 사용자 확인 권장).

### 모달/다이얼로그

- gateway: `ConfirmModal.tsx:12` — `.modal-backdrop/.modal-card/.modal-title/.modal-body/.modal-actions`(`app/globals.css:1468-1503`, z-index 1000, `rgba(0,0,0,.6)`). **접근성 완비**: `role="dialog" aria-modal aria-label`(`ConfirmModal.tsx:57-60`), 열릴 때 확인 버튼 자동 focus, Esc 키 취소, 백드롭 클릭 취소(`ConfirmModal.tsx:33-61`).
- game: `CommandModal.tsx:557` — `.modal-overlay/.modal-content/.modal-header`(`app/globals.css:300-327`, z-index 300, `rgba(0,0,0,.7)`). **접근성 속성 전무**(role/aria-modal/aria-label 없음, Esc 핸들러 없음, focus 관리 없음). 다만 콘텐츠형(카테고리 탭+그리드+폼, `.cmd-*` 전용 스타일 60여 줄)이라 목적 자체가 gateway의 단순 확인 모달과 다름.
- **기준**: 접근성은 gateway가 명백히 위. 공유 패키지는 **두 변형**으로 나눠야 함 — `ConfirmDialog`(gateway 패턴 이식, game도 이걸로 교체 가능한 확인창들이 있을 것) + `ContentModal`(game의 `CommandModal` 셸을 일반화, 접근성 속성을 gateway 수준으로 보강).

### 스피너

- 클래스명 `.spinner` 재사용됐지만 규칙 상이: gateway 24px, game 2.5rem; keyframe명도 다름(`spin` vs `auth-spin`).
- **기준**: 하나의 `<Spinner size="sm|md">` 컴포넌트로 흡수, keyframe 이름 하나로 통일.

### 토스트/에러 배너

- game: `Toast.tsx:14` — `.toast-container role="status" aria-live="polite"`, `.toast-success/.toast-error`(`app/globals.css:273-297`). 완비.
- gateway: 토스트 컴포넌트 자체가 **없음**. 로그인/가입 등에서 `<div className="auth-error">`(`login/page.tsx:83`, `app/globals.css:229`) 인라인 에러 배너만 사용.
- **기준**: game의 `Toast`. gateway의 `auth-error` 인라인 배너는 폼-필드-레벨 에러라 용도가 달라 병존 가능(모달과 마찬가지로 완전 대체 대상 아님).

### 탭

- gateway: `BoardTabs.tsx:12` — `.board-tabs role="group" aria-label`, `.board-tab`+active. 재사용 가능한 형태로 이미 컴포넌트화.
- game: tournament-admin 페이지에 인라인 탭만(`app/game/tournament-admin/page.tsx:168` 부근), 별도 컴포넌트/클래스 체계 없음.
- **기준**: gateway 패턴(접근성 속성 보유) 승격.

### 페이지네이션

- gateway: `BoardPagination.tsx` 전용 컴포넌트.
- game: 대응 기능·클래스 없음(grep 무결과).
- **기준**: gateway 전용 → 이관 시 신규 도입(game 쪽 신규 채택).

### 빈 상태

- 통일된 컴포넌트 없음. game은 `.cmd-empty`(`CommandModal.tsx:576`) 포함 각 파일에 산발적 `"xxx가 없습니다"` 텍스트, gateway도 컴포넌트화 안 됨.
- **기준**: 기존 재사용 패턴 자체가 없어 신설 여지만 있음. 이번 이관 1순위는 아님.

### 지도(HanMapCanvas 등) — 손대지 말라고 지시받은 영역

`web/game/components/game/HanMapCanvas.tsx`의 좌표·픽셀 값은 게임 정합성이라 감사 대상에서 제외했다(그대로 유지 지시 준수). 다만 복붙된 `.city-*` CSS 규칙이 이미 갈려 있다는 사실만 보고: 앞선 측정에서 `.city-base` 포함 13개 클래스가 gateway/game 간 규칙 불일치로 확인됨 — 좌표/픽셀이 아닌 **색상·보더 스타일** 차이로 추정되나 각 프로퍼티별 diff는 이번 라운드에서 재검증하지 않았다(UNKNOWN, 후속 정밀 diff 필요).

## 2. 공유 패키지 설계

### 현재 상태 (확인 완료)

- **루트에 pnpm workspace 없음.** `package.json`/`pnpm-workspace.yaml`이 리포 루트에 없다.
- `web/gateway`와 `web/game`은 **각자 별도의 `pnpm-workspace.yaml`**을 가진 완전히 독립된 패키지다. 내용은 둘 다 `onlyBuiltDependencies: [sharp, unrs-resolver]` 뿐(pnpm 10 네이티브 빌드 스크립트 허용 설정) — 크로스 앱 workspace 용도가 아니다.
- 각자 **별도의 `pnpm-lock.yaml`**(`web/gateway/pnpm-lock.yaml`, `web/game/pnpm-lock.yaml`).
- `docker/web-gateway.Dockerfile`, `docker/web-game.Dockerfile`은 `docker-compose.yml`에서 `context: .`(리포 루트)로 빌드되지만, 각 Dockerfile은 `COPY web/gateway/ .` / `COPY web/game/ .`처럼 **자기 앱 디렉터리만** 복사한다. 공유 패키지 디렉터리는 지금 이미지에 안 들어간다.

### 제안 구조

```
opensamguk/
  pnpm-workspace.yaml          # 신규 — packages: ["web/*", "packages/*"]
  packages/
    ui/
      package.json             # @opensamguk/ui
      src/
        tokens.css              # :root 공유 토큰(37개 중 겹치는 부분 + 통합된 --font-sans)
        Button.tsx  Button.module.css (or globals에 이식)
        StatusBadge.tsx
        Spinner.tsx
        Card.tsx
        ConfirmDialog.tsx
        Toast.tsx
        Tabs.tsx
      tsconfig.json
  web/gateway/
  web/game/
```

- `web/gateway`, `web/game` 둘 다 `"@opensamguk/ui": "workspace:*"`를 dependencies에 추가.
- 각 앱은 자기 `app/globals.css` 최상단에서 `@opensamguk/ui/tokens.css`를 import(또는 Next의 CSS 모듈 규칙에 맞춰 `layout.tsx`에서 import) — 나머지 앱 전용 CSS는 그대로 유지.
- `ConfirmDialog`/`Toast`/`Spinner`/`StatusBadge`/`Card`/`Tabs`는 순수 프레젠테이션(비즈니스 로직 없음)만 패키지로 뺀다. `GameTable`처럼 game 도메인에 묶인 것, `HanMapCanvas` 등은 이관 대상에서 제외.

### 빌드 파이프라인 영향 (반드시 바꿔야 함)

1. **루트 `pnpm-workspace.yaml` 신설** 필요. 지금은 앱별 workspace 파일이라 `packages/ui`를 아무도 인식 못 한다.
2. **lockfile 통합** — 지금처럼 앱마다 별도 lockfile을 유지한 채 workspace 패키지를 추가하면 `pnpm install --frozen-lockfile`이 workspace 참조를 못 풀어 CI 빌드가 깨진다. `pnpm-lock.yaml`을 **리포 루트로 단일화**하고 각 앱의 개별 lockfile은 제거해야 한다 — 이건 gateway/game 양쪽 의존성 트리를 한 번에 재설치하는 작업이라 별도 PR 하나로 분리할 가치가 있다.
3. **Dockerfile 수정** — 두 Dockerfile 모두 `COPY` 절을 확장해야 한다:
   ```
   COPY pnpm-workspace.yaml pnpm-lock.yaml package.json ./
   COPY packages/ui packages/ui
   COPY web/gateway/package.json web/gateway/
   RUN pnpm install --frozen-lockfile
   COPY web/gateway/ web/gateway/
   COPY packages/ui packages/ui   # 소스 변경도 다시 반영되도록 두 번째 COPY 필요(캐시 레이어 분리 목적이면)
   WORKDIR web/gateway
   RUN pnpm build
   ```
   현재처럼 `WORKDIR /src/web/gateway`에서 단독으로 install/build하는 구조를 리포 루트 기준으로 바꿔야 한다. 각 Dockerfile 주석에 있는 `onlyBuiltDependencies` 관련 경고(“pnpm-workspace.yaml MUST be copied with the manifest+lock”)는 루트 workspace 파일로 대체될 것.
4. `docker-compose.yml`은 `context: .`가 이미 루트라 이 부분은 수정 불필요.

### 리스크

- lockfile 통합은 되돌리기 번거로운 작업 — 별도 PR로 격리하고, 그 PR 하나는 "코드 변경 0, 빌드 파이프라인만" 이어야 리뷰가 쉽다.
- Next.js는 workspace 패키지의 CSS/컴포넌트를 그대로 import하는 데 별 문제 없지만, `transpilePackages: ['@opensamguk/ui']`를 각 `next.config`에 추가해야 할 가능성이 높다(패키지를 TS 소스 그대로 배포할 경우). **UNKNOWN** — 실제 빌드해서 확인 필요, 이번 감사에서 실행 검증은 안 함.

## 3. 이관 순서 (위험 낮은 것부터, PR 1개 크기)

1. **PR1 — 브랜드 자산 통일**(패키지 불필요). `logo-wordmark.png`를 `web/game/public/`에도 복사(또는 향후 packages/ui/assets로), `BoardShell.tsx`/`Header.tsx`/game `app/page.tsx`의 텍스트 브랜드를 이미지로 교체. 코드 변경 최소, 사용자가 직접 지적한 문제라 우선순위 1.
2. **PR2 — workspace 배관만**. 루트 `pnpm-workspace.yaml` + `packages/ui`(빈 패키지, `tokens.css`만) 신설, lockfile 통합, 두 Dockerfile 수정. 컴포넌트 이관은 0개 — 파이프라인이 도는지만 검증.
3. **PR3 — StatusBadge**. CSS가 이미 100% 동일이라 가장 안전. `packages/ui`로 이동, 양쪽에서 import 교체(gateway는 인라인 span 6곳을 컴포넌트 호출로 치환).
4. **PR4 — Spinner**. 사이즈 prop으로 통합, keyframe 이름 하나로.
5. **PR5 — Button**. gateway의 `.btn-*` 시스템을 패키지로 승격, game의 파편화된 버튼 클래스(`claim-btn`/`msg-send-btn`/`control-btn`/`global-menu-btn`/`back-bar-btn` 등)를 variant prop으로 교체 — 파일 수가 많아 이 PR이 가장 큼, 필요하면 더 쪼갤 것.
6. **PR6 — Card**. game의 `GameCard` 컴포넌트 구조 + gateway의 padding/token 병합.
7. **PR7 — ConfirmDialog**. gateway의 접근성 완비 구현을 기준으로 패키지화, game 쪽에 있을 단순 확인 용도를 찾아 교체(CommandModal은 그대로 두고 별도 Content 모달로 후속 검토).
8. **PR8 — Toast**. game 구현을 기준으로 패키지화, gateway 쪽에 신규 도입(현재 토스트가 없어 도입 자체가 새 기능이라 신중히).
9. **PR9 — Tabs / Pagination**. gateway 구현을 기준으로 패키지화, game 쪽 신규 채택은 필요해지는 시점에.
10. **후순위/보류** — GameTable(도메인 결합도 높아 범용화 가치 낮음), 빈 상태(재사용 패턴 자체가 없어 신설 논의 필요), `.city-*` CSS 정밀 diff(맵 렌더링 영역이라 사용자 별도 확인 후 진행).

## 4. 손대지 않은 것

- `HanMapCanvas.tsx` 등 맵 렌더링의 좌표·픽셀 값 — 지시대로 통일 대상에서 제외.
- 실제 pnpm workspace 설치·Next 빌드 검증은 수행하지 않음(감사·설계만, 코드 미수정 지시 준수). `transpilePackages` 필요 여부는 PR2 실행 시 검증 필요.
