// nginx 없이 게임(:3001)만 따로 띄운 로컬 개발 — 상태 띠(useTurnLoop)가 읽는 /api/server-basic-info 도 게이트웨이로 넘긴다.
import { afterEach, describe, expect, it, vi } from 'vitest';

// Sentry 감싸기는 rewrites 를 바꾸지 않는다 — 무거운 모듈을 싣지 않게 그대로 돌려준다(부하 때 5초 한도를 넘었다).
vi.mock('@sentry/nextjs', () => ({ withSentryConfig: (config: unknown) => config }));

afterEach(() => { vi.unstubAllEnvs(); vi.resetModules(); });

async function rewrites() {
    const { default: config } = await import('../next.config.mjs');
    if (!config.rewrites) throw new Error('rewrites 없음');
    return (await config.rewrites()) as { source: string; destination: string }[];
}

describe('web/game rewrites — 게이트웨이로 넘기는 경로', () => {
    it('server-basic-info 를 GATEWAY_WEB_URL 로 넘긴다', async () => {
        vi.stubEnv('GATEWAY_WEB_URL', 'http://gw.test:3000');
        expect(await rewrites()).toContainEqual({
            source: '/api/server-basic-info/:path*',
            destination: 'http://gw.test:3000/api/server-basic-info/:path*',
        });
    });

    it('GATEWAY_WEB_URL 이 없으면 로컬 게이트웨이(:3000), /api/game 규칙은 그대로', async () => {
        vi.stubEnv('GATEWAY_WEB_URL', '');
        const list = await rewrites();
        expect(list).toContainEqual({ source: '/api/server-basic-info/:path*', destination: 'http://localhost:3000/api/server-basic-info/:path*' });
        expect(list).toContainEqual({ source: '/api/game/:path*', destination: 'http://localhost:3000/api/game/:path*' });
    });
});
