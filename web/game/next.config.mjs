/** @type {import('next').NextConfig} */
const nextConfig = {
    output: 'standalone',
    reactStrictMode: true,
    // 공유 도메인(sam.peppone.dev)에서 gateway-frontend와 `/_next` 에셋 경로가 충돌한다(둘 다 Next 앱,
    // 같은 /_next). 게임 에셋만 구분 경로(`/game/_next/...`)로 내보내 nginx가 game-frontend로 보낼 수
    // 있게 한다. 라우트(app/game/* → /game)는 그대로 — assetPrefix는 에셋 URL만 바꾼다(basePath 아님).
    // 미설정(로컬 dev, web-game :3001 직접 접속)이면 기본 `/_next`. prod 빌드만 ASSET_PREFIX=/game.
    assetPrefix: process.env.ASSET_PREFIX || undefined,
};

export default nextConfig;
