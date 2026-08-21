# Coding Rules — opensamguk

세 등급을 구분한다. **Enforced를 어기면 빌드/CI가 실제로 막는다. Preferred를 Enforced처럼 말하지 말 것.**

## Enforced (빌드·테스트·CI로 실제 강제)

- **one-daemon-write rule**: 데몬 JPA write 금지 — `DaemonNoEntityManagerTest`/`InfraNoEntityManagerTest`가 fail시킴.
- **precheck 합의**: game-api precheck와 engine 평가의 Allow/Deny+reason 일치 — `PrecheckFullCrossCallSiteTest`.
- **동결 회귀 게이트**: 기존 `*GoldenTest`/`*ReplayGateTest`가 과거 draw-for-draw 기준선을 보호. 골든/테스트 약화는 리뷰·게이트에서 차단 대상이며 신규 기능에 PHP 일치를 요구하지 않는다.
- **`.editorconfig`**: UTF-8, LF, 끝 개행, `.kt`/`.kts` 4칸 · `.ts`/`.tsx`/`.json`/`.yml` 2칸.
- **Kotlin official style**: `gradle.properties`의 `kotlin.code.style=official`.
- **agent-system strict check**(CI/PR): `tools/agent-system/check.py --strict --base origin/main` — 코드 변경에 docs/evidence, 비자명 변경에 `docs/superpowers/reviews/*.md` critique 아티팩트, production compose `SCENARIO_SEED_ENABLED=false`, 게이트웨이 기본 서버 목록 empty 등을 강제.
- **web/game 테스트·typecheck**: `pnpm typecheck && pnpm test`(변경 시 CI/게이트 관행).

## Observed (코드베이스에서 반복 확인되는 패턴)

- 반올림은 항상 `PhpRound`(음수 스케일 포함), 정수화는 truncate-toward-zero, 데미지 클램프는 `ceil()`.
- 삽입 순서 보존 자료구조(`LinkedHashMap`), stable sort만.
- 한글 게임 로그는 UX 산출물이며 실행 순서를 안정적으로 보존. 기존 `Josa`·색/태그 형식은 동결 회귀가 보호하지만 신규 문구는 PHP byte 일치를 요구하지 않는다.
- 리졸버는 `ChangeRecorder` 델타만 기록 — 인라인 DB write 없음.
- 패키지 `opensamguk.<module>` 소문자, 클래스 PascalCase, 함수/변수 camelCase, 테스트명 backtick 서술형.
- 프론트: httpOnly 쿠키 인증, Next route handler 프록시(동일출처), 하드코딩 placeholder 대신 실제 API 상태 렌더.

## Preferred (문서/팀 정책 — 자동 강제 없음)

- 주석은 영어, 게임 콘텐츠 문자열은 한글 (`AGENTS.md` §코드 스타일).
- 한 작업 = 한 논리 커밋, 커밋 트레일러 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` (`CLAUDE.md`).
- 하드코딩 정책: 승인 spec/ADR의 제품 상수 / `.env.example` 문서화된 기본값 / 테스트·동결 회귀 픽스처 외의 하드코딩 데이터는 블로커 (`docs/superpowers/WORKING_SYSTEM.md`).
- 삭제 우선, 기존 유틸 재사용 우선.

## 충돌 시

최신 승인 ADR/spec이 제품 의도를 정하고 현재 구현·테스트가 실행 현실을 증명한다. 서로 어긋나 보이면 **조용히 택일하지 말고** `.ai/current-state.md`의 Open questions에 충돌을 기록하고 사람에게 올린다.
