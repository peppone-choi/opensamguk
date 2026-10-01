import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { MOBILE_TAB_KEYS, NAV31, groupHref, locateScreen, screenHref } from '../lib/nav31';

const APP_GAME = join(__dirname, '..', 'app', 'game');

/** 새 경로에 페이지가 있는지(route group `(campaign)` 을 거쳐). */
function builtPageExists(path: string): boolean {
  const clean = path.split('?')[0];
  const candidates = [join(APP_GAME, clean, 'page.tsx'), join(APP_GAME, '(campaign)', clean, 'page.tsx')];
  return candidates.some((file) => existsSync(file));
}

describe('NAV31 — 메뉴 한 벌 v3.1', () => {
  it('묶음 8개가 보드 순서 그대로다', () => {
    expect(NAV31.map((g) => g.label)).toEqual(['작전실', '부', '계책', '영지', '군단', '조정', '기록', '광장']);
    expect(MOBILE_TAB_KEYS).toEqual(['war', 'retinue', 'stratagem', 'records']);
  });

  it('built 로 적은 화면은 새 경로에 페이지가 실제로 있다(없는 화면을 켜지 않는다)', () => {
    for (const group of NAV31) {
      for (const screen of group.screens.filter((s) => s.built)) {
        expect(builtPageExists(screen.path), `${group.label} · ${screen.label} → /${screen.path}`).toBe(true);
      }
    }
  });

  it('새 화면이 없으면 지금 화면을 연다 — 지금 화면도 실제 페이지다', () => {
    for (const group of NAV31) {
      for (const screen of group.screens.filter((s) => !s.built && s.current)) {
        expect(existsSync(join(APP_GAME, screen.current!, 'page.tsx')), `${screen.label} → /${screen.current}`).toBe(true);
      }
    }
  });

  it('레일 링크는 묶음에서 열 수 있는 첫 화면이다', () => {
    const hrefs = Object.fromEntries(NAV31.map((g) => [g.key, groupHref(g)]));
    expect(hrefs).toEqual({
      war: '', retinue: 'retinue', stratagem: 'stratagem', territory: 'territory', corps: 'corps/siege',
      court: 'court?tab=orders', records: 'records', plaza: 'board',
    });
    expect(screenHref({ label: '역정보', path: 'stratagem/counter-intel', built: false })).toBeNull();
  });

  it('지금 경로로 묶음 · 화면을 찾는다 — 새 경로 · 쿼리 탭 · 지금 화면 · 308 옛 경로', () => {
    expect(locateScreen('')?.group.key).toBe('war');
    expect(locateScreen('retinue/yuedan')?.screen?.label).toBe('월단평');
    expect(locateScreen('retinue')?.screen?.label).toBe('편성 · 결속 · 명망');
    expect(locateScreen('court', 'tab=orders')?.screen?.label).toBe('발령 · 포상 · 조정 결정');
    expect(locateScreen('court')).toEqual({ group: NAV31.find((g) => g.key === 'court'), screen: null });
    expect(locateScreen('world-log')?.screen?.label).toBe('기록 5분류');
    expect(locateScreen('city')?.group.key).toBe('territory');
    expect(locateScreen('my-cities')?.group.key).toBe('territory');
    expect(locateScreen('battle-center')?.screen?.label).toBe('전투');
    expect(locateScreen('join')).toBeNull();
    expect(locateScreen('admin')).toBeNull();
  });
});
