'use client';

// SelectCityField — the `city` arg field-type (legacy processing/SelectCity.vue). The locked F2 decision
// turns the page-navigation arg collection into a modal sub-form. City list comes from api.mapPreview()
// (the same world snapshot MapViewer consumes — no extra city-list endpoint exists yet). 초성-search
// dropdown over the cities; the level label `【등급】` is the option `info`. Graceful: map snapshot
// missing/empty → empty-state message (never crash, never fabricate). Emits `destCityID:number`.

import { useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import type { MapPreviewResponse } from '@/lib/types';
import SearchableSelect, { type SelectOption } from './SearchableSelect';

const LEVEL_TEXT: Record<number, string> = {
    1: '진', 2: '관', 3: '촌', 4: '이', 5: '소', 6: '중', 7: '대', 8: '특',
};

export interface SelectCityFieldProps {
    value: number | null;
    onChange: (value: number) => void;
}

export default function SelectCityField({ value, onChange }: SelectCityFieldProps) {
    const [data, setData] = useState<MapPreviewResponse | null>(null);
    const [failed, setFailed] = useState(false);

    useEffect(() => {
        let on = true;
        api.mapPreview()
            .then((d) => on && setData(d))
            .catch(() => on && setFailed(true));
        return () => {
            on = false;
        };
    }, []);

    const options: SelectOption[] = useMemo(
        () =>
            (data?.cities ?? []).map((c) => ({
                value: c.id,
                label: c.name,
                info: LEVEL_TEXT[c.level] ?? String(c.level),
                searchText: c.name,
            })),
        [data],
    );

    return (
        <SearchableSelect
            options={options}
            value={value}
            onChange={onChange}
            placeholder="도시 선택 (초성 검색)"
            loading={!data && !failed}
            emptyText={failed ? '도시 목록을 불러올 수 없습니다.' : '선택 가능한 도시가 없습니다.'}
        />
    );
}
