# 오픈삼국 (OpenSamguk)

오픈삼국은 웹 기반 삼국지 전략 게임입니다. 
백엔드는 Spring Boot(Kotlin), 프론트엔드는 Next.js로 구성되어 있습니다.

## 기술 스택

| 계층 | 기술 |
| --- | --- |
| 백엔드 | Spring Boot 3, Kotlin |
| 프론트엔드 | Next.js 16, React 19 |
| 데이터베이스 | PostgreSQL 16 |
| 캐시 | Redis 7 |
| 배포 | Docker Compose, GitHub Actions, GHCR |

## 저장소 구성

```text
opensam/
├── backend/                  # gateway-app, game-app, shared
├── frontend/                 # Next.js 앱
├── nginx/                    # reverse proxy 설정
├── docs/                     # 아키텍처/패러티 문서
├── legacy/                   # 레거시 PHP 참조 코드
├── docker-compose.yml        # GHCR 이미지 기반 실행 구성
├── CLAUDE.md                 # 개발 에이전트 작업 가이드
└── README.md
```

## 관련 저장소

- 배포 전용 저장소: `https://github.com/peppone-choi/opensamguk-deploy`
- 이미지 에셋 저장소: `https://github.com/peppone-choi/opensamguk-image`

## 로컬 개발 실행

### 1) DB/Redis 실행

```bash
docker compose up -d postgres redis
```

### 2) 백엔드 실행 (각각 별도 터미널)

```bash
cd backend
./gradlew :gateway-app:bootRun
```

```bash
cd backend
./gradlew :game-app:bootRun
```

### 3) 프론트엔드 실행

```bash
cd frontend
pnpm install
pnpm dev
```

## 빌드 및 테스트

### 백엔드

```bash
cd backend
./gradlew build
./gradlew test
```

### 프론트엔드

```bash
cd frontend
pnpm build
```

## Docker 배포

실제 운영 배포는 본 저장소가 아니라 배포 전용 저장소(`opensamguk-deploy`)를 사용합니다.

```bash
git clone https://github.com/peppone-choi/opensamguk-deploy.git
cd opensamguk-deploy
cp .env.example .env
docker compose pull
docker compose up -d
```

## 이미지 CDN 설정

프론트엔드 에셋 CDN 기본값은 아래와 같습니다.

`https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-image@master/`

환경변수 `NEXT_PUBLIC_IMAGE_CDN_BASE`로 변경할 수 있습니다.

예시:

```bash
NEXT_PUBLIC_IMAGE_CDN_BASE=https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-image@master/
```
