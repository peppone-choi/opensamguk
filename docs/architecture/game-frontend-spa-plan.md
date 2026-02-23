# Game Frontend SPA Plan

이 문서는 `app/game-frontend`를 Vue 3 + Pinia + Vue Router 기반 SPA로 구축하기 위한
지속 사용 가능한 작업 플랜이다. 레거시 화면(`legacy/hwe`)과 문서(`docs/`)를 기준으로
화면 목록, 인증/권한 분기, 데이터 계약을 정리하고 단계별 구현 순서를 정의한다.

## Goals

- 레거시 화면과 정보 제공 범위를 보존하면서 SPA로 재구성한다.
- 인증 상태별 정보 공개 범위를 엄격히 분리한다.
- API 통신은 tRPC로 통일하고, 추후 SSE 실시간 업데이트 경로를 고려한다.
- UI/레이아웃은 레거시와 유사하게 유지하되, 정보 구조는 SPA에 맞게 재배치 가능.

## Reference Sources

- Legacy view entrypoints: `legacy/hwe/{b_,v_,a_,index}*.php`
- Legacy Vue/TS sources: `legacy/hwe/ts/`, `legacy/hwe/ts/components/`
- Docs: `docs/architecture/overview.md`, `docs/architecture/rewrite-plan.md`,
  `docs/architecture/runtime.md`, `docs/architecture/legacy-engine*.md`

## User State Matrix

- Public (미로그인/장수 미생성): 공개 정보만 노출
  - 레거시 기준: 10분 캐시 지도 + 동향(최소 정보)
- Authed (로그인 + 장수 생성): 대부분의 정보 접근 허용
- Admin/GM: 운영자 전용 화면 및 도구 (후순위)

## Legacy Screen Inventory (Route 후보)

정확한 데이터 흐름/권한은 각 PHP 엔트리포인트와 연관 TS 컴포넌트에서 확인한다.

- `legacy/hwe/index.php`
- `legacy/hwe/v_cachedMap.php`
- `legacy/hwe/v_join.php`
- `legacy/hwe/v_processing.php`
- `legacy/hwe/v_board.php`
- `legacy/hwe/v_history.php`
- `legacy/hwe/v_vote.php`
- `legacy/hwe/v_auction.php`
- `legacy/hwe/v_battleCenter.php`
- `legacy/hwe/v_chiefCenter.php`
- `legacy/hwe/v_globalDiplomacy.php`
- `legacy/hwe/v_inheritPoint.php`
- `legacy/hwe/v_NPCControl.php`
- `legacy/hwe/v_nationBetting.php`
- `legacy/hwe/v_nationGeneral.php`
- `legacy/hwe/v_nationStratFinan.php`
- `legacy/hwe/v_troop.php`
- `legacy/hwe/a_bestGeneral.php`
- `legacy/hwe/a_emperior.php`
- `legacy/hwe/a_emperior_detail.php`
- `legacy/hwe/a_genList.php`
- `legacy/hwe/a_hallOfFame.php`
- `legacy/hwe/a_kingdomList.php`
- `legacy/hwe/a_npcList.php`
- `legacy/hwe/a_traffic.php`
- `legacy/hwe/b_betting.php`
- `legacy/hwe/b_currentCity.php`
- `legacy/hwe/b_genList.php`
- `legacy/hwe/b_myBossInfo.php`
- `legacy/hwe/b_myCityInfo.php`
- `legacy/hwe/b_myGenInfo.php`
- `legacy/hwe/b_myKingdomInfo.php`
- `legacy/hwe/b_myPage.php`
- `legacy/hwe/b_tournament.php`

## Architecture Decisions (SPA)

- Vue 3 + `<script setup>` 기반 단일 라우터 구조
- Pinia로 세션/월드/장수/도시/알림 상태 관리
- API: tRPC client + zod 기반 타입 안전성 유지
- UI 데이터 구성은 client-driven을 기본으로 하되 숨겨야 할 정보는 서버에서 제거
- 최소 정보 공개용 public API는 서버 캐시(10분)와 함께 제공
- 한국인 사용자 대상이며, 다국어 지원은 고려하지 않음.

## Implementation Phases

### Phase 0: Discovery & Mapping

- 레거시 화면별 데이터 소스, 권한 레벨, 갱신 주기 파악
- `legacy/hwe/ts`의 컴포넌트 재사용 가능성 평가
- 화면/기능을 다음 3단계로 분류: Public / Core / Advanced
- tRPC 엔드포인트 목록과 데이터 계약 초안 작성

### Phase 1: Frontend Skeleton

- `app/game-frontend`에 Vite + Vue 3 + TS 기본 설정
- Router/Pinia/일괄 에러 처리/로딩 UI 스켈레톤 구축
- 인증 상태 전환 흐름(로그인, 장수 생성)을 위한 상태 머신 정의

### Phase 2: API Client Integration

- tRPC client 플러그인 및 요청 기본 래퍼 구성
- 요청 상관관계 `requestId` 생성 규칙 정리 (`docs/architecture/runtime.md` 참고)
- Public/Authed 라우트별 데이터 로딩 전략 정립

### Phase 3: Public Views (로그인 전/장수 미생성)

- 10분 캐시 지도/동향 화면부터 이행
- 공개 가능한 데이터만 제공하는 전용 tRPC API 추가
- 로그인/회원가입/장수 생성 진입 화면 정리

### Phase 4: Core Auth Views

- 핵심 화면 우선: 내 장수/내 도시/내 국가/세계 지도/게시판
- 상태 저장소(Pinia)를 도메인별로 분리
- 레거시 화면과 데이터 항목 매칭 후 누락 항목 체크

### Phase 5: Advanced/Peripheral Views

- 전투/외교/경매/베팅/통계/명예전당/NPC 제어 등 확장 기능
- 실시간 업데이트 필요 기능에 SSE 적용 여부 결정

### Phase 6: Hardening

- 라우트 가드, 에러 복구, 캐시/재시도 정책 확정
- 테스트(스토어 단위 + 최소 E2E 경로) 추가
- 성능 점검(맵/리스트 가상화, 이미지/아이콘 정리)

## Deliverables

- 화면 라우트 매핑 표(legacy -> SPA)
- 권한/데이터 공개 범위 명세 (Public vs Authed)
- tRPC API 스키마 초안 + 클라이언트 호출 규칙
- SPA 초기 스캐폴딩 + 핵심 화면 MVP

## Open Questions

- Public 상태 동향 범위는 캐싱된 지도, 중원 정세, 세력일람으로 제한한다. 장수일람은 실시간 제공하되 이름/NPC 여부/국가/기본 능력치만 노출한다. 그 외 장수 정보는 캐싱된 자료에 기반하며, 빈번한 접근 제한 우회를 막기 위해 캐싱 전략을 유지한다.
- UI 스타일은 당분간 \"고전 게임\" 감성을 유지한다. 전체 이식이 완료된 뒤 현대화하며, 새 UI는 Tailwind 등 CSS 라이브러리를 적극 활용한다.
- 실시간 업데이트는 메인 화면에 한정한다. 대상: 지도, 명령 목록, 현재 도시 정보, 소속 국가 정보, 장수 스탯, 장수 동향, 개인 기록, 중원 정세, 메시지함. 메인 화면에는 \"실시간 동기화 켬/끔\" 토글이 필요하다.
