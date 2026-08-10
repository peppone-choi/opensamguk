'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import { ICON_CDN } from '@/lib/constants';
import type { FrontInfoResponse, GameConstResponse, GameUnitConstItem, RecruitAvailabilityResponse } from '@/lib/types';

export interface SelectRecruitFieldProps {
    onChange: (value: Record<string, unknown> | null) => void;
}

function armTypeLabel(armType: number): string {
    if (armType === 1) return '보병';
    if (armType === 2) return '궁병';
    if (armType === 3) return '기병';
    if (armType === 4) return '병기';
    if (armType === 5) return '특수';
    return `병과 ${armType}`;
}

function clampAmount(value: number, max: number): number {
    return Math.max(0, Math.min(max, Math.trunc(value || 0)));
}

export default function SelectRecruitField({ onChange }: SelectRecruitFieldProps) {
    const [constData, setConstData] = useState<GameConstResponse | null>(null);
    const [frontInfo, setFrontInfo] = useState<FrontInfoResponse | null>(null);
    const [availability, setAvailability] = useState<RecruitAvailabilityResponse | null>(null);
    const [failed, setFailed] = useState(false);
    const [crewType, setCrewType] = useState<number | null>(null);
    const [amountUnit, setAmountUnit] = useState(0);
    const [showAllUnits, setShowAllUnits] = useState(false);

    useEffect(() => {
        let on = true;
        Promise.all([api.gameConst(), api.frontInfo()])
            .then(async ([constants, front]) => {
                const generalId = front.general.generalId;
                if (generalId == null) throw new Error('general is unavailable');
                const nextAvailability = await api.recruitAvailability(generalId);
                if (!nextAvailability.result) throw new Error('recruit availability is unavailable');
                if (!on) return;
                setConstData(constants);
                setFrontInfo(front);
                setAvailability(nextAvailability);
            })
            .catch(() => on && setFailed(true));
        return () => {
            on = false;
        };
    }, []);

    const units = useMemo(() => constData?.gameUnitConst ?? [], [constData]);
    const availabilityByCrewType = useMemo(
        () => new Map(availability?.crewTypes.map((entry) => [entry.crewType, entry])),
        [availability],
    );
    const unitAvailability = useCallback(
        (unit: GameUnitConstItem): { available: boolean; reason: string | null } =>
            availabilityByCrewType.get(unit.id) ?? {
                available: false,
                reason: '현재 선택할 수 없는 병종입니다.',
            },
        [availabilityByCrewType],
    );
    const visibleUnits = useMemo(
        () => units.filter((unit) => showAllUnits || unitAvailability(unit).available),
        [showAllUnits, unitAvailability, units],
    );
    const currentGeneral = frontInfo?.general ?? null;
    const fullLeadership = Math.max(
        1,
        (currentGeneral?.leadership ?? 1) + Math.max(0, currentGeneral?.leadershipBonus ?? currentGeneral?.lbonus ?? 0),
    );
    const maxAmountUnit = Math.max(1, Math.floor(fullLeadership * 1.2));

    const fillAmountFor = useCallback((nextCrewType: number | null): number => {
        if (!currentGeneral) return 0;
        const currentCrewUnit = Math.floor((currentGeneral.crew ?? 0) / 100);
        if (nextCrewType != null && currentGeneral.crewTypeId === nextCrewType) {
            return clampAmount(fullLeadership - currentCrewUnit, maxAmountUnit);
        }
        return clampAmount(fullLeadership, maxAmountUnit);
    }, [currentGeneral, fullLeadership, maxAmountUnit]);

    useEffect(() => {
        if (crewType != null || visibleUnits.length === 0) return;
        const current = currentGeneral?.crewTypeId;
        const next = current && visibleUnits.some((u) => u.id === current) ? current : visibleUnits[0].id;
        setCrewType(next);
        setAmountUnit(fillAmountFor(next));
    }, [crewType, currentGeneral, fillAmountFor, visibleUnits]);

    useEffect(() => {
        if (crewType == null || visibleUnits.some((unit) => unit.id === crewType)) return;
        const next = visibleUnits[0]?.id ?? null;
        setCrewType(next);
        setAmountUnit(fillAmountFor(next));
    }, [crewType, fillAmountFor, visibleUnits]);

    useEffect(() => {
        if (crewType == null) {
            onChange(null);
            return;
        }
        onChange({ crewType, amount: clampAmount(amountUnit, maxAmountUnit) * 100 });
    }, [amountUnit, crewType, maxAmountUnit, onChange]);

    const grouped = useMemo(() => {
        const result = new Map<number, GameUnitConstItem[]>();
        for (const unit of visibleUnits) {
            const list = result.get(unit.armType) ?? [];
            list.push(unit);
            result.set(unit.armType, list);
        }
        return Array.from(result.entries());
    }, [visibleUnits]);
    const selected = units.find((unit) => unit.id === crewType) ?? null;

    function chooseUnit(id: number) {
        const unit = units.find((u) => u.id === id);
        if (unit && !unitAvailability(unit).available) return;
        setCrewType(id);
        setAmountUnit(fillAmountFor(id));
    }

    if ((!constData || !availability) && !failed) return <p className="cmd-select-empty">병종 정보를 불러오는 중입니다.</p>;
    if (failed) return <p className="cmd-select-empty">병종 정보를 불러올 수 없습니다.</p>;

    return (
        <div className="cmd-recruit">
            <div className="cmd-recruit-current">
                <span>현재 병종: {currentGeneral?.crewTypeName ?? '-'}</span>
                <span>병력: {(currentGeneral?.crew ?? 0).toLocaleString('ko-KR')}명</span>
                <span>자금: {(currentGeneral?.gold ?? 0).toLocaleString('ko-KR')}</span>
            </div>
            {selected && (
                <div className="cmd-recruit-selected">
                    <img src={`${ICON_CDN}/crewtype${selected.id}.png`} alt="" width={48} height={48} />
                    <div>
                        <strong>{selected.name}</strong>
                        <span>
                            공격 {selected.attack} / 방어 {selected.defence} / 속도 {selected.speed} / 회피 {selected.avoid}
                        </span>
                        <small>비용 {selected.cost.toLocaleString('ko-KR')}금 / 군량 {selected.rice.toLocaleString('ko-KR')}</small>
                    </div>
                </div>
            )}
            <div className="cmd-recruit-controls">
                <button type="button" onClick={() => setAmountUnit(clampAmount(Math.ceil(fullLeadership * 0.5), maxAmountUnit))}>
                    절반
                </button>
                <button type="button" onClick={() => setAmountUnit(fillAmountFor(crewType))}>
                    채우기
                </button>
                <button type="button" onClick={() => setAmountUnit(maxAmountUnit)}>
                    가득
                </button>
                <label>
                    <span>수량</span>
                    <input
                        type="number"
                        value={amountUnit}
                        min={0}
                        max={maxAmountUnit}
                        onChange={(e) => setAmountUnit(clampAmount(Number(e.target.value), maxAmountUnit))}
                    />
                    <em>00명</em>
                </label>
            </div>
            <label className="cmd-recruit-toggle">
                <input
                    type="checkbox"
                    checked={showAllUnits}
                    onChange={(e) => setShowAllUnits(e.target.checked)}
                />
                <span>전체 병과 보기</span>
            </label>
            {selected && (
                <div className="cmd-recruit-cost">
                    예상 비용 {(amountUnit * selected.cost).toLocaleString('ko-KR')}금 / 군량{' '}
                    {(amountUnit * selected.rice).toLocaleString('ko-KR')}
                </div>
            )}
            <div className="cmd-recruit-list">
                {grouped.map(([armType, armUnits]) => (
                    <section key={armType}>
                        <h4>{armTypeLabel(armType)}</h4>
                        <div>
                            {armUnits.map((unit) => {
                                const availability = unitAvailability(unit);
                                return (
                                    <button
                                        key={unit.id}
                                        type="button"
                                        className={`${unit.id === crewType ? 'selected' : ''}${availability.available ? '' : ' unavailable'}`}
                                        disabled={!availability.available}
                                        title={availability.reason ?? undefined}
                                        onClick={() => chooseUnit(unit.id)}
                                    >
                                        <img src={`${ICON_CDN}/crewtype${unit.id}.png`} alt="" width={28} height={28} />
                                        <span>{unit.name}</span>
                                        {!availability.available && <small>{availability.reason ?? '불가능'}</small>}
                                    </button>
                                );
                            })}
                        </div>
                    </section>
                ))}
            </div>
        </div>
    );
}
