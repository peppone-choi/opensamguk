import { GATEWAY_API_URL } from '@/lib/server-api';

export async function GET(_req: Request, context: { params: Promise<{ picture: string; variant: string }> }) {
    const { picture, variant } = await context.params;
    if (!/^[0-9a-f]{8}\.portrait$/.test(picture) || !/^(hero|card|icon)\.jpg$/.test(variant)) return new Response(null, { status: 404 });
    try {
        const upstream = await fetch(`${GATEWAY_API_URL}/profile-icons/${picture}/${variant}`, { signal: AbortSignal.timeout(10_000), cache: 'no-store' });
        if (!upstream.ok) return new Response(null, { status: upstream.status === 404 ? 404 : 502 });
        return new Response(upstream.body, { headers: { 'Content-Type': 'image/jpeg', 'X-Content-Type-Options': 'nosniff', 'Cache-Control': 'public, max-age=86400' } });
    } catch { return new Response(null, { status: 502 }); }
}
