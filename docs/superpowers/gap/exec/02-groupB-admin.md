# 그룹 B — 어드민(Admin) 실행계획

> **데이터 소스**: `_full_audit_2026-06-07.raw.json` (`.missingPages[]`, `.partialPorts[]`, `.comparisons[]`) + 실제 legacy PHP grep/Read.
> **grand truth**: `legacy/devsam-core` PHP. 날조 금지 — 모든 단위는 file:line 근거.
> **읽기전용 산출**: 빌드/실행 없음.

---

## 0. 권한모델 divergence (전 단위 공통 전제 — 반드시 먼저 읽을 것)

legacy 어드민은 **`member.GRADE` 0–9 다단계** 게이트다. opensamguk은 **`users.role ∈ {USER, ADMIN}` boolean** 로 평탄화됐다(`UserEntity.role:String`, `infra/.../entity/UserEntity.kt:38`; `SecurityConfig` `requestMatchers("/admin/**").hasRole("ADMIN")` `app/gateway-api/.../security/SecurityConfig.kt:38`).

legacy grade 임계값 (감사 확인):
| grade | 의미 | 게이트하는 단위 |
|------|------|----------------|
| `<4` 거부 | 게임관리 마스터 | `_119`/`_119_b` (`hwe/_119.php:8`, `hwe/_119_b.php:10`) |
| `<5` 거부 | 부운영자 | `j_get_userlist`(목록조회, `:9`), `j_server_change_status`(notice/open/close, `:27`), `BanEmailAddress`(`:39`), grade>=4 `_admin1` 본문 일부 |
| `<6` 거부 | 운영자 | `_admin1`(`:9`), `_admin2`/`_admin2_submit`(`:10/:18`), `_admin5`(`:23`)/`_admin5_submit`(`:15`), `_admin7`(`:40`), `_admin8`(`:15`), `_admin_force_rehall`(`:10`), `j_set_userlist`(상태변경, `:10`), `reset`(서버리셋, `change_status:90`) |
| 대상보호 | — | `j_set_userlist`: 대상 grade ≥ 본인 grade면 거부(`:162`), `set_userlevel` param ≥ 본인 grade 거부(`:274`) |
| ACL | per-server | `j_server_change_status`: `openClose`/`reset`/`notice`/`update`/`fullUpdate` ACL(`:39,50,137`) |

**divergence 결정 (0.9.0 패러티 vs 운영 현실)**:
- 0.9.0 기준 = 단일 `ADMIN` role로 전 어드민 게이트(현 구조 유지). grade 4/5/6 다단계 + per-server ACL은 **의도된 인증 divergence**로 수용 — parityViolation 아님(audit `ts-admin-server`/`ts-admin-member` 명시).
- **단, `j_set_userlist`의 "대상이 자신과 같거나 높은 권한이면 거부"(`:162,274`)는 단순 ADMIN으로는 표현 불가** → 다른 ADMIN을 강제탈퇴/차단할 수 있는 보안 구멍. **B-AUTH 결정 필요**: ① `UserEntity`에 `grade:Int` 컬럼 추가(권장, 1.0.0 멀티운영자 대비) 또는 ② "ADMIN은 다른 ADMIN을 변경 불가" 단일 규칙. 본 계획은 **②(self/peer 보호 규칙)** 을 기본값으로, grade 컬럼은 B-AUTH 백로그로 둔다.

---

## 1. 현 admin 구현 실측 — 무엇이 stub인가

