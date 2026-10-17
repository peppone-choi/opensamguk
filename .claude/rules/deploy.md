---
paths:
  - "infra/**"
  - "docker-compose*.yml"
  - ".github/workflows/**"
  - "scripts/deploy.sh"
  - "**/Dockerfile*"
  - "docs/admin/**"
---

# 배포·운영 도달성

[작업 참고](../../docs/development/agent-reference.md)에서 옮긴 경로별 규칙이다. 경로와 명령은 저장소 루트 기준이다. Codex는 자동으로 읽지 않으므로 작업 참고 표를 보고 직접 읽는다. 이 규칙은 명령 실행 승인을 대신하지 않는다.

## 배포

> **nginx 도달성 규칙:** 라이브 정본은 sibling `opensamguk-docker` 저장소의
> `infra/nginx/nginx.conf`이다. 이 저장소의 `infra/nginx/default.conf`는 배포·마운트·로드되지
> 않는 과거 토폴로지 참고용 파일이므로 프로덕션 라우팅 근거로 인용하지 않는다. 런타임
> 도달성이 중요도·심각도 판정에 영향을 주면 compose 마운트, 배포 동기화 경로, 라이브
> `nginx -T` 중 해당하는 증거를 확인한다. 확인하지 못한 도달성은 `UNKNOWN`으로 보고한다.

```bash
# 로컬 전체 스택 (9서비스: postgres·redis·gateway-api·board-api·game-api·game-engine·web-gateway·web-game·nginx)
docker compose up -d --build

# 프로덕션 호환 표면 (GCP Compute Engine e2-standard-2, GHCR 이미지 풀, POSTGRES_PASSWORD 필수)
docker compose -f docker-compose.production.yml up -d
```

- 백엔드 이미지 멀티스테이지: `gradle:8.12-jdk21` 빌드 → `eclipse-temurin:21-jre` 런타임. 프론트: `node:22-alpine` 빌드(`next build`) → `node:22-alpine` standalone 런타임.
- nginx(`infra/nginx/nginx.conf`) 라우팅: `/api/gateway/`→gateway-api · `/api/board/`→web-gateway Next 프록시→board-api · `/api/game/`→web-gateway Next 프록시(httpOnly 쿠키→Bearer, 서버 선택) · `/api/game/realtime/`→game-api(SSE, 버퍼링 off) · `/game/`→web-game · `/`→web-gateway · `/health`.
- CI/CD: `.github/workflows/deploy.yml`(빌드 → GHCR push → GCP VM의 `gcp-prod` self-hosted runner에서 공유 스택 동기화), 호환용 수동 `scripts/deploy.sh`(헬스 체크 루프). 런타임 외부 API 의존 0, LLM-free.
