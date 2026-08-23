'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import { submitCommandAndAwaitResult } from '../../../lib/commandSubmit';
import { formatNumber } from '../../../lib/format';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import type { FrontInfoResponse } from '../../../lib/types';
import type { MyNationDetailResponse, MyNationCityRef, InheritPointResponse } from '../../../types/game';

const INHERIT_BUFFS = [
    { key: 'warAvoidRatio', label: '전투 회피율', desc: '자신의 전투 회피율 +1% per level' },
    { key: 'warCriticalRatio', label: '전투 필살율', desc: '자신의 전투 필살율 +1% per level' },
    { key: 'warMagicTrialProb', label: '계략 시도 확률', desc: '자신의 계략 시도 확률 +1% per level' },
    { key: 'success', label: '내정 성공 확률', desc: '내정 성공 확률 +1% per level' },
    { key: 'fail', label: '내정 실패 감소', desc: '내정 실패 확률 -1% per level' },
    { key: 'warAvoidRatioOppose', label: '상대 회피율 감소', desc: '상대 회피율 -1% per level' },
    { key: 'warCriticalRatioOppose', label: '상대 필살율 감소', desc: '상대 필살율 -1% per level' },
    { key: 'warMagicTrialProbOppose', label: '상대 계략 확률 감소', desc: '상대 계략 시도 확률 -1% per level' },
];

// legacy func_converter.php newColor() 충실 포팅.
const DARK_COLORS = new Set([
    '', '#330000', '#FF0000', '#800000', '#A0522D', '#FF6347', '#808000',
    '#008000', '#2E8B57', '#008080', '#6495ED', '#0000FF', '#000080',
    '#483D8B', '#7B68EE', '#800080', '#A9A9A9', '#000000',
]);
function newColor(color: string): string {
    return DARK_COLORS.has(color === '' ? '' : color.toUpperCase()) ? '#FFFFFF' : '#000000';
}

