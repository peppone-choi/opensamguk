'use client';

import { useEffect, useState } from 'react';

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
const SERVER_COOKIE = 'sam_server';

const LEGACY_GAME_ROUTE_MAP: Record<string, string> = {
    'v_nationBetting.php': '/game/nation-betting',
    'a_kingdomList.php': '/game/rankings/kingdoms',
    'v_nationList.php': '/game/rankings/kingdoms',
    'a_genList.php': '/game/rankings/generals',
    'v_generalList.php': '/game/rankings/generals',
    'a_bestGeneral.php': '/game/rankings/best-generals',
    'v_bestGeneral.php': '/game/rankings/best-generals',
    'a_hallOfFame.php': '/game/rankings/hall-of-fame',
    'v_hallOfFame.php': '/game/rankings/hall-of-fame',
    'a_emperior.php': '/game/rankings/emperor',
    'v_dynastyList.php': '/game/rankings/emperor',
    'v_history.php': '/game/history',
    'v_battleCenter.php': '/game/battle-center',
    'battle_simulator.php': '/game/simulator',
    'a_traffic.php': '/game/rankings/traffic',
    'v_trafficInfo.php': '/game/rankings/traffic',
    a_npcList: '/game/rankings/npcs',
    'a_npcList.php': '/game/rankings/npcs',
    'v_npcList.php': '/game/rankings/npcs',
    'v_vote.php': '/game/vote',
    'b_myPage.php': '/game/my',
    'v_nationGeneral.php': '/game/my-generals',
    'v_nationCity.php': '/game/my-cities',
    'v_nationInfo.php': '/game/my-nation',
};

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

export function normalizeLegacyGamePath(href: string): string {
    const trimmed = href.trim();
    if (!trimmed) return trimmed;
    if (/^(?:https?:)?\/\//i.test(trimmed) || /^[a-z][a-z0-9+.-]*:/i.test(trimmed)) return trimmed;

    const { base, suffix } = splitSuffix(trimmed);
    const key = base
        .replace(/^\/game\//, '')
        .replace(/^\.?\//, '')
        .replace(/^game\//, '')
        .replace(/\/+$/, '');
    const mapped = LEGACY_GAME_ROUTE_MAP[key];
    return mapped ? `${mapped}${suffix}` : trimmed;
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

export function readServerCookie(): string | undefined {
  if (typeof document === 'undefined') return undefined;
  const match = document.cookie.split('; ').find((row) => row.startsWith(`${SERVER_COOKIE}=`));
  const serverId = match?.split('=')[1];
  return serverId && isPathServerId(serverId) ? serverId : undefined;
}

/** 현재 `sam_server` 쿠키 값을 클라이언트에서 읽는다(SSR 시점에는 undefined). */
export function useServerId(): string | undefined {
    const [serverId, setServerId] = useState<string | undefined>(undefined);
    useEffect(() => {
        setServerId(readServerCookie());
    }, []);
    return serverId;
}

/** `/game/{childPath}`를 현재 서버 식별자에 맞게 변환. */
export function useServerGameUrl(childPath: string): string {
    const serverId = useServerId();
    if (!serverId) return childPath ? `/game/${childPath.replace(/^\/+/, '')}` : '/game';
    return resolveServerGamePath(undefined, serverId, '/game', childPath);
}

/** `/game/...` href에서 `/game/` 접두사를 떼어내 resolveServerGamePath용 childPath로 변환. */
export function gameChildPath(href: string): string {
    const trimmed = href.trim();
    if (trimmed === '/game') return '';
    if (trimmed.startsWith('/game/')) return trimmed.slice('/game/'.length);
    if (trimmed.startsWith('/game?')) return trimmed.slice('/game'.length); // keep leading ?
    return trimmed;
}

export function normalizeGamePathname(pathname: string, serverId?: string): string {
  if (!serverId || !isPathServerId(serverId)) return pathname;

  const serverPath = `/game/${serverId}`;
  if (pathname !== serverPath && !pathname.startsWith(`${serverPath}/`)) return pathname;
  return `/game${pathname.slice(serverPath.length)}`;
}
