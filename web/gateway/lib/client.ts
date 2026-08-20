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
    nickname: string;
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

async function accountRequest(path: string, body: Record<string, unknown>, method = 'POST'): Promise<Record<string, unknown>> {
    const res = await fetch(path, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    const data = await readJson(res);
    if (!res.ok) throw new Error((data.error as string) ?? '계정 변경에 실패했습니다.');
    return data;
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await accountRequest('/api/account/password', { currentPassword, newPassword });
}

export async function updateProfileIcon(picture: string | null, imgsvr: number): Promise<User> {
    const data = await accountRequest('/api/account/profile-icon', { picture, imgsvr });
    return data as unknown as User;
}

// multipart 업로드 — Content-Type은 브라우저가 boundary와 함께 설정하게 둔다(직접 지정 금지).
// body엔 file part만 담고, 신원(Bearer)은 route proxy가 httpOnly 쿠키에서만 붙인다.
export async function uploadProfileIcon(file: File): Promise<User> {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch('/api/account/profile-icon', { method: 'POST', body: form });
    const data = await readJson(res);
    if (!res.ok) throw new Error((data.error as string) ?? '전콘 업로드에 실패했습니다.');
    return data as unknown as User;
}

export async function deleteProfileIcon(): Promise<void> {
    const res = await fetch('/api/account/profile-icon', { method: 'DELETE' });
    if (!res.ok) {
        const data = await readJson(res);
        throw new Error((data.error as string) ?? '전콘 삭제에 실패했습니다.');
    }
}

export async function deleteAccount(currentPassword: string): Promise<void> {
    await accountRequest('/api/account', { currentPassword }, 'DELETE');
}
