# OAuth Gate E2E + Ops Verification Checklist

목표: OAuth 경로 기준으로 **로그인 → 로비 → 월드 입장**이 정상 동작하는지, 배포/운영 환경에서 빠르게 점검한다.

## 1) 필수 환경변수

프론트엔드 기준:

- `NEXT_PUBLIC_API_URL` (예: `http://localhost:8080/api`)
- `NEXT_PUBLIC_KAKAO_CLIENT_ID` (카카오 OAuth 버튼/리다이렉트 시작용)

권장:

- `NEXT_PUBLIC_WS_URL` (게임 진입 후 실시간 구독)
- `NEXT_PUBLIC_SERVER_MAP_URL` (로그인 화면 지도 iframe)

빠른 점검:

```bash
cd frontend
cp .env.example .env.local
pnpm verify:oauth-gate
# API 라우트까지 연결 확인하려면
pnpm verify:oauth-gate --probe
```

## 2) E2E 테스트 범위

파일: `frontend/e2e/oauth-gate.spec.ts`

포함 시나리오:

1. **성공 경로**
   - `/auth/kakao/callback?code=...` 진입
   - `/auth/oauth/login` 성공 응답 수신
   - 로비 이동 확인
   - 월드 선택 + `입장` 버튼 확인
   - 게임 메인(`/`) 진입
2. **실패 경로**
   - `/auth/oauth/login` 실패 응답
   - `/login` 리다이렉트 확인

> 주의: 해당 스펙은 Playwright `route.fulfill` 기반의 프론트엔드 게이트 검증(스텁)이다.
> 백엔드/카카오 실연동 E2E는 별도 스테이징 환경에서 실행해야 한다.

## 3) 실행 방법

### A. 이미 프론트 서버가 떠 있는 경우

```bash
cd frontend
pnpm e2e:oauth
```

Linux/Docker에서 브라우저 시스템 라이브러리가 없으면 먼저:

```bash
cd frontend
pnpm e2e:setup
```

### B. Playwright가 dev 서버를 띄우게 하는 경우

```bash
cd frontend
PLAYWRIGHT_USE_WEBSERVER=1 pnpm e2e:oauth
```

## 4) 운영 장애 체크 포인트

- 로그인 버튼 클릭 시 카카오 authorize URL로 이동하는가?
- 카카오 callback 후 `/api/auth/oauth/login` 호출이 404/405가 아닌가?
- 성공 시 로비로 이동하고 토큰(`localStorage.token`)이 저장되는가?
- 로비에서 월드 목록 조회가 정상인가?
- 월드 선택 후 `입장` 버튼이 노출되는가?
- 게임 화면(`/`) 진입 후 인증/월드 누락으로 로비로 튕기지 않는가?

## 5) 실패 시 분류 가이드

- **즉시 환경설정 이슈**: 필수 env 누락 (`verify:oauth-gate` 실패)
- **API 라우팅 이슈**: oauth login endpoint 404/405
- **인증 이슈**: callback 성공 후도 `/login` 리다이렉트
- **게임 컨텍스트 이슈**: 로비는 진입되나 월드 입장에서 실패

## 6) CI 권장(선택)

- PR 단계: `pnpm verify:oauth-gate` + `pnpm e2e:oauth` (stub)
- 배포 전 스테이징: 실카카오 연동 수동 체크(체크리스트 4번)
