'use client';

import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { EMPTY_COMMAND_NAMES, type ReservedCommandNames } from '@/lib/command-flow/reserved-command-view';

/** 실패한 이름 읽기는 예약 링을 빈 순/실패로 바꾸지 않는다. formatter가 이름 확인 불가를 표시한다. */
export function useReservedCommandNames(generalId: number | null, refreshKey: unknown = 0): ReservedCommandNames {
    const [loaded, setLoaded] = useState<{ generalId: number; names: ReservedCommandNames } | null>(null);
    useEffect(() => {
        if (generalId == null) return undefined;
        let alive = true;
        const mapNames = Promise.resolve().then(() => api.mapPreview()).then(preview => ({
            cities: Object.fromEntries(preview.cities.map(city => [String(city.id), city.displayName?.trim() || city.name])),
            nations: Object.fromEntries((preview.nations ?? []).map(nation => [String(nation.id), nation.name])),
        })).catch(() => ({ cities: {}, nations: {} }));
        const units = Promise.resolve().then(() => api.gameConst()).then(bundle => Object.fromEntries(
            (bundle.gameUnitConst ?? []).map(unit => [String(unit.id), unit.name]),
        )).catch(() => ({}));
        void Promise.all([mapNames, units]).then(([map, unitNames]) => {
            if (alive) setLoaded({ generalId, names: { ...map, units: unitNames } });
        });
        return () => { alive = false; };
    }, [generalId, refreshKey]);
    return loaded?.generalId === generalId ? loaded.names : EMPTY_COMMAND_NAMES;
}
