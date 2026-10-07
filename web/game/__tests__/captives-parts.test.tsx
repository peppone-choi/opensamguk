import { fireEvent, render, screen, within } from '@testing-library/react';
import { useState } from 'react';
import { expect, test, vi } from 'vitest';
import { CaptivePanel, TalentPanel, employQuery, persuadeQuery, talentRows } from '../components/people/CaptivesParts';
import type { CaptivesRead, PeopleOptions } from '../lib/types';

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

test('포로 — 실제 구금행에서 설득은 흐름으로, 석방은 별도 무순 처리로 보낸다', () => {
    const captives: CaptivesRead = { available: true, targets: [{ generalId: 52, name: '포로', nationId: 2, nationName: '원소',
        heldProvinceId: 'P-1', actualProvinceId: 'P-1', capturedAt: { year: 200, month: 3, phase: 2 }, expiry: 'NONE',
        persuadeAvailable: true, releaseAvailable: true }] };
    const persuade = vi.fn();
    const release = vi.fn();
    render(<CaptivePanel captives={captives} busy={false} onPersuade={persuade} onRelease={release} />);
    expect(screen.getByText(/구금 위치 P-1/)).toHaveTextContent('기한 없음');
    fireEvent.click(screen.getByRole('button', { name: /설득 — 순 고르기/ }));
    fireEvent.click(screen.getByRole('button', { name: '석방' }));
    expect(persuade).toHaveBeenCalledWith(52);
    expect(release).toHaveBeenCalledWith(52);
    expect(screen.queryByRole('button', { name: /억류/ })).toBeNull();
});

test('employQuery — 대상 장수를 미리 채운 흐름 주소', () => {
    expect(employQuery(41)).toBe('?do=action.employ&target=general%3A41');
    expect(persuadeQuery(52)).toBe('?do=action.persuadeCaptive&target=general%3A52');
});
