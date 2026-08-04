const PATH_SERVER_ID = /^[a-z0-9]{1,48}$/;
const RESERVED_PATH_SERVER_IDS = new Set([
  'all',
  'main',
  'admin1',
  'admin2',
  'admin5',
  'admin7',
  'admin8',
  'auction',
  'battle-center',
  'betting',
  'board',
  'chief-center',
  'city',
  'coming-soon',
  'diplomacy',
  'generals',
  'global-diplomacy',
  'history',
  'inherit',
  'join',
  'mailbox',
  'map',
  'my',
  'my-boss',
  'my-cities',
  'my-generals',
  'my-nation',
  'nation',
  'nation-betting',
  'nation-finance',
  'npc-control',
  'rankings',
  'register',
  'select-pool',
  'simulator',
  'tournament',
  'tournament-admin',
  'troop',
  'vote',
  'world-log',
]);

function splitSuffix(value: string): { base: string; suffix: string } {
    const queryIdx = value.indexOf('?');
    const hashIdx = value.indexOf('#');
    const suffixIdx =
        queryIdx === -1 ? hashIdx : hashIdx === -1 ? queryIdx : Math.min(queryIdx, hashIdx);
    if (suffixIdx === -1) return { base: value, suffix: '' };
    return { base: value.slice(0, suffixIdx), suffix: value.slice(suffixIdx) };
}

function ensureGameBase(value: string): string {
    const clean = value.trim().replace(/\/+$/, '');
    const { base, suffix } = splitSuffix(clean);
    const baseNoSlash = base.replace(/\/+$/, '');
    if (baseNoSlash.endsWith('/game') || /\/game\/[^/]+$/.test(baseNoSlash)) {
        return `${baseNoSlash}${suffix}`;
    }
    return `${baseNoSlash}/game${suffix}`;
}

export function isPathServerId(serverId: string): boolean {
  return PATH_SERVER_ID.test(serverId) && !RESERVED_PATH_SERVER_IDS.has(serverId);
}

export function fallbackGameUrlForServer(serverId: string): string {
  const id = serverId;
  return isPathServerId(id) ? `/game/${encodeURIComponent(id)}` : `/game?server=${encodeURIComponent(id)}`;
}

export function resolveServerGameBase(
    gameUrl: string | undefined,
    serverId: string,
    fallback = '/game',
): string {
  const id = serverId;
    const raw = gameUrl?.trim() || fallbackGameUrlForServer(id) || fallback;
    const gameBase = ensureGameBase(raw || fallback);
    if (!isPathServerId(id)) return gameBase;

    const { base, suffix } = splitSuffix(gameBase);
    const baseNoSlash = base.replace(/\/+$/, '');
    if (/\/game\/[^/]+$/.test(baseNoSlash)) {
        return `${baseNoSlash}${suffix}`;
    }
    return `${baseNoSlash}/${encodeURIComponent(id)}${suffix}`;
}

export function resolveServerGamePath(
    gameUrl: string | undefined,
    serverId: string,
    fallback = '/game',
    childPath = '',
): string {
    const gameBase = resolveServerGameBase(gameUrl, serverId, fallback);
    const cleanChildPath = childPath.trim().replace(/^\/+/, '').replace(/\/+$/, '');
    if (!cleanChildPath) return gameBase;
    const { base, suffix } = splitSuffix(gameBase);
    return `${base.replace(/\/+$/, '')}/${cleanChildPath}${suffix}`;
}
