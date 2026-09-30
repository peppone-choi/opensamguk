import { gameChildPath, normalizeLegacyGamePath, resolveServerGamePath } from './serverGameUrl';

/** `/game/…` 메뉴 주소를 서버 경로(`/game/<서버>/…`)로 — 해시 · 바깥 주소는 그대로 둔다. */
export function resolveDeptHref(href: string, serverId: string | undefined): string {
    if (/^https?:\/\//i.test(href) || href.startsWith('#')) return href;
    const hashIndex = href.indexOf('#');
    const hash = hashIndex >= 0 ? href.slice(hashIndex) : '';
    const noHash = hashIndex >= 0 ? href.slice(0, hashIndex) : href;
    const normalized = normalizeLegacyGamePath(noHash);
    const isGame = normalized === '/game' || normalized.startsWith('/game/') || normalized.startsWith('/game?');
    if (!serverId || !isGame) return `${normalized}${hash}`;
    return `${resolveServerGamePath(undefined, serverId, '/game', gameChildPath(normalized))}${hash}`;
}
