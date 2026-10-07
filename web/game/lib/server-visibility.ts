import { GATEWAY_API_URL } from './server-api';

export async function serverVisibility(serverId: string): Promise<'public' | 'private' | 'unknown'> {
    try {
        const response = await fetch(`${GATEWAY_API_URL.replace(/\/+$/, '')}/servers`,
            { cache: 'no-store', signal: AbortSignal.timeout(5000), redirect: 'error' });
        if (!response.ok) return 'unknown';
        const list: unknown = await response.json();
        if (!Array.isArray(list) || list.some((item) => !item || typeof item.id !== 'string')) return 'unknown';
        return list.some((item) => item.id === serverId) ? 'public' : 'private';
    } catch { return 'unknown'; }
}
