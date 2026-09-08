// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest';
let access: string | undefined = 'test-access';
vi.mock('next/headers', () => ({ cookies: async () => ({ get: () => access ? { value: access } : undefined }) }));
import { GET as source } from '@/app/api/account/profile-icon/source/route';
import { GET as crops } from '@/app/api/account/profile-icon/crops/route';
import { GET as variant } from '@/app/profile-icons/[picture]/[variant]/route';

describe('portrait source and public variant proxies', () => {
    beforeEach(() => { access = 'test-access'; vi.stubGlobal('fetch', vi.fn()); });
    it('requires login before reading original or crop metadata', async () => {
        access = undefined;
        expect((await source()).status).toBe(401);
        expect((await crops()).status).toBe(401);
        expect(fetch).not.toHaveBeenCalled();
    });
    it('preserves original bytes and matching identity, but never caches private data', async () => {
        vi.mocked(fetch).mockResolvedValue(new Response(new Uint8Array([1, 2, 3]), { headers: { 'Content-Type': 'image/png', 'X-Portrait-Id': 'aabbccdd.portrait', 'Cache-Control': 'public' } }));
        const response = await source();
        expect(response.headers.get('Cache-Control')).toBe('private, no-store');
        expect(response.headers.get('X-Portrait-Id')).toBe('aabbccdd.portrait');
        expect([...new Uint8Array(await response.arrayBuffer())]).toEqual([1, 2, 3]);
        expect(fetch).toHaveBeenCalledWith('http://localhost:8080/auth/account/profile-icon/source', expect.objectContaining({ headers: { Authorization: 'Bearer test-access' }, cache: 'no-store' }));
    });
    it('does not accept public source, archive or traversal requests', async () => {
        for (const [picture, name] of [['aabbccdd.portrait', 'source.jpg'], ['../aabbccdd.portrait', 'icon.jpg'], ['aabbccdd.jpg', 'hero.jpg']]) {
            expect((await variant(new Request('http://localhost'), { params: Promise.resolve({ picture, variant: name }) })).status).toBe(404);
        }
        expect(fetch).not.toHaveBeenCalled();
    });
    it('only serves a JPEG variant and does not forward authentication publicly', async () => {
        vi.mocked(fetch).mockResolvedValue(new Response(new Uint8Array([1]), { headers: { 'Content-Type': 'image/jpeg' } }));
        const response = await variant(new Request('http://localhost'), { params: Promise.resolve({ picture: 'aabbccdd.portrait', variant: 'icon.jpg' }) });
        expect(response.status).toBe(200);
        expect(response.headers.get('X-Content-Type-Options')).toBe('nosniff');
        expect(vi.mocked(fetch).mock.calls[0][1]?.headers).toBeUndefined();
    });
});
