# Coding Rules — opensamguk

세 등급을 구분한다. **Enforced를 어기면 빌드/CI가 실제로 막는다. Preferred를 Enforced처럼 말하지 말 것.**

## Enforced (빌드·테스트·CI로 실제 강제)

- **one-daemon-write rule**: 데몬 JPA write 금지 — `DaemonNoEntityManagerTest`/`InfraNoEntityManagerTest`가 fail시킴.
- **precheck 합의**: game-api precheck와 engine 평가의 Allow/Deny+reason 일치 — `PrecheckFullCrossCallSiteTest`.
- **골든 게이트**: `*GoldenTest`/`*ReplayGateTest`가 draw-for-draw 검증. 골든/테스트 약화는 리뷰·게이트에서 차단 대상.
- **`.editorconfig`**: UTF-8, LF, 끝 개행, `.kt`/`.kts` 4칸 · `.ts`/`.tsx`/`.json`/`.yml` 2칸.
- **Kotlin official style**: `gradle.properties`의 `kotlin.code.style=official`.
- **agent-system strict check**(CI/PR): `tools/agent-system/check.py --strict --base origin/main` — 코드 변경에 docs/evidence, 비자명 변경에 `docs/superpowers/reviews/*.md` critique 아티팩트, production compose `SCENARIO_SEED_ENABLED=false`, 게이트웨이 기본 서버 목록 empty 등을 강제.
- **web/game 테스트·typecheck**: `pnpm typecheck && pnpm test`(변경 시 CI/게이트 관행).

## Observed (코드베이스에서 반복 확인되는 패턴)

- 반올림은 항상 `PhpRound`(음수 스케일 포함), 정수화는 truncate-toward-zero, 데미지 클램프는 `ceil()`.
- 삽입 순서 보존 자료구조(`LinkedHashMap`), stable sort만.
- 한글 게임 로그 문자열은 PHP byte-일치 형식(`Josa`, 색/태그 마크업), 로그 순서 = 실행 순서.
- 리졸버는 `ChangeRecorder` 델타만 기록 — 인라인 DB write 없음.
- 패키지 `opensamguk.<module>` 소문자, 클래스 PascalCase, 함수/변수 camelCase, 테스트명 backtick 서술형.
- 프론트: httpOnly 쿠키 인증, Next route handler 프록시(동일출처), 하드코딩 placeholder 대신 실제 API 상태 렌더.

## Preferred (문서/팀 정책 — 자동 강제 없음)

- 주석은 영어, 게임 콘텐츠 문자열은 한글 (`AGENTS.md` §코드 스타일).
- 한 작업 = 한 논리 커밋, 커밋 트레일러 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` (`CLAUDE.md`).
- 하드코딩 정책: PHP 패러티 상수 / `.env.example` 문서화된 기본값 / 테스트 픽스처 외의 하드코딩 데이터는 블로커 (`docs/superpowers/WORKING_SYSTEM.md`).
- 삭제 우선, 기존 유틸 재사용 우선.

## 충돌 시

코드(테스트로 강제되는 것)가 문서보다 이긴다. 단, 코드가 `CLAUDE.md` 패러티 규율과 어긋나 보이면 **문서를 조용히 무시하지 말고** `.ai/current-state.md`의 Open questions에 충돌을 기록하고 사람에게 올린다.
