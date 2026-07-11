# GOLDENSET — live-gap-closure-2026-07-10

## Mode

라이브 무동작·PHP 로직 불일치·5능력치 완결 (기존 골든과 repo gate가 시험지)

## 결정적 채점자

1. `tools/parity/gate.sh backend` — `BUILD SUCCESSFUL`과 XML failures/errors 0
2. 변경 영역의 집중 테스트 — 월간 훅, 명령 intake/dispatch, 대회 상태기계, 장수 생성·시드
3. `web/game` typecheck/test와 Playwright 실제 클릭·network·console 관측
4. PHP grand truth의 순서·조건·로그·부수효과를 보존하고 기존 골든/assert를 약화하지 않음
5. `tools/agent-system/check.py --strict --base origin/main`

## 라이브 합격 조건

- 서버가 수락한 명령은 `미구현`, unsupported deny, 빈 callback으로 끝나지 않는다.
- 화면의 활성 버튼은 실제 API 요청 또는 의도한 이동을 수행한다.
- 월간/매턴 순환에서 PHP가 호출하는 게임 로직을 Kotlin 데몬도 같은 시점과 순서에 호출한다.
- 대회 시작·진행·종료·초기화가 영속 상태와 UI에서 관측된다.
- NPC 정치·매력은 외부 RTK14 sidecar에서 시나리오 장수에 결정적으로 매칭된다.
- 유저 장수 생성은 통솔·무력·지력·정치·매력 5개 값을 저장하며 총 능력치 상한도 5능력치 기준이다.
- raw XLSX와 생성된 RTK14 데이터는 커밋하지 않는다.

## 배포 채점자

- PR merge to `main`
- 전체 production 재기동과 필요 시 게임 서버 reset/reseed
- `/health`, gateway/game actuator, `/game/s1` 정상
- 실제 라이브 버튼/API 흐름 재검증
- 옵시디언 진행 기록에 PR·배포·라이브 증거 갱신

## 제외

- 의도적으로 stateless인 배포 구조나 DB 보존 정책 자체의 변경
- PHP에 없는 신규 전투/RNG 공식 발명
- XLSX 원본 또는 파생된 코에이 데이터의 git 추적
