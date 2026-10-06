import { fireEvent, render, screen, within } from '@testing-library/react';
import { useState } from 'react';
import { expect, test, vi } from 'vitest';
import { CaptivePanel, TalentPanel, employQuery, talentRows } from '../components/people/CaptivesParts';
import type { PeopleOptions } from '../lib/types';

// 도움말 고리(HelpedInputAction)는 도움말 서랍 없이도 그린다 — 여기서는 단추 · 사유만 본다.
vi.mock('../components/campaign/HelpedInputAction', async () => {
    const { InputAction } = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
    return { HelpedInputAction: InputAction };
});

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
    // 고른 인재는 화면이 쥔다(모바일은 그걸로 시트를 연다) — 시험에서는 작은 틀로 쥔다.
    function Harness({ opt }: { readonly opt: PeopleOptions }) {
        const [picked, setPicked] = useState<number | null>(null);
        return <TalentPanel employ={opt} search={search}
            searchAvailability={{ inputId: 'action.search', status: 'AVAILABLE' }} employAvailability={{ inputId: 'action.employ', status: 'AVAILABLE' }}
            onSearch={() => {}} picked={picked} onPick={setPicked} onEmploy={onEmploy} />;
    }
    const { rerender } = render(<Harness opt={employ} />);
    expect(screen.getByText('찾지 못한 인물 3명')).toBeInTheDocument();
    const list = screen.getByRole('listbox', { name: '인재' });
    expect(within(list).getByRole('option', { name: /곽도/ })).toHaveAttribute('aria-disabled', 'true');
    expect(within(list).getByRole('option', { name: /곽도/ })).toHaveTextContent('이미 소속이 있습니다.');
    expect(screen.queryByRole('button', { name: /등용 — 명령 목록에 넣기/ })).toBeNull();
    fireEvent.click(within(list).getByRole('option', { name: /석도/ }));
    fireEvent.click(screen.getByRole('button', { name: '석도 등용 — 명령 목록에 넣기' }));
    expect(onEmploy).toHaveBeenCalledWith(41);

    rerender(<Harness opt={{ ...employ, targets: [] }} />);
    expect(screen.getByRole('status')).toHaveTextContent('이 현에서 찾은 재야 인물이 없습니다');
});

test('포로 — 목록은 서버 대기, 설득은 원장 PLANNED 「준비 중」, 원장 행이 없는 석방 · 억류는 그리지 않는다(지어낸 inputId 금지)', () => {
    render(<CaptivePanel persuade={{ inputId: 'action.persuadeCaptive', status: 'NOT_DELIVERED' }} onPersuade={() => {}} />);
    expect(screen.getByText('포로 목록 — 준비 중')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /설득/ })).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
    expect(screen.queryByRole('button', { name: /석방/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /억류/ })).toBeNull();
});

test('employQuery — 대상 장수를 미리 채운 흐름 주소', () => {
    expect(employQuery(41)).toBe('?do=action.employ&target=general%3A41');
});
