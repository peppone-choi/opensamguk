# OPENSAM-93 독립 적대 리뷰 — same-origin `/d_pic/` serving

- **reviewer:** `reviewer-93-dpic` (독립 · 구현 미참여)
- **일자:** 2026-07-17
- **리뷰 기준:** `docs/superpowers/plans/2026-07-17-opensam-92-93-94-97-103-execution-contract.md` §5(9개 acceptance criteria) + §3 D2
- **ground truth:** `app/gateway-api/.../profile/LocalProfileIconStorage.kt` `MANAGED_FILE = [0-9a-f]{8}\.(avif|webp|jpg|png|gif)` (line 650), `.ops` 디렉터리 + marker/stage/tmp 아티팩트, storage root `/var/lib/opensamguk/profile-icons`
- **최종 판정: `cleared`** (라이브 익스플로잇 가능한 취약점 0. `disable_symlinks` 하드닝 1건은 강력 권고 note — 병합 차단 아님)

---

## 1. 리뷰 범위와 아티팩트 SHA-256

```
707f314a2af23612852c779d97bb5977cbf1e9440fdae23ca318f553b48c884d  infra/nginx/nginx.conf
3efa5847b0584632a6d362908950b84764824219c93ec93d5fd2e596fa0a8411  infra/nginx/default.conf
1108525cbe6d0f2e057c51f8c500990c9a74389f61318d0bf222691ec14f40d4  docker-compose.yml
2aa9809599af10ef698ecc5d5998f45f3fb52daeb24b9aa3cfc804f303d128c1  docker-compose.production.yml
8c329e52a7434fbabb666fb64f8ac62bd37932e995508e4d5cff4e7b9b57da18  web/gateway/lib/portrait.ts
6642a9147ca36c81ba1936c1bc5f1d36d7ddb1b2d955c3c5af7472550512c350  web/game/lib/portrait.ts
c880fa314bddfec8242ef289a096a34163b739e7a99a9e1126e55a7a66ed6ed8  web/gateway/__tests__/portrait.test.tsx
453cbe51f1e4e403632f29260f0dd56fac5deec4849614eaf9d8261360ecf25a  web/gateway/__tests__/lobby-portrait.test.tsx
c81ccb36a6426f2de6062868aa4fe28df53ac28ca16788b2aaead403c4ee1f18  web/game/__tests__/portrait.test.tsx
```

`web/gateway/lib/portrait.ts`는 신규 파일(untracked). `web/game/lib/portrait.ts`는 modified(imgsvr=1 분기만 변경). nginx 2파일·compose 2파일은 modified.

---

## 2. 정규식 경계 / 3중 패리티

세 위치의 canonical 파일명 정규식이 **byte-for-byte 동일**하고 Kotlin ground truth와 일치한다:

| 위치 | 패턴 | 앵커 | 대소문자 |
|---|---|---|---|
| Kotlin `MANAGED_FILE` (line 650) | `[0-9a-f]{8}\.(avif\|webp\|jpg\|png\|gif)` | `.matches()`(전체 앵커) | 소문자 전용 |
| nginx (nginx.conf:105, default.conf:39·192) | `^/d_pic/(?<profile_icon>[0-9a-f]{8}\.(?:avif\|webp\|jpg\|png\|gif))$` | `^...$` | `~`(대소문자 구분) |
| helper `MANAGED_ICON` (양 앱) | `/^[0-9a-f]{8}\.(avif\|webp\|jpg\|png\|gif)$/` | `^...$` | 플래그 없음(구분) |

- 확장자 집합 `{avif,webp,jpg,png,gif}` 일치(`jpeg` 없음 — 3중 일관). Kotlin `EXTENSIONS` 집합과도 동일.
- nginx 정규식은 `{8}` 때문에 **쌍따옴표 인용**됨(미인용 시 nginx block 파서 충돌 → 구문 오류). 올바르게 인용됨. `nginx -t` = `syntax is ok`.
- 캡처 그룹은 `/`·`.`(단 하나의 리터럴 dot 제외)·대문자를 포함할 수 없으므로 `alias .../$profile_icon` 경로에 traversal 주입 불가.

