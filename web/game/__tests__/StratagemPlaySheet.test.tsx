// 계책 쓰기 · 걸기(P-S02) — 손패의 카드 한 장 · 대상 후보 · 비용은 서버 대기(지어내지 않음) · 결정 단추는 「준비 중」(stratagem.play PLANNED)
// · 손패 줄로 카드 바꾸기 · 닫기와 Esc(사유 시트 안 Esc 는 사유만) · 손패에 없는 카드 · 첫 손패 전 · 실패.
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { StratagemPlaySheet } from '../components/stratagem/StratagemPlaySheet';
import { parseCardParam, toHandView } from '../lib/stratagem/hand';

const hand = toHandView({
    loading: false, error: null,
    data: { status: 'READY', handLimit: 3, canUse: false, cards: [{ instanceId: 1, type: 'FORTIFY', label: '견벽' }, { instanceId: 2, type: 'INSIGHT', label: '간파' }] },
});

function renderSheet(cardId: number | null, over: Partial<Parameters<typeof StratagemPlaySheet>[0]> = {}) {
    const props = { hand, cardId, onPick: vi.fn(), onClose: vi.fn(), onRetry: vi.fn(), ...over };
    render(<StratagemPlaySheet {...props} />);
    return props;
}

describe('주소 카드 값', () => {
    it('부호 있는 0 아닌 정수만 — 나머지는 null', () => {
        expect(parseCardParam('2')).toBe(2);
        expect(parseCardParam('-7')).toBe(-7);
        expect(parseCardParam('0')).toBeNull();
        expect(parseCardParam('2x')).toBeNull();
        expect(parseCardParam('')).toBeNull();
        expect(parseCardParam(null)).toBeNull();
    });
});

describe('계책 쓰기 시트', () => {
    it('대응 카드(간파): 「계책 걸기」 — 카드 · 방어 칸은 서버 대기 · 비용 서버 대기 · 「간파 걸기」는 준비 중', () => {
        renderSheet(2);
        const sheet = screen.getByRole('complementary', { name: '계책 걸기' });
        expect(within(sheet).getByRole('heading', { level: 3, name: '간파' })).toBeInTheDocument();
        expect(within(sheet).getByText('대응')).toBeInTheDocument();
        expect(within(sheet).getByText('상대 계책 한 장을 무효로 한다.')).toBeInTheDocument();
        expect(within(sheet).getByRole('img', { name: '간파 카드 그림' })).toHaveAttribute('src', '/stratagem-cards/ganpa.webp');
        expect(within(sheet).getByText('공격받을 때 공개됩니다.')).toBeInTheDocument();
        const target = within(sheet).getByRole('region', { name: '어느 방어 칸에 걸까' });
        expect(within(target).getByText('대상 후보 준비 중')).toBeInTheDocument();
        // 후보 · 사거리를 지어내지 않는다 — 고를 목록이 없다.
        expect(within(sheet).queryByRole('listbox')).toBeNull();
        expect(within(sheet).getByText('비용').nextElementSibling).toHaveTextContent('서버 대기');
        const act = within(sheet).getByRole('button', { name: /간파 걸기/ });
        expect(act).toHaveAttribute('data-input-id', 'stratagem.play');
        expect(act).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
    });

    it('준비 중 단추를 누르면 사유가 열리고, 사유 안 Esc 는 시트를 닫지 않는다', () => {
        const { onClose } = renderSheet(2);
        fireEvent.click(screen.getByRole('button', { name: /간파 걸기/ }));
        const reason = screen.getByRole('dialog', { name: '계책 쓰기 — 아직 열리지 않았습니다' });
        expect(reason).toHaveTextContent('준비 중');
        fireEvent.keyDown(within(reason).getAllByRole('button')[0], { key: 'Escape' });
        expect(onClose).not.toHaveBeenCalled();
    });

    it('손패 줄에서 다른 카드를 고르면 onPick · 닫기와 Esc 는 onClose(한글 조합 중 Esc 는 두기)', () => {
        const { onPick, onClose } = renderSheet(2);
        const row = screen.getByRole('group', { name: '손패' });
        expect(within(row).getByRole('button', { name: '간파' })).toHaveAttribute('aria-pressed', 'true');
        fireEvent.click(within(row).getByRole('button', { name: '견벽' }));
        expect(onPick).toHaveBeenCalledWith(1);
        const sheet = screen.getByTestId('stratagem-play');
        fireEvent.keyDown(sheet, { key: 'Escape', isComposing: true });
        expect(onClose).not.toHaveBeenCalled();
        fireEvent.keyDown(sheet, { key: 'Escape' });
        expect(onClose).toHaveBeenCalledTimes(1);
        fireEvent.click(screen.getByRole('button', { name: '닫기(Esc)' }));
        expect(onClose).toHaveBeenCalledTimes(2);
    });

    it('손패에 없는 카드 · 형식이 틀린 주소는 「그 카드는 손패에 없습니다」 + 손패 줄(결정 단추 없음)', () => {
        renderSheet(9);
        expect(screen.getByRole('complementary', { name: '계책 쓰기' })).toBeInTheDocument();
        expect(screen.getByText('그 카드는 손패에 없습니다')).toBeInTheDocument();
        expect(screen.getByRole('group', { name: '손패' })).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: /걸기|쓰기$/ })).toBeNull();
    });

    it('첫 손패 전 · 실패는 덱과 같은 문구 — 실패는 다시 시도', () => {
        const { onRetry } = renderSheet(2, { hand: { state: 'error', message: '손패를 불러오지 못했습니다' } });
        fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
        expect(onRetry).toHaveBeenCalled();
    });
});
