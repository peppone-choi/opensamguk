import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PortraitCropEditor from '@/components/account/PortraitCropEditor';
import { initialCrops } from '@/lib/portraitCrop';

function source(w = 1200, h = 800, fails = false) {
    vi.stubGlobal('Image', class {
        naturalWidth = w; naturalHeight = h;
        onload: (() => void) | null = null;
        onerror: (() => void) | null = null;
        set src(_value: string) { queueMicrotask(() => fails ? this.onerror?.() : this.onload?.()); }
    });
}
const file = new File(['source'], 'photo.jpg', { type: 'image/jpeg' });
describe('manual portrait editor', () => {
    beforeEach(() => {
        source();
        URL.createObjectURL = vi.fn(() => 'blob:portrait');
        URL.revokeObjectURL = vi.fn();
    });
    afterEach(() => vi.unstubAllGlobals());
    it('edits all three independently and resets only selected composition', async () => {
        const changed = vi.fn();
        render(<PortraitCropEditor file={file} onChange={changed} />);
        await screen.findByLabelText('히어로 확대·축소');
        const initial = changed.mock.calls.at(-1)![0];
        fireEvent.change(screen.getByLabelText('히어로 확대·축소'), { target: { value: '2' } });
        const hero = changed.mock.calls.at(-1)![0].hero;
        expect(hero.width).toBeCloseTo(initial.hero.width / 2);
        fireEvent.click(screen.getByRole('button', { name: '아이콘' }));
        fireEvent.change(screen.getByLabelText('아이콘 확대·축소'), { target: { value: '3' } });
        fireEvent.change(screen.getByLabelText('아이콘 좌우 위치'), { target: { value: '1' } });
        const modified = changed.mock.calls.at(-1)![0];
        expect(modified.hero).toEqual(hero);
        expect(modified.card).toEqual(initial.card);
        expect(modified.icon.x + modified.icon.width).toBeCloseTo(1);
        fireEvent.click(screen.getByRole('button', { name: '이 구도 초기화' }));
        expect(changed.mock.calls.at(-1)![0]).toEqual({ ...initial, hero });
        expect(screen.getAllByAltText(/저장 미리보기/)).toHaveLength(3);
    });
    it('uses saved rectangles and revokes original URL after unmount', async () => {
        const saved = initialCrops(1200, 800);
        saved.icon = { x: 0.1, y: 0.2, width: 0.2, height: 0.3 };
        const changed = vi.fn();
        const { unmount } = render(<PortraitCropEditor file={file} initial={saved} onChange={changed} />);
        await waitFor(() => expect(changed).toHaveBeenLastCalledWith(saved));
        expect(screen.getByAltText('아이콘 저장 미리보기').style.left).toBe('-50%');
        unmount();
        expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:portrait');
    });
    it('wheel zooms without modifying other crops', async () => {
        const changed = vi.fn();
        render(<PortraitCropEditor file={file} onChange={changed} />);
        const frame = await screen.findByLabelText('히어로 자르기 영역');
        fireEvent.wheel(frame, { deltaY: -200 });
        const next = changed.mock.calls.at(-1)![0];
        expect(next.hero.width).toBeLessThan(initialCrops(1200, 800).hero.width);
        expect(next.card).toEqual(initialCrops(1200, 800).card);
    });
    it('rejects excessive pixel dimensions before making an editable crop', async () => {
        source(8192, 8192);
        const changed = vi.fn();
        render(<PortraitCropEditor file={file} onChange={changed} />);
        expect(await screen.findByRole('alert')).toHaveTextContent('2,400만 화소');
        expect(changed).toHaveBeenLastCalledWith(null);
    });
    it('explains decode failure and never enables upload', async () => {
        source(0, 0, true);
        const changed = vi.fn();
        render(<PortraitCropEditor file={file} onChange={changed} />);
        expect(await screen.findByRole('alert')).toHaveTextContent('이미지를 읽지 못했습니다');
        expect(changed).toHaveBeenLastCalledWith(null);
    });
});
