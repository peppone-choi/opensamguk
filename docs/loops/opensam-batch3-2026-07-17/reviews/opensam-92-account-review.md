# OPENSAM-92 독립 적대적 리뷰 — account 프로필 아이콘 multipart UI + Next proxy

- **reviewer:** `reviewer-92-account` (구현 미참여, 적대적 검토)
- **date:** 2026-07-17
- **basis:** 실행계약 §4 (8개 acceptance criteria) + root CLAUDE.md F0 보안 자세(httpOnly `sam_access`/`sam_refresh`, same-origin proxy, 브라우저 JS 토큰 미노출)
- **판정:** `cleared`
- **fix-required:** 0 (note 3건)
- **범위:** code + tests only. docker stack/live browser QA는 후속 verifier 단계(본 리뷰에서 deferred로 표기).

## 검토 대상 (lane-92 scope)

git working-tree diff로 확인한 lane-92 변경 파일:

- `web/gateway/app/api/account/profile-icon/route.ts` (M, +69/-…) — multipart POST + DELETE + JSON selectShared 유지, ApiError{message}→{error} 변환
- `web/gateway/app/account/page.tsx` (M, +82/-…) — file picker + 사전검증 + upload/delete 배선, 공유코드 chooser 유지
- `web/gateway/lib/client.ts` (M, +19) — `uploadProfileIcon`/`deleteProfileIcon`
- `web/gateway/__tests__/account-settings.interaction.test.tsx` (M) — 14 tests
- `web/gateway/__tests__/profile-icon-route.test.ts` (NEW, node env) — 8 tests