| 구현물 | 위치 | 실측 | legacy 대응 |
|--------|------|------|------------|
| `AdminController` | `app/gateway-api/.../controller/AdminController.kt` | `/admin/version` · `/admin/deploy/status` · `/admin/deploy` 3개만. **전부 DevOps(GHCR 태그 재배포)** — legacy 어드민 기능 0개 | `j_updateServer.php`(부분), 나머지 전무 |
| `AdminDto` | `app/gateway-api/.../dto/AdminDto.kt` | `ServiceVersion`/`ServerVersion`/`VersionResponse`/`DeployStatus`/`DeployRequest`/`DeployResult` — 전부 배포용 | — |
| `AdminSeeder` | `app/gateway-api/.../config/AdminSeeder.kt` | ENV `ADMIN_USERNAME`/`ADMIN_PASSWORD`로 ADMIN 1명 멱등 시드. 정상 | — |
| `web/gateway/app/admin/page.tsx` | 동 | 탭 3개(`회원 관리`/`서버 제어`/`게임 환경`). **`서버 제어`=배포 UI만**, `회원 관리`·`게임 환경`=`'준비 중'` PLACEHOLDER(`:339`) | 라벨만 verbatim, 본문 전무 |
| `StatusController` | `app/game-engine/.../status/StatusController.kt` | `GET /admin/turn-daemon/status` → **하드코딩 stub**(`paused=false, running=false` 항상, `:22`) | `_119` 락(plock) 상태 표시의 후보 백엔드 |
| `UserEntity` | `infra/.../entity/UserEntity.kt` | `username/password/email/nickname/role/createdAt/updatedAt`. **GRADE·BLOCK_DATE·delete_after·oauth_type·PICTURE/IMGSVR·member_log(loginDate) 전부 부재** | `member` 테이블 |

**결론**: 어드민 영역은 *배포 기능을 제외하면 100% stub*. legacy의 게임 내 관리(시간/봉급/락/회원/일제/로그/외교/명전)는 단 하나도 포팅되지 않음. `'게임 환경'` 탭은 legacy 근거 없는 신규 분류이나 `_119`/`_admin1`의 의도된 자리로 재해석한다.

---

## 2. BE-먼저 의존 순서 (FE는 전부 BE에 BLOCKED)

audit가 admin FE gap 대부분을 `blocked=true`로 표시한 근본 원인 = **대응 BE 엔드포인트/데이터모델 부재**. 따라서 순서는 엄격히 BE→FE.

```
[B-AUTH 권한규칙 결정]  ──┐
[B-DATA UserEntity 확장] ─┼──▶ [BE 엔드포인트 신설] ──▶ [FE admin 탭 본문] ──▶ tsc
[game_env 노출(read/write)]┘        (gateway-api +              (web/gateway/app/admin)
                                     game-engine intake)
```

- **B-DATA**(UserEntity 컬럼 확장: grade?/blockUntil/deleteAfter/oauthType/picture/imgsvr + member_log 상당 loginDate)는 거의 모든 회원관리 단위의 선결. Flyway 마이그레이션 1건.
- **game_env 키**(turntime/starttime/tnmt_time/maxgeneral/maxnation/startyear/turnterm/msg/isunited)는 이미 world_state/config jsonb 또는 KVStorage 상당으로 존재(`WorldSnapshotLoader`/`BootstrapConfig`가 참조) — read 노출 + 어드민 write 경로만 신설.
- **strict parity 위반 forced-mutation**(강제사망/블럭/하야/방랑/숙련도지급)은 general 테이블 직접 변경 → **one-daemon-write-rule** 때문에 gateway-api JDBC 직접쓰기 불가. game-engine intake(Redis XADD) 또는 game-api 경유 intake로 가야 함 → 아키텍처상 가장 무거운 단위(B6).

---

## 3. 단위 표

> 컬럼: **id | kind | legacy(file:line) | 대상 파일(opensamguk) | 의존 | 골든(RNG Y/N) | 게이트 | 상태 | fixSpec/서브태스크**
> kind: `admin-be`=gateway-api 또는 game-engine BE, `admin-fe`=web/gateway, `admin-data`=infra 스키마, `admin-intake`=game-engine 강제뮤테이션.

