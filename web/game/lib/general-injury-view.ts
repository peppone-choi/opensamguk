/** Public base abilities remain visible when the private injury rate is unknown. */
export function generalInjuryView(stat: number, injury: number | null | undefined): { value: number; injured: boolean | null } {
    if (typeof injury !== 'number' || !Number.isSafeInteger(injury) || injury < 0 || injury > 100) {
        return { value: stat, injured: null };
    }
    return { value: Math.trunc((stat * (100 - injury)) / 100), injured: injury > 0 };
}
