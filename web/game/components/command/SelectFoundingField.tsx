'use client';

import { useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import type { GameConstResponse, IActionConstItem } from '@/lib/types';

export interface SelectFoundingFieldProps {
    onChange: (value: Record<string, unknown> | null) => void;
}

function nationTypesOf(data: GameConstResponse | null): IActionConstItem[] {
    const typed = data?.iAction?.nationType;
    if (Array.isArray(typed) && typed.length > 0) return typed;
    const fallback = data?.gameConst?.availableNationType;
    if (!Array.isArray(fallback)) return [];
    return fallback.filter((v): v is string => typeof v === 'string').map((value) => ({ value }));
}

export default function SelectFoundingField({ onChange }: SelectFoundingFieldProps) {
    const [data, setData] = useState<GameConstResponse | null>(null);
    const [failed, setFailed] = useState(false);
    const [nationName, setNationName] = useState('');
    const [nationType, setNationType] = useState('');
    const [colorType, setColorType] = useState(0);

    useEffect(() => {
        let on = true;
        api.gameConst()
            .then((res) => {
                if (!on) return;
                setData(res);
            })
            .catch(() => on && setFailed(true));
        return () => {
            on = false;
        };
    }, []);

    const colors = useMemo(
        () => (Array.isArray(data?.gameConst?.nationColors) ? data.gameConst.nationColors : []),
        [data],
    );
    const nationTypes = useMemo(() => nationTypesOf(data), [data]);

    useEffect(() => {
        if (!nationType && nationTypes.length > 0) setNationType(nationTypes[0].value);
    }, [nationType, nationTypes]);

    useEffect(() => {
        const name = nationName.trim();
        if (!name || !nationType || !colors[colorType]) {
            onChange(null);
            return;
        }
        onChange({ nationName: name, nationType, colorType });
    }, [colorType, colors, nationName, nationType, onChange]);

    if (!data && !failed) return <p className="cmd-select-empty">건국 설정을 불러오는 중입니다.</p>;
    if (failed) return <p className="cmd-select-empty">건국 설정을 불러올 수 없습니다.</p>;

    const selectedType = nationTypes.find((item) => item.value === nationType);

    return (
        <div className="cmd-founding">
            <label>
                <span>국명</span>
                <input
                    className="cmd-text-input"
                    value={nationName}
                    maxLength={12}
                    onChange={(e) => setNationName(e.target.value)}
                    placeholder="국가명"
                />
            </label>
            <label>
                <span>성향</span>
                <select value={nationType} onChange={(e) => setNationType(e.target.value)}>
                    {nationTypes.map((item) => (
                        <option key={item.value} value={item.value}>
                            {item.name ?? item.value}
                        </option>
                    ))}
                </select>
            </label>
            {selectedType?.info && selectedType.info.length > 0 && (
                <div className="cmd-type-info">{selectedType.info.join(' ')}</div>
            )}
            <div className="cmd-color-grid" role="group" aria-label="국가 색상">
                {colors.map((color, idx) => (
                    <button
                        key={`${color}-${idx}`}
                        type="button"
                        className={idx === colorType ? 'selected' : ''}
                        style={{ backgroundColor: color }}
                        aria-label={`색상 ${idx + 1}`}
                        onClick={() => setColorType(idx)}
                    />
                ))}
            </div>
        </div>
    );
}