### B0 — 기반 (Tier-0, 모든 후속의 선결)

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B0-AUTH** | 결정 | (권한모델 §0) | `infra/.../entity/UserEntity.kt`, `AdminController` self-guard 헬퍼 | — | N | 단위테스트(self/peer 거부) | 🔴 미착수 | self/peer 보호 규칙 확정. legacy `j_set_userlist:162,274` 동작을 "ADMIN은 다른 ADMIN/자기 자신 강등·삭제·차단 불가"로 단순화. grade 컬럼 확장은 B-AUTH-EXT 백로그(1.0.0 멀티운영자). |
| **B0-DATA** | admin-data | `member` 컬럼(`j_get_userlist:28-40`) | `infra/.../entity/UserEntity.kt` + `infra/.../db/migration/V__admin_member_fields.sql` + `UserRepository` 쿼리 | — | N | infra IT(컬럼 매핑) | 🔴 미착수 | `UserEntity`에 `grade:Int?`(divergence면 nullable), `blockUntil:LocalDateTime?`, `deleteAfter:LocalDateTime?`, `oauthType:String?`, `picture:String?`, `imgsvr:Boolean`, `lastLoginAt:LocalDateTime?`(member_log 대체) 추가. Flyway V마이그레이션. loginDate는 `member_log.action_type=login` 최신 → 별도 login_log 테이블 or `lastLoginAt` 갱신. |
| **B0-GAMEENV** | admin-be | `game_env` KVStorage(`_admin1.php:34`, `_119.php:13`) | `app/game-api/.../read/GameEnvReadRepository.kt` + game-engine `world_state`/config 노출 | — | N | game-api IT | 🟡 부분(world_state 존재) | turntime/starttime/tnmt_time/maxgeneral/maxnation/startyear/turnterm/msg/isunited read 노출. write는 B1/B5에서 소비. **world_state 컬럼 부재 필드(opentime/starttime 등) 확인 필요** — 없으면 B0-GAMEENV-EXT 백로그(데이터모델 보강 선행, 날조 금지). |

### B1 — 게임관리(`_119` + `_admin1`) = '게임 환경' 탭 BE+FE

legacy 2개 화면이 opensamguk '게임 환경' 탭으로 합류. 시간/봉급/락 + 시작시간/최대장수/최대국가/시작년도/턴시간/운영자메시지/중원정세로그.

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B1a-time-be** | admin-be | `_119_b.php:31-97` (분당김/분지연/토너분당김/토너분지연) | game-engine 신규 lifecycle 엔드포인트 `POST /admin/turn-daemon/shift-time` + gateway-api 프록시 | B0-GAMEENV | N | game-engine IT | 🔴 | turntime/starttime/tnmt_time을 ±N분. legacy는 `DATE_SUB/ADD INTERVAL`로 general.turntime + ng_auction.close_date(finished=0) 일괄 조정 + tryLock(분당김 10회/분지연 5회). **one-daemon-write-rule** → game-engine이 InMemoryTurnWorld 직접 시프트 + ChangeRecorder flush. tnmt_time만 조정(토너분당김/지연)은 별도 분기. |
| **B1b-lock-be** | admin-be | `_119.php:17,36` + `_119_b.php:104-115`(락걸기/락풀기) | `app/game-engine/.../status/StatusController.kt` 확장 `POST /admin/turn-daemon/pause`·`/resume` + `GET /status`를 실 상태로 | B0 | N | game-engine IT | 🟡 stub 존재 | 현 `StatusController.status()`는 하드코딩(`:22`). plock(GAME) = 데몬 일시정지. `TurnDaemonLifecycle`에 paused 플래그 + 게이트 추가, status를 실값으로. 락걸기=tryLock 최대10회, 락풀기=unlock. 표시 `현재 : 동결중/가동중`. |
| **B1c-income-be** | admin-be | `_119_b.php:98-103`(금지급=processGoldIncome, 쌀지급=processRiceIncome) | game-engine `POST /admin/income/gold`·`/income/rice` → `ProcessIncome` 호출 | B0 | **검토** | game-engine IT | 🟡 `ProcessIncome.kt` 존재 | `logic/.../world/ProcessIncome.kt` 이미 포팅됨(월틱 경로). 어드민 수동 트리거 = 같은 로직 1회 실행. **봉급 계산 자체는 결정적이나 ProcessIncome 내부 RNG 호출 여부 확인** — 호출 있으면 골든 Y(seed 고정 캡처), 없으면 N. 월틱 골든 재사용 가능성. |
| **B1d-gameenv-set-be** | admin-be | `_admin1_submit.php:39-78`(변경/로그쓰기/변경1~4/N분턴) | gateway-api `POST /admin/game-env`(msg/starttime/maxgeneral/maxnation/startyear) + game-engine `changeServerTerm`(턴기간) | B0-GAMEENV | N | game-api IT + game-engine IT | 🔴 | msg=운영자메시지, 로그쓰기=`pushGlobalHistoryLog(["<R>★</><S>{log}</>"])`(world_history nation_id=0, **로그 byte-parity 대상**), starttime/maxgeneral/maxnation/startyear = game_env write, N분턴=`ServerTool::changeServerTerm(turnterm)`(general.turntime 재계산 동반 — game-engine). |
| **B1e-game-env-fe** | admin-fe | `_119.php`+`_admin1.php` 전체 | `web/gateway/app/admin/page.tsx` '게임 환경' 탭 본문 | B1a-d | N | tsc + 수동 QA | 🔴 PLACEHOLDER(`:339`) | `'준비 중'` 제거. 시간조정(minute±) / 토너시간(minute2±) / 봉급(금·쌀) / 락(걸기·풀기 + 동결중/가동중 표시) + 운영자메시지 textarea / 중원정세추가 / 시작시간 / 최대장수·국가 / 시작년도 / 턴시간 버튼군(1·2·5·10·20·30·60·120분). 각 → `/api/proxy/admin/...` POST. 라벨 verbatim 패러티(`_119`/`_admin1`). |

