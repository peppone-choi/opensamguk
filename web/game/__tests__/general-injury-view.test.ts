import { describe, expect, it } from 'vitest';
import { generalInjuryView } from '../lib/general-injury-view';

describe('private injury projection', () => {
    it.each([null, undefined, -1, 101, 1.5, Number.NaN, Number.POSITIVE_INFINITY])('keeps unavailable rate %s unknown and base ability intact', injury => {
        expect(generalInjuryView(71, injury)).toEqual({ value: 71, injured: null });
    });

    it('distinguishes actual healthy zero from a hidden value', () => {
        expect(generalInjuryView(71, 0)).toEqual({ value: 71, injured: false });
    });

    it('preserves truncation for a verified nonzero injury rate', () => {
        expect(generalInjuryView(71, 20)).toEqual({ value: 56, injured: true });
        expect(generalInjuryView(71, 100)).toEqual({ value: 0, injured: true });
    });
});
