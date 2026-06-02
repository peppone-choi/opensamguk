import { NextResponse } from 'next/server';
import { clearAuthCookies } from '@/lib/cookies';

export async function POST() {
    const res = NextResponse.json({ ok: true });
    clearAuthCookies(res);
    return res;
}
