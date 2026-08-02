import { describe, expect, it } from 'vitest';
import {
  fallbackGameUrlForServer,
  gameChildPath,
  isPathServerId,
  normalizeGamePathname,
  normalizeLegacyGamePath,
  resolveServerGamePath,
} from '@/lib/serverGameUrl';

describe('serverGameUrl', () => {
    it('normalizes legacy PHP menu URLs to app routes', () => {
        expect(normalizeLegacyGamePath('v_history.php')).toBe('/game/history');
        expect(normalizeLegacyGamePath('v_battleCenter.php')).toBe('/game/battle-center');
        expect(normalizeLegacyGamePath('b_myPage.php')).toBe('/game/my');
        expect(normalizeLegacyGamePath('v_nationGeneral.php')).toBe('/game/my-generals');
        expect(normalizeLegacyGamePath('/game/a_genList.php')).toBe('/game/rankings/generals');
        expect(normalizeLegacyGamePath('battle_simulator.php?mode=test')).toBe('/game/simulator?mode=test');
        expect(normalizeLegacyGamePath('https://open.kakao.com/o/')).toBe('https://open.kakao.com/o/');
    });

    it('resolves normalized game routes under the selected server', () => {
        const normalized = normalizeLegacyGamePath('a_traffic.php');
        expect(resolveServerGamePath(undefined, 's1', '/game', gameChildPath(normalized))).toBe('/game/s1/rankings/traffic');
    });

  it('accepts only canonical lowercase public server IDs', () => {
    for (const serverId of ['pep', 'a1', 's1']) {
      expect(isPathServerId(serverId)).toBe(true);
      expect(resolveServerGamePath(undefined, serverId, '/game', 'join')).toBe(`/game/${serverId}/join`);
    }

    for (const serverId of ['', 'A1', 'Pep', 'pep-id', 'pep_id', 'pep/id', '한글', ' pep ']) {
      expect(isPathServerId(serverId)).toBe(false);
    }
  });

  it('uses the query fallback for invalid server IDs', () => {
    expect(fallbackGameUrlForServer('pep-id')).toBe('/game?server=pep-id');
    expect(fallbackGameUrlForServer('A1')).toBe('/game?server=A1');
    expect(fallbackGameUrlForServer('pep/id')).toBe('/game?server=pep%2Fid');
    expect(resolveServerGamePath(undefined, 'A1', '/game', 'join')).toBe('/game/join?server=A1');
    expect(resolveServerGamePath(undefined, 'pep-id', '/game', 'join')).toBe('/game/join?server=pep-id');
  });

  it('normalizes only the selected server path and preserves ordinary child routes', () => {
    expect(normalizeGamePathname('/game/pep/join', 'pep')).toBe('/game/join');
    expect(normalizeGamePathname('/game/a1/rankings', 'a1')).toBe('/game/rankings');
    expect(normalizeGamePathname('/game/s1', 's1')).toBe('/game');
    expect(normalizeGamePathname('/game/join', 'pep')).toBe('/game/join');
    expect(normalizeGamePathname('/game/A1/join', 'A1')).toBe('/game/A1/join');
    expect(normalizeGamePathname('/game/pep/join')).toBe('/game/pep/join');
    expect(normalizeGamePathname('/game/pep/join', 'pep-id')).toBe('/game/pep/join');
  });
});
