'use client';

// BettingDetail — legacy hwe/ts/components/BettingDetail.vue 충실 포팅(FE grand truth, PHP가 이김).
// 후보 선택(단일/다중 selectCnt) + 선택율 + 베팅 제출 + 배당 순위(기대배율/확정배율) 렌더.
//
// read: game-api `GET /api/bettings/{id}/detail`(D5, per-OWNER 인증 필요)
//   → {result, bettingInfo(raw: candidates 인라인 + winner + isHtml), bettingDetail[], myBetting[],
//      remainPoint, year, month}.
//   주의: BE는 bettingDetail/myBetting을 legacy `[string, number][]` 튜플이 아니라
//   `{bettingType, sumAmount}[]` 배열로 날린다(BettingDto.kt). 본 컴포넌트가 그 모양을 소비한다.
//   배당(odds)은 BE가 계산해주지 않는다 — 여기서 bettingDetail로 직접 산출(legacy 동일).
// write: api.commands.placeBet({bettingId, bettingType, amount})(wire 코드 기존).

import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../../lib/api';

// ── 와이어 타입 ──────────────────────────────────────────────────────────────────────────
interface SelectItem {
    title: string;
    info?: string;
    isHtml?: boolean;
    aux?: Record<string, unknown>;
}

interface BettingInfo {
    id: number;
    type: string;
    name: string;
    finished: boolean;
    selectCnt: number;
    isExclusive?: boolean;
    reqInheritancePoint: boolean;
    openYearMonth: number;
    closeYearMonth: number;
    candidates: Record<string, SelectItem>;
    winner?: number[];
}

interface BettingDetailItem {
    bettingType: string;
    sumAmount: number;
}

interface BettingDetailResponse {
    result: boolean;
    bettingInfo: BettingInfo;
    bettingDetail: BettingDetailItem[];
    myBetting: BettingDetailItem[];
    remainPoint: number;
    year: number;
    month: number;
}

// legacy util/parseYearMonth.ts / joinYearMonth.ts 충실 포팅.
function parseYearMonth(yearMonth: number): [number, number] {
    return [(yearMonth / 12) | 0, (yearMonth % 12) + 1];
}
function joinYearMonth(year: number, month: number): number {
    return year * 12 + month - 1;
}

function sum(values: number[]): number {
    return values.reduce((acc, v) => acc + v, 0);
}

// lodash range(start, end, step) 동치(end 미포함).
function range(start: number, end: number, step: number): number[] {
    const out: number[] = [];
    if (step > 0) for (let i = start; i < end; i += step) out.push(i);
    else for (let i = start; i > end; i += step) out.push(i);
    return out;
}

interface Props {
    bettingId: number;
    generalId: number | null;
    onToast: (msg: string) => void;
}

