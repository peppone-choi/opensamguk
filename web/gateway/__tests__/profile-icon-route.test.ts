// @vitest-environment node
// route handler는 서버(Node)에서 실행된다. jsdom의 FormData는 undici Request가 multipart로
// 직렬화하지 못해(→ text/plain) 라운드트립이 깨지므로, undici 글로벌이 일관된 node 환경에서 돈다.
import { beforeEach, describe, expect, it, vi } from 'vitest';

// route handler는 httpOnly 쿠키에서만 Bearer를 파생한다 — next/headers.cookies()를 제어한다.
let cookieValue: string | undefined;
vi.mock('next/headers', () => ({
    cookies: async () => ({ get: (name: string) => (name === 'sam_access' && cookieValue ? { value: cookieValue } : undefined) }),
}));

import { DELETE, POST } from '@/app/api/account/profile-icon/route';

const ICON_URL = 'http://localhost:8080/auth/account/profile-icon';
const TOKEN = 'test-access-token';

function multipartRequest(form: FormData): Request {
    return new Request('http://localhost:3000/api/account/profile-icon', { method: 'POST', body: form });
}

function fileForm(extra: Record<string, string> = {}): FormData {
    const form = new FormData();
    form.append('file', new File([new Uint8Array(2048)], 'icon.png', { type: 'image/png' }));
    for (const [k, v] of Object.entries(extra)) form.append(k, v);
    return form;
}

function lastFetchInit(): RequestInit {
    return (fetch as unknown as { mock: { calls: [string, RequestInit][] } }).mock.calls.at(-1)![1];
}

describe('profile-icon route proxy', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        cookieValue = TOKEN;
    });

    it('forwards multipart upload with a server-side Bearer and returns the canonical response without the token', async () => {
        const canonical = { id: 1, username: 'tester', picture: 'a1b2c3d4.png', imageServer: 1 };
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
            new Response(JSON.stringify(canonical), { status: 200, headers: { 'Content-Type': 'application/json' } }),
        ));

        const res = await POST(multipartRequest(fileForm()));

        expect(fetch).toHaveBeenCalledTimes(1);
        const [url, init] = (fetch as unknown as { mock: { calls: [string, RequestInit][] } }).mock.calls[0];
        expect(url).toBe(ICON_URL);
        expect(init.method).toBe('POST');
        expect((init.headers as Record<string, string>).Authorization).toBe(`Bearer ${TOKEN}`);
        // Content-Type을 직접 지정하지 않는다 — fetch가 boundary와 함께 설정하도록 둔다.
        expect((init.headers as Record<string, string>)['Content-Type']).toBeUndefined();
        expect(init.body).toBeInstanceOf(FormData);

        expect(res.status).toBe(200);
        const bodyText = await res.text();
        expect(bodyText).toContain('a1b2c3d4.png');
        expect(bodyText).not.toContain(TOKEN);
    });

    it('forwards only the file part, dropping any browser-injected identity fields', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 200 })));

        await POST(multipartRequest(fileForm({ userId: '999', imgsvr: '1', picture: '../evil.png', url: 'http://x' })));

        const forwarded = lastFetchInit().body as FormData;
        expect([...forwarded.keys()]).toEqual(['file']);
        expect(forwarded.get('file')).toBeInstanceOf(File);
    });

    it('rejects a multipart upload without an auth cookie and never calls upstream', async () => {
        cookieValue = undefined;
        vi.stubGlobal('fetch', vi.fn());

        const res = await POST(multipartRequest(fileForm()));

        expect(res.status).toBe(401);
        expect(fetch).not.toHaveBeenCalled();
    });

    it('rejects an empty multipart body before contacting upstream', async () => {
        vi.stubGlobal('fetch', vi.fn());
        const res = await POST(multipartRequest(new FormData()));
        expect(res.status).toBe(400);
        expect(fetch).not.toHaveBeenCalled();
    });

    it('translates the upstream 409 ApiError message into the {error} contract, preserving the status', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
            JSON.stringify({ message: '프로필 아이콘은 하루에 한 번만 변경할 수 있습니다.', status: 409 }),
            { status: 409, headers: { 'Content-Type': 'application/json' } },
        )));

        const res = await POST(multipartRequest(fileForm()));

        expect(res.status).toBe(409);
        expect(await res.json()).toEqual({ error: '프로필 아이콘은 하루에 한 번만 변경할 수 있습니다.' });
    });

    it('returns a safe 502 when the gateway is unreachable', async () => {
        vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));

        const res = await POST(multipartRequest(fileForm()));

        expect(res.status).toBe(502);
        expect(await res.json()).toEqual({ error: '게이트웨이에 연결할 수 없습니다.' });
    });

    it('forwards a DELETE with the server-side Bearer and reports success', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));

        const res = await DELETE();

        const [url, init] = (fetch as unknown as { mock: { calls: [string, RequestInit][] } }).mock.calls[0];
        expect(url).toBe(ICON_URL);
        expect(init.method).toBe('DELETE');
        expect((init.headers as Record<string, string>).Authorization).toBe(`Bearer ${TOKEN}`);
        expect(res.status).toBe(200);
        expect(await res.json()).toEqual({ deleted: true });
    });

    it('rejects a DELETE without an auth cookie and never calls upstream', async () => {
        cookieValue = undefined;
        vi.stubGlobal('fetch', vi.fn());

        const res = await DELETE();

        expect(res.status).toBe(401);
        expect(fetch).not.toHaveBeenCalled();
    });
});
