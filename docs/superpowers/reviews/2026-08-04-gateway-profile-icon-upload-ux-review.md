# Gateway 전콘 업로드 UX Review

- Date: 2026-08-04
Scope: `web/` (gateway account page + profile-icon client normalization).
- Review focus: 계정 설정 전콘 업로드가 사용자에게 "아무 반응 없음"으로 보이던 버그의
  수정 — 피드백 위치 재배치 + 브라우저측 자동 크롭/축소.
- Independent critique: `oh-my-claudecode:code-reviewer` (Opus), read-only,
  구현 컨텍스트와 분리된 별도 실행.
- PR: 미생성 (구현 에이전트가 git 권한 없음 — 오케스트레이터가 push/PR 수행).

Verdict: cleared

## 증상 → 원인

사용자 신고: "일반 사진을 골라 업로드를 눌렀는데 아무 반응이 없었어."

원인은 두 개가 겹친 것이다.

1. **사전 거부.** `web/gateway/app/account/page.tsx`의 `prevalidateIcon`이 파일을
   선택 즉시 50KB / 64~128px 정사각형으로 검사해 실패하면 `setError(...)` 후 리턴했다.
   네트워크 요청이 아예 나가지 않았다. 폰 사진은 100% 여기서 걸린다.
2. **화면 밖 메시지.** 그 메시지를 그리는 `<p role="status">` / `<p role="alert">`가
   페이지 최상단(비밀번호 패널보다 위)에 있었고, 업로드 버튼은 한참 아래 패널에 있었다.
   스크롤·포커스 이동이 없어 사용자는 에러가 떴다는 사실 자체를 볼 수 없었다.

즉 "조용히 거부 + 보이지 않는 곳에 에러" = 무반응. 두 원인을 모두 닫았다.

## 수정 내용

- **피드백을 액션 옆으로.** 전역 배너 하나를 없애고 `{ scope, ok, text }` 단일 상태로
  바꿔 비밀번호 폼 / 전콘 업로드 폼 / 공유 전콘 폼 / 탈퇴 패널 각각에서 렌더한다.
  `role="status"` / `role="alert"`는 그대로 유지했고, 단일 상태라 두 메시지가 동시에
  뜨는 구조가 성립하지 않는다.
- **자동 리사이즈.** `web/gateway/lib/profileIcon.ts` 신규. 규격 밖 이미지는 중앙
  정사각형으로 크롭 → 128x128 축소 → 50KB 이하가 될 때까지 인코딩한다. 이미 규격에
  맞는 파일은 변환 없이 원본 File 객체 그대로 전송한다.
- **서버 검증 불변.** `ProfileIconDecoder.kt`, `profile-icon.max-bytes=51200`,
  `LocalProfileIconStorage` 모두 손대지 않았다. 클라이언트 변환은 편의일 뿐이고
  최종 경계는 서버다. 202/비-2xx를 성공으로 위장하지 않는 기존 계약도 그대로다.

이 규격(64~128px·50KB)은 PHP 패러티 제약이 아니다. `legacy/devsam-core`에는 아이콘
업로드 핸들러가 없고(`d_pic`은 `.gitignore`/`getIconPath.ts`/`AppConf.php`에만 등장),
오픈삼국 자체 설계값이다. 따라서 클라이언트 처리 방식 변경은 골든/패러티에 영향이 없다.

## 독립 critique 결과와 대응

CRITICAL 0 / HIGH 0 / MEDIUM 4 / LOW 12. MEDIUM 4건 전부 대응했다.

1. **format-blind passthrough (MEDIUM).** `isCompliant`가 크기·치수만 봐서, 50KB 이하
   64~128px 정사각형인 **애니메이션 GIF**가 변환 없이 통과한 뒤 서버
   `ProfileIconDecoder.kt:50` `getNumImages(true) != 1`에서 거부됐다. BMP/ICO/SVG처럼
   `createImageBitmap`은 디코딩하지만 서버가 안 받는 형식도 같은 경로였다.
   → `PASSTHROUGH_TYPES = {png, jpeg, webp, avif}` 가드를 추가해 gif와 미지의 타입은
   항상 재인코딩한다. 테스트로 고정(`isCompliant` 표 + gif 재인코딩 케이스).
2. **크롭 좌표 미검증 (MEDIUM).** `drawImage` 인자를 아무도 검증하지 않아 sx/sy를
   바꾸거나 크롭 사각형을 빠뜨려도 전 테스트가 녹색이었다.
   → 4000x3000 케이스에서 `drawImage(bitmap, 500, 0, 3000, 3000, 0, 0, 128, 128)`와
   흰 배경 `fillRect(0, 0, 128, 128)`를 명시적으로 검증한다.
