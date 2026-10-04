# 보안 머리글

두 프런트엔드(`web/game` · `web/gateway`)의 응답 머리글이다. 값은 각 앱 `next.config.mjs` 의 `SECURITY_HEADERS` 한 곳에 있고, 두 앱이 같은 값을 쓴다. 운영 빌드(`next start`)에서 실제로 붙는지는 `e2e/smoke/security-headers.spec.ts`(두 앱)가 본다.

## 지금 켠 것 (2026-10-04, 원장 D90)

| 머리글 | 값 | 뜻 |
| --- | --- | --- |
| `X-Frame-Options` | `DENY` | 다른 페이지가 이 화면을 틀 안에 싣지 못한다(옛 브라우저용) |
| `Content-Security-Policy` | `frame-ancestors 'none'` | 같은 뜻의 표준 머리글. **지금 CSP 에는 이 지시 하나만 있다** |
| `X-Content-Type-Options` | `nosniff` | 응답 형식을 브라우저가 짐작하지 않는다 |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | 다른 사이트로는 출처(도메인)만 보낸다 |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=(), payment=(), usb=()` | 쓰지 않는 기기 권한을 막는다 |
| (없앰) `X-Powered-By` | — | `poweredByHeader: false` |

- 두 앱 모두 틀(iframe) 안에 화면을 싣는 곳이 없다(`<iframe` 0건, 2026-10-04). 틀 안에 실어야 하는 화면이 생기면 이 표부터 고친다.
- `Strict-Transport-Security`(HSTS)는 앱이 아니라 엣지(nginx · 운영 설정) 몫이다. 운영 반영은 운영 승인 카드로 한다.
- 인증 쿠키의 Secure 기본값도 같은 묶음이다. 운영 빌드는 켜고, HTTP 로 뜨는 로컬 compose · 샌드박스 · CI 스모크는 `COOKIE_SECURE=false` 로 끈다(`web/*/lib/cookies.ts` `cookieSecureFrom`).

## 다음: 전체 CSP 는 report-only 로 먼저

스크립트 · 스타일 · 연결 대상까지 거는 전체 CSP 를 바로 켜면 화면이 깨질 위험이 크다. 인라인 스타일 · Next 스크립트 · Sentry · 이미지 CDN 이 얽혀 있어서다. 그래서 다음 순서로 간다.

1. **관측 판을 붙인다**: `Content-Security-Policy-Report-Only`. 막지는 않고 어긋난 것만 보고한다.
   - `default-src 'self'`
   - `script-src 'self' 'nonce-<요청마다>' 'strict-dynamic'` — nonce 는 미들웨어가 요청마다 만들어 Next 에 넘긴다. nonce 를 쓰는 화면은 정적 생성이 아니라 요청마다 그린다.
   - `style-src 'self' 'unsafe-inline'` — 화면이 `style={…}` 인라인 스타일을 많이 쓴다. 인라인 스타일을 줄이는 것은 따로 한다.
   - `img-src 'self' data: blob:` + 이미지 CDN(opensamguk-images export 를 받는 주소)
   - `connect-src 'self'` + Sentry 수집 주소
   - `font-src 'self'`, `frame-ancestors 'none'`, `base-uri 'self'`, `form-action 'self'`
   - 보고 주소: Sentry 의 CSP 보고 끝점(또는 `/api/csp-report` 하나)
2. **1–2 주 관측한다**: 보고를 모아 실제 위반(필요한 출처 빠짐)과 잡음(확장 프로그램 등)을 가른다. 화면별로 스모크를 돌려 보고 0 을 확인한다.
3. **강제로 바꾼다**: `Content-Security-Policy` 로 옮긴다. 지금의 `frame-ancestors 'none'` 은 그 안에 합친다.

각 단계는 운영 반영이 따로 필요하다. 관측 판도 응답 머리글을 바꾸므로 같은 승인 흐름을 탄다.
