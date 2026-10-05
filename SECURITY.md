# 보안 정책

> **Reporting a vulnerability:** please do not open a public issue, pull request or discussion.
> Email **peppone.choi@gmail.com** with the details below. We will acknowledge your report and keep you informed.

## 취약점 알리기

보안 취약점은 **공개 이슈 · PR · 토론에 올리지 마세요.** 아래 메일로 알려 주세요.

- 메일: **peppone.choi@gmail.com**
- 담아 주실 것:
  - 영향 받는 곳(화면 경로 · API 경로 · 파일)과 판(커밋 또는 날짜)
  - 재현 절차
  - 예상되는 영향
  - 공개 감사 인사에 넣을 이름(원하시는 경우)

받은 알림은 확인했다는 답을 먼저 드리고, 고치는 동안 진행 상황을 알려 드립니다.
고친 판이 운영 서버에 반영되기 전에는 내용을 공개하지 말아 주세요.

## 대상

- 이 저장소의 코드(`app/**`, `web/**`, `common`, `logic`, `infra`, `tools/**`)와 CI 설정
- 이 코드로 운영하는 공개 게임 서버
- 배포 구성 저장소 [opensamguk-docker](https://github.com/peppone-choi/opensamguk-docker)

다음은 하지 말아 주세요.

- 서비스를 느리게 하거나 멈추게 하는 시험(대량 요청 · 부하 시험)
- 다른 사람의 계정 · 개인 정보 · 게임 자료에 접근하거나 바꾸는 시험
- 운영자 · 사용자를 상대로 한 사회공학

## 고치는 판

`main` 브랜치와 지금 운영 중인 공개 서버만 고칩니다. 지난 판은 따로 고치지 않습니다.

## 비밀값

비밀값(키 · 토큰 · 비밀번호 · 운영 `.env`)은 저장소에 커밋하지 않습니다. 저장소나 이력에서 비밀값처럼 보이는 것을
발견하면 공개 이슈 대신 위 메일로 알려 주세요.