### B2 — 회원관리(루트DB, `j_get_userlist`/`j_set_userlist`/`BanEmailAddress`) = '회원 관리' 탭

루트DB(gateway 공유) 유저 관리. 게임 내 general 아님 → gateway-api + UserEntity. **strict 위반 아님(루트DB 직접 쓰기 OK, one-daemon-write-rule 무관)**.

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B2a-userlist-be** | admin-be | `j_get_userlist.php:16-58` | gateway-api `GET /admin/users` + `AdminUserDto` | B0-DATA | N | gateway-api IT | 🔴 | member 전체 + loginDate(member_log 최신 login) + 서버별 아이콘(imgsvr 분기 완성 URL) + system REG/LOGIN 플래그. opensamguk: `UserRepository` 전체 + lastLoginAt + role→grade라벨. **grade<5 거부**(divergence: ADMIN). |
| **B2b-system-toggle-be** | admin-be | `j_set_userlist.php:36-70`(allow_login/allow_join) | gateway-api `POST /admin/system/{allow_login\|allow_join}` + `system` 상당 KV/테이블 | B0 | N | gateway-api IT | 🔴 | 가입/로그인 전역 허용 Y/N. opensamguk엔 `system.REG/LOGIN` 상당 부재 → **신규 system_flag 테이블 or config KV** 필요(데이터모델 보강). 로그인/가입 경로(AuthController)가 이 플래그를 읽도록 배선. |
| **B2c-scrub-be** | admin-be | `j_set_userlist.php:72-144`(scrub_deleted/scrub_icon/scrub_old_user) | gateway-api `POST /admin/users/scrub/{deleted\|icon\|old}` | B0-DATA | N | gateway-api IT | 🔴 | 탈퇴(delete_after<today) 정리 / 미사용 아이콘(1개월+, FS glob — opensamguk 이미지 CDN이라 **scrub_icon은 N/A 또는 백로그**) / 6개월+ 미접속 정리. affected count 반환. |
| **B2d-user-cmd-be** | admin-be | `j_set_userlist.php:146-301`(delete/reset_pw/block/unblock/set_userlevel) | gateway-api `POST /admin/users/{id}/{action}` | B0-AUTH, B0-DATA | N | gateway-api IT(self/peer 거부 포함) | 🔴 | delete=member 삭제, reset_pw=랜덤6자 임시PW(`Util::randomStr(6)`)+detail 반환, block=grade0+block_date(param일, ≤0이면 50년), unblock=grade1+block_date null, set_userlevel=grade 설정(1~본인-1). **대상보호 B0-AUTH 규칙 적용**. |
| **B2e-ban-email-be** | admin-be | `BanEmailAddress.php:34-58` | gateway-api `POST /admin/ban-email` + `banned_member` 상당 테이블 | B0 | N | gateway-api IT | 🔴 | `sha512(salt+email+salt)` 해시 영구차단. opensamguk엔 banned_member 테이블 부재 → 신규 + 회원가입 경로가 이 해시 체크하도록 배선. **grade<5 거부**(divergence). |
| **B2f-member-fe** | admin-fe | `admin_member.ts`+`admin_userlist.php` | `web/gateway/app/admin/page.tsx` '회원 관리' 탭 | B2a-e | N | tsc + 수동 QA | 🔴 PLACEHOLDER | 가입/로그인 라디오 토글 + 계정정리 3버튼 + 11열 테이블(코드/유저명/EMAIL+@줄바꿈+authType/등급+차단만료/닉네임/전콘/장수명(서버별)/가입일/최근로그인/탈퇴신청/명령) + 행당 6버튼(강제탈퇴/암호변경/유저차단(기간prompt)/차단해제/영구차단/별도권한(등급prompt)). 등급라벨 매핑(0차단/1일반/4특별/5부운영자/6운영자) `admin_member.ts` 정본. |

