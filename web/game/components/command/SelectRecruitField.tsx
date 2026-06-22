'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import { ICON_CDN } from '@/lib/constants';
import type { FrontInfoResponse, GameConstResponse, GameUnitConstItem } from '@/lib/types';

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
    const [failed, setFailed] = useState(false);
    const [crewType, setCrewType] = useState<number | null>(null);
    const [amountUnit, setAmountUnit] = useState(0);

    useEffect(() => {
        let on = true;
        Promise.all([api.gameConst(), api.frontInfo()])
            .then(([constants, front]) => {
                if (!on) return;
                setConstData(constants);
                setFrontInfo(front);
            })
            .catch(() => on && setFailed(true));
        return () => {
            on = false;
        };
    }, []);

    const units = useMemo(() => constData?.gameUnitConst ?? [], [constData]);
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
        if (crewType != null || units.length === 0) return;
        const current = currentGeneral?.crewTypeId;
        const next = current && units.some((u) => u.id === current) ? current : units[0].id;
        setCrewType(next);
        setAmountUnit(fillAmountFor(next));
    }, [crewType, currentGeneral, fillAmountFor, units]);

    useEffect(() => {
        if (crewType == null) {
            onChange(null);
            return;
        }
        onChange({ crewType, amount: clampAmount(amountUnit, maxAmountUnit) * 100 });
    }, [amountUnit, crewType, maxAmountUnit, onChange]);

    const grouped = useMemo(() => {
        const result = new Map<number, GameUnitConstItem[]>();
        for (const unit of units) {
            const list = result.get(unit.armType) ?? [];
            list.push(unit);
            result.set(unit.armType, list);
        }
        return Array.from(result.entries());
    }, [units]);
    const selected = units.find((unit) => unit.id === crewType) ?? null;

    function chooseUnit(id: number) {
        setCrewType(id);
        setAmountUnit(fillAmountFor(id));
    }

    if (!constData && !failed) return <p className="cmd-select-empty">병종 정보를 불러오는 중입니다.</p>;
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
                            {armUnits.map((unit) => (
                                <button
                                    key={unit.id}
                                    type="button"
                                    className={unit.id === crewType ? 'selected' : ''}
                                    onClick={() => chooseUnit(unit.id)}
                                >
                                    <img src={`${ICON_CDN}/crewtype${unit.id}.png`} alt="" width={28} height={28} />
                                    <span>{unit.name}</span>
                                </button>
                            ))}
                        </div>
                    </section>
                ))}
            </div>
        </div>
    );
}