export default function BettingDetail({ bettingId, generalId, onToast }: Props) {
    const [detail, setDetail] = useState<BettingDetailResponse | undefined>();
    const [yearMonth, setYearMonth] = useState<number>(0);

    const [pickedBetType, setPickedBetType] = useState<Set<number>>(new Set());
    const [pickedBetTypeKey, setPickedBetTypeKey] = useState<string>('[]');
    const [betPoint, setBetPoint] = useState<number>(0);

    const info = detail?.bettingInfo;

    const loadBetting = useCallback(async (id: number) => {
        try {
            const result = await api.bettingDetail<BettingDetailResponse>(id);
            setDetail(result);
            setYearMonth(joinYearMonth(result.year, result.month));
            setPickedBetType(new Set());
            setPickedBetTypeKey('[]');
        } catch (e) {
            console.error(e);
            onToast('베팅 상세를 불러올 수 없습니다.');
        }
    }, [onToast]);

    useEffect(() => {
        void loadBetting(bettingId);
    }, [bettingId, loadBetting]);

    // ── 파생 계산(legacy loadBetting 본문) ──────────────────────────────────────────────
    const derived = useMemo(() => {
        if (!detail || !info) return undefined;

        // candidates별 부분합(partialBet) + 사용자 베팅 정렬(betSort) + 총액/순수총액 계산.
        const partialBet = new Map<number, number>();
        const betSort = new Map<string, number>();
        let bettingAmount = 0;
        let adminBettingAmount = 0;

        for (const { bettingType, sumAmount } of detail.bettingDetail) {
            const amount = sumAmount;
            let userBet = true;
            const subTypes = JSON.parse(bettingType) as number[];
            for (const subType of subTypes) {
                if (subType < 0) { userBet = false; continue; }
                partialBet.set(subType, (partialBet.get(subType) ?? 0) + amount);
            }
            if (userBet) betSort.set(bettingType, (betSort.get(bettingType) ?? 0) + amount);
            bettingAmount += amount;
            if (!userBet) adminBettingAmount += amount;
        }

        const pureBettingAmount = bettingAmount - adminBettingAmount;
        const maxBettingReward = (info.isExclusive || info.selectCnt === 1)
            ? bettingAmount
            : bettingAmount / 2;

        // detailBet = betSort entries DESC by value(legacy sort).
        const detailBet = Array.from(betSort.entries()).sort(([, l], [, r]) => r - l);

        const winner = new Set<number>(info.winner ?? []);

        const myBettings = new Map<string, number>();
        for (const { bettingType, sumAmount } of detail.myBetting) {
            myBettings.set(bettingType, sumAmount);
        }

        // calcMatchPointWithColor(legacy).
        function calcMatchPointWithColor(type: string): [number, string | undefined] {
            if (!info!.finished) return [0, undefined];
            const subTypes = JSON.parse(type) as number[];
            if (subTypes[0] < -1) return [0, undefined];
            let matchPoint = 0;
            for (const subType of subTypes) if (winner.has(subType)) matchPoint += 1;
            if (info!.isExclusive) {
                return matchPoint === info!.selectCnt ? [matchPoint, 'green'] : [matchPoint, 'red'];
            }
            let color = 'green';
            if (matchPoint === 0) color = 'red';
            else if (matchPoint < info!.selectCnt) color = 'yellow';
            return [matchPoint, color];
        }

        // calcReward(legacy) — 확정 시 matchPoint 그룹별 상금 배분.
        const selectCnt = info.selectCnt;
        const calculatedReward = new Array<number>(selectCnt).fill(0);
        const calculatedSubAmount = new Map<number, number>();
        if (info.finished) {
            for (const { bettingType, sumAmount } of detail.bettingDetail) {
                if (sumAmount === 0) continue;
                const [matchPoint] = calcMatchPointWithColor(bettingType);
                calculatedSubAmount.set(matchPoint, (calculatedSubAmount.get(matchPoint) ?? 0) + sumAmount);
            }
            if (selectCnt === 1 || info.isExclusive) {
                calculatedReward[selectCnt - 1] = bettingAmount;
            } else {
                let remainRewardAmount = bettingAmount;
                let accumulatedRewardAmount = 0;
                let givenRewardAmount = bettingAmount;
                for (const matchPoint of range(selectCnt, 0, -1)) {
                    givenRewardAmount /= 2;
                    accumulatedRewardAmount += givenRewardAmount;
                    if (!calculatedSubAmount.has(matchPoint)) continue;
                    calculatedReward[matchPoint] = accumulatedRewardAmount;
                    remainRewardAmount -= accumulatedRewardAmount;
                    accumulatedRewardAmount = 0;
                }
                // 남은 상금은 '당첨자'에게 몰아준다. 없으면 0개 맞춘 그룹.
                for (const matchPoint of range(selectCnt, -1, -1)) {
                    if (!calculatedSubAmount.has(matchPoint)) continue;
                    calculatedReward[matchPoint] += remainRewardAmount;
                    break;
                }
            }
        }

        return {
            partialBet, detailBet, bettingAmount, pureBettingAmount, maxBettingReward,
            winner, myBettings, calcMatchPointWithColor, calculatedReward, calculatedSubAmount,
        };
    }, [detail, info]);

    // typeMap 캐시(getTypeStr) — candidates 인덱스 배열 → 콤마 결합 타이틀.
    const getTypeStr = useCallback((type: string): string => {
        if (!info) return type;
        const subTypes = JSON.parse(type) as number[];
        if (subTypes[0] < -1) return 'Invalid';
        return subTypes.map(idx => info.candidates[idx]?.title ?? '').join(', ');
    }, [info]);

    // ── 후보 토글(legacy toggleCandidate) ──────────────────────────────────────────────
    function toggleCandidate(idx: number) {
        if (!info || !detail) return;
        if (info.closeYearMonth < yearMonth) return;
        if (info.finished) return;
        const selectCnt = info.selectCnt;
        if (selectCnt === 1) {
            setPickedBetType(new Set([idx]));
            setPickedBetTypeKey(JSON.stringify([idx]));
            return;
        }
        const next = new Set(pickedBetType);
        if (next.has(idx)) {
            next.delete(idx);
        } else if (next.size < selectCnt) {
            next.add(idx);
        } else {
            onToast(`이미 ${selectCnt}개를 선택했습니다.`);
            return;
        }
        setPickedBetType(next);
        const arr = Array.from(next.values()).sort((l, r) => l - r);
        setPickedBetTypeKey(JSON.stringify(arr));
    }

    async function submitBet() {
        if (!info) return;
        if (generalId == null) {
            onToast('장수가 없어 베팅할 수 없습니다.');
            return;
        }
        const bettingType = JSON.parse(pickedBetTypeKey) as number[];
        try {
            await api.commands.placeBet(
                { bettingId: info.id, bettingType, amount: betPoint },
                generalId,
            );
            onToast('베팅했습니다');
            await loadBetting(info.id);
        } catch (e) {
            console.error(e);
            onToast('베팅에 실패했습니다.');
        }
    }

    if (!detail || !info || !derived) {
        return <div style={{ color: 'var(--text-muted)' }}>베팅 정보를 불러오는 중...</div>;
    }

    const {
        partialBet, detailBet, bettingAmount, pureBettingAmount, maxBettingReward,
        winner, myBettings, calcMatchPointWithColor, calculatedReward, calculatedSubAmount,
    } = derived;

    const candidateEntries = Object.entries(info.candidates);
    const bettingOpen = !info.finished && yearMonth <= info.closeYearMonth;

    return (
        <div>
            <div className="bg2">
                {info.name}
                {info.finished && <span>(종료)</span>}
                {!info.finished && yearMonth <= info.closeYearMonth && (
                    <span>
                        {' '}({parseYearMonth(info.closeYearMonth)[0]}년 {parseYearMonth(info.closeYearMonth)[1]}월까지)
                    </span>
                )}
                {!info.finished && yearMonth > info.closeYearMonth && <span> (베팅 마감)</span>}
                {' '}(총액: {bettingAmount.toLocaleString()})
            </div>

            {/* 후보 카드(선택율). */}
            <div className="candidates">
                {candidateEntries.map(([idxStr, candidate]) => {
                    const idx = parseInt(idxStr, 10);
                    const picked = pickedBetType.has(idx) || (info.finished && winner.has(idx));
                    const pickRate = pureBettingAmount > 0
                        ? (((partialBet.get(idx) ?? 0) / pureBettingAmount) * 100).toFixed(1)
                        : '0.0';
                    return (
                        <div key={idx} className="candidateCell" onClick={() => toggleCandidate(idx)}>
                            <div className={picked ? 'candidate picked' : 'candidate'}>
                                <div className="title bg1">{candidate.title}</div>
                                {candidate.isHtml
                                    ? <div className="info" dangerouslySetInnerHTML={{ __html: candidate.info ?? '' }} />
                                    : <div className="info">{candidate.info}</div>}
                                <div className="pickRate">선택율: {pickRate}%</div>
                            </div>
                        </div>
                    );
                })}
            </div>

            {/* 베팅 입력(진행 중이고 마감 전일 때만). */}
            {bettingOpen && (
                <div className="betInput">
                    <span>잔여 {info.reqInheritancePoint ? '포인트' : '금'} : {detail.remainPoint.toLocaleString()}</span>
                    <span>사용 포인트: {sum(Array.from(myBettings.values())).toLocaleString()}</span>
                    <span>대상: {getTypeStr(pickedBetTypeKey)}</span>
                    <input
                        type="number" value={betPoint} min={10} max={1000} step={10}
                        onChange={e => setBetPoint(Math.trunc(Number(e.target.value)))}
                    />
                    <button onClick={() => void submitBet()}>베팅</button>
                </div>
            )}

            {/* 배당 순위. */}
            <div>
                <div className="bg2">배당 순위</div>
                <div className="oddsHeader">
                    <div className="text-center">대상</div>
                    <div className="text-center">베팅액</div>
                    <div className="text-center">내 베팅</div>
                    <div className="text-center">{info.finished ? '배율' : '기대 배율'}</div>
                </div>
                {detailBet.map(([betType, amount]) => {
                    const mine = myBettings.has(betType);
                    if (info.finished) {
                        const [matchPoint, color] = calcMatchPointWithColor(betType);
                        const subAmount = calculatedSubAmount.get(matchPoint) ?? 1;
                        const reward = calculatedReward[matchPoint] ?? 0;
                        const ratio = reward === 0 ? '0' : (reward / subAmount).toFixed(1);
                        const subPoint = myBettings.get(betType) ?? 0;
                        const myReward = reward === 0 ? '0' : ((subPoint * reward) / subAmount).toFixed(1);
                        return (
                            <div key={betType} className="oddsRow">
                                <div style={{ fontWeight: mine ? 'bold' : undefined, color: color ?? undefined }}>
                                    {getTypeStr(betType)}
                                </div>
                                <div className="text-end">{amount.toLocaleString()}</div>
                                <div className="text-center">
                                    {mine ? `(${subPoint.toLocaleString()} -> ${myReward})` : ''}
                                </div>
                                <div className="text-end">{ratio}배</div>
                            </div>
                        );
                    }
                    const subPoint = myBettings.get(betType) ?? 0;
                    const myReward = ((subPoint * maxBettingReward) / amount).toFixed(1);
                    const ratio = (maxBettingReward / amount).toFixed(1);
                    return (
                        <div key={betType} className="oddsRow">
                            <div style={{ fontWeight: mine ? 'bold' : undefined }}>{getTypeStr(betType)}</div>
                            <div className="text-end">{amount.toLocaleString()}</div>
                            <div className="text-center">
                                {mine ? `(${subPoint.toLocaleString()} -> ${myReward})` : ''}
                            </div>
                            <div className="text-end">{ratio}배</div>
                        </div>
                    );
                })}
            </div>

            <style jsx>{`
                .bg1 { font-weight: 600; color: var(--text-secondary); }
                .bg2 { font-weight: 700; color: var(--text-primary); padding: var(--space-xs) 0; }
                .text-center { text-align: center; }
                .text-end { text-align: end; }
                .candidates {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
                    gap: var(--space-sm);
                    margin-bottom: var(--space-md);
                }
                .candidateCell { cursor: pointer; }
                .candidate {
                    border: 1px solid var(--border-subtle);
                    border-radius: var(--radius-sm);
                    overflow: hidden;
                }
                .candidate.picked { border-color: var(--gold); background: rgba(201, 162, 39, 0.12); }
                .candidate .title { padding: var(--space-xs); text-align: center; }
                .candidate .info { padding: var(--space-xs); font-size: var(--text-sm); }
                .candidate .pickRate { padding: var(--space-xs); font-size: var(--text-xs); color: var(--text-muted); }
                .betInput {
                    display: flex;
                    flex-wrap: wrap;
                    gap: var(--space-sm);
                    align-items: center;
                    margin-bottom: var(--space-md);
                }
                .betInput input { width: 8rem; }
                .oddsHeader, .oddsRow {
                    display: grid;
                    grid-template-columns: 5fr 2fr 3fr 2fr;
                    border-bottom: gray solid 1px;
                    padding: var(--space-xs) 0;
                }
                .oddsHeader { color: var(--text-secondary); }
            `}</style>
        </div>
    );
}
