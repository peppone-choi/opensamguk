# 문서 기여 가이드

## 새 문서를 어디에 둘까요?

| 내용 | 경로 |
|---|---|
| 플레이 방법·화면 안내 | `docs/user/` |
| 관리자 화면·운영·복구 | `docs/admin/` |
| 제품 방향·로드맵·정본 지도 | `docs/design/` |
| 승인된 상세 계약 | `docs/superpowers/specs/` |
| 실행 계획 | `docs/superpowers/plans/` |
| 조사·측정 | `docs/superpowers/research/` |
| 검토 결과 | `docs/superpowers/reviews/` |
| 반복 실험 원장 | `docs/loops/<task>/` |

기존 `superpowers`와 `loops` 문서는 path:line 증거로 쓰이므로 문서 정리를 이유로 대규모 이동하지 않습니다.

## 머리말에 남길 정보

현재 상태나 외부 사실을 설명하는 문서는 본문 위에 다음 정보를 적습니다.

```md
> 상태: 현재 / 기획 / 이력 / 참고
> 마지막 검토: YYYY-MM-DD
> 근거: 코드 경로, ADR, GitHub/Jira 링크
```

Jira를 직접 확인하지 못했다면 `Jira 상태: UNKNOWN`이라고 씁니다. GitHub 미러 상태로 Jira 상태를 추정하지
않습니다.

## 내용 규칙

- `현재`: 코드와 실행 가능한 표면에서 확인된 내용만 씁니다.
- `기획`: 승인된 ADR/스펙과 아직 선택되지 않은 제안을 분리합니다.
- 외부 레포 문장은 그대로 복사하지 않고 현재 Kotlin/Spring/Next.js 구조에 맞춰 검증합니다.
- 명령, URL, 권한, 데이터 삭제 범위를 실제 코드 또는 운영 정본과 대조합니다.
- 테스트 숫자와 배포 상태는 빠르게 낡으므로 정본 ledger나 이슈로 링크하고, 문서에는 확인 날짜를 둡니다.
- 비밀값은 변수 이름만 적고 값, 토큰, 내부 URL의 credential은 적지 않습니다.

## 변경 시 함께 볼 문서

| 변경 | 같이 갱신할 곳 |
|---|---|
| 로그인·가입·계정 | `docs/user/getting-started.md`, `docs/admin/member-and-game-management.md` |
| 게임 메뉴·route | `docs/user/features.md` |
| 턴·명령 판정 | `docs/user/gameplay-guide.md` |
| 관리자 화면/API | `docs/admin/` 전체의 관련 표 |
| 배포·서버 생명주기 | `README.md`, `docs/admin/server-lifecycle.md`, `docs/admin/operations-and-recovery.md` |
| 제품 정본·ADR | `docs/design/README.md`, `docs/design/roadmap.md`, `docs/design/v1-v2-boundary.md` |

## 최소 검증

문서만 바꿔도 다음을 확인합니다.

```bash
git diff --check
```

코드 변경은 영향받는 모듈의 테스트와 빌드를 함께 실행합니다.

링크 검사는 저장소 안의 상대 Markdown 링크가 실제 파일을 가리키는지 확인해야 합니다. 외부 링크는 네트워크나
권한으로 확인하지 못할 수 있으므로 실패 원인을 결과에 남깁니다. 문서 변경만으로 백엔드·프론트 테스트를
실행했다고 주장하지 않습니다.
