// 운영 콘솔 「공지」 API 클라이언트 — gateway-api /admin/notices(ROLE_ADMIN)를 /api/proxy 로 부른다.
// 화면은 이 파일을 직접 부르지 않고 useAdminNotices(lib/use-admin-notices.ts)를 쓴다(D105 층: 화면 → 훅 → API 클라이언트).
// 실패는 서버 문구(message · error)를 담은 Error 로 던진다. 없으면 「요청 실패 (상태)」.
import type { Notice } from './notices';

interface NoticeDraft {
    readonly title: string;
    readonly body: string;
    readonly pinned: boolean;
}

async function adminJson<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await fetch(`/api/proxy/admin/notices${path}`, {
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json' },
        ...init,
    });
    if (!res.ok) {
        let message = `요청 실패 (${res.status})`;
        try {
            const body = (await res.json()) as { message?: string; error?: string };
            message = body.message ?? body.error ?? message;
        } catch {
            /* keep default */
        }
        throw new Error(message);
    }
    return (await res.json()) as T;
}

/** 공지 목록(삭제분 포함). */
export async function listAdminNotices(): Promise<Notice[]> {
    const data = await adminJson<{ notices: Notice[] }>('');
    return data.notices;
}

export function createAdminNotice(draft: NoticeDraft): Promise<Notice> {
    return adminJson<Notice>('', { method: 'POST', body: JSON.stringify(draft) });
}

export function updateAdminNotice(id: number, draft: NoticeDraft): Promise<Notice> {
    return adminJson<Notice>(`/${id}`, { method: 'PUT', body: JSON.stringify(draft) });
}

export function setAdminNoticePinned(id: number, pinned: boolean): Promise<Notice> {
    return adminJson<Notice>(`/${id}/pin`, { method: 'PATCH', body: JSON.stringify({ pinned }) });
}

/** soft-delete — 목록에는 「삭제됨」으로 남는다. */
export function deleteAdminNotice(id: number): Promise<Notice> {
    return adminJson<Notice>(`/${id}`, { method: 'DELETE' });
}
