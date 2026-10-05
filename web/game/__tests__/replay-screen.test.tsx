// 다시 보기(P-H03) — 계약판 K5-09 가 없어 서버 대기로만 그린다(보드 V31K5ReplayWait). 번호 검사 · 링크 · 표지 · 판 배율 도우미.
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { expectServerWait } from '@opensamguk/ui';
import { clampCenter, fitScale, REPLAY_MAX_SCALE, zoomScale } from '@/components/battle/ReplayBoard';

vi.mock('@/components/campaign/CampaignLink', () => ({
    default: ({ slug, children, ...rest }: { slug: string; children: React.ReactNode }) => <a href={`/game/pep/${slug}`} {...rest}>{children}</a>,
}));

import ReplayScreen, { REPLAY_WAIT_TITLE, replayBattleId } from '@/components/records/ReplayScreen';

describe('replayBattleId', () => {
    it('v2 Long 10진 문자열(1 이상)만 받는다', () => {
        expect(replayBattleId('42')).toBe('42');
        expect(replayBattleId('9223372036854775807')).toBe('9223372036854775807');
        expect(replayBattleId('9223372036854775808')).toBeNull();
        expect(replayBattleId('0')).toBeNull();
        expect(replayBattleId('007')).toBeNull();
        expect(replayBattleId('-3')).toBeNull();
        expect(replayBattleId('abc')).toBeNull();
        expect(replayBattleId(null)).toBeNull();
    });
});

describe('ReplayScreen', () => {
    it('맞는 번호면 서버 대기(K5-09) — 결과 반영 뒤 공개 안내와 전투 · 기록 링크, 다른 값은 지어내지 않는다', () => {
        const { container } = render(<ReplayScreen rawId="42" />);
        expectServerWait(container, ['K5-09']);
        expect(screen.getByText(REPLAY_WAIT_TITLE)).toBeTruthy();
        expect(screen.getByRole('link', { name: '군단 › 전투로' }).getAttribute('href')).toBe('/game/pep/corps/battle');
        expect(screen.getByRole('link', { name: '기록으로' }).getAttribute('href')).toBe('/game/pep/records');
        expect(container.querySelector('canvas')).toBeNull();
    });

    it('틀린 번호면 서버 대기가 아니라 「번호가 올바르지 않습니다」 + 기록으로', () => {
        const { container } = render(<ReplayScreen rawId="abc" />);
        expectServerWait(container, []);
        expect(screen.getByText('다시 볼 전투 번호가 올바르지 않습니다')).toBeTruthy();
        expect(screen.getByRole('link', { name: '기록으로' }).getAttribute('href')).toBe('/game/pep/records');
    });
});

describe('ReplayBoard 배율 도우미', () => {
    const board = { width: 2048, height: 1024 };

    it('가장 작은 배율은 판 전체가 들어오는 배율이다', () => {
        expect(fitScale(board, { width: 768, height: 408 })).toBeCloseTo(0.375);
        expect(fitScale(board, { width: 390, height: 207 })).toBeCloseTo(390 / 2048);
        expect(fitScale(board, { width: 0, height: 0 })).toBe(1);
    });

    it('확대 · 축소는 판 전체 ~ 가장 크게 사이로 자른다', () => {
        expect(zoomScale(1, 1, 0.375)).toBe(1.5);
        expect(zoomScale(1, -1, 0.375)).toBeCloseTo(1 / 1.5);
        expect(zoomScale(0.4, -1, 0.375)).toBe(0.375);
        expect(zoomScale(3.5, 1, 0.375)).toBe(REPLAY_MAX_SCALE);
    });

    it('가운데 점은 판 밖으로 나가지 않는다', () => {
        expect(clampCenter({ x: -10, y: 5000 }, board)).toEqual({ x: 0, y: 1024 });
        expect(clampCenter({ x: 300, y: 200 }, board)).toEqual({ x: 300, y: 200 });
    });
});