### B3 — 일제정보(`_admin5`) = read + 국가변경 (게임서버 내)

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B3a-nation-stats-be** | admin-be | `_admin5.php:1-353` | game-api `GET /admin/nation-stats?type=&type2=` + DTO | B0 | N | game-api IT | 🔴 | 국가별 전체 통계(국력/장수/도시/기술/자원/숙련/인구 등) + 정렬(type 0~17) + 역사 통계. **read-only** → game-api JPA. legacy 정렬키 18종 verbatim. |
| **B3b-nation-change-be** | admin-intake | `_admin5_submit.php:22-46`(국가변경) | game-engine intake `admin_nation_change` 핸들러 | B0, B6-INTAKE | N | game-engine IT | 🔴 | admin **자신의** general 소속 국가 강제 변경(nation/officer_level/officer_city + 양국 gennum ±1). general 직접 변경 → **one-daemon-write-rule** → intake. |
| **B3c-admin5-fe** | admin-fe | `_admin5.php` UI | `web/game/app/game/admin5/` (게임서버 내, web/game) | B3a-b | N | tsc + QA | 🔴 | 게임 내 화면이므로 **web/game**(gateway 아님). 정렬 select + 통계 테이블 + 국가변경 폼. |

### B4 — 로그/외교 정보(`_admin7`/`_admin8`) = read-only

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B4a-general-log-be** | admin-be | `_admin7.php:1-181` | game-api `GET /admin/general-log?gen=&query_type=` + DTO | B0 | N | game-api IT | 🔴 | 장수 상세/개인기록/전투기록/장수열전/전투결과. 정렬 queryMap 4종(turntime/recent_war/name/warnum) verbatim(`:15-31`). read-only JPA. |
| **B4b-diplomacy-be** | admin-be | `_admin8.php:1-124` | game-api `GET /admin/diplomacy-all` + DTO | B0 | N | game-api IT | 🔴 | 전 국가간 외교(교전/선포/통상/불가침) 전체. 기존 `DiplomacyController`(중립 마스킹된 GetDiplomacy) 와 달리 **마스킹 없음**(어드민). read-only. |
| **B4c-log-diplo-fe** | admin-fe | `_admin7`/`_admin8` UI | `web/game/app/game/admin7/`·`admin8/` | B4a-b | N | tsc + QA | 🔴 | 게임 내(web/game). 로그 select+테이블 / 외교 매트릭스. read 렌더. |

### B5 — 강제 명전 등록(`_admin_force_rehall`)

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B5-force-rehall-be** | admin-intake | `_admin_force_rehall.php:1-32` | game-engine `POST /admin/force-rehall` → `CheckHall` + `InheritancePointManager.mergeTotalInheritancePoint`/`applyInheritanceUser` | B6-INTAKE, B0-GAMEENV(isunited) | **Y(검토)** | game-engine 골든 IT | 🔴 | isunited 후만 실행(아니면 거부). age>=40 & npc<2 전 장수 `CheckHall` + npc=0 전 장수 상속포인트 재계산. **CheckHall은 명전 등록 — RNG/로그 byte-parity 가능성** → 골든 캡처 검토. `Rebirth.kt`/`ReservedTurnHandler`에 CheckHall 상당 존재(재사용). general 변경 → intake. |

