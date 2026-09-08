import { describe, expect, it } from 'vitest';
import { initialCrops, cropAt, moveCrop, zoomCrop, PORTRAIT_FRAMES } from '@/lib/portraitCrop';

describe('portrait crop geometry', () => {
    it('makes three distinct source rectangles with each output aspect ratio', () => {
        const crops = initialCrops(1200, 800);
        for (const key of ['hero', 'card', 'icon'] as const) {
            const r = crops[key];
            expect(r.width * 1200 / (r.height * 800)).toBeCloseTo(PORTRAIT_FRAMES[key].width / PORTRAIT_FRAMES[key].height, 8);
            expect(r.x).toBeGreaterThanOrEqual(0);
            expect(r.x + r.width).toBeLessThanOrEqual(1);
        }
        expect(crops.card.width).not.toEqual(crops.icon.width);
    });
    it('clamps zoom and center even for extreme pan input', () => {
        const r = cropAt(800, 1200, 'icon', 100, -5, 5);
        expect(r.width).toBeCloseTo(1 / 8);
        expect(r.x).toBe(0);
        expect(r.y + r.height).toBeCloseTo(1);
    });
    it('zoom preserves the subject center until constrained by image bounds', () => {
        const original = cropAt(1000, 1000, 'card', 2, 0.4, 0.6);
        const zoomed = zoomCrop(original, 1000, 1000, 'card', 4);
        expect(zoomed.x + zoomed.width / 2).toBeCloseTo(0.4);
        expect(zoomed.y + zoomed.height / 2).toBeCloseTo(0.6);
        expect(zoomed.width).toBeCloseTo(original.width / 2);
    });
    it('moving one crop does not mutate other variants and cannot uncover blank area', () => {
        const crops = initialCrops(800, 1200);
        const before = structuredClone(crops);
        const moved = moveCrop(crops.icon, 5, -5);
        expect(moved.x + moved.width).toBeCloseTo(1);
        expect(moved.y).toBe(0);
        expect(crops).toEqual(before);
    });
});
