'use client';

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
                    info: `통 ${g.leadership} / 무 ${g.strength} / 지 ${g.intel} / 정치 ${g.politics ?? '-'} / 매력 ${g.charm ?? '-'}`,
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
