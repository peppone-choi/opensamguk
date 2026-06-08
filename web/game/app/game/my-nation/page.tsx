'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import type { MyNationDetailResponse } from '../../../types/game';

// legacy func_converter.php newColor() 충실 포팅 — 어두운 국가색 위에 올릴 헤더 텍스트 대비색.
const DARK_COLORS = new Set([
    '', '#330000', '#FF0000', '#800000', '#A0522D', '#FF6347', '#808000',
    '#008000', '#2E8B57', '#008080', '#6495ED', '#0000FF', '#000080',
    '#483D8B', '#7B68EE', '#800080', '#A9A9A9', '#000000',
]);
function newColor(color: string): string {
    return DARK_COLORS.has(color === '' ? '' : color.toUpperCase()) ? '#FFFFFF' : '#000000';
}

// 8열 단일표 행 — PHP b_myKingdomInfo.php의 td 레이아웃을 grid로 옮긴다(라벨 bg1 + 값).
const labelStyle: React.CSSProperties = {
    color: 'var(--text-secondary)',
    fontWeight: 600,
    textAlign: 'center',
};
const cellStyle: React.CSSProperties = { textAlign: 'center' };

export default function MyNationPage() {
    const [data, setData] = useState<MyNationDetailResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.myNationDetail<MyNationDetailResponse>();
            setData(res);
        } catch {
            setError('국가 정보를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
    }, []);

    if (loading) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>세력 정보</h1>
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>세력 정보</h1>
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={fetchData}>다시 시도</button>
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
                    <h1>세력 정보</h1>
                    <p className="text-muted">재야입니다.</p>
                </div>
            </Shell>
        );
    }

    return (
        <Shell>
            <div className="page-content">
                <h1>세력 정보</h1>

                <GameCard>
                    {/* 국가명 헤더 — 국가색 배경 + newColor 대비 텍스트 (PHP 【{name}】) */}
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

                    {/* 19필드 단일표(8열) — PHP td 6행을 grid로. */}
                    <div
                        style={{
                            display: 'grid',
                            gridTemplateColumns: 'auto 1fr auto 1fr auto 1fr',
                            gap: '2px var(--space-sm)',
                            fontSize: 'var(--text-sm)',
                            alignItems: 'center',
                        }}
                    >
                        {/* 1행: 총주민 / 총병사 / 국력 */}
                        <span style={labelStyle}>총주민</span>
                        <span style={cellStyle}>
                            {formatNumber(data.population)}/{formatNumber(data.populationMax)}
                        </span>
                        <span style={labelStyle}>총병사</span>
                        <span style={cellStyle}>
                            {formatNumber(data.crew)}/{formatNumber(data.crewMax)}
                        </span>
                        <span style={labelStyle}>국 력</span>
                        <span style={cellStyle}>{data.power}</span>

                        {/* 2행: 국고 / 병량 / 세율 */}
                        <span style={labelStyle}>국 고</span>
                        <span style={cellStyle}>{formatNumber(data.gold)}</span>
                        <span style={labelStyle}>병 량</span>
                        <span style={cellStyle}>{formatNumber(data.rice)}</span>
                        <span style={labelStyle}>세 율</span>
                        {/* 세율 — §2 BLOCKED(meta UNVERIFIED) → null이면 "-" */}
                        <span style={cellStyle}>{data.taxRate == null ? '-' : `${data.taxRate} %`}</span>

                        {/* 3행: 세금/단기 · 세곡/둔전 · 지급률 — income 6종은 §2 BLOCKED → "-" */}
                        <span style={labelStyle}>세금/단기</span>
                        <span style={cellStyle}>-</span>
                        <span style={labelStyle}>세곡/둔전</span>
                        <span style={cellStyle}>-</span>
                        <span style={labelStyle}>지급률</span>
                        {/* 지급률 — §2 BLOCKED(meta UNVERIFIED) → null이면 "-" */}
                        <span style={cellStyle}>{data.bill == null ? '-' : `${data.bill} %`}</span>

                        {/* 4행: 수입/지출 · 수입/지출 · 속령 / 장수 — income §2 BLOCKED → "-" */}
                        <span style={labelStyle}>수입/지출</span>
                        <span style={cellStyle}>-</span>
                        <span style={labelStyle}>수입/지출</span>
                        <span style={cellStyle}>-</span>
                        <span style={labelStyle}>속 령</span>
                        <span style={cellStyle}>{data.cityCount}</span>

                        {/* 5행: 국고 예산 · 병량 예산 · 장수 + 기술력 — 예산 §2 BLOCKED → "-" */}
                        <span style={labelStyle}>국고 예산</span>
                        <span style={cellStyle}>-</span>
                        <span style={labelStyle}>병량 예산</span>
                        <span style={cellStyle}>-</span>
                        <span style={labelStyle}>장 수</span>
                        <span style={cellStyle}>{data.generalCount}</span>

                        {/* 6행: 기술력 / 작위 (2칸 점유) */}
                        <span style={labelStyle}>기술력</span>
                        <span style={cellStyle}>{formatNumber(data.tech)}</span>
                        <span style={labelStyle}>작 위</span>
                        <span style={{ ...cellStyle, gridColumn: 'span 3' }}>{data.levelText}</span>
                    </div>

                    {/* 속령일람 — 수도 cyan 강조([name]) */}
                    <div style={{ fontSize: 'var(--text-sm)', marginTop: 'var(--space-sm)' }}>
                        <span style={labelStyle}>속령일람 : </span>
                        {data.cities.map((c, i) => (
                            <span key={c.cityId} style={{ color: c.isCapital ? 'cyan' : undefined }}>
                                {c.isCapital ? `[${c.name}]` : c.name}
                                {i < data.cities.length - 1 ? ', ' : ''}
                            </span>
                        ))}
                    </div>

                    {/* 국가열전 — §2 BLOCKED(nation-history read 원천 부재) → "-" */}
                    <div style={{ fontSize: 'var(--text-sm)', marginTop: 'var(--space-sm)' }}>
                        <span style={labelStyle}>국가열전 : </span>
                        <span className="text-muted">-</span>
                    </div>
                </GameCard>
            </div>
        </Shell>
    );
}