### B6 — 어드민 forced-mutation intake 기반 (B1a/B3b/B5 공통 선결)

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B6-INTAKE** | admin-intake | `_admin2_submit.php` 외 일괄 general write | game-engine `app/game-engine/.../intake/AdminMutationHandler.kt` + game-api precheck + `CommandWireMapper` 확장 | B0-AUTH | N | game-engine IT(권한+멱등) | 🔴 | **one-daemon-write-rule 준수 골격**: gateway/game-api는 general/nation 직접쓰기 금지. 어드민 강제뮤테이션은 game-engine intake(Redis XADD or game-api 경유)로만. 권한 검증(ADMIN)은 game-api precheck. 이 핸들러가 B6a~g(아래) 케이스를 dispatch. |

### B6a~g — 회원관리(게임서버 내, `_admin2`/`_admin2_submit`) 강제뮤테이션

> 전부 game-engine intake 경유(general/general_turn/general_access_log/nation 변경). genlist 다중 대상.

| id | kind | legacy(`_admin2_submit.php`) | 의존 | 골든 | fixSpec |
|----|------|------------------------------|------|------|---------|
| **B6a-block** | admin-intake | 블럭해제/1·2·3단계블럭/무한삭턴 (`:50-100`) | B6-INTAKE | N | block 0/1/2/3 + killturn(24, 무한=8000) + member.block_num/block_date(루트DB). 1단계=발언권, 2·3단계=gold/rice 0 + 턴블럭. |
| **B6b-forcekill** | admin-intake | 강제사망 (`:101-112`) | B6-INTAKE | N | killturn=0 + turntime=now + general_turn[0] action=휴식. |
| **B6c-dex** | admin-intake | 보·궁·기·귀·차숙10000 (`:135-187`) | B6-INTAKE | N | dex1~5 += 10000 + 각 장수 Message 발송("N숙련도+10000 지급!"). **Message byte-parity**. |
| **B6d-access** | admin-intake | 전체/개별 접속허용·제한 (`:40-49,188-197`) | B6-INTAKE | N | general_access_log.refresh_score 0(허용)/1000(제한). 전체=true 조건. |
| **B6e-message** | admin-intake | 메세지 전달 (`:198-204`) | B6-INTAKE | N | genlist 각 → 어드민 general발 private Message(만료 9999-12-31). |
| **B6f-command-set** | admin-intake | 하야입력/방랑해산 (`:205-223`) | B6-INTAKE | N | general_turn[0] action=che_하야 또는 (turn0=che_방랑, turn1=che_해산). brief 동반. **che_하야/방랑/해산 명령 포팅 의존**(그룹 A). |
| **B6g-admin2-fe** | admin-fe | `_admin2.php` UI | B6a-f | N | 게임 내(web/game) 회원선택 multi-select(NPC색/블럭배경) + 12행 명령 버튼군. 라벨 verbatim. |

---

## 4. 서버 개폐(`j_server_*`) — 아키텍처 divergence 노트 (대부분 백로그)

| id | legacy | 현 대응 | 결정 |
|----|--------|---------|------|
| **j-server-get-status** | `j_server_get_status.php` (color/korName/name/exists/enable) | `web/gateway`가 **정적 `servers.json`** 으로 서버목록 관리(`lobby/page.tsx` import) | 🟡 동적 API화는 선택. 다중 서버 운영 시 `GET /admin/servers` 신설. **저우선** |
| **j-server-change-status** | `j_server_change_status.php` (notice/open/close/reset) | AdminController **부재**. open/close=`.htaccess`(PHP) → opensamguk은 **배포(compose)** 로 대체 | 🔴 **개념 divergence**. notice(system.NOTICE)만 즉시 가치 有 → `POST /admin/notice` 신설 권장. open/close/reset은 docker/배포 영역(별 인프라). **부분만 포팅** |
| **j-update-server** | `j_updateServer.php` (git pull+webpack) | `AdminController /admin/deploy`(GHCR 태그) | ✅ **기능 등가 divergence**(audit partial). 추가 작업 없음 |
| **j-server-get-admin-status** | valid/run/installed/version/ACL | `/admin/version`(버전만) | 🟡 valid/run/installed/한글명·색상/ACL 누락. 배포모델 전환으로 대부분 무의미. **버전 표는 유지, 상태 라벨만 선택 보강** |

