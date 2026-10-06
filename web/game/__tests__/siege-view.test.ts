import { expect, test } from 'vitest';
import type { RoadFort } from '../lib/campaign-reads';
import { ASSAULT_NOT_READY, fortRow, moralePercent, siegeProgressText, siegeStatus, siegeVerdict, timelineRow, turnsToAssault } from '../lib/siege-view';

test('상태 · 사기 · 강공까지 — 모르는 상태는 「알 수 없음」, 끝난 포위는 강공 칸이 없다', () => {
    expect(siegeStatus('ACTIVE')).toEqual({ label: '포위 중', tone: 'bronze' });
    expect(siegeStatus('SOMETHING_NEW').label).toBe('알 수 없음');
    expect(moralePercent(4249)).toBe('42%');
    expect(turnsToAssault({ status: 'ACTIVE', turns: 1 })).toBe(2);
    expect(turnsToAssault({ status: 'ACTIVE', turns: 5 })).toBe(0);
    expect(turnsToAssault({ status: 'FALLEN', turns: 5 })).toBeNull();
    expect(siegeProgressText({ status: 'ACTIVE', turns: 2 })).toBe('포위 2순째 · 강공까지 1순');
    expect(siegeProgressText({ status: 'LIFTED', turns: 2 })).toBe('포위 2순');
});

test('가능 여부 — 강공은 포위 3순째부터(서버 사유 코드 그대로), 지휘관 아님 · 끝난 포위는 코드 없이 문장만', () => {
    const base = { status: 'ACTIVE', turns: 3, canAct: true };
    expect(siegeVerdict(base, 'action.assault')).toEqual({ available: true });
    expect(siegeVerdict({ ...base, turns: 2 }, 'action.assault')).toEqual({ available: false, ...ASSAULT_NOT_READY });
    expect(siegeVerdict({ ...base, turns: 2 }, 'action.demandSurrender')).toEqual({ available: true });
    expect(siegeVerdict({ ...base, canAct: false }, 'action.demandSurrender')).toEqual({ available: false, reason: '포위 지휘관만 명령할 수 있습니다.' });
    expect(siegeVerdict({ ...base, status: 'FALLEN', canAct: false }, 'action.assault')).toEqual({ available: false, reason: '끝난 포위입니다.' });
});

test('포위 기록 — 아는 키만, 모르는 사건 · 틀린 형은 원문 대신 일상어', () => {
    expect(timelineRow({ year: 190, month: 2, phase: 3, event: 'DEMAND_REFUSED', morale: 3100 })).toEqual({ when: '190년 2월 하순', what: '항복 권고 거절', detail: '사기 31%' });
    expect(timelineRow({ event: 'NEW_CODE', garrison: '많음' })).toEqual({ when: '때 모름', what: '공성 사건', detail: null });
});

test('도로 보루 — 이름을 못 풀면 「이름 모를 구역」 · 「어느 세력」, 우리 보루 표시', () => {
    const fort: RoadFort = { id: 'f', edgeId: 'e', provinceId: 'p9', row: 1, col: 2, ownerNationId: 4, wall: 1200, garrison: 30,
        besiegerGeneralId: 9, siegeProgress: 40, canBesiege: false };
    const row = fortRow(fort, { province: () => null, nation: () => undefined, myNationId: 4 });
    expect(row).toMatchObject({ name: '보루 — 이름 모를 구역', ownerName: '어느 세력', mine: true, besieged: true });
    expect(row.cells.map((c) => `${c.label} ${c.value}`)).toEqual(['성벽 1,200', '수비 30', '포위 진척 40%']);
});
