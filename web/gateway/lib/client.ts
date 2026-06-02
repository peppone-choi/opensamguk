'use client';

import type { User } from './types';

// 브라우저용 인증 호출 — Next route handler(/api/auth/*)만 친다(gateway-api 직접 호출 X).
// 실패 시 route handler가 {error}로 내려준 한글 메시지를 throw.

async function readJson(res: Response): Promise<Record<string, unknown>> {
    return (await res.json().catch(() => ({}))) as Record<string, unknown>;
}

export async function login(username: string, password: string): Promise<User> {
    const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
    });
    const data = await readJson(res);
    if (!res.ok) throw new Error((data.error as string) ?? '로그인에 실패했습니다.');
    return data.user as User;
}

export async function register(input: {
    username: string;
    password: string;
    email?: string;
    nickname?: string;
}): Promise<User> {
    const res = await fetch('/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input),
    });
    const data = await readJson(res);
    if (!res.ok) throw new Error((data.error as string) ?? '회원가입에 실패했습니다.');
    return data.user as User;
}

export async function getCurrentUser(): Promise<User | null> {
    const res = await fetch('/api/auth/me', { cache: 'no-store' });
    const data = await readJson(res);
    return (data.user as User) ?? null;
}

export async function logout(): Promise<void> {
    await fetch('/api/auth/logout', { method: 'POST' });
}
