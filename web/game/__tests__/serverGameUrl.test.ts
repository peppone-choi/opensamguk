import { describe, expect, it } from 'vitest';
import { gameChildPath, normalizeLegacyGamePath, resolveServerGamePath } from '@/lib/serverGameUrl';

describe('serverGameUrl', () => {
    it('normalizes legacy PHP menu URLs to app routes', () => {
        expect(normalizeLegacyGamePath('v_history.php')).toBe('/game/history');
        expect(normalizeLegacyGamePath('/game/a_genList.php')).toBe('/game/rankings/generals');
        expect(normalizeLegacyGamePath('battle_simulator.php?mode=test')).toBe('/game/simulator?mode=test');
        expect(normalizeLegacyGamePath('https://open.kakao.com/o/')).toBe('https://open.kakao.com/o/');
    });

    it('resolves normalized game routes under the selected server', () => {
        const normalized = normalizeLegacyGamePath('a_traffic.php');
        expect(resolveServerGamePath(undefined, 's1', '/game', gameChildPath(normalized))).toBe('/game/s1/rankings/traffic');
    });
});