**라이브 nginx 스팟체크(격리 컨테이너, nginx:1.27-alpine, 레포 `/d_pic/` location 버전 그대로 + SPA catch-all 스텁, 적대 파일 세트 마운트):**

| 요청 | 결과 | 판정 |
|---|---|---|
| `/d_pic/deadbeef.png` | `200 image/png` + `nosniff` + `Cache-Control: max-age=604800` | SERVE ✓ |
| `/d_pic/cafebabe.avif` | `200 image/avif` + nosniff | SERVE ✓ (MIME 정상) |
| `/d_pic/12345678.webp` | `200 image/webp` | SERVE ✓ |
| `/d_pic/00000000.gif` | `200 image/gif` | SERVE ✓ |
| `/d_pic/secret.txt` | `404` | 비이미지 확장자 차단 ✓ |
| `/d_pic/DEADBEEF.png` | `404` | 대문자 hex 차단 ✓ |
| `/d_pic/deadbee.png` (7-hex) | `404` | 차단 ✓ |
| `/d_pic/deadbeeff.png` (9-hex) | `404` | 차단 ✓ |
| `/d_pic/deadbeef.png.jpg` (이중 확장자) | `404` | 차단 ✓ |
| `/d_pic/deadbeef.png/x` (경로 접미) | `404` | 차단 ✓ |
| `/d_pic/deadbeef.png/../secret.txt` | `404`(정규화 후 `/d_pic/secret.txt`) | 볼륨 미유출 ✓ |
| `/d_pic/../secret.txt` | `/secret.txt`로 정규화 → SPA(`location /`)로 이탈 | **볼륨 미유출** ✓ |
| `/d_pic/%2e%2e/secret.txt` | 동일 → SPA로 이탈 | **볼륨 미유출** ✓ |
| `/d_pic/.ops/<32hex>.json` | `404` | `.ops` 자식 차단 ✓ |
| `/d_pic/<32hex>.json` (루트 marker) | `404` | 차단 ✓ |
| `/d_pic/.marker-<32hex>.tmp` | `404` | temp 차단 ✓ |
| `/d_pic/.stage-<32hex>` | `404` | stage dotfile 차단 ✓ |
| `/d_pic/` (디렉터리) | `404` (listing 없음) | autoindex 누출 없음 ✓ |
| `/d_pic/deadbeef.png?foo=bar` | `200 image/png` | 쿼리스트링 무해 ✓ |
| `/d_pic/deadf00d.png` → symlink→`/etc/passwd` | **`200`, `/etc/passwd` 내용 유출** | **F-1 참조** |

- 모든 traversal/marker/dot 케이스에서 볼륨의 `secret.txt`(`TOPSECRET`) 유출 0건(`grep -c TOPSECRET = 0`).
- `../` 이탈 케이스는 nginx가 location 매칭 **전에** URI를 정규화하므로 `/d_pic/` 공간을 벗어나 `/secret.txt`가 되고, 이는 alias 루트가 아니라 SPA proxy(`location /` → web app)로 간다. 즉 볼륨 파일을 읽지 못한다. **정상 동작**(finding 아님).
- avif MIME: 실검증 이전 우려 항목이었으나 `nginx:1.27-alpine`의 `/etc/nginx/mime.types`에 `image/avif avif;` 매핑 존재 확인 → `application/octet-stream`+nosniff로 렌더 깨짐 없음. 클리어.

---

## 3. location 우선순위 / 셰도잉

