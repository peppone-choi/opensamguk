import serversData from '@/config/servers.json';
import { fallbackGameUrlForServer, isPathServerId, resolveServerGameBase } from '@/lib/serverGameUrl';

export interface ServerEntry {
    id: string;
    name: string;
    generation?: number;
    gameUrl?: string;
    gameApiUrl?: string;
}

type RuntimeEntries =
    | { configured: false; entries: ServerEntry[] }
    | { configured: true; entries: ServerEntry[] };

const BAKED = parseEntries(serversData.servers) ?? [];

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function expectedGameApiUrl(id: string): string {
    return `http://s${id}-game-api:8081`;
}

function parseEntries(value: unknown): ServerEntry[] | undefined {
    if (!Array.isArray(value)) return undefined;
    const entries: ServerEntry[] = [];
    const seenIds = new Set<string>();
    const append = (id: string, entry: unknown): boolean => {
        const normalized = normalizeServerEntry(id, entry);
        if (!normalized || seenIds.has(id)) return false;
        seenIds.add(id);
        entries.push(normalized);
        return true;
    };

    for (const entry of value) {
        if (!isRecord(entry) || typeof entry.id !== 'string' || !append(entry.id, entry)) return undefined;
    }
    return entries;
}

function runtimeEntries(): RuntimeEntries {
    const raw = process.env.SERVER_REGISTRY_JSON;
    if (!raw || raw.trim() === '') return { configured: false, entries: [] };
    try {
        const parsed: unknown = JSON.parse(raw);
        return { configured: true, entries: parseEntries(parsed) ?? [] };
    } catch {
        return { configured: true, entries: [] };
    }
}

function normalizeServerEntry(id: string, value: unknown): ServerEntry | undefined {
    if (!isPathServerId(id)) return undefined;
    const entry = value;
    if (!isRecord(entry)) return undefined;
    if ('id' in entry && (typeof entry.id !== 'string' || entry.id !== id)) return undefined;

    const expectedApiUrl = expectedGameApiUrl(id);
    if (
        'gameApiUrl' in entry &&
        (typeof entry.gameApiUrl !== 'string' || entry.gameApiUrl !== expectedApiUrl)
    ) {
        return undefined;
    }
    const rawName = entry.name;
    const rawGameUrl = entry.gameUrl;
    if (rawName !== undefined && typeof rawName !== 'string') return undefined;
    if (rawGameUrl !== undefined && typeof rawGameUrl !== 'string') return undefined;

    const name = typeof rawName === 'string' ? rawName.trim() || id : id;
    const gameUrl = typeof rawGameUrl === 'string' ? rawGameUrl.trim() || undefined : undefined;
    const generation = parseGeneration(entry.generation);
    if ('generation' in entry && generation === undefined) return undefined;

    return {
        id,
        name,
        generation,
        gameUrl: resolveServerGameBase(gameUrl, id, fallbackGameUrlForServer(id)),
        gameApiUrl: expectedApiUrl,
    };
}

function parseGeneration(value: unknown): number | undefined {
    if (typeof value === 'number' && Number.isFinite(value)) return Math.trunc(value);
    if (typeof value === 'string' && value.trim()) {
        const parsed = Number.parseInt(value, 10);
        if (Number.isFinite(parsed)) return parsed;
    }
    return undefined;
}

export function getServers(): ServerEntry[] {
    const runtime = runtimeEntries();
    return runtime.configured ? runtime.entries : BAKED;
}

export function getServer(id: string): ServerEntry | undefined {
    return getServers().find((server) => server.id === id);
}

export function resolveGameApiOrigin(id: string): string | undefined {
    return getServer(id)?.gameApiUrl;
}
