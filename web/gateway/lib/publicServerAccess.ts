import { NextResponse } from 'next/server';
import { readPublicServers } from '@/lib/serverPublication';

/** Every request checks current visibility; a stale list cannot authorize a feed. */
export async function publicServerAccess(id: string): Promise<NextResponse | null> {
    const list = await readPublicServers();
    const status = list.kind === 'unknown' ? 503 : list.servers.some((server) => server.id === id) ? 0 : 404;
    return status ? NextResponse.json({ error: { code: status === 503 ? 'SERVER_LIST_UNAVAILABLE' : 'SERVER_NOT_FOUND' } },
        { status, headers: { 'Cache-Control': 'no-store' } }) : null;
}
