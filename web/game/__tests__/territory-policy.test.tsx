import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { PolicyPanel, PolicySheet } from '../components/territory/PolicyParts';
import type { Policies } from '../lib/campaign-reads';
import { commanderyPolicyRows, corpsPolicyRows, countyPolicyRows, phaseText, policyBody, policyOptions } from '../lib/territory-view';

// 결정 단추가 도움말 고리(useReasonHelp → useOpenHelp)를 쓴다 — 지금 경로 · 쿼리 · router 흉내.
vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep/territory', useSearchParams: () => new URLSearchParams(), useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }) }));

const policies: Policies = {
    status: 'READY',
    countyOptions: [{ code: 'FARM', label: '농업' }, { code: 'TRADE', label: '상업' }],
    corpsOptions: [{ code: 'DRILL', label: '조련' }],
    defaultPolicy: { code: 'FARM', label: '농업' },
    provisional: 'DRAFT',
    counties: [
        { countyId: 129, name: '양성현', commanderyId: 'c-yc', commanderyName: '영천군',
            active: { policy: 'TRADE', label: '상업', since: { year: 200, month: 2, phase: 3 } }, pending: { policy: null, label: null },
            effective: { policy: 'TRADE', label: '상업', source: 'COUNTY' }, seat: { generalId: 102, name: '이전', placed: false },
            lastApplied: { at: { year: 200, month: 3, phase: 1 }, policy: 'TRADE', label: '상업', seat: '102', result: 'APPLIED' },
            settable: true, blocked: null },
        { countyId: 130, name: '허현', commanderyName: '영천군', active: null, pending: null,
            effective: { policy: 'FARM', label: '농업', source: 'DEFAULT' }, seat: null, settable: false,
            blocked: { code: 'NOT_MAGISTRATE', reason: '현령이나 군주만 방침을 정합니다.' } },
    ],
    commanderies: [{ commanderyId: 'c-yc', name: '영천군', countyIds: [129, 130], active: null, pending: { policy: 'FARM', label: '농업' }, settable: true, blocked: null }],
    corps: [],
};

test('보기 모델 — 출처 칩 · 건 때 · 거두기 대기 · 지난 적용(결과 코드는 안 씀) · 빈자리', () => {
    const [a, b] = countyPolicyRows(policies);
    expect(a).toMatchObject({ seat: '이전(부임 중)', now: '상업', source: '현 방침', since: '200년 2월 하순', pending: '거두기', lastApplied: '200년 3월 상순 · 상업' });
    expect(JSON.stringify(a)).not.toContain('APPLIED');
    expect(b).toMatchObject({ seat: '빈자리', now: '농업', source: '기본', pending: null });
    expect(commanderyPolicyRows(policies)[0]).toMatchObject({ sub: '현 2곳', pending: '농업' });
    expect(corpsPolicyRows(policies)).toEqual([]);
    expect(phaseText({ year: 200, month: 3, phase: 9 })).toBe('200년 3월');
});

test('선택지 · 몸통 — 건 방침이 있으면 「방침 거두기」(NONE), 범위마다 서버가 받는 필드 셋', () => {
    const [a, b] = countyPolicyRows(policies);
    expect(policyOptions(policies, a).map((o) => o.code)).toEqual(['FARM', 'TRADE', 'NONE']);
    expect(policyOptions(policies, b).map((o) => o.code)).toEqual(['FARM', 'TRADE']);
    expect(policyBody(a, 'FARM')).toEqual({ scope: 'COUNTY', countyId: 129, policy: 'FARM' });
    expect(policyBody({ scope: 'COMMANDERY', targetId: 'c-yc' }, 'FARM')).toEqual({ scope: 'COMMANDERY', commanderyId: 'c-yc', policy: 'FARM' });
    expect(policyBody({ scope: 'CORPS', targetId: 'o-1' }, 'DRILL')).toEqual({ scope: 'CORPS', orderId: 'o-1', policy: 'DRILL' });
});

test('방침 칸 — 기본 · 잠정 칩, 탭 수, 권한 없는 현은 점선 + 서버 사유, 빈 탭은 이유 한 줄', () => {
    const avail = (r: { settable: boolean; blocked: { code: string; reason: string } | null }) => r.settable
        ? { inputId: 'policy.set', status: 'AVAILABLE' as const }
        : { inputId: 'policy.set', status: 'BLOCKED' as const, code: r.blocked?.code, reason: r.blocked?.reason };
    render(<PolicyPanel policies={policies} availabilityOf={avail} onChange={() => {}} />);
    expect(screen.getByText('빈자리 기본 · 농업')).toBeInTheDocument();
    expect(screen.getByText('잠정')).toBeInTheDocument();
    const tabs = within(screen.getByRole('radiogroup', { name: '방침 대상' })).getAllByRole('radio');
    expect(tabs.map((t) => t.textContent)).toEqual(['현2', '군1', '군단0']);
    const [, heo] = screen.getAllByRole('listitem');
    expect(within(heo).getByRole('button', { name: /바꾸기/ })).toHaveAttribute('aria-disabled', 'true');
    expect(heo).toHaveTextContent('현령이나 군주만 방침을 정합니다.');
    fireEvent.click(tabs[2]);
    expect(screen.getByRole('status')).toHaveTextContent('방침을 걸 군단이 없습니다.');
});

test('방침 시트 — 고른다고 보내지 않는다, 「이 방침으로」를 눌러야 몸통이 간다', () => {
    const onSubmit = vi.fn();
    const row = countyPolicyRows(policies)[0];
    render(<PolicySheet policies={policies} row={row} busy={false} onSubmit={onSubmit} onCancel={() => {}} />);
    expect(screen.getByRole('button', { name: '이 방침으로' })).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(screen.getByRole('option', { name: '농업' }));
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '이 방침으로' }));
    expect(onSubmit).toHaveBeenCalledWith({ scope: 'COUNTY', countyId: 129, policy: 'FARM' });
    expect(within(screen.getByRole('option', { name: /상업/ })).getByText('지금')).toBeInTheDocument();
});
