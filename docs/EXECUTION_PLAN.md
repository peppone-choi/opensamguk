# Execution Plan: Legacy Parity & Missing Features

Generated: 2026-01-15
Source: `reports/legacy_ts_mapping.md`

## 목표

- Legacy(PHP) 대비 누락된 기능/엔드포인트/페이지를 TS로 완성
- API 응답 스키마와 Web UI의 정합성 확보
- 빌드/테스트 안정화

---

## 1) Logic/Rules Parity (Constraints + Commands)

### 작업 단위

1. **ConstraintFactory 미구현 제약 구현**
   - 파일: `packages/logic/src/constraints/ConstraintFactory.ts`
   - 대상: `AdhocCallback`, `AvailableRecruitCrewType`, `CheckNationNameDuplicate`,
     `DisallowDiplomacyBetweenStatus`, `ExistsAllowJoinNation`, `NearNation`, `NoPenalty`,
     `ReqCityTrust`, `ReqCityValue`, `ReqDestCityValue`, `ReqGeneralCrewMargin`
   - 기준: legacy 제약과 동일한 입력/출력/메시지

2. **제약-커맨드 연결**
   - 파일:
     - `packages/logic/src/command/general/che_징병.ts`
     - `packages/logic/src/command/general/che_건국.ts`
     - `packages/logic/src/command/general/che_무작위건국.ts`
     - `packages/logic/src/command/general/cr_건국.ts`
     - `packages/logic/src/command/general/che_거병.ts`
     - `packages/logic/src/command/general/che_임관.ts`
     - `packages/logic/src/command/general/che_화계.ts`
     - `packages/logic/src/command/nation/che_선전포고.ts`
     - `packages/logic/src/command/nation/che_불가침제의.ts`
     - `packages/logic/src/command/nation/che_불가침수락.ts`
     - `packages/logic/src/command/nation/che_감축.ts`
     - `packages/logic/src/command/nation/che_증축.ts`
   - 기준: legacy 명령에서 사용되는 제약을 동일하게 연결

3. **랜덤임관 포팅 완성**
   - 파일: `packages/logic/src/command/general/che_랜덤임관.ts`
   - 기준: `legacy/hwe/sammo/Command/General/che_랜덤임관.php`와 동등 동작

### 실행 순서

- 제약 구현 → 제약 연결 → 랜덤임관 완성

### 완료 체크

- 해당 명령 실행 시 제약 메시지와 결과가 legacy와 동일

---

## 2) AI / Autorun

### 작업 단위

1. **GeneralAI 포팅**
   - 파일: `packages/logic/src/ai/GeneralAI.ts` (신규)
   - 기준: `legacy/hwe/sammo/GeneralAI.php`

2. **Autorun 정책 포팅**
   - 파일:
     - `packages/logic/src/ai/AutorunGeneralPolicy.ts` (신규)
     - `packages/logic/src/ai/AutorunNationPolicy.ts` (신규)
     - `packages/logic/src/ai/TurnExecutionHelper.ts` (신규)
   - 기준: legacy 동작 동일

### 실행 순서

- GeneralAI → Policy → Helper

### 완료 체크

- NPC 턴 자동 생성/실행이 정상 동작

---

## 3) API Coverage (누락 j\_ 엔드포인트)

### 작업 단위

1. **설치/초기화**
   - legacy: `legacy/f_install/*`, `legacy/hwe/j_install.php`, `legacy/hwe/j_install_db.php`
   - 라우트: `trpc.install.*` (신규)

2. **외교 서신**
   - legacy: `legacy/hwe/j_diplomacy_*_letter.php`
   - 라우트: `trpc.diplomacy.*` (신규)

3. **NPC 선택**
   - legacy: `j_select_npc.php`, `j_get_select_pool.php`, `j_get_select_npc_token.php`,
     `j_select_picked_general.php`, `j_update_picked_general.php`

4. **전투 시뮬레이터**
   - legacy: `j_simulate_battle.php`, `j_export_simulator_object.php`

5. **자동 리셋**
   - legacy: `j_autoreset.php`

6. **유저 설정/휴가/권한**
   - legacy: `j_set_my_setting.php`, `j_vacation.php`, `j_general_set_permission.php`

7. **관리자/유저 관리**
   - legacy: `i_entrance/j_get_userlist.php`, `j_set_userlist.php`,
     `j_server_change_status.php`, `j_server_get_admin_status.php`

8. **계정 관리**
   - legacy: `j_change_password.php`, `j_delete_me.php`,
     `j_icon_change.php`, `j_icon_delete.php`, `j_disallow_third_use.php`

9. **업데이트 파이프라인**
   - legacy: `j_updateServer.php`

### 실행 순서

- 설치 → 계정 → 관리자 → 외교/전투 → 기타

### 완료 체크

- legacy API 대비 동일한 기능 접근 가능

---

## 4) API Contract Alignment (partial 매핑 개선)

### 작업 단위

- `j_basic_info.php` → `trpc.getGeneralDetail` 응답 확장
- `j_get_basic_general_list.php` → `trpc.getGeneralList` 응답 정합성
- `j_get_city_list.php` → `trpc.getAllCities` 응답 정합성
- `j_map_recent.php` → `trpc.getCachedMap` + `trpc.getCurrentHistory` 응답 통합
- `j_server_basic_info.php` / `j_server_get_status.php` → 응답 스키마 보강
- `j_check_OTP.php` → OTP 플로우 스키마 정합화

### 실행 순서

- API 응답 타입 정의 → 서비스 반환 형식 수정 → 프론트 반영

### 완료 체크

- Web에서 타입 `any` 없이 정상 빌드

---

## 5) Web Pages/Flows

### 작업 단위

- `legacy/hwe/v_processing.php` 대응 페이지 구현
  - 파일: `apps/web/src/app/(game)/processing/page.tsx`
  - 로딩/대기 상태 UI

### 실행 순서

- 페이지 생성 → 라우팅 연결

### 완료 체크

- 직접 접근 및 흐름 연결 확인

---

## 6) Install/Setup Flow

### 작업 단위

- 설치 페이지 + DB 세팅 UI
- 관리자 생성 UI
- 설치 상태 체크 UI

### 실행 순서

- API 구현 후 UI 작성

### 완료 체크

- 새 서버에서 설치/초기화 완료 가능

---

## 실행 일정 (권장)

1. **Week 1**: Logic/Rules Parity (Constraints + Commands)
2. **Week 2**: API Coverage (Install + Account + Admin)
3. **Week 3**: API Coverage (Diplomacy + NPC + Battle)
4. **Week 4**: AI/Autorun
5. **Week 5**: Contract Alignment + Web Pages/Flows

---

## 진행 체크리스트

- [ ] 제약 구현 완료
- [ ] 명령-제약 연결 완료
- [ ] 랜덤임관 완료
- [ ] 누락 API 라우트 구현 완료
- [ ] partial 응답 스키마 정합
- [ ] 누락 페이지 구현 완료
- [ ] AI/Autorun 동작 확인
- [ ] 전체 빌드 통과
