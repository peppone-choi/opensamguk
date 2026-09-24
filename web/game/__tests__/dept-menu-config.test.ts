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

  it('서버의 예전 전역 메뉴가 삼모 링크를 주어도 제품 메뉴에 반영하지 않는다', () => {
    const serverMenu = [
      { type: 'item' as const, name: '경매장', url: '/game/auction' },
      { type: 'item' as const, name: '세력일람', url: '/game/rankings/kingdoms' },
    ];
    const routes = buildDeptGroups(serverMenu).flatMap((group) => group.entries);
    expect(routes).toEqual(DEPT_GROUPS.flatMap((group) => group.entries));
    expect(routes.some((entry) => entry.href === '/game/auction')).toBe(false);
    expect(routes.some((entry) => /\b(?:betting|tournament|inherit|vote|npc-control|troop|v2-lab)\b/.test(entry.href))).toBe(false);
    expect(routes.every((entry) => evaluateEntry(entry, USER, {}).enabled)).toBe(true);
  });

  it('모바일 다섯 탭은 휘하 경로를 쓰고 국가 권한 사유를 유지한다', () => {
    expect(MOBILE_TABS.map((tab) => tab.href)).toEqual([
      '/game/hwiha/war-room', '/game/map',
      '/game/hwiha/war-room#reservedCommandPanel', '/game/my-nation', '#dept-more',
    ]);
    const nation = MOBILE_TABS.find((tab) => tab.key === 'nation')!;
    expect(evaluateMobileTab(nation, USER, {}, 'ready')).toMatchObject({
      enabled: false, reason: '장수 직위 이상 필요',
    });
    expect(evaluateMobileTab(nation, { ...USER, myLevel: 1 }, {}, 'ready').enabled).toBe(true);
  });
});
