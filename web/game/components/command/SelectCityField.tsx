'use client';

// SelectCityField — the `city` arg field-type (legacy processing/SelectCity.vue). The locked F2 decision
// turns the page-navigation arg collection into a modal sub-form. City list comes from api.mapPreview()
// (the same world snapshot MapViewer consumes — no extra city-list endpoint exists yet). 초성-search
// dropdown over the cities; the level label `【등급】` is the option `info`. Graceful: map snapshot
// missing/empty → empty-state message (never crash, never fabricate). Emits `destCityID:number`.

import { useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import type {
    FrontInfoResponse,
    GameCityConstItem,
    GameConstResponse,
    MapPreviewCity,
    MapPreviewResponse,
} from '@/lib/types';
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

function cityDistancePolicy(commandKey?: string, commandName?: string): { hardLimit: number; guideLimit: number } {
    const token = `${commandKey ?? ''} ${commandName ?? ''}`;
    if (/이동/.test(token)) return { hardLimit: 1, guideLimit: 1 };
    if (/강행/.test(token)) return { hardLimit: 3, guideLimit: 3 };
    if (/첩보|화계/.test(token)) return { hardLimit: 0, guideLimit: 3 };
    return { hardLimit: 0, guideLimit: 0 };
}

function cityPath(city: MapPreviewCity): string {
    return [city.regionName, city.commanderyName, city.name]
        .filter((part, index, parts): part is string => Boolean(part) && parts.indexOf(part) === index)
        .join(' › ');
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

    const currentCityId = frontInfo?.general.cityId ?? null;
    const ownNationId = frontInfo?.general.nationId ?? null;
    const { hardLimit, guideLimit } = cityDistancePolicy(commandKey, commandName);
    const nearbyRows = useMemo(
        () => distanceRows(constData?.cityConst ?? [], currentCityId, guideLimit),
        [constData, currentCityId, guideLimit],
    );
    const distanceByCity = useMemo(() => new Map(
        nearbyRows.flatMap((row) => row.cities.map((city) => [city.id, row.distance] as const)),
    ), [nearbyRows]);
    const commandToken = `${commandKey ?? ''} ${commandName ?? ''}`;
    const candidateCities = useMemo(() => {
        const cities = data?.cities ?? [];
        if (hardLimit > 0) {
            return cities.filter((city) => (distanceByCity.get(city.id) ?? Number.POSITIVE_INFINITY) <= hardLimit);
        }
        if (/화계/.test(commandToken) && ownNationId != null) {
            return cities.filter((city) => city.nationId !== 0 && city.nationId !== ownNationId);
        }
        if (/첩보/.test(commandToken) && ownNationId != null) {
            return cities.filter((city) => city.nationId !== ownNationId);
        }
        if (/천도/.test(commandToken) && ownNationId != null) {
            return cities.filter((city) => city.nationId === ownNationId);
        }
        return cities;
    }, [commandToken, data, distanceByCity, hardLimit, ownNationId]);
    const options: SelectOption[] = useMemo(() => {
        const nationById = new Map((data?.nations ?? []).map((nation) => [nation.id, nation.name]));
        return candidateCities.map((city) => {
            const distance = distanceByCity.get(city.id);
            const territory = city.nationId === 0
                ? '공백지'
                : city.nationId === ownNationId
                    ? '아국령'
                    : (nationById.get(city.nationId) ?? `국가 ${city.nationId}`);
            const info = [
                LEVEL_TEXT[city.level] ?? String(city.level),
                city.id === currentCityId ? '현재' : null,
                distance == null ? null : `${distance}칸`,
                territory,
                city.isCapital ? '수도' : null,
                city.isCommanderySeat ? '군치' : null,
            ].filter(Boolean).join(' · ');
            const label = cityPath(city);
            return {
                value: city.id,
                label,
                info,
                searchText: `${label} ${city.name} ${city.regionName ?? ''} ${city.commanderyName ?? ''} ${city.id}`,
            };
        });
    }, [candidateCities, currentCityId, data, distanceByCity, ownNationId]);
    const defaultOptions = useMemo(() => {
        if (hardLimit > 0) return options;
        const cityById = new Map(candidateCities.map((city) => [city.id, city]));
        const adjacentIds = new Set(distanceRows(constData?.cityConst ?? [], currentCityId, 1)
            .flatMap((row) => row.cities.map((city) => city.id)));
        const priority = (option: SelectOption): number => {
            const city = cityById.get(option.value);
            if (option.value === value) return 0;
            if (option.value === currentCityId) return 1;
            if (adjacentIds.has(option.value)) return 2;
            if (city?.nationId === ownNationId && city.isCapital) return 3;
            if (city?.nationId === ownNationId && city.isCommanderySeat) return 4;
            if (city?.nationId === ownNationId) return 5;
            if (city?.isCapital) return 6;
            if (city?.isCommanderySeat) return 7;
            return 8;
        };
        return [...options]
            .sort((left, right) => priority(left) - priority(right) || left.label.localeCompare(right.label, 'ko'))
            .slice(0, 20);
    }, [candidateCities, constData, currentCityId, hardLimit, options, ownNationId, value]);
    const candidateCityIds = useMemo(() => new Set(candidateCities.map((city) => city.id)), [candidateCities]);
    const candidateNearbyRows = useMemo(() => nearbyRows
        .map((row) => ({ ...row, cities: row.cities.filter((city) => candidateCityIds.has(city.id)) }))
        .filter((row) => row.cities.length > 0), [candidateCityIds, nearbyRows]);
    const selectCandidate = (cityId: number) => {
        if (candidateCityIds.has(cityId)) onChange(cityId);
    };

    return (
        <div className="cmd-city-field">
            {data && (
                <MapViewer
                    mapData={data}
                    isDetailMap={false}
                    currentCityId={currentCityId}
                    selectedCityId={value}
                    onCitySelect={selectCandidate}
                    gameConst={constData?.gameConst}
                />
            )}
            <SearchableSelect
                options={options}
                defaultOptions={defaultOptions}
                resultLimit={30}
                value={value}
                onChange={onChange}
                placeholder="현 검색 (주·군·현·초성·ID)"
                loading={(!data || (guideLimit > 0 && !constData)) && !failed}
                emptyText={failed ? '도시 목록을 불러올 수 없습니다.' : '선택 가능한 도시가 없습니다.'}
            />
            {candidateNearbyRows.length > 0 && (
                <div className="cmd-city-distance">
                    {candidateNearbyRows.map((row) => (
                        <div key={row.distance} className={`cmd-city-distance-row d${row.distance}`}>
                            <span>{row.distance}칸 떨어진 도시:</span>
                            <div>
                                {row.cities.map((city) => (
                                    <button
                                        key={city.id}
                                        type="button"
                                        className={value === city.id ? 'selected' : ''}
                                        onClick={() => selectCandidate(city.id)}
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
