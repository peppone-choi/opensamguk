import { NextResponse } from 'next/server';
import { getServers } from '@/lib/serverRegistry';

export const dynamic = 'force-dynamic';

export async function GET() {
    const servers = getServers().map(({ id, name, gameUrl }) => ({ id, name, gameUrl }));
    return NextResponse.json({ servers });
}