export default function NationPage() {
    const [data, setData] = useState<MyNationDetailResponse | null>(null);
    const [myBuffs, setMyBuffs] = useState<Record<string, number>>({});
    // 유산 버프 비용은 현재 API inheritActionCost.buff에서 소비한다. 역사 PHP 직렬화는 동결 회귀 참고일 뿐
    // 현재 제품 정본이 아니다(ADR-LITE-042). web 레이어 상수 사본 금지(D3-04).
    const [buffCosts, setBuffCosts] = useState<number[]>([]);
    // P0-50 — 명령 인테이크는 ?generalId= 필수(누락 시 무조건 400). inherit 페이지 방식대로
    // front-info.general.generalId를 받아 전달한다.
    const [generalId, setGeneralId] = useState<number | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');

    // OPENSAM-196: background=true면 로딩 스피너를 건너뛴다(턴 갱신 시 화면이 잠깐 비는 것을 방지).
    const fetchData = useCallback(async (background = false) => {
        if (!background) setLoading(true);
        try {
            const [nationRes, inheritRes, frontRes] = await Promise.all([
                api.myNationDetail<MyNationDetailResponse>(),
                api.inheritPoint() as Promise<InheritPointResponse>,
                api.frontInfo() as Promise<FrontInfoResponse>,
            ]);
            setData(nationRes);
            if (inheritRes?.currentInheritBuff) {
                setMyBuffs(inheritRes.currentInheritBuff);
            }
            setBuffCosts(inheritRes?.inheritActionCost?.buff ?? []);
            setGeneralId(frontRes?.general?.generalId ?? null);
            setError('');
        } catch {
            // 턴 갱신(background) 실패는 보고 있던 화면을 지우지 않는다.
            if (!background) setError('데이터를 불러올 수 없습니다.');
        } finally {
            if (!background) setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    // OPENSAM-196: 턴 완료 시 국가 정보/유산 버프를 백그라운드로 다시 읽는다.
    useTurnRefresh(() => {
        fetchData(true);
    });

    async function buyBuff(buffKey: string, level: number) {
        const prevLevel = myBuffs[buffKey] ?? 0;
        if (prevLevel >= level) {
            setToast('이미 구입했거나 더 높은 등급을 보유 중입니다.');
            setTimeout(() => setToast(''), 3000);
            return;
        }
        // P0-50 — generalId 없이 보내면 CommandController @RequestParam 400으로 매번 실패했었다.
        if (generalId == null) {
            setToast('장수 정보가 없습니다.');
            setTimeout(() => setToast(''), 3000);
            return;
        }
        try {
            const out = await submitCommandAndAwaitResult(() =>
                api.command('BuyHiddenBuff', { buffKey, level, prevLevel }, generalId));
            if (out.status === 'applied') {
                setToast('구매가 처리되었습니다.');
                fetchData();
            } else if (out.status === 'rejected') {
                setToast(out.reason ?? '구매할 수 없습니다.');
            } else {
                setToast(out.reason);
            }
        } catch {
            setToast('구매 요청에 실패했습니다.');
        }
        setTimeout(() => setToast(''), 3000);
    }

    async function buyRandomUnique() {
        if (generalId == null) {
            setToast('장수 정보가 없습니다.');
            setTimeout(() => setToast(''), 3000);
            return;
        }
        try {
            const out = await submitCommandAndAwaitResult(() => api.command('BuyRandomUnique', {}, generalId));
            if (out.status === 'applied') {
                setToast('구매가 처리되었습니다.');
                fetchData();
            } else if (out.status === 'rejected') {
                setToast(out.reason ?? '구매할 수 없습니다.');
            } else {
                setToast(out.reason);
            }
        } catch {
            setToast('구매 요청에 실패했습니다.');
        }
        setTimeout(() => setToast(''), 3000);
    }

    if (loading) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>국가 정보</h1>
                <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>국가 정보</h1>
                <p style={{ color: 'var(--crimson)' }}>{error}</p>
                <button onClick={() => fetchData()} style={{ marginTop: 'var(--space-md)' }}>다시 시도</button>
            </Shell>
        );
    }

    if (!data || !data.hasNation) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>국가 정보</h1>
                <p style={{ color: 'var(--text-muted)' }}>재야입니다.</p>
            </Shell>
        );
    }

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>국가 정보</h1>

            {toast && (
                <div className="toast" style={{ position: 'fixed', top: 'var(--space-md)', right: 'var(--space-md)', zIndex: 200 }}>
                    {toast}
                </div>
            )}

            {/* 국가 헤더 + 19필드 단일표(8열) — PHP b_myKingdomInfo.php 동치 */}
            <GameCard style={{ marginBottom: 'var(--space-md)' }}>
                <div
                    style={{
                        background: data.color,
                        color: newColor(data.color),
                        textAlign: 'center',
                        fontWeight: 700,
                        padding: 'var(--space-xs) 0',
                        marginBottom: 'var(--space-sm)',
                    }}
                >
                    【{data.name}】
                </div>

                <div
                    style={{
                        display: 'grid',
                        gridTemplateColumns: 'auto 1fr auto 1fr auto 1fr',
                        gap: '2px var(--space-sm)',
                        fontSize: 'var(--text-sm)',
                        alignItems: 'center',
                    }}
                >
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>총주민</span>
                    <span style={{ textAlign: 'center' }}>{formatNumber(data.population)}/{formatNumber(data.populationMax)}</span>
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>총병사</span>
                    <span style={{ textAlign: 'center' }}>{formatNumber(data.crew)}/{formatNumber(data.crewMax)}</span>
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>국 력</span>
                    <span style={{ textAlign: 'center' }}>{data.power}</span>

                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>국 고</span>
                    <span style={{ textAlign: 'center' }}>{formatNumber(data.gold)}</span>
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>병 량</span>
                    <span style={{ textAlign: 'center' }}>{formatNumber(data.rice)}</span>
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>세 율</span>
                    <span style={{ textAlign: 'center' }}>{data.taxRate == null ? '-' : `${data.taxRate} %`}</span>

                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>세금/단기</span>
                    <span style={{ textAlign: 'center' }}>-</span>
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>세곡/둔전</span>
                    <span style={{ textAlign: 'center' }}>-</span>
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>지급률</span>
                    <span style={{ textAlign: 'center' }}>{data.bill == null ? '-' : `${data.bill} %`}</span>

                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>수입/지출</span>
                    <span style={{ textAlign: 'center' }}>-</span>
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>수입/지출</span>
                    <span style={{ textAlign: 'center' }}>-</span>
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>속 령</span>
                    <span style={{ textAlign: 'center' }}>{data.cityCount}</span>

                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>국고 예산</span>
                    <span style={{ textAlign: 'center' }}>-</span>
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>병량 예산</span>
                    <span style={{ textAlign: 'center' }}>-</span>
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>장 수</span>
                    <span style={{ textAlign: 'center' }}>{data.generalCount}</span>

                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>기술력</span>
                    <span style={{ textAlign: 'center' }}>{formatNumber(data.tech)}</span>
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600, textAlign: 'center' }}>작 위</span>
                    <span style={{ textAlign: 'center', gridColumn: 'span 3' }}>{data.levelText}</span>
                </div>

                {/* 속령일람 */}
                <div style={{ fontSize: 'var(--text-sm)', marginTop: 'var(--space-sm)' }}>
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600 }}>속령일람 : </span>
                    {data.cities.map((c: MyNationCityRef, i: number) => (
                        <span key={c.cityId} style={{ color: c.isCapital ? 'cyan' : undefined }}>
                            {c.isCapital ? `[${c.name}]` : c.name}
                            {i < data.cities.length - 1 ? ', ' : ''}
                        </span>
                    ))}
                </div>

                {/* 국가열전 */}
                <div style={{ fontSize: 'var(--text-sm)', marginTop: 'var(--space-sm)' }}>
                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600 }}>국가열전 : </span>
                    <span style={{ color: 'var(--text-muted)' }}>-</span>
                </div>
            </GameCard>

            {/* 유산 버프 구매 */}
            <GameCard style={{ marginBottom: 'var(--space-md)' }}>
                <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>유산 버프 구매</h2>
                <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)', marginBottom: 'var(--space-md)' }}>
                    각 버프는 레벨 1~5까지 구매 가능합니다. 비용은 누적 차액입니다.
                </p>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)' }}>
                    {INHERIT_BUFFS.map(buff => {
                        const currentLevel = myBuffs[buff.key] ?? 0;
                        return (
                            <div key={buff.key} style={{ border: '1px solid var(--border-subtle)', borderRadius: 'var(--radius-sm)', padding: 'var(--space-sm)' }}>
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 'var(--space-xs)' }}>
                                    <div>
                                        <strong>{buff.label}</strong>
                                        <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', marginLeft: 'var(--space-sm)' }}>{buff.desc}</span>
                                    </div>
                                    <span style={{ fontSize: 'var(--text-sm)' }}>
                                        현재 레벨: <strong style={{ color: 'var(--gold)' }}>{currentLevel}</strong>/5
                                    </span>
                                </div>
                                <div style={{ display: 'flex', gap: 'var(--space-xs)', flexWrap: 'wrap' }}>
                                    {[1, 2, 3, 4, 5].map(lvl => {
                                        // 비용 = 누적 차액(BuyHiddenBuff: inheritBuffPoints[lvl] - [prevLevel]).
                                        // API 배열 미수신 시 비용 표기를 생략(날조 금지 — 사본 폴백 없음).
                                        const cost = buffCosts[lvl] != null && buffCosts[currentLevel] != null
                                            ? buffCosts[lvl] - buffCosts[currentLevel]
                                            : null;
                                        const disabled = currentLevel >= lvl;
                                        return (
                                            <button
                                                key={lvl}
                                                onClick={() => buyBuff(buff.key, lvl)}
                                                disabled={disabled}
                                                style={{
                                                    fontSize: 'var(--text-xs)',
                                                    padding: 'var(--space-xs) var(--space-sm)',
                                                    border: `1px solid ${disabled ? 'var(--border-subtle)' : 'var(--gold-dim)'}`,
                                                    background: disabled ? 'var(--bg-hover)' : 'rgba(201,162,39,0.1)',
                                                    color: disabled ? 'var(--text-muted)' : 'var(--gold)',
                                                }}
                                            >
                                                L{lvl}{cost != null ? ` (${cost.toLocaleString()}P)` : ''}
                                            </button>
                                        );
                                    })}
                                </div>
                            </div>
                        );
                    })}
                </div>
            </GameCard>

            <GameCard>
                <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>기타 유산 구매</h2>
                <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
                    <button onClick={buyRandomUnique}>랜덤 유니크 아이템 구매</button>
                </div>
            </GameCard>
        </Shell>
    );
}