3. **canvas WebP가 서버 디코더에서 검증된 적 없음 (MEDIUM).** 최초 구현은 webp를 1순위
   인코딩으로 썼다. 서버에 `libs.imageio.webp` 의존성이 있는 것은 확인했으나
   `canvas.toBlob('image/webp')`가 실제로 만드는 바이트가 `hasExactWebpBounds` +
   해당 리더를 통과하는지는 브라우저 없이 확인할 수 없다. 틀리면 변환 업로드 100%가 400이다.
   → **jpeg를 1순위로, webp를 fallback으로 뒤집었다.** 128x128에서는 50KB 상한이 전혀
   빡빡하지 않아(q0.9 jpeg가 수 KB) webp의 압축 이점이 얻는 게 없는 반면, jpeg는 JDK
   내장 리더 경로라 디코딩 확실성이 가장 높다. 리스크 비대칭(전량 실패 vs 이득 없음)이
   명백해 안전한 쪽을 골랐다. 알파 손실은 아래 4번 흰 배경으로 상쇄했다.
4. **과장된 안내 문구 + `accept="image/*"` (MEDIUM).** "아무 이미지나"라고 적었지만
   iPhone HEIC는 Chrome/Firefox `createImageBitmap`이 못 읽는다. 신고자 그 자체 케이스다.
   → `accept`를 서버가 받는 타입만 나열하도록 좁혔다(iOS 사진 선택기가 HEIC를 jpeg로
   변환해 넘겨준다). 문구도 지원 형식을 명시하도록 고쳤다.

반영한 LOW: jpeg 알파 손실 방지용 흰 배경 `fillRect`, `imageSmoothingQuality = 'high'`,
`ICON_DIMENSION` dead export 제거, interaction 테스트 `vi.unstubAllGlobals()` 누락,
401 테스트에 `queryByRole('status')` null 단언 추가, bitmap `close()` 호출 검증.

반영하지 않은 LOW(모두 이번 변경 이전부터 존재하던 것 — 범위 밖으로 남긴다):
`logout()`의 hard redirect 때문에 `router.replace('/')`가 죽은 코드인 점,
삭제 버튼이 저장 전 `imgsvr` 셀렉트 값에 묶이는 점, `uploadProfileIcon` 응답 shape 미검증,
`toBlob` 콜백 무한 대기(이론상 — 실제 브라우저는 항상 콜백한다).

## 리사이즈 정책과 근거

- **크롭:** 중앙 정사각형. `min(w,h)` 변을 취하고 남는 여백은 양쪽에서 균등하게 버린다
  (홀수는 내림 → 왼쪽/위를 한 픽셀 덜 버린다). 얼굴 아이콘에서 중앙이 가장 안전하다.
- **축소:** 서버 허용 상한인 128x128 고정. 하한(64)에 맞추면 화질을 버리게 되고,
  상한을 쓰면 서버 규격 안에서 최대 해상도를 얻는다. 64px 미만 원본은 128로 확대되는데,
  거부하는 것보다 낫다.
- **인코딩 재시도:** `[jpeg, webp] x [0.9, 0.75, 0.6, 0.45, 0.3]`, 50KB 이하 첫 결과 채택.
  실제로는 128x128 jpeg q0.9가 수 KB라 첫 시도에서 끝난다. 사다리는 병리적 입력용 보험이다.
  요청한 타입과 다른 타입이 돌아오면(브라우저가 미지원 타입을 조용히 png로 떨구는 동작)
  품질 재시도를 접고 다음 타입으로 넘어간다. png도 서버 허용 형식이라 50KB 이하면 그대로 쓴다.
- **실패:** 어떤 조합도 50KB 이하가 안 되면 한글 에러로 throw 한다. 조용한 실패 없음.

## 검증 증거

- `pnpm test` (web/gateway): **Test Files 9 passed (9) / Tests 111 passed (111)**.
  변경 전 8 files / 78 tests → `profileIcon.test.ts` 30건 신규 + interaction 15→18건.
- `pnpm lint`: 에러 0. 경고 2건은 모두 기존 경고
  (`app/account/page.tsx` `<img>` LCP 권고, `app/admin/page.tsx` exhaustive-deps).
- `npx tsc --noEmit`: 출력 없음(클린).
- `python3 tools/agent-system/check.py --strict --base origin/main`: 이 문서 추가 전
  errors 3 → `docs-drift`와 `cross-agent-critique`는 이 문서로 해소.
  남는 `codex-surface`("Project Codex config must not pin a personal model",
  `.codex/config.toml:1` `model = "gpt-5.6-sol"`)는 **이번 변경과 무관한 기존 main 상태**다.
  변경 파일 4개에 `.codex/`는 포함되지 않는다.

## 검증하지 못한 것 (jsdom 한계 — 지어내지 않는다)

- jsdom에는 canvas 인코더가 없다. `getContext('2d')`와 `toBlob`을 스텁했으므로
  **실제 픽셀 크롭 결과·실제 인코딩 바이트·실제 파일 크기는 검증되지 않았다.**
  검증된 것은 크롭 좌표 계산, 재시도/폴백 분기, 통과/변환 판정, 에러 경로뿐이다.
- 따라서 **브라우저가 만든 jpeg/webp 바이트가 서버 `ProfileIconDecoder`를 통과하는지는
  end-to-end로 확인되지 않았다.** jpeg 1순위 선택으로 리스크를 낮췄을 뿐 증명은 아니다.
  실브라우저 확인(webapp-testing/Playwright + gateway-api 왕복)은 후속 과제로 남긴다.
- `imageSmoothingQuality`, 흰 배경이 실제 화질/투명도에 미치는 효과도 육안 확인 없음.
