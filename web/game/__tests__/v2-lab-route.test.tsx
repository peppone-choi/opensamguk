import { render, screen } from '@testing-library/react';
import type { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import V2LabLayout from '@/app/game/v2-lab/layout';
import V2LabPage from '@/app/game/v2-lab/page';

// notFound()는 실제로 NEXT_HTTP_ERROR_FALLBACK digest를 던진다. digest 문자열은 Next 내부 규약이라
// 테스트가 그 형태에 결합되지 않도록 모킹하고, "호출됐는가"만 판정한다.
const notFound = vi.hoisted(() => vi.fn(() => {
    throw new Error('NEXT_NOT_FOUND');
}));

vi.mock('next/navigation', () => ({ notFound }));

// middleware는 `new NextResponse(null, { status: 404 })`를 쓰므로 생성자 형태의 모킹이 필요하다.
const nextServer = vi.hoisted(() => ({
    next: vi.fn(() => ({ cookies: { set: vi.fn() } })),
    rewrite: vi.fn(() => ({ cookies: { set: vi.fn() } })),
}));

vi.mock('next/server', () => {
    class NextResponse {
        status: number;
        cookies = { set: vi.fn() };
        static next = nextServer.next;
        static rewrite = nextServer.rewrite;
        constructor(_body: unknown, init?: { status?: number }) {
            this.status = init?.status ?? 200;
        }
    }
    return { NextResponse };
});

const original = process.env.V2_ENABLED;

describe('/game/v2-lab 네임스페이스 게이트', () => {
    beforeEach(() => {
        notFound.mockClear();
        delete process.env.V2_ENABLED;
    });

    afterEach(() => {
        if (original === undefined) delete process.env.V2_ENABLED;
        else process.env.V2_ENABLED = original;
    });

    it('V2_ENABLED 미설정이면 404 (리다이렉트·빈 페이지 아님)', () => {
        expect(() => V2LabLayout({ children: <div>v2</div> })).toThrow();
        expect(notFound).toHaveBeenCalledTimes(1);
    });

    // 프론트는 strict `=== 'true'` — 백엔드 `havingValue="true"`(equalsIgnoreCase)보다 의도적으로 엄격하다.
    // 백엔드에는 `@Profile("v2-sandbox")` 2차 조건이 있지만 프론트에는 없어, 느슨하게 맞추면 `TRUE` 오타 하나가
    // production 노출이 된다. 근거와 실측은 app/game/v2-lab/layout.tsx 주석 참조.
    it('V2_ENABLED가 true가 아닌 값이면 404 — 대소문자 변형 포함', () => {
        for (const value of ['false', '1', 'TRUE', 'True', 'tRuE', '']) {
            notFound.mockClear();
            process.env.V2_ENABLED = value;
            expect(() => V2LabLayout({ children: <div>v2</div> })).toThrow();
            expect(notFound, `V2_ENABLED=${value}`).toHaveBeenCalledTimes(1);
        }
    });

    it('V2_ENABLED=true면 자식을 렌더하고 404를 내지 않는다', () => {
        process.env.V2_ENABLED = 'true';
        render(<>{V2LabLayout({ children: <V2LabPage /> })}</>);
        expect(notFound).not.toHaveBeenCalled();
        expect(screen.getByRole('heading', { name: 'v2-lab' })).toBeInTheDocument();
    });
});

describe('middleware v2-lab 게이트 (진짜 404)', () => {
    const originalServerId = process.env.SERVER_ID;

    function makeRequest(path: string): NextRequest {
        const url = new URL(path, 'https://game.example.test');
        return {
            nextUrl: {
                pathname: url.pathname,
                searchParams: url.searchParams,
                clone: () => new URL(url.toString()),
            },
        } as unknown as NextRequest;
    }

    beforeEach(() => {
        nextServer.next.mockClear();
        nextServer.rewrite.mockClear();
        process.env.SERVER_ID = 'pep';
        delete process.env.V2_ENABLED;
    });

    afterEach(() => {
        if (originalServerId === undefined) delete process.env.SERVER_ID;
        else process.env.SERVER_ID = originalServerId;
        if (original === undefined) delete process.env.V2_ENABLED;
        else process.env.V2_ENABLED = original;
    });

    // 두 진입 형태 — 직접 경로와 serverId rewrite 경유. 후자를 원시 pathname으로 판정하면 게이트가 샌다.
    const gated = [
        '/game/v2-lab',
        '/game/v2-lab/anything',
        '/game/pep/v2-lab',
        '/game/pep/v2-lab/anything',
    ];

    it.each(gated)('V2_ENABLED 미설정이면 %s → 404 (rewrite/next 아님)', async (path) => {
        const { middleware } = await import('../middleware');
        const res = middleware(makeRequest(path)) as unknown as { status?: number };
        expect(res.status).toBe(404);
        expect(nextServer.rewrite).not.toHaveBeenCalled();
        expect(nextServer.next).not.toHaveBeenCalled();
    });

    it.each(gated)('V2_ENABLED=true면 %s는 게이트를 통과한다', async (path) => {
        process.env.V2_ENABLED = 'true';
        const { middleware } = await import('../middleware');
        const res = middleware(makeRequest(path)) as unknown as { status?: number };
        expect(res.status).toBeUndefined();
        expect(nextServer.next.mock.calls.length + nextServer.rewrite.mock.calls.length).toBe(1);
    });

    // 진짜 404를 내는 층은 미들웨어다 — 대소문자 변형이 여기서 새면 layout 게이트만으로는 status 200이 나간다.
    it.each(['TRUE', 'True', 'tRuE'])('V2_ENABLED=%s는 strict 비교라 여전히 404', async (value) => {
        process.env.V2_ENABLED = value;
        const { middleware } = await import('../middleware');
        const res = middleware(makeRequest('/game/v2-lab')) as unknown as { status?: number };
        expect(res.status).toBe(404);
        expect(nextServer.rewrite).not.toHaveBeenCalled();
        expect(nextServer.next).not.toHaveBeenCalled();
    });

    it('v2-lab이 아닌 경로는 영향받지 않는다', async () => {
        const { middleware } = await import('../middleware');
        middleware(makeRequest('/game/rankings'));
        expect(nextServer.next).toHaveBeenCalled();
    });

    // `v2-lab-x` 같은 접두 일치가 게이트에 걸리면 무관한 라우트를 죽인다.
    it('접두사만 같은 경로는 게이트 대상이 아니다', async () => {
        const { middleware } = await import('../middleware');
        middleware(makeRequest('/game/v2-lab-x'));
        expect(nextServer.next).toHaveBeenCalled();
    });
});

/**
 * 0A-a 격리가 서 있는 전제를 지킨다.
 *
 * 미들웨어 matcher는 `['/game', '/game/:path*']`뿐이라 `/_next/**` 정적 자산은 구조적으로 게이트 밖이다.
 * 그런데도 격리가 성립하는 이유는 "정적 경로도 404를 낸다"가 아니라 **게이트 밖 경로에 보호할 내용이
 * 애초에 없다**는 것이다 — v2-lab이 서버 전용이라 프로덕션 청크가 556 B 빈 스텁이다(s3b §7.4~§7.6 실측).
 *
 * `'use client'`가 붙는 순간 그 자리에 실제 v2 코드가 실리고, 그 번들은 v1 이미지에서 게이트 없이
 * 200으로 서빙된다 — 404 뒤에 숨겼다고 믿은 코드가 정적 경로로 샌다.
 *
 * 천장(정직하게): 소스 스캔이라 **직접 붙은** `'use client'`만 잡는다. v2-lab이 바깥의 클라이언트
 * 컴포넌트를 import하면 통과한다. 그것까지 잡으려면 CI에 프로덕션 빌드 + 청크 크기 기준선이 필요하다.
 */
describe('v2-lab 정적 자산 격리 전제', () => {
    it("v2-lab 라우트에 'use client'가 없다 — 있으면 s3b §7.6 판정을 다시 해야 한다", async () => {
        const { readdirSync, readFileSync } = await import('node:fs');
        const { join } = await import('node:path');

        const root = join(__dirname, '..', 'app', 'game', 'v2-lab');
        const walk = (dir: string): string[] =>
            readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
                e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)],
            );

        const files = walk(root);
        // 스캔이 빈 디렉터리를 훑고 공허하게 통과하는 것을 막는다.
        expect(files.length).toBeGreaterThan(0);

        const clientFiles = files.filter((f) => /^\s*['"]use client['"]/m.test(readFileSync(f, 'utf8')));
        expect(clientFiles).toEqual([]);
    });
});
