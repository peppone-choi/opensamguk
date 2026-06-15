# GOLDENSET — general-registration-404 (동결)

5문항 yes/no. 채점 기준: 코드/로컬 실행/프로덕션 nginx 모두 포함.

1. **서버별 장수 생성 링크**: 로비 `장수 생성` 버튼 href가 서버 식별자를 포함하는가? (예: `/game/{serverId}/join` 또는 `/game/join?server={serverId}`)
2. **404 없는 진입**: 서버별 링크로 브라우저 이동 시 Next.js/game-frontend가 404가 아닌 장수 생성 페이지를 렌더하는가?
3. **쿠키 고정**: 서버별 진입 URL 도착 후 `sam_server` 쿠키가 해당 serverId로 설정되는가?
4. **API 라우팅 일관성**: 장수 생성 페이지 내 `/api/game/*` 호출이 쿠키에 따라 올바른 game-api 서버로 프록시되는가?
5. **레거시 패러티**: devsam `entrance.ts`의 서버별 입장/등록 흐름과 동일하게 "서버 선택 → 해당 서버의 등록 페이지"로 연결되는가?
