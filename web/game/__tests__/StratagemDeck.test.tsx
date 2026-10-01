// 계책 덱(P-S01) — 서버 손패만 · 카드 쓰기는 「준비 중」 · 첫 손패 전 ≠ 빈 손패 ≠ 실패 · 덱 기여 · 지난 발동은 서버 대기.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { StratagemDeck } from '../components/stratagem/StratagemDeck';
import { CARD_ART, toHandView } from '../lib/stratagem/hand';

const ready = (cards: { instanceId: number; type: string; label: string }[]) =>
    toHandView({ loading: false, error: null, data: { status: 'READY', cards, handLimit: 3, canUse: false } });

describe('손패 모델', () => {
    it('상태를 가른다 — 첫 손패 전 · 실패 · 규칙 밖 서버(오류 번호)', () => {
        expect(toHandView({ loading: false, error: null, data: { status: 'NOT_READY', cards: [], handLimit: 3, canUse: false } })).toEqual({ state: 'first-draw' });
        expect(toHandView({ loading: false, error: '500', data: null }).state).toBe('error');
        expect(toHandView({ loading: false, error: null, data: { status: 'UNSUPPORTED_WORLD_FORMAT' as never, cards: [], handLimit: 3, canUse: false } }))
            .toEqual({ state: 'error', message: '이 서버에서는 손패를 읽을 수 없습니다', code: 'UNSUPPORTED_WORLD_FORMAT' });
    });

    it('모르는 카드 종류는 칩 · 설명 없이 이름만', () => {
        const v = ready([{ instanceId: 1, type: 'NEW_KIND', label: '새 카드' }]);
        expect(v).toMatchObject({ state: 'ready', cards: [{ label: '새 카드', mode: null, effect: null, art: null }] });
    });
});

describe('카드 그림 export(opensamguk-images 정본)', () => {
    const roots = [resolve(process.cwd(), 'public/stratagem-cards'), resolve(process.cwd(), '../gateway/public/stratagem-cards')];
    const manifests = roots.map((r) => JSON.parse(readFileSync(resolve(r, 'manifest.json'), 'utf8')) as { source: string; cards: Record<string, { file: string; sha256: string }> });

    it('게임 · 게이트웨이 사본이 같고, 파일이 manifest 해시와 맞고, 코드 표와 같다', () => {
        expect(manifests[1]).toEqual(manifests[0]);
        expect(manifests[0].source).toMatch(/^opensamguk-images@[0-9a-f]{7,}:exports\/stratagem-cards$/);
        expect(Object.fromEntries(Object.entries(manifests[0].cards).map(([k, v]) => [k, v.file]))).toEqual(CARD_ART);
        for (const root of roots) {
            for (const card of Object.values(manifests[0].cards)) {
                expect(createHash('sha256').update(readFileSync(resolve(root, card.file))).digest('hex'), `${root}/${card.file}`).toBe(card.sha256);
            }
        }
    });

    it('아는 종류만 그림을 붙인다', () => {
        const v = ready([{ instanceId: 1, type: 'FORTIFY', label: '견벽' }, { instanceId: 2, type: 'NEW_KIND', label: '새 카드' }]);
        if (v.state !== 'ready') throw new Error('ready');
        expect(v.cards.map((c) => c.art)).toEqual(['/stratagem-cards/gyeonbyeok.webp', null]);
    });
});

describe('계책 덱 화면', () => {
    it('손패 수 / 한도 · 카드를 누르면 고르고, 걸기는 「준비 중」(stratagem.play PLANNED)', () => {
        render(<StratagemDeck hand={ready([{ instanceId: 7, type: 'FORTIFY', label: '견벽' }, { instanceId: 8, type: 'INSIGHT', label: '간파' }])} onRetry={vi.fn()} />);
        expect(screen.getByText('손패 2 / 3')).toBeInTheDocument();
        const cards = within(screen.getByRole('listbox', { name: '손패 카드' })).getAllByRole('option');
        expect(cards[0]).toHaveAttribute('aria-selected', 'true');
        fireEvent.click(cards[1]);
        expect(cards[1]).toHaveAttribute('aria-selected', 'true');
        const act = screen.getByRole('button', { name: /간파 — 대응 칸에 걸기/ });
        expect(act).toHaveAttribute('data-input-id', 'stratagem.play');
        expect(act).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
        expect(act).toHaveAttribute('aria-disabled', 'true');
        // 비용 · 사거리는 서버 값이 없으니 카드에 그리지 않는다.
        expect(screen.queryByText(/비용 \[/)).toBeNull();
    });

    it('첫 손패 전 · 빈 손패 · 실패는 서로 다른 문구 · 모양', () => {
        const { rerender } = render(<StratagemDeck hand={{ state: 'first-draw' }} onRetry={vi.fn()} />);
        expect(screen.getByText('아직 첫 손패를 받지 않았습니다')).toBeInTheDocument();
        rerender(<StratagemDeck hand={ready([])} onRetry={vi.fn()} />);
        expect(screen.getByText('손패가 비었습니다')).toBeInTheDocument();
        const retry = vi.fn();
        rerender(<StratagemDeck hand={{ state: 'error', message: '손패를 불러오지 못했습니다' }} onRetry={retry} />);
        fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
        expect(retry).toHaveBeenCalled();
    });

    it('덱 기여 · 지난 발동은 서버 대기로 그린다(지어내지 않는다)', () => {
        render(<StratagemDeck hand={ready([])} onRetry={vi.fn()} />);
        expect(screen.getByText('덱 기여 읽기 준비 중')).toBeInTheDocument();
        expect(screen.getByText('발동 기록 준비 중')).toBeInTheDocument();
    });
});
