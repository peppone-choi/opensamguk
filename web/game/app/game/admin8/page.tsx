'use client';

// ── 외교정보 (Admin8) — READ-ONLY 전 국가간 외교 관계 (마스킹 없음) ──────────────────
// Grand-truth: legacy hwe/_admin8.php. game-api `GET /api/admin/diplomacy-all`
// (AdminReadController, B4b). 전부 READ 렌더.
//
// ADMIN 게이트는 BE 강제: 비ADMIN→403, 비로그인→401. FE는 graceful 안내.
//
// legacy _admin8.php는 매트릭스가 아니라 **관계 리스트 테이블**(국가명/국가명/상태/기간)이다
// (`SELECT * from diplomacy where me < you order by state desc`, state==2(통상) 행 skip).
// grand-truth가 리스트이므로 매트릭스가 아닌 리스트를 충실 재현한다. BE relations가 me<you·state!=2·
// state desc 정렬·stateText verbatim까지 모두 적용해 내려준다 — FE는 가공 없이 행만 렌더.
//
// 정렬 form(_admin8.php:48-54): type select(상태 단일 옵션) + '정렬하기'. legacy도 옵션이 '상태'
// 하나뿐이라 정렬 분기 없음(고정). 형태 패러티 위해 비활성 select + 새로고침 버튼으로 노출.
//
// 국가명 셀은 nation color 배경 + 대비 텍스트(legacy newColor). 상태 셀은 BE stateText 원문
// (교 전/선포중/통 상/불가침)을 색과 함께 렌더(legacy <font color=...> 등가).
//
// EMPTY-SAFE: relations [] → 빈-상태 행. 절대 크래시하지 않는다.

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api } from '../../../lib/api';
import type { AdminDiplomacyAllResponse } from '../../../lib/api';
import { BRIGHT_COLOR_THRESHOLD } from '../../../lib/constants';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';

// legacy newColor: perceived-luminance > 140 → 어두운 글자.
function contrastText(color: string): string {
    const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(color.trim());
    if (!m) return '#fff';
    const r = parseInt(m[1], 16);
    const g = parseInt(m[2], 16);
    const b = parseInt(m[3], 16);
    return r * 0.299 + g * 0.587 + b * 0.114 > BRIGHT_COLOR_THRESHOLD ? '#000' : '#fff';
}

// 상태 텍스트 색 — legacy <font color=...>(0 교전 red / 1 선포중 magenta / 2 통상 무색 / 7 불가침 green).
// (state==2는 BE에서 이미 제외되지만 안전상 매핑은 유지.)
function stateColor(state: number): string | undefined {
    switch (state) {
        case 0: return 'red';
        case 1: return 'magenta';
        case 7: return 'green';
        default: return undefined;
    }
}

export default function Admin8Page() {
    const [data, setData] = useState<AdminDiplomacyAllResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');

    const fetchData = useCallback(async () => {
        setLoading(true);
        try {
            const d = await api.admin.diplomacyAll();
            setData(d);
            setError('');
        } catch (e) {
            const msg = e instanceof Error ? e.message : '';
            setError(
                msg.startsWith('403')
                    ? '관리자 권한이 필요합니다.'
                    : msg.startsWith('401')
                        ? '로그인이 필요합니다.'
                        : '데이터를 불러올 수 없습니다.',
            );
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    // 턴 완료 시 외교 갱신.
    useTurnRefresh(() => fetchData());

    const relations = data?.relations ?? [];

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>외교 정보</h1>

            {/* ── 정렬 form (_admin8.php:48-54, 단일 '상태' 옵션) ─────────────────── */}
            <GameCard style={{ marginBottom: 'var(--space-lg)' }}>
                <div style={{ display: 'flex', gap: 'var(--space-md)', alignItems: 'center', flexWrap: 'wrap' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>정렬순서 :</span>
                    {/* legacy는 옵션이 '상태' 하나뿐 → 정렬 분기 없음(고정). 형태만 노출. */}
                    <select value={0} disabled>
                        <option value={0}>상태</option>
                    </select>
                    <button onClick={fetchData}>정렬하기</button>
                </div>
            </GameCard>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {/* ── 외교 관계 리스트 (_admin8.php:58-114) ──────────────────────────── */}
            {!error && (
                <GameCard>
                    <div style={{ overflowX: 'auto' }}>
                        <table className="game-table" style={{ margin: 'auto', minWidth: 480 }}>
                            <thead>
                                <tr>
                                    <th colSpan={4} style={{ textAlign: 'center', background: 'blue', color: '#fff' }}>
                                        외 교 관 계
                                    </th>
                                </tr>
                                <tr>
                                    <th style={{ textAlign: 'center' }}>국 가 명</th>
                                    <th style={{ textAlign: 'center' }}>국 가 명</th>
                                    <th style={{ textAlign: 'center' }}>상 태</th>
                                    <th style={{ textAlign: 'center' }}>기 간</th>
                                </tr>
                            </thead>
                            <tbody>
                                {relations.map((d) => (
                                    <tr key={`${d.me}-${d.you}`}>
                                        <td style={{ textAlign: 'center', color: contrastText(d.meColor), backgroundColor: d.meColor }}>
                                            {d.meName}
                                        </td>
                                        <td style={{ textAlign: 'center', color: contrastText(d.youColor), backgroundColor: d.youColor }}>
                                            {d.youName}
                                        </td>
                                        <td style={{ textAlign: 'center', color: stateColor(d.state) }}>
                                            {d.stateText}
                                        </td>
                                        <td style={{ textAlign: 'center' }}>{d.term} 개월</td>
                                    </tr>
                                ))}
                                {relations.length === 0 && !loading && (
                                    <tr>
                                        <td colSpan={4} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
                                            외교 관계가 없습니다.
                                        </td>
                                    </tr>
                                )}
                            </tbody>
                        </table>
                    </div>
                </GameCard>
            )}
        </Shell>
    );
}
