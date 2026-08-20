# 제품 로드맵

> 마지막 검토: 2026-08-20
> GitHub 상태는 해당 날짜에 확인했다. Jira 실시간 상태는 권한 문제로 `UNKNOWN`이다.

이 문서는 우선순위와 의존성을 보여 주는 기획 지도다. 티켓의 완료 조건이나 세부 구현 계약은 각 이슈와
승인 spec을 따른다.

## 제품 기준선

| 영역 | 상태 | 근거 | 기획 의미 |
|---|---|---|---|
| PHP 절대 패러티 종료 | 승인됨 | ADR-LITE-042, [#478](https://github.com/peppone-choi/opensamguk/issues/478) | 기존 골든은 회귀 기준선으로 보존하고 신규 설계는 오픈삼국 정본을 따른다. |
| 한나라 세계 전환 | 승인됨 | ADR-LITE-041 | 목표 데이터는 175군·780성·14지역이다. 현재 구현 완료와 동일한 뜻은 아니다. |
| CQRS daemon write 경로 | 구현됨·유지 | 아키텍처 테스트, ADR-LITE-042 | 메모리 월드와 JDBC delta flush의 단일 쓰기 경로를 유지한다. |
| 연 36순 날짜 규칙 | 구현됨 | ADR-LITE-024, 현재 UI·설정 | 실제 한 순 간격은 서버 설정을 따르며 예약과 실행 시각을 구분한다. |

## 우선순위

### P0: 운영 가능한 제품 경계

| 작업군 | 현재 판정 | 추적 | 완료 조건 |
|---|---|---|---|
| 서버 레지스트리·격리 | 진행 중 | [#452](https://github.com/peppone-choi/opensamguk/issues/452), [#466](https://github.com/peppone-choi/opensamguk/issues/466), [#467](https://github.com/peppone-choi/opensamguk/issues/467) | v1/v2와 서버별 DB·Redis·daemon·deploy 표면이 서로 오염되지 않고 복구 절차가 검증된다. |
| 운영 복구 | 진행 중 | [#477](https://github.com/peppone-choi/opensamguk/issues/477) | 메모리 한도·재시작·read barrier·복구 시나리오를 실제 운영 표면에서 검증한다. |
| 게시판 관리 | 진행 중 | [#223](https://github.com/peppone-choi/opensamguk/issues/223), [#468](https://github.com/peppone-choi/opensamguk/issues/468) | 관리자 UI와 board API가 실제 CRUD·권한·감사 경로로 닫힌다. |
| v2 관리자 표면 | 진행 중 | [#213](https://github.com/peppone-choi/opensamguk/issues/213) | 서버 선택, 권한, 위험 작업 확인, 결과 피드백이 v2에서도 일관된다. |

### P1: 하나의 디자인 시스템과 지도 경험

| 작업군 | 현재 판정 | 추적 | 기획 결정 |
|---|---|---|---|
| 디자인 방향 | 일부 승인 | [#256](https://github.com/peppone-choi/opensamguk/issues/256) | Concept A를 기본 방향으로 사용한다. |
| 공유 UI 패키지 | 진행 중 | [#258](https://github.com/peppone-choi/opensamguk/issues/258), [#470](https://github.com/peppone-choi/opensamguk/issues/470), [#472](https://github.com/peppone-choi/opensamguk/issues/472) | gateway/game/v2가 토큰·컴포넌트·접근성 기준을 공유한다. 과거 PHP 화면은 참고일 뿐 절대 정본이 아니다. |
| 한나라 지도 | 진행 중 | [#475](https://github.com/peppone-choi/opensamguk/issues/475) | 175군·780성에서 정보 계층, LOD, 색상, 선택·이동 피드백을 일관되게 제공한다. |
| 이동 체계 | 기획 진행 | [#473](https://github.com/peppone-choi/opensamguk/issues/473), [#474](https://github.com/peppone-choi/opensamguk/issues/474) | multi-turn travel을 정본화하고 `che`/`miniche` 의존을 제거한다. |

### P2: 플레이어 자기관리와 콘텐츠 확장

| 작업군 | 현재 판정 | 추적 | 완료 조건 |
|---|---|---|---|
| 닉네임 변경 | 진행 중 | [#479](https://github.com/peppone-choi/opensamguk/issues/479) | 정책, 중복 검사, 비용·쿨다운, 감사 로그와 사용자 피드백을 함께 정의한다. |
| 자원·경제 확장 | 제안 | [#457](https://github.com/peppone-choi/opensamguk/issues/457) | 기존 경제 루프와 운영 난이도를 시뮬레이션한 뒤 승인한다. |
| RTK 콘텐츠 교체 | 진행 중 | [#244](https://github.com/peppone-choi/opensamguk/issues/244) | 저작권·출처·데이터 마이그레이션·UI 문구를 함께 닫는다. |

## 이번 문서 개편에서 반영한 기획 정리

1. 사용자 매뉴얼과 관리자 매뉴얼을 현재 route와 API에 맞춘 제품 문서로 신설했다.
2. PHP 절대 패러티 표현을 현행 ADR에 맞게 “동결 회귀 기준선”으로 낮췄다.
3. 디자인 관련 #258, #470, #472를 하나의 공유 디자인 시스템 작업군으로 묶었다.
4. 161군이나 PHP 화면 절대 복제를 전제로 한 과거 티켓 문구는 ADR-LITE-041/042에 맞춰 재정리 대상이다.
5. 기능 목록은 route 존재만으로 완료 처리하지 않고 live intake·daemon·운영 결과까지 검증해야 한다.

## 티켓 운영 제안

외부 티켓은 이번 작업에서 수정하지 않았다. 다음 트리아지 때 아래를 권장한다.

- #478은 코드와 ADR 반영 여부를 확인한 뒤 상태와 라벨을 실제 완료 상태에 맞춘다.
- #472와 #475의 PHP grand truth 표현을 ADR-LITE-042 기준으로 고친다.
- 161군 전제를 가진 열린 티켓을 175군·780성 기준으로 교체하거나 supersede 관계를 남긴다.
- #258, #470, #472에 공통 디자인 토큰과 컴포넌트의 단일 소유권을 지정한다.
- GitHub 본문에 연결된 Jira 키는 Jira 원본을 조회할 수 있을 때만 상태를 동기화한다.