- nginx 우선순위: `= exact` → `^~ prefix` → `~ regex`(등장 순서) → 최장 prefix.
- **nginx.conf**: 유일한 regex location이 `/d_pic/` 하나. canonical 요청은 이 regex가 최장 prefix(`location /d_pic/`, `location /`)를 override → 서빙. non-canonical은 regex 불일치 → 최장 prefix `location /d_pic/ { return 404; }`(`/`보다 김) → 404. SPA로 새지 않음(라이브 확인: `/d_pic/secret.txt`·`/d_pic/deadbeef.png/x` 모두 404, SPA 아님).
- **default.conf**: 각 server 블록(80·443)에서 `/d_pic/` regex가 **첫 regex**(line 39·192), 그 뒤 `^~ /game/_next/`·`~ ^/game/...`. `/game/` regex는 `^/game/`를 요구하므로 `/d_pic/` 요청과 disjoint. 셰도잉 없음.
- 양 server 블록(80/443)에 canonical regex + `location /d_pic/ { return 404; }` **둘 다** 존재 확인(default.conf 39–50, 192–203).

---

## 4. compose `:ro` 마운트 계약

`docker compose config --format json`로 렌더(프로덕션은 더미 env 주입):

| compose | service | target | read_only |
|---|---|---|---|
| docker-compose.yml | gateway-api | `/var/lib/opensamguk/profile-icons` | **False (rw, sole writer)** ✓ |
| docker-compose.yml | nginx | `/var/lib/opensamguk/profile-icons` | **True (:ro)** ✓ |
| docker-compose.production.yml | gateway-api | 동일 | **False** ✓ |
| docker-compose.production.yml | nginx | 동일 | **True** ✓ |

- 두 compose 모두 nginx는 gateway-api가 쓰는 **같은 named volume `profile-icons`**를 `:ro`로 마운트. gateway-api는 rw 단일 writer.
- 다른 서비스/볼륨 부수 변경 없음. `docker compose config` 양쪽 exit 0.

---

## 5. helper(portrait.ts) 검토

- **imgsvr=1 + canonical** → 두 helper 모두 `MANAGED_ICON.test(...) ? '/d_pic/${name}' : DEFAULT` — 정확히 same-origin `/d_pic/<managed-name>` 반환. helper가 정규식으로 사전검증하므로 브라우저가 임의 문자열 URL을 만들지 않음(추가로 nginx가 404로 방어 — 이중 방어).
- **imgsvr!=1(0/null/undefined)** 분기는 diff에서 미변경 — 기존 공유 CDN 해석 그대로(회귀 없음, OPENSAM-90 유지). game diff는 `if (imageServer) return DEFAULT;` → managed 분기로 교체한 것뿐.
- **guarded onError** 단발 수렴: 양 helper 모두 이미 default면 재설정 안 함(무한 루프 방지). 단, 구현이 **다름** — gateway는 `ownerDocument.baseURI` 기준 URL 비교(상대경로 CDN·중첩 default.jpg까지 견고), game은 `img.src.endsWith('/default.jpg')`. 둘 다 단발이며 각 앱 테스트로 커버. (note N-3)
- **whitespace**: gateway `portraitUrl`은 `picture?.trim()`으로 트림(+`account/page.tsx`도 `picture.trim()`), game은 트림 안 함. game에서 공백 패딩된 canonical 이름은 앵커 정규식 불일치 → default 폴백(안전, 잘못된 URL 생성 안 함). 동작 차이지만 양쪽 안전. (note N-3)

---

## 6. Acceptance criteria(§5) 판정

