// gateway-api(:8080)에 도달하는 서버 전용 헬퍼.
// route handler는 Next 서버에서 실행 → 브라우저와 동일 출처이므로 CORS가 개입하지 않는다.
// 브라우저는 gateway-api를 직접 호출하지 않는다(httpOnly 토큰을 노출하지 않기 위함).
// 배포 compose 관례는 `GATEWAY_API_ORIGIN`(게임 프론트의 `GAME_API_ORIGIN`과 짝) — 이름 불일치로
// 로그인 route handler가 localhost:8080으로 폴백해 ECONNREFUSED(500)났던 prod 회귀를 막기 위해
// GATEWAY_API_URL → GATEWAY_API_ORIGIN 순으로 읽는다.
export const GATEWAY_API_URL =
    process.env.GATEWAY_API_URL ?? process.env.GATEWAY_API_ORIGIN ?? 'http://localhost:8080';
