'use client';

// SelectNationField — the `nation` arg field-type (legacy processing/SelectNation.vue). Nation list comes
// from api.mapPreview()'s `nations[]` (the only nation roster web/game has without a dedicated endpoint;
// neutral id 0 is excluded). 초성-search dropdown. The caller's own nation is filtered out (you cannot
// 선전포고 your own nation). Diplomatic availability is NOT known client-side (no per-nation diplo read),
// so nothing is marked `(불가)` here — the precheck on submit returns the PHP-faithful BLOCKED reason.
// Emits `destNationID:number`.

import { useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import type { MapPreviewResponse } from '@/lib/types';
import SearchableSelect, { type SelectOption } from './SearchableSelect';

export interface SelectNationFieldProps {
    value: number | null;
    onChange: (value: number) => void;
    /** The caller's own nation id — excluded from the list. */
    ownNationId?: number;
}

export default function SelectNationField({ value, onChange, ownNationId }: SelectNationFieldProps) {
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
            (data?.nations ?? [])
                .filter((n) => n.id !== 0 && n.id !== ownNationId)
                .map((n) => ({ value: n.id, label: n.name, searchText: n.name })),
        [data, ownNationId],
    );

    return (
        <SearchableSelect
            options={options}
            value={value}
            onChange={onChange}
            placeholder="국가 선택 (초성 검색)"
            loading={!data && !failed}
            emptyText={failed ? '국가 목록을 불러올 수 없습니다.' : '선택 가능한 국가가 없습니다.'}
        />
    );
}