| # | 기준 | 판정 | 근거 |
|---|---|---|---|
| 1 | canonical→200 + decoder 일치 Content-Type + nosniff | **PASS** | 라이브: png/avif/webp/gif 각 정확 MIME + `X-Content-Type-Options: nosniff` |
| 2 | 없는 canonical→404, 경로 미노출 | **PASS** | 404 + nginx 기본 404 body(파일시스템 경로 없음) |
| 3 | traversal/dot/.ops/temp/marker/symlink → 파일 내용 절대 미반환 | **PASS(조건부)** | traversal·marker·dot 전부 차단, 볼륨 secret 유출 0. **symlink-escape만 nginx 단독이 아니라 gateway-api 단일-writer 불변식에 의존**(F-1) |
| 4 | nginx 볼륨 read-only, 생성·수정·삭제 불가 | **PASS** | 양 compose nginx `read_only=True`, gateway-api rw |
| 5 | 새 upload→CDN purge 없이 same-origin 노출, fallback 무충돌 | **PASS** | content-addressed 랜덤 8-hex 파일명 → upload마다 URL 변경, 7d 캐시 안전(사실상 immutable) |
| 6 | helper imgsvr=1+canonical → 정확히 `/d_pic/<name>` | **PASS** | 코드+단위테스트(강제 default 아님, 정규식 사전검증) |
| 7 | imgsvr=1 → 양 앱 동일 canonical URL 요청·성공 표시 | **PASS** | lobby DOM 테스트 src=`/d_pic/...`, 양 helper 동일 해석 |
| 8 | missing/invalid/404 → guarded onError 1회 수렴, 무한 루프 없음 | **PASS** | 양 앱 단발 가드 테스트 통과 |
| 9 | imgsvr!=1/기존 default → OPENSAM-90 회귀 없음 | **PASS** | diff는 imgsvr truthy 분기만 변경, imgsvr=0/null/undefined→CDN 유지 테스트 통과 |

---

## 7. Findings

### F-1 `disable_symlinks` 부재 — canonical 이름 symlink로 임의 파일 읽기 (note · 하드닝 강권)
- **위치:** `infra/nginx/nginx.conf:105`, `infra/nginx/default.conf:39`·`:192` (`/d_pic/` regex location)
- **PoC(라이브 확인):** 볼륨 안에 `deadf00d.png -> /etc/passwd` symlink를 두고 `/d_pic/deadf00d.png` 요청 → **`200 image/png`, 응답 body = `root:x:0:0:root:/root:/bin/sh ...`**. nginx에 `disable_symlinks`가 없어 canonical 이름 symlink를 따라가 대상 파일을 서빙함.
- **평가:** **애플리케이션 경유로는 익스플로잇 불가.** gateway-api가 유일 writer이고 `LocalProfileIconStorage`가 (a) 파일명을 `[0-9a-f]{8}.ext`로 강제, (b) `SecureDirectoryStream` + `NOFOLLOW_LINKS` + `CREATE_NEW`로만 기록, (c) symlink·symlink 조상 전면 거부하므로 볼륨에 symlink가 생길 수 없다. 게다가 nginx 마운트는 `:ro`. 즉 이 취약점은 볼륨에 symlink를 심을 수 있는 **박스 파일시스템 직접 접근/오배치 bind-mount** 같은 out-of-band 전제가 필요하며, 그 시점이면 공격자는 이미 파일읽기 이상을 가진다.
- **권고:** `/d_pic/` location(또는 http/server 컨텍스트)에 `disable_symlinks on;` 1줄 추가. writer 불변식과 무관하게 nginx 자체가 symlink 추종을 거부 → 미래의 다른 writer, 복원/백업 시 symlink 보존, gateway-api 컨테이너 침해, 박스 compose 오배치 등 모든 경로에 대한 defense-in-depth. 비용 0(경로당 stat 몇 회), 이 저트래픽 정적 location에 적합.
- **차단 아님 근거:** 현재 배포 구성에서 라이브 익스플로잇 경로가 없음(단일-writer 통제 실검증). 팀 기준이 "모든 serving location은 writer 불변식과 독립적으로 견고해야 함"이면 fix-required로 승격 가능.

