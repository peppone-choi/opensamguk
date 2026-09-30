import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { CaptivePanel, TalentPanel, talentRows } from '../components/people/CaptivesParts';
import type { PeopleOptions } from '../lib/types';

const employ: PeopleOptions = {
    inputId: 'action.employ', available: true,
    targets: [
        { generalId: 41, name: '석도', available: true },
        { generalId: 42, name: '곽도', available: false, code: 'NOT_FREE', reason: '이미 소속이 있습니다.' },
    ],
};
const search: PeopleOptions = { inputId: 'action.search', available: true, undiscoveredCount: 3, targets: [] };

test('인재 — 불가는 사유(縣 은 현으로), 비면 인재탐색 안내, 고르면 「{이름} 등용」', () => {
    expect(talentRows({ ...employ, targets: [{ generalId: 43, name: '갑', available: false, reason: '현재 縣의 재야 인물만 등용합니다.' }] })[0].reason)
        .toBe('현재 현의 재야 인물만 등용합니다.');
    const onEmploy = vi.fn();
    const { rerender } = render(<TalentPanel employ={employ} search={search}
        searchAvailability={{ inputId: 'action.search', status: 'AVAILABLE' }} employAvailability={{ inputId: 'action.employ', status: 'AVAILABLE' }}
        onSearch={() => {}} onEmploy={onEmploy} />);
    expect(screen.getByText('찾지 못한 인물 3명')).toBeInTheDocument();
    const list = screen.getByRole('listbox', { name: '인재' });
    expect(within(list).getByRole('option', { name: /곽도/ })).toHaveAttribute('aria-disabled', 'true');
    expect(within(list).getByRole('option', { name: /곽도/ })).toHaveTextContent('이미 소속이 있습니다.');
    expect(screen.queryByRole('button', { name: /등용 — 명령 목록에 넣기/ })).toBeNull();
    fireEvent.click(within(list).getByRole('option', { name: /석도/ }));
    fireEvent.click(screen.getByRole('button', { name: '석도 등용 — 명령 목록에 넣기' }));
    expect(onEmploy).toHaveBeenCalledWith(41);

    rerender(<TalentPanel employ={{ ...employ, targets: [] }} search={search} searchAvailability={null} employAvailability={null} onSearch={() => {}} onEmploy={onEmploy} />);
    expect(screen.getByRole('status')).toHaveTextContent('이 현에서 찾은 재야 인물이 없습니다');
});

test('포로 — 목록은 서버 대기, 설득은 준비 중, 원장 행 없는 석방 · 억류는 그리지 않고 행이 오면 그 id 로', () => {
    const { rerender } = render(<CaptivePanel persuade={{ inputId: 'action.persuadeCaptive', status: 'NOT_DELIVERED' }} release={null} detain={null}
        onPersuade={() => {}} onRelease={() => {}} onDetain={() => {}} />);
    expect(screen.getByText('포로 목록 — 준비 중')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /설득/ })).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
    expect(screen.queryByRole('button', { name: /석방/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /억류/ })).toBeNull();
    rerender(<CaptivePanel persuade={null} release={{ inputId: 'court.releaseCaptive', status: 'NOT_DELIVERED' }} detain={null}
        onPersuade={() => {}} onRelease={() => {}} onDetain={() => {}} />);
    expect(screen.getByRole('button', { name: /석방/ })).toHaveAttribute('data-input-id', 'court.releaseCaptive');
});
