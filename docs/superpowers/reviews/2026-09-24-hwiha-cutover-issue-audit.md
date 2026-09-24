# 휘하 컷오버 이슈 감사 (2026-09-24)

## 범위와 판정

작업 시작 시 GitHub 열린 이슈 89건의 제목·본문을 전수 확인했다. ADR-LITE-065와 충돌하는 삼모 기본값·제품 분기·`/hwiha` 임시 경로·수치 `PROVISIONAL`·pep 전환 시점 문구를 찾고, 목표 자체가 유효한 이슈는 기존 목표를 보존하면서 본문 맨 앞에 `2026-09-24 컷오버 반영` 블록을 넣었다. 다음 표의 18건은 GitHub와 짝인 Jira 본문을 함께 갱신했다.

| GitHub | Jira | 갱신 근거 |
|---|---|---|
| [#199](https://github.com/peppone-choi/opensamguk/issues/199) | OPENSAM-57 | 전투 replay는 휘하 제품 경로와 동결 삼모 골든의 경계를 명시 |
| [#205](https://github.com/peppone-choi/opensamguk/issues/205) | OPENSAM-63 | 세력 정체성·AI 목표를 휘하 유일 제품 규칙에 맞춤 |
| [#208](https://github.com/peppone-choi/opensamguk/issues/208) | OPENSAM-66 | 관직 권한과 제품 입력 기준을 휘하로 명시 |
| [#213](https://github.com/peppone-choi/opensamguk/issues/213) | OPENSAM-71 | 관리 UI 경로를 휘하 메인 승격 계획에 맞춤 |
| [#225](https://github.com/peppone-choi/opensamguk/issues/225) | OPENSAM-83 | 편집기 적용 범위에서 제거 예정 삼모 화면과 휘하 화면을 구분 |
| [#249](https://github.com/peppone-choi/opensamguk/issues/249) | OPENSAM-106 | 컷오버 AC를 ADR-LITE-065, B 구현, C pep 검증, 한 시즌 롤백으로 교체 |
| [#270](https://github.com/peppone-choi/opensamguk/issues/270) | OPENSAM-124 | 입력·실패 의미론을 휘하 registry 기준으로 명시 |
| [#287](https://github.com/peppone-choi/opensamguk/issues/287) | OPENSAM-141 | 결정론·장애 게이트의 제품 대상과 동결 회귀 기준을 명시 |
| [#465](https://github.com/peppone-choi/opensamguk/issues/465) | OPENSAM-205 | 지도 오버레이의 휘하 메인 경로 승격을 반영 |
| [#474](https://github.com/peppone-choi/opensamguk/issues/474) | OPENSAM-214 | 다턴 카드 이동·호송의 휘하 입력 연결을 명시 |
| [#475](https://github.com/peppone-choi/opensamguk/issues/475) | OPENSAM-215 | 省 전략 지도 작업의 지도 통일 선행 관계를 명시 |
| [#492](https://github.com/peppone-choi/opensamguk/issues/492) | OPENSAM-226 | 지리·뱃길 정본화의 제품 입력·지도 검증을 휘하 통일 지도에 맞춤 |
| [#494](https://github.com/peppone-choi/opensamguk/issues/494) | OPENSAM-228 | 지난 순 서술을 휘하 작전실의 정식 제품 흐름으로 명시 |
| [#562](https://github.com/peppone-choi/opensamguk/issues/562) | OPENSAM-231 | S3 통과, 후속 컷오버·pep 미완료를 마스터 이슈에 반영 |
| [#596](https://github.com/peppone-choi/opensamguk/issues/596) | OPENSAM-237 | 전체 지도 역사 시나리오는 S4, 컷오버 기본은 `scenario_990002`로 구분 |
| [#616](https://github.com/peppone-choi/opensamguk/issues/616) | OPENSAM-247 | 장기 시뮬레이션의 휘하 제품 규칙·동결 기준선을 명시 |
| [#779](https://github.com/peppone-choi/opensamguk/issues/779) | OPENSAM-259 | 입력 registry와 구 명령 전환 정책의 제품 기준을 갱신 |
| [#787](https://github.com/peppone-choi/opensamguk/issues/787) | OPENSAM-267 | 개인 턴 결정론 계약의 정식 상태와 미구현 차이를 연결 |

새 이슈는 세 건이다.

| GitHub | Jira | 목적 |
|---|---|---|
| [#891](https://github.com/peppone-choi/opensamguk/issues/891) | OPENSAM-290 | 컷오버 한 시즌 뒤 삼모 롤백 env 스위치 제거 |
| [#892](https://github.com/peppone-choi/opensamguk/issues/892) | OPENSAM-291 | 입력 registry 스키마·통일 결과·70개 구 명령 대응 차이 |
| [#893](https://github.com/peppone-choi/opensamguk/issues/893) | OPENSAM-292 | 개인 턴 결정론 G3·G4·G6 구현 차이 |

삼모 전용 기능으로만 남아 즉시 닫을 열린 이슈는 없었다. 옛 화면 제거는 #249의 B단계 AC이고, 별도 도메인 목표가 있는 이슈는 위처럼 전제를 수정했다. 1차 후보 #220은 이미 닫혀 있어 열린 이슈 처리 대상이 아니며, #268은 공통 인프라 계약으로 본문 수정이 필요하지 않았다. 이번 감사에서 닫은 GitHub/Jira 이슈와 상태 전이는 0건이다.

[#562의 S3 통과 코멘트](https://github.com/peppone-choi/opensamguk/issues/562#issuecomment-5805075948)와 동일한 내용을 Jira OPENSAM-231에도 남겼다. B 구현과 C pep 전환 결과는 실제 검증 뒤 #249·#562와 Jira 짝에 다시 기록한다. 현재 S3 통과만으로 컷오버 완료 처리하지 않는다.

## GitHub·Jira 대조

갱신 18건과 신규 3건의 제목·본문을 자동 대조했다. GitHub 제목에서 `[OPENSAM-n]` 접두어를 제외하고, 본문에서 Jira 변환 시 빠지는 Markdown 참조 정의(`^[label]: URL`)를 제외했다. 이후 NFKC 정규화, 영숫자만 보존, 소문자 변환을 양쪽에 동일하게 적용했다. **21건 모두 제목·본문 일치, 차이 0건**이다.
