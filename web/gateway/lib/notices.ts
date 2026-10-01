// 공지(gateway-api /notices) 클라이언트 계약. 본문은 평문 — 렌더는 pre-line.
export interface Notice {
    readonly id: number;
    readonly title: string;
    readonly body: string;
    readonly pinned: boolean;
    readonly publishedAt: string;
    readonly deleted: boolean;
}

function isNotice(value: unknown): value is Notice {
    if (typeof value !== 'object' || value === null) return false;
    const v = value as Record<string, unknown>;
    return typeof v.id === 'number' && typeof v.title === 'string' && typeof v.body === 'string' && typeof v.pinned === 'boolean' && typeof v.publishedAt === 'string';
}

/** 공개 공지 목록. 실패하면 null(화면은 「공지 없음」이 아니라 「불러올 수 없음」으로 구분한다). */
export async function fetchNotices(): Promise<Notice[] | null> {
    try {
        const res = await fetch('/api/notices', { cache: 'no-store' });
        if (!res.ok) return null;
        const data = (await res.json()) as { notices?: unknown };
        if (!Array.isArray(data.notices)) return null;
        return data.notices.filter(isNotice);
    } catch {
        return null;
    }
}

/** `2026-09-05T…` → `09.05` (시안 표기). 잘못된 값은 빈 문자열. */
export function formatNoticeDate(iso: string): string {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '';
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${mm}.${dd}`;
}
