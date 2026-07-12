'use client';

// SelectCityField — the `city` arg field-type (legacy processing/SelectCity.vue). The locked F2 decision
// turns the page-navigation arg collection into a modal sub-form. City list comes from api.mapPreview()
// (the same world snapshot MapViewer consumes — no extra city-list endpoint exists yet). 초성-search
// dropdown over the cities; the level label `【등급】` is the option `info`. Graceful: map snapshot
// missing/empty → empty-state message (never crash, never fabricate). Emits `destCityID:number`.

import { useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import type { FrontInfoResponse, GameCityConstItem, GameConstResponse, MapPreviewResponse } from '@/lib/types';
import MapViewer from '../game/MapViewer';
import SearchableSelect, { type SelectOption } from './SearchableSelect';

const LEVEL_TEXT: Record<number, string> = {
    1: '진', 2: '관', 3: '촌', 4: '이', 5: '소', 6: '중', 7: '대', 8: '특',
};

export interface SelectCityFieldProps {
    commandKey?: string;
    commandName?: string;
    value: number | null;
    onChange: (value: number) => void;
}

interface DistanceRow {
    distance: number;
    cities: GameCityConstItem[];
}

function cityDistanceLimit(commandKey?: string, commandName?: string): number {
    const token = `${commandKey ?? ''} ${commandName ?? ''}`;
    if (/이동|출병/.test(token)) return 1;
    if (/강행|첩보|화계/.test(token)) return 3;
    return 0;
}

function distanceRows(cityConst: GameCityConstItem[], currentCityId: number | null, maxDistance: number): DistanceRow[] {
    if (!currentCityId || maxDistance <= 0) return [];
    const byId = new Map(cityConst.map((c) => [c.id, c]));
    const rows: DistanceRow[] = [];
    const seen = new Set<number>([currentCityId]);
    let frontier = [currentCityId];

    for (let distance = 1; distance <= maxDistance; distance += 1) {
        const next: number[] = [];
        for (const cityId of frontier) {
            const city = byId.get(cityId);
            if (!city) continue;
            for (const idText of Object.keys(city.path ?? {})) {
                const id = Number(idText);
                if (!Number.isFinite(id) || seen.has(id)) continue;
                seen.add(id);
                next.push(id);
            }
        }
        const cities = next.map((id) => byId.get(id)).filter((c): c is GameCityConstItem => Boolean(c));
        if (cities.length > 0) rows.push({ distance, cities });
        frontier = next;
    }

    return rows;
}

export default function SelectCityField({ commandKey, commandName, value, onChange }: SelectCityFieldProps) {
    const [data, setData] = useState<MapPreviewResponse | null>(null);
    const [constData, setConstData] = useState<GameConstResponse | null>(null);
    const [frontInfo, setFrontInfo] = useState<FrontInfoResponse | null>(null);
    const [failed, setFailed] = useState(false);

    useEffect(() => {
        let on = true;
        Promise.all([api.mapPreview(), api.gameConst(), api.frontInfo()])
            .then(([map, constants, front]) => {
                if (!on) return;
                setData(map);
                setConstData(constants);
                setFrontInfo(front);
            })
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
    const currentCityId = frontInfo?.general.cityId ?? null;
    const maxDistance = cityDistanceLimit(commandKey, commandName);
    const nearbyRows = useMemo(
        () => distanceRows(constData?.cityConst ?? [], currentCityId, maxDistance),
        [constData, currentCityId, maxDistance],
    );

    return (
        <div className="cmd-city-field">
            {data && (
                <MapViewer
                    mapData={data}
                    isDetailMap={false}
                    currentCityId={currentCityId}
                    selectedCityId={value}
                    onCitySelect={onChange}
                    gameConst={constData?.gameConst}
                />
            )}
            <SearchableSelect
                options={options}
                value={value}
                onChange={onChange}
                placeholder="도시 선택 (초성 검색)"
                loading={!data && !failed}
                emptyText={failed ? '도시 목록을 불러올 수 없습니다.' : '선택 가능한 도시가 없습니다.'}
            />
            {nearbyRows.length > 0 && (
                <div className="cmd-city-distance">
                    {nearbyRows.map((row) => (
                        <div key={row.distance} className={`cmd-city-distance-row d${row.distance}`}>
                            <span>{row.distance}칸 떨어진 도시:</span>
                            <div>
                                {row.cities.map((city) => (
                                    <button
                                        key={city.id}
                                        type="button"
                                        className={value === city.id ? 'selected' : ''}
                                        onClick={() => onChange(city.id)}
                                    >
                                        {city.name}
                                    </button>
                                ))}
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