### F-2 default.conf 볼륨 마운트는 레포 밖 박스 compose 소관 (note · 운영 후속)
- **위치:** `infra/nginx/default.conf:38`·`:190-191`(인라인 주석 존재 확인)
- **내용:** default.conf는 라이브 EC2 박스의 자체 compose(레포 밖, `./docker/nginx/default.conf` 마운트)가 쓰는 정본이다. `/d_pic/`가 실제로 서빙되려면 **박스 compose가 nginx에 `- profile-icons:/var/lib/opensamguk/profile-icons:ro`를 추가**하고 그 박스의 gateway-api도 같은 볼륨/경로에 기록해야 한다. 주석에 요건이 명시돼 있음. 미적용 시 alias 대상 부재 → 전건 404(fail-closed, 보안 문제 아님).
- **추가 주의:** 레포 `docker-compose.production.yml`은 default.conf 헤더(line 7-9)가 밝히듯 박스와 **토폴로지가 다르다**(web-gateway/8081 vs gateway-frontend·gateway-api/18081). 따라서 레포 프로덕션 compose가 올바르게 `:ro`를 걸어도 그것이 박스에 자동 반영되지 않는다. A5 deploy 전 박스 compose 반영 필요.

### F-3 두 portrait.ts helper의 미세 divergence (note · 유지보수)
- **위치:** `web/gateway/lib/portrait.ts` vs `web/game/lib/portrait.ts`
- **내용:** (a) whitespace 트림 — gateway 트림, game 미트림. (b) onError 가드 구현 — gateway는 baseURI URL 비교(더 견고), game은 `endsWith('/default.jpg')`. 둘 다 안전하고 각 앱 테스트로 커버됨. 통일 강제는 아니나 향후 공유 헬퍼화 시 정리 대상.

*추가 미세 note:* content-addressed 8-hex(32bit) 파일명은 release 후 재사용 시(1/2^32) 7d 캐시로 이전 내용을 잠시 보일 수 있음 — 확률·영향 모두 무시 가능. finding 아님.

---

## 8. 테스트 재실행 결과(reviewer 직접 실행)

```
web/gateway  pnpm test -- portrait      → 3 files / 37 tests PASS
             (portrait.test.tsx 24, lobby-portrait.test.tsx 7, account-settings 6)
web/gateway  pnpm typecheck (tsc --noEmit) → clean, 오류 0
web/game     pnpm exec vitest run __tests__/portrait.test.tsx → 22 tests PASS
web/game     pnpm test -- portrait       → 39 files / 186 tests PASS(전체 스위트 green)
web/game     pnpm typecheck (tsc --noEmit) → clean, 오류 0
```
(corepack 미설치 → plain `pnpm` 사용. 계약의 `corepack pnpm`과 동등 실행.)

테스트 커버리지 적정성: 양 앱 helper 테스트가 위험 케이스를 실제 검증 — 대문자 hex, 7/9-hex, `.svg`, 확장자 없음, `../` traversal 형태 이름, `deadbeef.png/x` 경로주입 형태를 전부 default 폴백으로 단언. onError는 단발 수렴 + (gateway) 소스 교체 후 재폴백 + 상대경로 CDN self-error 무한루프 방지까지 커버. imgsvr!=1 회귀 방지 케이스 존재.

---

## 9. 최종 판정

**`cleared`**

9개 acceptance criteria 전부 PASS(AC3은 symlink-escape 절만 gateway-api 단일-writer 불변식에 조건부 의존). 라이브 nginx 스팟체크로 canonical 서빙·MIME·nosniff·캐시·전 traversal/marker/dot 차단·양 compose `:ro`를 실측 확인. 라이브 익스플로잇 가능한 취약점 없음.

- **F-1**(disable_symlinks): 병합 차단 아님. **배포 전 하드닝 강력 권고**(1줄).
- **F-2**(박스 compose 마운트): A5 deploy 전 레포 밖 박스 compose에 `:ro` 볼륨 반영 필요(운영 후속, fail-closed).
- **F-3**(helper divergence): 유지보수 note.

`fix-required` 0건.
