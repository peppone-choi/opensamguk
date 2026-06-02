'use client';

// SelectGeneralField — the `general` arg field-type (legacy processing/SelectGeneral.vue). The legacy row
// is `name(color) (통/무/지)`; web/game's nation-scoped roster is api.myGenerals() (the only general list
// available without a broader read endpoint), so this targets generals in the caller's own nation
// (commands like 부대탈퇴지시). 초성-search dropdown; the row label appends the (통/무/지) triple as the
// legacy does. The caller's own general is filtered out. Emits `destGeneralID:number`.

import { useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import type { MyGeneralsResponse } from '@/lib/types';
import SearchableSelect, { type SelectOption } from './SearchableSelect';

export interface SelectGeneralFieldProps {
    value: number | null;
    onChange: (value: number) => void;
    /** The caller's own general id — excluded from the list. */
    ownGeneralId?: number | null;
}

export default function SelectGeneralField({ value, onChange, ownGeneralId }: SelectGeneralFieldProps) {
    const [data, setData] = useState<MyGeneralsResponse | null>(null);
    const [failed, setFailed] = useState(false);

    useEffect(() => {
        let on = true;
        api.myGenerals<MyGeneralsResponse>()
            .then((d) => on && setData(d))
            .catch(() => on && setFailed(true));
        return () => {
            on = false;
        };
    }, []);

    const options: SelectOption[] = useMemo(
        () =>
            (data?.generals ?? [])
                .filter((g) => g.generalId !== ownGeneralId)
                .map((g) => ({
                    value: g.generalId,
                    label: g.name,
                    info: `${g.leadership}/${g.strength}/${g.intel}`,
                    searchText: g.name,
                })),
        [data, ownGeneralId],
    );

    return (
        <SearchableSelect
            options={options}
            value={value}
            onChange={onChange}
            placeholder="장수 선택 (초성 검색)"
            loading={!data && !failed}
            emptyText={failed ? '장수 목록을 불러올 수 없습니다.' : '선택 가능한 장수가 없습니다.'}
        />
    );
}
