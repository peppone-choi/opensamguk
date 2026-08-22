# 계정 닉네임 표시 계약

계정 닉네임은 `users.nickname`의 현재값이 정본이다. 닉네임 변경에는 쿨다운을 두지 않으며,
`POST /auth/account/nickname` 성공 응답이 새 access/refresh 토큰과 최신 사용자 응답을 함께 돌려준다.
게이트웨이 웹은 이 사용자 응답을 즉시 세션 상태에 적용하므로 헤더가 별도 `/me` 왕복 없이 바뀐다.

## 소비 표면

- 게시판 목록·상세·댓글은 `authorAccountId`로 현재 `users` 행을 조회해 현재 닉네임을 표시한다.
  글과 댓글에 저장된 `authorName`은 작성 시점 스냅샷이지만, 정상 조회의 표시값으로 쓰지 않는다.
  계정이 삭제되어 현재 행을 찾을 수 없을 때만 이 스냅샷을 역사 폴백으로 사용한다.
- 게임 랭킹은 계정 닉네임이 아니라 게임 안 장수의 `general.name`을 표시한다. 따라서 계정 닉네임
  변경이 기존 장수명이나 명예의 전당 기록을 다시 쓰지 않는 것이 의도된 동작이다.
- 계정 표시 이름이 필요한 게임 API 경로는 access JWT의 닉네임 스냅샷이 아니라 매 요청
  `users` 행을 읽어 `MemberProfile.name`을 만든다. 닉네임이 비어 있을 때만 로그인 아이디로 폴백한다.

## 회귀 증거

- `GatewayBoardPostMutationSecurityTest.author display resolves live nickname and portrait, not the write-time snapshot`
- `GatewayBoardPostMutationSecurityTest.author display falls back to the stored name when the account is gone`
- `MemberProfileTest.표시 이름은 닉네임이고, 비었으면 아이디로 떨어진다`
- `RankReadServiceTest`의 장수 랭킹 투영 테스트: 이름 원천은 `GeneralReadEntity.name`
