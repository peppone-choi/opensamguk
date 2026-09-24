import { describe, expect, it } from 'vitest';
import {
  DEPT_GROUPS,
  MOBILE_TABS,
  buildDeptGroups,
  evaluateEntry,
  evaluateMobileTab,
  type ControlGating,
} from '@/lib/dept-menu-config';

const USER: ControlGating = { myLevel: 0 };

describe('휘하 제품 부서 메뉴', () => {
  it('작전실과 휘하 입력 아홉 화면에 모두 연결한다', () => {
    const routes = DEPT_GROUPS.flatMap((group) => group.entries.map((entry) => entry.href));
    expect(DEPT_GROUPS.map((group) => group.label)).toEqual(['작전실', '국가 운영', '군사', '정보', '광장', '기록']);
    for (const slug of ['war-room', 'retinue', 'hand', 'posts', 'orders', 'supply', 'siege', 'court', 'yuedan']) {
      expect(routes).toContain(`/game/hwiha/${slug}`);
    }
    expect(routes).toContain('/game/board');
    expect(routes).toContain('/game/mailbox');
    expect(routes).toContain('/game/rankings');
  });

  it('정적 제품 메뉴에는 삼모 전역 링크가 없다', () => {
    const routes = buildDeptGroups().flatMap((group) => group.entries);
    expect(routes).toEqual(DEPT_GROUPS.flatMap((group) => group.entries));
    expect(routes.some((entry) => entry.href === '/game/auction')).toBe(false);
    expect(routes.some((entry) => /\b(?:betting|tournament|inherit|vote|npc-control|troop|v2-lab)\b/.test(entry.href))).toBe(false);
    expect(routes.every((entry) => entry.href !== '/game/auction')).toBe(true);
  });

  it('모바일 다섯 탭과 데스크톱 세력 정보가 같은 권한 사유를 쓴다', () => {
    expect(MOBILE_TABS.map((tab) => tab.href)).toEqual([
      '/game/hwiha/war-room', '/game/map',
      '/game/hwiha/war-room#reservedCommandPanel', '/game/my-nation', '#dept-more',
    ]);
    const nation = MOBILE_TABS.find((tab) => tab.key === 'nation')!;
    const desktopNation = DEPT_GROUPS.flatMap((group) => group.entries).find((entry) => entry.href === '/game/my-nation')!;
    expect(evaluateEntry(desktopNation, USER, 'ready')).toMatchObject({
      enabled: false, reason: '장수 직위 이상 필요',
    });
    expect(evaluateMobileTab(nation, USER, 'ready')).toMatchObject({
      enabled: false, reason: '장수 직위 이상 필요',
    });
    expect(evaluateMobileTab(nation, { ...USER, myLevel: 1 }, 'ready').enabled).toBe(true);
    expect(evaluateEntry(desktopNation, null, 'error')).toMatchObject({ enabled: false, reason: '서버 정보 없음' });
  });
});