**노트**: 서버 개폐 영역은 PHP의 파일시스템(.htaccess) + git pull 모델을 docker/GHCR 배포로 **의도 전환**한 영역이라, 대부분 패러티 대상이 아니라 백로그/divergence. **단 `system.NOTICE`(공지) 변경은 게임 무관 루트 기능이라 B2와 함께 `POST /admin/notice`로 포팅 권장.**

---

## 5. 실행 순서 요약 (의존 위상)

```
1. B0-AUTH (권한규칙 결정)        ← 최우선, 모든 회원/intake의 선결
2. B0-DATA (UserEntity 확장+Flyway) ┐ 병렬 가능
   B0-GAMEENV (game_env read 노출)  ┘
3. B6-INTAKE (forced-mutation 골격)  ← B1a/B3b/B5/B6a-g 선결
4. ── 병렬 웨이브 (disjoint 파일) ──
   B2a-e (회원관리 BE, 루트DB)
   B1a-d (게임환경 BE, game-engine)
   B3a/B4a/B4b (read-only BE, game-api)
   B6a-f (회원 강제뮤테이션 intake)
   B5 (명전, 골든 검토)
   B3b (국가변경 intake)
5. ── FE 웨이브 (BE green 후) ──
   B1e (게임환경 탭, web/gateway)
   B2f (회원관리 탭, web/gateway)
   B3c/B4c/B6g (게임 내, web/game)
6. tsc(양 web) + 수동 QA
```

**병렬 격리 규칙**(CLAUDE.md): worktree 가족은 disjoint 파일. `page.tsx`는 단일 파일 co-widen 위험 → B1e/B2f는 **순차**(creator-then-consumer) 또는 탭별 컴포넌트 추출 후 병렬.

---

## 6. 골든 필요(RNG-bearing) 판정

| 단위 | RNG | 근거 |
|------|-----|------|
| **B5-force-rehall** | **Y(검토)** | `CheckHall` 명전 등록 + 상속포인트 재계산 — 로그/순위 byte-parity. 골든 캡처(isunited 시나리오) 검토. |
| **B1c-income** | **검토** | `ProcessIncome` 내부 RNG 호출 여부 확인. 있으면 Y(월틱 골든 재사용), 없으면 N. |
| **B6c-dex / B6e-message** | N(로그 byte-parity) | RNG 없음. 단 Message 텍스트("N숙련도+10000 지급!") byte-parity 대상. |
| 그 외 전부 | **N** | 결정적 CRUD(블럭/사망/시간시프트/접속/회원관리/통계/외교). 단위테스트로 충분. |

---

## 7. 미해결/백로그 (날조 금지 — 데이터모델 보강 선행 항목)

- **B0-GAMEENV-EXT**: opentime/turntime substr 표시 필드가 world_state 컬럼에 없으면 → 데이터모델 보강 선행(audit `j-server-basic-info` blocked 항목과 동일 뿌리). 없는 값 날조 금지.
- **B2b system_flag**: opensamguk에 `system.REG/LOGIN` 상당 부재 → 신규 테이블/KV 필요.
- **B2e banned_member**: 신규 테이블 + 회원가입 경로 배선 필요.
- **B2c scrub_icon**: opensamguk 이미지=CDN(opensam-images)이라 FS glob 정리 **N/A 가능** — 적용 대상 확인 후 백로그/제외.
- **B-AUTH-EXT**: grade 0–9 다단계 + per-server ACL 복원(1.0.0 멀티운영자). 0.9.0은 ADMIN 단일로 수용.
- **서버 개폐(j_server_change_status open/close/reset)**: docker/배포 모델 전환으로 의도 divergence. `system.NOTICE`만 `POST /admin/notice`로 부분 포팅.
- **CreateAdminNPC**: PHP 본체가 'NYI' 스텁(`Event/Action/CreateAdminNPC.php`) → 포팅 불필요(audit 명시).
