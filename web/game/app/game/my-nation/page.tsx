'use client';
// 세력 정보(08 국가 운영 아트보드 · NationBasicCard 항목) — 히어로(깃발·국명·작위·속령·장수·국력) + 19항목 KV + 속령일람.
// 라벨은 b_myKingdomInfo.php 그대로. §2 BLOCKED 항목(세율·지급률·수입 6종·예산·국가열전)은 null → "-"(수치 날조 없음).
import { useEffect, useState } from 'react';
import { Flag, Panel, SectionHeader } from '@opensamguk/ui';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import OperationPanel from '../../../components/game/OperationPanel';
import type { MyNationDetailResponse } from '../../../types/game';

function signedNumber(value: number | null): string {
    if (value == null) return '-';
    return `${value > 0 ? '+' : ''}${formatNumber(value)}`;
}

export default function MyNationPage() {
    const [data, setData] = useState<MyNationDetailResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    // OPENSAM-196: background=true면 로딩 스피너를 건너뛴다(턴 갱신 시 화면이 잠깐 비는 것을 방지).
    const fetchData = async (background = false) => {
        if (!background) setLoading(true);
        setError('');
        try {
            const res = await api.myNationDetail<MyNationDetailResponse>();
            setData(res);
        } catch {
            setError('국가 정보를 불러올 수 없습니다.');
        } finally {
            if (!background) setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
    }, []);

    // OPENSAM-196: 턴 완료 시 세력 정보를 백그라운드로 다시 읽는다.
    useTurnRefresh(() => {
        fetchData(true);
    });

    if (loading) {
        return (
            <Shell>
                <div className="page-content">
                    <PageHead title="세력 정보" />
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <PageHead title="세력 정보" />
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={() => fetchData()}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    // PHP: nation==0 → "재야입니다." exit. 미점유/재야는 hasNation=false.
    if (!data || !data.hasNation) {
        return (
            <Shell>
                <div className="page-content">
                    <PageHead title="세력 정보" />
                    <p className="text-muted">재야입니다.</p>
                </div>
            </Shell>
        );
    }

    const goldIn = data.goldIncome == null || data.warIncome == null ? null : data.goldIncome + data.warIncome;
    const riceIn = data.riceIncome == null || data.farmIncome == null ? null : data.riceIncome + data.farmIncome;
    // 19필드 — PHP td 6행(8열)의 라벨·순서 그대로. 값이 없는 항목은 "-".
    const fields: [string, React.ReactNode][] = [
        ['총주민', `${formatNumber(data.population)}/${formatNumber(data.populationMax)}`],
        ['총병사', `${formatNumber(data.crew)}/${formatNumber(data.crewMax)}`],
        ['국 력', data.power],
        ['국 고', formatNumber(data.gold)],
        ['병 량', formatNumber(data.rice)],
        ['세 율', data.taxRate == null ? '-' : `${data.taxRate} %`],
        ['세금/단기', `${signedNumber(data.goldIncome)} / ${signedNumber(data.warIncome)}`],
        ['세곡/둔전', `${signedNumber(data.riceIncome)} / ${signedNumber(data.farmIncome)}`],
        ['지급률', data.bill == null ? '-' : `${data.bill} %`],
        ['수입/지출', `${signedNumber(goldIn)} / ${data.outcome == null ? '-' : `-${formatNumber(data.outcome)}`}`],
        ['수입/지출', `${signedNumber(riceIn)} / ${data.outcome == null ? '-' : `-${formatNumber(data.outcome)}`}`],
        ['속 령', data.cityCount],
        ['국고 예산', data.goldBudget == null ? '-' : `${formatNumber(data.goldBudget)} (${signedNumber(data.goldBudgetDiff)})`],
        ['병량 예산', data.riceBudget == null ? '-' : `${formatNumber(data.riceBudget)} (${signedNumber(data.riceBudgetDiff)})`],
        ['장 수', data.generalCount],
        ['기술력', formatNumber(data.tech)],
        ['작 위', data.levelText],
    ];
    return (
        <Shell>
            <PageHead title="세력 정보" chip={data.levelText} />
            {/* 히어로 — 국가색은 깃발에만(ADR-LITE-049). PHP 【{name}】 헤더 대응. */}
            <section className="nation-hero" aria-label={`${data.name} 개요`}>
                <Flag color={data.color} size={32} label={`${data.name} 깃발`} />
                <div>
                    <div className="nation-hero__name">【{data.name}】</div>
                    <div className="nation-hero__meta">
                        <span>{data.levelText}</span>
                        <span>속령 <b className="os-num">{data.cityCount}</b></span>
                        <span>장수 <b className="os-num">{data.generalCount}</b></span>
                        <span>국력 <b className="os-num">{data.power}</b></span>
                    </div>
                </div>
            </section>
            <div className="record-grid nation-grid">
                <Panel className="record-panel">
                    <SectionHeader title="세력 정보" sub={`${fields.length}항목`} />
                    <dl className="os-kv nation-kv">
                        {fields.map(([k, v], i) => (
                            <div key={`${k}-${i}`} style={{ display: 'contents' }}>
                                <dt>{k}</dt>
                                <dd>{v}</dd>
                            </div>
                        ))}
                    </dl>
                </Panel>
                {/* Phase 4X-B 08 「작전 진행」 — 원천 /api/operations, 명령은 인테이크. */}
                <OperationPanel />
                <Panel className="record-panel">
                    <SectionHeader title="속령일람" tone="info" sub={`${data.cities.length}`} />
                    {/* 수도는 [이름] + 정보색 강조(PHP cyan). */}
                    <div className="nation-cities">
                        {data.cities.map((c) => (
                            <span key={c.cityId} className={`os-chip${c.isCapital ? ' os-chip--info' : ''}`}>
                                {c.isCapital ? `[${c.name}]` : c.name}
                            </span>
                        ))}
                    </div>
                    {/* 국가열전 — §2 BLOCKED(nation-history read 원천 부재) → "-" */}
                    <dl className="os-kv nation-kv">
                        <dt>국가열전</dt>
                        <dd className="text-muted">-</dd>
                    </dl>
                </Panel>
            </div>
        </Shell>
    );
}
