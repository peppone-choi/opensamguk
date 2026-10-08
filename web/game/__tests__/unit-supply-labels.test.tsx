import { fireEvent, render, screen } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { CourtChoiceSheet } from '../components/court/CourtParts';
import { UnitCards } from '../components/retinue/UnitCards';
import type { Retinue } from '../lib/campaign-reads';
import { courtChoices } from '../lib/court-view';
import { unitRows } from '../lib/retinue-view';

const retinue: Retinue = {
    status: 'READY', renown: 30, costSum: 0, overCapacity: false, people: [],
    units: [{ id: 10, name: '내 부곡', troops: 300, crewTypeId: 1, crewTypeName: '보병',
        training: 50, morale: 60, fatigue: 5, provisions: 0, provisionMonths: 2, commanderRetainerId: null }],
};

test('부장 미지정 부곡은 본인 지휘를 안내하고 출병 성공이나 불가를 단정하지 않는다', () => {
    render(<UnitCards units={unitRows(retinue)} />);
    expect(screen.getByRole('listitem')).toHaveTextContent('본인 지휘 — 출병 조건 확인');
    expect(screen.getByRole('listitem')).not.toHaveTextContent('움직일 수 없음');
});

test('부장 ID는 있으나 명부 이름이 없으면 본인 지휘로 오인하지 않는다', () => {
    const missing = { ...retinue, units: [{ ...retinue.units[0], commanderRetainerId: 999 }] };
    render(<UnitCards units={unitRows(missing)} />);
    expect(screen.getByRole('listitem')).toHaveTextContent('지휘 인물 확인 필요');
    expect(screen.getByRole('listitem')).not.toHaveTextContent('본인 지휘');
    expect(screen.getByRole('listitem')).not.toHaveTextContent('999');
});

test('서버 물자 꼬리만 한글로 표시하고 선택과 접수 인자는 그대로 보존한다', () => {
    const onSubmit = vi.fn();
    const choices = courtChoices({ inputId: 'court.diplomacy', available: true, choices: [
        { label: 'MONEY 연합 · MONEY', arguments: { targetNationId: 2, resource: 'MONEY', amount: 1 }, available: true },
        { label: '이웃 세력 · GRAIN', arguments: { targetNationId: 3, resource: 'GRAIN', amount: 1 }, available: false, code: 'STOCK_SHORT', reason: '쌀이 부족합니다.' },
    ] });
    render(<CourtChoiceSheet inputId="court.diplomacy" title="물자 지원" choices={choices} busy={false}
        onSubmit={onSubmit} onCancel={() => {}} />);
    fireEvent.click(screen.getByRole('option', { name: 'MONEY 연합 · 금' }));
    fireEvent.click(screen.getByRole('button', { name: '이대로 접수' }));
    expect(onSubmit).toHaveBeenCalledWith({ targetNationId: 2, resource: 'MONEY', amount: 1 });
    expect(screen.getByRole('option', { name: /이웃 세력 · 쌀/ })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('option', { name: /이웃 세력 · 쌀/ })).toHaveTextContent('쌀이 부족합니다.');
});

test('다섯 물자·알 수 없는 코드·이미 번역된 이름을 구분한다', () => {
    const resources = ['MONEY', 'GRAIN', 'IRON', 'TIMBER', 'HORSES', 'UNKNOWN_CARGO'];
    const choices = courtChoices({ inputId: 'court.confiscate', available: true, choices: [
        ...resources.map(resource => ({ label: `장수 · ${resource}`, arguments: { resource }, available: true })),
        { label: '장수 · 쌀', arguments: { resource: 'GRAIN' }, available: true },
        { label: 'MONEY 장수 (42)', arguments: { targetGeneralId: 42 }, available: true },
    ] });
    expect(choices.map(c => c.label)).toEqual(['장수 · 금', '장수 · 쌀', '장수 · 철', '장수 · 목재', '장수 · 말',
        '장수 · 알 수 없는 물자', '장수 · 쌀', 'MONEY 장수']);
    expect(choices[5].args.resource).toBe('UNKNOWN_CARGO');
});
