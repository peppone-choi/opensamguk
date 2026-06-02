'use client';

// SelectAmountField — the `amount` arg field-type (legacy processing/SelectAmount.vue). Numeric input
// clamped to [min,max] plus the legacy quick-adjust button row (-만 / -천 / -백 / +백 / +천 / +만),
// each shown only when the max is large enough (legacy `v-if="maxAmount > N"`). Optional guide dropdown
// (legacy `amountGuide`) for quick presets. Emits `amount:number` (clamped). Commands: 세금/헌납/기부.

import { useCallback } from 'react';

export interface SelectAmountFieldProps {
    value: number | null;
    onChange: (value: number) => void;
    min?: number;
    max?: number;
    /** Quick-preset guide values (legacy amountGuide). */
    guide?: number[];
}

export default function SelectAmountField({
    value,
    onChange,
    min = 0,
    max = 1_000_000,
    guide,
}: SelectAmountFieldProps) {
    const current = value ?? min;
    const clamp = useCallback((v: number) => Math.min(max, Math.max(min, v)), [min, max]);
    const adjust = (delta: number) => onChange(clamp(current + delta));

    return (
        <div className="cmd-amount">
            <div className="cmd-amount-row">
                {max > 20000 && <button type="button" onClick={() => adjust(-10000)}>-만</button>}
                {max > 2000 && <button type="button" onClick={() => adjust(-1000)}>-천</button>}
                {max > 200 && <button type="button" onClick={() => adjust(-100)}>-백</button>}
                <input
                    type="number"
                    className="cmd-amount-input"
                    value={current}
                    min={min}
                    max={max}
                    onChange={(e) => onChange(clamp(Number(e.target.value)))}
                    placeholder="금액"
                />
                {max > 200 && <button type="button" onClick={() => adjust(100)}>+백</button>}
                {max > 2000 && <button type="button" onClick={() => adjust(1000)}>+천</button>}
                {max >= 10000 && <button type="button" onClick={() => adjust(10000)}>+만</button>}
            </div>
            {guide && guide.length > 0 && (
                <div className="cmd-amount-guide">
                    {guide.map((g) => (
                        <button key={g} type="button" onClick={() => onChange(clamp(g))}>
                            {g.toLocaleString('ko-KR')}
                        </button>
                    ))}
                </div>
            )}
            <div className="cmd-amount-hint">
                {min.toLocaleString('ko-KR')} ~ {max.toLocaleString('ko-KR')} 범위
            </div>
        </div>
    );
}
