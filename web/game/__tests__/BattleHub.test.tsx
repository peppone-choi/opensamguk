// 전투 · 부재 대비(P-C04) — 전투는 「열리지 않음(서버 준비 중)」 · 부재 대비는 방침 읽기(군단 · 내가 맡은 현)만.
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { BattleHub, BattleRoomUnavailable } from '../components/battle/BattleHub';
import { toAbsence } from '../lib/battle/absence';
import type { Policies } from '../lib/campaign-reads';

const policies: Policies = {
    status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: { code: 'DEFEND', label: '수비' },
    corps: [{ orderId: 'O-1', commanderName: '[나]', active: { policy: 'INTERCEPT', label: '요격' }, pending: { policy: 'EVADE', label: '회피' }, settable: true, blocked: null }],
    counties: [
        { countyId: 30, name: '허현', commanderyName: '영천군', active: null, pending: null, effective: { policy: 'DEFEND', label: '수비', source: 'DEFAULT' }, seat: { generalId: 1, name: '[나]', placed: true }, settable: true } as never,
        { countyId: 31, name: '양적현', commanderyName: '영천군', active: null, pending: null, effective: null, seat: { generalId: 2, name: '[남]', placed: true }, settable: false } as never,
    ],
};

describe('부재 대비 모델', () => {
    it('내 군단 방침(지금 · 다음 순) · 내가 맡은 현만', () => {
        const v = toAbsence(policies, 1);
        if (v.state !== 'ready') throw new Error('ready');
        expect(v.rows).toEqual([
            { key: 'corps:O-1', kind: 'corps', name: '[나] 군단', policy: '요격', pending: '회피', settable: true, blocked: null },
            { key: 'county:30', kind: 'county', name: '허현', policy: '수비', pending: null, settable: true, blocked: null },
        ]);
        expect(toAbsence({ ...policies, status: 'WRONG_RULE_PROFILE' }, 1)).toEqual({ state: 'unreadable', status: 'WRONG_RULE_PROFILE' });
    });
});

describe('전투 · 부재 대비 화면', () => {
    it('전투 목록은 서버 대기, 부재 대비는 방침 행과 고치러 가는 길', () => {
        const onOpenPolicy = vi.fn();
        render(<BattleHub absence={{ state: 'ready', view: toAbsence(policies, 1), onRetry: vi.fn() }} onOpenPolicy={onOpenPolicy} />);
        expect(within(screen.getByRole('region', { name: '내 전투' })).getByText('전투가 열리지 않습니다(서버 준비 중)')).toBeInTheDocument();
        const rows = within(screen.getByRole('list', { name: '없을 때 싸우는 것' })).getAllByRole('listitem');
        expect(rows[0]).toHaveTextContent('[나] 군단요격 · 다음 순부터 회피');
        expect(rows[1]).toHaveTextContent('맡은 현허현수비');
        fireEvent.click(screen.getByRole('button', { name: '방침 고치기' }));
        expect(onOpenPolicy).toHaveBeenCalled();
    });

    it('방침을 못 읽으면 다시 읽기가 실제로 불린다', () => {
        const onRetry = vi.fn();
        render(<BattleHub absence={{ state: 'ready', view: { state: 'unreadable', status: 'NOT_READY' }, onRetry }} />);
        fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
        expect(onRetry).toHaveBeenCalled();
    });

    it('전투 방 자리(P-C03 · C05)는 서버가 전투를 열기 전에는 서버 대기뿐', () => {
        render(<BattleRoomUnavailable onBack={vi.fn()} />);
        expect(screen.getByText('전투가 열리지 않습니다(서버 준비 중)')).toBeInTheDocument();
    });
});
