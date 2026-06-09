import serversData from '@/config/servers.json';

export interface ServerEntry {
    id: string;
    name: string;
    gameUrl?: string;
    gameApiUrl?: string;
}

const BAKED = ((serversData.servers as unknown[]) ?? [])
    .map((entry) => normalizeServerEntry(entry as Record<string, unknown>))
    .filter((s) => s.id);

function runtimeEntries(): ServerEntry[] {
    const raw = process.env.SERVER_REGISTRY_JSON;
    if (!raw) return [];
    try {
        const parsed = JSON.parse(raw);
        const out: ServerEntry[] = [];
        if (Array.isArray(parsed)) {
            for (const e of parsed) {
                if (e && typeof e.id === 'string') {
                    out.push(normalizeServerEntry(e as Record<string, unknown>));
                }
            }
        } else if (parsed && typeof parsed === 'object') {
            for (const [id, v] of Object.entries(parsed)) {
                if (typeof v === 'string') {
                    out.push(normalizeServerEntry({ id, gameApiUrl: v }));
                } else if (v && typeof v === 'object') {
                    out.push(normalizeServerEntry({ id, ...(v as Record<string, unknown>) }));
                }
            }
        }
        return out.filter((s) => s.id);
    } catch {
        return [];
    }
}

function normalizeServerEntry(entry: Record<string, unknown>): ServerEntry {
    const id = typeof entry.id === 'string' ? entry.id.trim() : '';
    const name = typeof entry.name === 'string' && entry.name.trim() ? entry.name.trim() : id;
    const fallbackGameUrl = /^s[A-Za-z0-9_-]*$/.test(id) ? `/game/${id}` : `/game?server=${encodeURIComponent(id)}`;
    const gameUrl = typeof entry.gameUrl === 'string' && entry.gameUrl.trim() ? entry.gameUrl.trim() : fallbackGameUrl;
    const gameApiUrl =
        typeof entry.gameApiUrl === 'string' && entry.gameApiUrl.trim() ? entry.gameApiUrl.trim() : undefined;
    return { id, name, gameUrl, gameApiUrl };
}

export function getServers(): ServerEntry[] {
    const runtime = runtimeEntries();
    return runtime.length > 0 ? runtime : BAKED;
}

export function getServer(id: string): ServerEntry | undefined {
    return getServers().find((s) => s.id === id);
}

export function resolveGameApiOrigin(id: string): string | undefined {
    const server = getServer(id);
    if (server?.gameApiUrl) return server.gameApiUrl;
    const defaultID = getServers()[0]?.id;
    if (id === defaultID && process.env.GAME_API_ORIGIN) return process.env.GAME_API_ORIGIN;
    return undefined;
}
