# 2026-09-26 웹 화면 문구 lint

## 범위

- ADR-LITE-049 2026-09-26 개정 「표기」(한글 우선, 금·쌀, 화면에 사료 원문 없음)와 ADR-LITE-057 「부」 개정을 CI로 지킨다.
- 대상은 `web/game`·`web/gateway`·`web/shared`의 `.ts`·`.tsx`다. 테스트·e2e·`.d.ts`·`public`은 뺀다. 주석은 화면 문구가 아니라 뺀다.
- `che_`는 기존 `naming_lint.py`가 web 까지 이미 센다. 이 lint는 겹치지 않게 화면 문구만 본다.

## 규칙

| 종류 | 세는 것 | 고칠 말 |
|---|---|---|
| `hanja` | 한자가 이어진 한 덩이 | 縣→현, 郡→군, 城→성, 省→구역, 年月→숫자·한글. 상세 머리 병기 한 번은 허용 목록으로 |
| `retired_term` | 숙련전환·군량매매·자금·군량·병량·국고·세율·빙의·삭턴·벌점·휘하 | 금·쌀·부, 삼모 개념은 화면째 삭제 |

- 기준선(`tools/ci/web_copy_lint_baseline.json`)은 래칫이다. 늘면 실패하고, 줄면 기준선을 낮추라며 실패한다.
- 허용 목록(`tools/ci/web_copy_lint_allowlist.json`)은 파일·종류·이유가 있어야 한다. 처음 두 파일은 동명 현 한자 병기 생성물과 데이터 이름 정규화 표다.

## 검증

- `python3 -m unittest discover -s tools/ci -p 'test_web_copy_lint.py'` 10/10. 적색 프로브: JSX·문자열·템플릿의 한자, 은퇴 용어, 기준선 초과·미달.
- 실제 파일 적색 프로브: `web/game/lib/types.ts`에 위반 한 줄을 넣으면 두 종류 모두 FAIL(66→67, 92→93), 되돌리면 통과.
- `python3 tools/ci/naming_lint.py` 4종 기준선과 같음.

## 남은 것

- 기준선 한자 66·은퇴 용어 92는 화면 재구축(F2~F7)에서 줄인다. 섬 지도 배지 한자(`cityBadgeLayer.ts`)는 탑다운 렌더러로 바꿀 때 없어진다.
- 원장 표시 이름(숙련전환·군량매매)은 `data/commands/input-catalog.json` 쪽이라 이 lint 밖이다. 원장 소유 레인이 고친다.
