# 미사용 서버 API 은퇴

GH #917, 메타 API 계약판 K9-01의 웹 호출 없는 경로를 제거한다. 새 화면이 대체해야 할 경로는 후속 API와 화면이 병합된 뒤 은퇴한다.

## 제거 경로

| 컨트롤러 | 제거 경로 |
|---|---|
| GlobalMenu | `/api/global-menu` |
| InheritPoint | `/api/inherit-point` |
| InstantAction | `/api/instant-action/{code}` |
| NationFinance | `/api/nation/{id}/finance` |
| NpcPolicy | `/api/nation/npc-policy` |
| Possession | `/api/generals/claimable`, `/api/general/claim` |
| Retinue | `/api/my-retinue`, `/api/generals/{id}/retinue` |
| SelectPool | `/api/select-pool`, `/api/select-pool/refresh` |
| Simulator | `/api/simulate-battle` |
| Vote | `/api/votes`, `/api/votes/{id}` |
| Battlefield | `/api/battlefields` |
| Ranking 일부 | `/api/rankings/{generals,npcs,hall-of-fame,traffic,emperor}`, `/api/rankings/emperor/{id}` |
| My 일부 | `/api/my-boss` |

부(府) 조회 `/api/retinue`의 부곡 응답은 `CampReader`에서 같은 규칙으로 만든다. 샌드박스 도시 명령 접수 응답은 독립 DTO로 옮겨 폐기 컨트롤러 의존을 없앴다.

## 후속 경계

- `/api/command/push`는 승인된 12순 당기기·밀기의 대체 `/api/turn-slots/shift`가 구현되기 전 유지한다(계약판 §2e A2).
- `/api/city/{id}`, `/api/generals`, `/api/front-info`와 기존 전투 리플레이는 새 API·화면 대체 전 유지한다.
- `/api/map/ju`는 C9 사용 여부 확인이 필요하다.
- 엔진 처리기·DB 원장·현행 저장 형식은 이번 API 제거에서 바꾸지 않는다. 운영 반영·데이터 변경은 별도 작업이다.

## 검증

실제 Spring Boot 컨텍스트의 경로 등록 검사에서 제거 경로 22개가 없는지 확인하고, `/api/retinue`, `/api/command/push`, 기존 전투 리플레이·도시·인물 조회가 등록되는지 함께 검사한다. `CampReaderTest`는 이동한 부곡 투영을 검증한다. 로컬 Docker·Gradle 슬롯 없이 통합 검사 실행 여부를 통과로 표시하지 않으며 PR CI 증거를 따른다.
