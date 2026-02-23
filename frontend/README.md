# OpenSamguk Frontend

오픈삼국 프론트엔드 애플리케이션입니다.
Next.js(App Router) 기반으로 동작하며, API/WebSocket/CDN URL은 환경변수로 구성됩니다.

## 요구 사항

- Node.js 20+
- pnpm

## 환경변수

주요 클라이언트 환경변수는 다음과 같습니다.

- `NEXT_PUBLIC_API_URL` (기본값: `http://localhost:8080/api`)
- `NEXT_PUBLIC_WS_URL` (기본값: `http://localhost:8080`)
- `NEXT_PUBLIC_IMAGE_CDN_BASE` (기본값: `https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-image@master/`)

## 개발 서버 실행

```bash
pnpm install
pnpm dev
```

기본 접속 주소: `http://localhost:3000`

## 프로덕션 빌드

```bash
pnpm build
pnpm start
```

## Docker 빌드

Docker 빌드 시에도 CDN/API/WS 값을 build-arg로 주입할 수 있습니다.

```bash
docker build \
  --build-arg NEXT_PUBLIC_API_URL=/api \
  --build-arg NEXT_PUBLIC_WS_URL= \
  --build-arg NEXT_PUBLIC_IMAGE_CDN_BASE=https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-image@master/ \
  -t opensam/frontend:latest .
```

## 참고

- 이미지 에셋 저장소: `https://github.com/peppone-choi/opensamguk-image`
- 프론트엔드에서 CDN 경로 조립 로직: `src/lib/image.ts`