**스코프 감사 결과 (check #6): 위반 없음.** `web/gateway/app/lobby/page.tsx`(M)와 `web/gateway/lib/portrait.ts`(NEW), `__tests__/portrait.test.tsx`, `__tests__/lobby-portrait.test.tsx`는 lane-93 소유다. lobby diff는 로컬 `getIconPath` 제거 → lane-93 공유 helper `portraitUrl`/`onPortraitError` 소비로의 마이그레이션뿐이며 account 로직이 아니다. lane-92는 portrait.ts를 만들지도 수정하지도 않았다. 오귀속 없음.

## Mandatory check 1 — 검증 명령 재실행 (reviewer가 직접)

`corepack`이 이 호스트에 없어 계약 명령의 `corepack pnpm`은 실행 불가. `/usr/local/bin/pnpm@10.33.0`로 직접 재실행(package.json `packageManager: pnpm@10.33.0` 일치).

```
$ pnpm test           # web/gateway
 ✓ __tests__/profile-icon-route.test.ts (8 tests) 50ms
 ✓ __tests__/portrait.test.tsx (24 tests) 148ms
 ✓ __tests__/lobby-portrait.test.tsx (7 tests) 309ms
 ✓ __tests__/account-settings.interaction.test.tsx (14 tests) 552ms
 Test Files  4 passed (4)
      Tests  53 passed (53)

$ pnpm typecheck      # tsc --noEmit
TYPECHECK_EXIT=0   (무출력, 에러 없음)

$ pnpm build          # next build
 ✓ Compiled successfully in 9.5s
 ○ /account                             4.32 kB   191 kB
 ƒ /api/account/profile-icon              345 B   185 kB
```

- lane-92 소유 2파일(`account-settings.interaction.test.tsx` 14 + `profile-icon-route.test.ts` 8 = 22)을 포함해 **53/53 PASS**. 구현자 주장(53 passed)과 일치.
- typecheck exit 0, build 성공, `/account`(4.32 kB)·`/api/account/profile-icon` route emit — 구현자 주장과 일치.
- skip/only/stub 스윕(check #4): `NONE FOUND` — `.skip`/`.only`/`.todo`/`xit`/`xdescribe` 0건.
- 비차단 lint 경고 1건: `app/account/page.tsx:140` `<img>` 사용(`@next/next/no-img-element`). lobby/admin 페이지와 동일 패턴이며 guarded `onError` fallback을 위해 raw `<img>`가 필요(note-1 참조). 빌드 차단 아님.

## Mandatory check 2 — route.ts 전수 검토

**(a) Bearer는 서버측 httpOnly 쿠키에서만, 클라이언트 미노출 — PASS.**
`accessToken()`이 `cookies().get(ACCESS_COOKIE='sam_access')?.value`만 읽는다(route.ts:21-23). `lib/cookies.ts:26 httpOnly:true`로 세팅되는 쿠키라 브라우저 JS 접근 불가. 응답 본문은 upstream UserResponse 텍스트(토큰 없음)만 전달. 테스트가 `bodyText).not.toContain(TOKEN)` 단언.

**(b) multipart 브랜치는 `file` part만 전달 — PASS (프록시 + upstream 이중 방어).**
route.ts:37-38이 `new FormData()`를 만들어 `forward.append('file', file, file.name)`로 file part만 재구성. userId/path/imgsvr/url 등 임의 필드는 재구성 과정에서 원천 소실. 테스트 `forwards only the file part`가 `userId/imgsvr/picture=../evil.png/url` 주입 시 `[...forwarded.keys()]).toEqual(['file'])` 단언 → 통과.
- 심층 방어: 원본 client filename(`file.name`)은 upstream에 전달되지만 저장 경로에 미사용. `LocalProfileIconStorage`의 저장명은 `SecureRandomProfileIconNameGenerator`(8-hex 랜덤) + `decoder`가 이미지 내용에서 판정한 확장자로만 구성(`prepareUpload:157-161`), 원본 filename은 무시. filename-trick으로 path 도달 불가.
- 신원은 upstream도 `@AuthenticationPrincipal`(Bearer)에서만 파생(`ProfileIconController.upload:30`), body에서 userId를 읽지 않음.

**(c) 에러 변환 status 보존 + 내부 미노출 — PASS.**
`surface(status, text, fallback)`(route.ts:10-19)이 upstream ApiError의 `message`(사용자용 한글)만 추출하거나 fallback 사용, `status`는 그대로 재전송. upstream 상태별 매핑 확인(check #5):
- 409 `ProfileIconChangedTodayException` → 하루1회 메시지 (테스트 단언)
- 413 `ProfileIconPayloadTooLargeException`/`MaxUploadSizeExceededException`
- 400 `InvalidProfileIconException`(→ `IllegalArgumentException` → `illegalArg` 핸들러) shape/decode 거부
- 401 필터 미인증
- 500 `ProfileIconStorage/PersistenceException` → "프로필 아이콘 변경을 완료할 수 없습니다."(stack/path 없음)
비-JSON upstream이면 fallback 메시지 유지. token/username/path/stack trace 유출 경로 없음.

**(d) DELETE 핸들러 — PASS.** 쿠키 없으면 upstream 미접촉 401(route.ts:73-74). `upstream.ok`(204 포함)면 `{deleted:true}`, 아니면 `surface(...)`. upstream DELETE는 관리 업로드 없을 때 `InvalidProfileIconException`→400. 계정 페이지는 `imgsvr!==1`일 때 삭제 버튼 disable로 그 400을 선제 회피.

## Mandatory check 3 — page.tsx 검토

- **사전검증 = 네트워크 이전 — PASS.** `prevalidateIcon`(page.tsx:16-30): `size > 51200` → 거부, `createImageBitmap` 실패 → 거부, `width!==height || width<64 || width>128` → 거부. `submitUpload`가 upload 전에 호출, 실패 시 error 세팅 후 return(네트워크 없음). 경계값 51200은 upstream `profile-icon.max-bytes:51200` = Spring `max-file-size:50KB`(=51200)와 정확히 일치.
- **실패는 success 아님 — PASS.** `run()`(page.tsx:45-57)이 성공시에만 `setNotice(success)`, throw 시 `setError`. 사전검증 통과 후 서버 거부도 client가 throw → alert만. 테스트 `never reports a server reject as success even when prevalidation passed` 단언.
- **preview는 서버 canonical — PASS.** upload 성공시 `setPicture(updated.picture)`/`setImgsvr(updated.imageServer)`(client filename 아님), `await refresh()`로 auth-context 동기화. delete 성공시 `picture=''/imgsvr=0` → DEFAULT_PORTRAIT 수렴. preview URL은 lane-93 `portraitUrl(picture.trim()||null, imgsvr)` 사용(ground truth `lib/portrait.ts`와 일관) — imgsvr=1 & 8-hex managed명이면 `/d_pic/<name>`, 아니면 default.
- **신원을 client state에서 취하지 않음 — PASS.** upload/delete 어디서도 userId/token을 요청에 싣지 않음. `uploadProfileIcon`은 `FormData{file}`만, 헤더 미지정(테스트 `init.headers).toBeUndefined()`).

## Mandatory check 4 — 테스트 파일 적정성

security-critical 속성이 실제로 단언됨:
- forwarded keys === `['file']` (임의 필드 차단) — 단언됨
- 응답 본문 토큰 부재 (`not.toContain(TOKEN)`, `not.toMatch(/Bearer|eyJ/)`) — 단언됨
- 409/400/401 → error(alert)이며 status(성공표시) 아님 (`queryByRole('status')).toBeNull()`) — 단언됨
- 쿠키 없을 때 upstream 미호출 (`fetch).not.toHaveBeenCalled()`) — 단언됨
- 사전검증 4종(오버사이즈/64미만/128초과/비정사각형) 네트워크 이전 차단 — 단언됨

**node-env 선택 정당(profile-icon-route.test.ts:1-3):** jsdom의 FormData는 undici Request가 multipart로 직렬화하지 못해(→text/plain) 라운드트립이 깨진다. route handler는 서버(Node/undici)에서 도는 코드이므로 일관된 node 환경 선택이 옳다. 위장/우회 아님. stub/skip/tautological 단언 없음.

## Mandatory check 5 — upstream 계약 대조

`ProfileIconController.kt` + `GlobalExceptionHandler.kt` + `ProfileIconService.kt` 실체 확인:
- POST multipart field=`file` → `ResponseEntity.ok(UserResponse)` (200) ✓
- DELETE → `noContent()` (204), 관리 업로드 없으면 `InvalidProfileIconException`→400 ✓
- `selectShared`는 `imgsvr!=0`이면 400, picture는 catalog 검증 → **JSON 경로로 imgsvr=1(관리) 승격 불가.** imgsvr=1은 실제 업로드로만 획득 → 권한 상승 없음 ✓
- 401(필터)/409/413/400 상태코드 프록시 가정과 일치 ✓

프록시가 가정한 계약과 실제 upstream 불일치 없음.

## Contract §4 acceptance criteria 판정

| AC | 내용 | 판정 | 근거 |
|---|---|---|---|
| 1 | multipart 보존 + 서버측 Bearer + canonical preview | **met** | route.ts:37-47, test `forwards multipart upload…`, `uploads a valid icon…` |
| 2 | 토큰 미노출 + 임의 userId 불가 | **met** | 쿠키 파생 Bearer, forward keys=['file'], upstream principal 기반 |
| 3 | 초과/비정사각형/판독불가 사전 차단, 우회해도 위장 없음 | **met** | prevalidateIcon + `never reports a server reject as success` |
| 4 | client filename 아닌 서버 canonical이 preview/account/lobby 동일 | **met (code+mock)** / live reload persistence는 deferred | setPicture(updated.picture)+refresh(); lobby도 portraitUrl 소비. 페이지 reload 지속은 브라우저 verifier |
| 5 | 같은 날 2번째 → 409 명시 + 기존 preview 유지 | **met** | `shows the 하루 1회 message on a 409 and keeps the existing preview` |
| 6 | delete 성공 → default 수렴, stale URL 미유지 | **met** | removeUpload → picture=''/imgsvr=0; `deletes… converges to the default` |
| 7 | 미인증/만료 → 안전한 401, token/stack/path 미노출 | **met** | route 401(무upstream) + surface 안전메시지; `surfaces the 401 boundary…` |
| 8 | image 404 → default 1회 수렴, 무한 onError 없음 | **met (lane-93 helper)** | onPortraitError 재귀가드; `falls back once…` (srcSetter 1회) |

**Live-QA deferred (후속 verifier 몫, 실패 아님):** 실제 upstream 상대 valid upload/delete 네트워크, DevTools/page-state Bearer 부재, 실제 `/d_pic/` 404, 페이지 reload 후 canonical 지속, 만료 쿠키 실제 경로. 계약 §10·§4 검증절 "브라우저 verifier … mocked/live 분리" 지침대로 본 code+test 리뷰 범위 밖.

## Notes (non-blocking)

- **note-1:** `page.tsx:140` `<img>` lint 경고. next/image는 same-origin 동적 `/d_pic/` managed 파일 + guarded `onError` fallback 패턴과 상충하고 lobby/admin와 동일 패턴이므로 raw `<img>`가 의도적 선택. 조치 불필요.
- **note-2:** 공유코드 form의 "이미지 서버" select가 `업로드(1)` 옵션을 노출하나, 그 값으로 JSON selectShared를 보내면 upstream이 400(`imgsvr!=0`)으로 거부. 서버가 정확히 강제하므로 보안 문제 아님. 기존 유지 경로의 경미한 UX 잔가지 — 향후 select에서 1 옵션 제거 고려.
- **note-3:** AC7의 "refresh/login 경계"에서 프록시는 안전한 401 메시지만 반환하고 자동 토큰 refresh를 시도하지 않음. AC가 "안전한 401"을 허용하고 refresh는 middleware 별도 처리이므로 met. 자동 refresh는 선택적 개선.

## 판정

**verdict: `cleared` · fix-required=0 · notes=3.**
lane-92는 계약 §4 스코프(account multipart upload/delete UI + httpOnly cookie→Bearer same-origin proxy) 안에 정확히 머물렀고, 보안 핵심(토큰 서버측 전용, file-only 전달, status 보존 에러 변환, 서버 canonical preview)이 코드와 테스트로 실증됐다. 53/53 test·typecheck·build green. commit/release는 계약 A4/A5 별도 승인 대상이며 본 리뷰는 그 승인을 대신하지 않는다.

---
sha256(본 문서 이 라인 위 전체 내용): `24752e2319c9636a97cb985353aaac1d8337c193159b2a428a20fce295283f9c`
