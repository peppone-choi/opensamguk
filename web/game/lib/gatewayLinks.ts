// 게이트웨이(로비 · 커뮤니티) 주소 — 게임 앱 밖이라 전체 페이지로 연다.
const gatewayPublicUrl = process.env.NEXT_PUBLIC_GATEWAY_URL ?? process.env.NEXT_PUBLIC_GATEWAY_ORIGIN;
const gatewayBase = gatewayPublicUrl ? gatewayPublicUrl.replace(/\/$/, '') : '';

export const LOBBY_HREF = `${gatewayBase}/lobby`;
export const COMMUNITY_HREF = `${gatewayBase}/board`;
