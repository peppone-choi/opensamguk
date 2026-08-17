'use client';

// ── 일제정보 (Admin5) — READ-ONLY 국가별 전체 통계 + 정렬 ────────────────────────────
// Grand-truth: legacy hwe/_admin5.php. game-api `GET /api/admin/nation-stats?type=&type2=`
// (AdminReadController, B3a). 전부 READ 렌더 — 국가변경 폼(_admin5_submit, B3b intake)은
// 아직 미배선이라 이 페이지에 두지 않는다(read 우선; mutation은 B6 intake 별도 웨이브).
//
// ADMIN 게이트는 BE가 강제한다: 비ADMIN→403, 비로그인→401. FE는 권한 오류를 graceful 안내로 처리.
//
// 정렬 form(_admin5.php:56-85): type select(국력/장수/.../차숙) + type2 select(국력/.../기타) +
// '정렬하기'. select 변경 즉시 재조회(legacy submit 등가). 옵션 라벨/value는 BE sortOptions를
// 그대로 렌더 — 라벨 byte-parity는 BE 책임(verbatim).
//
// 통계 테이블(_admin5.php:103-263): 28열 헤더 verbatim. 국명 셀은 nation.color 배경 + 대비 텍스트
// (legacy newColor = perceived-luminance 분기). 총병 셀은 `{crew}/{sumLeadership}00`(legacy
// `{crew}/{leadership}00`). 인구율은 BE popRate(1자리), 비율(%)은 BE 2자리 그대로.
//
// historyStats(statistic 테이블)·sabotageLog(_sabotagelog.txt)는 opensamguk 스키마 원천 부재로
// BE가 BLOCKED(빈 리스트 + *Blocked=true) — 값 날조 금지(CLAUDE.md §5). FE는 "원천 부재" 안내로
// 대체 노출만 한다.
//
// EMPTY-SAFE: rows [] → 빈-상태 행. 절대 크래시하지 않는다.

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api } from '../../../lib/api';
import type { AdminNationStatsResponse } from '../../../lib/api';
import { BRIGHT_COLOR_THRESHOLD } from '../../../lib/constants';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';

// legacy newColor: perceived-luminance(r*.299+g*.587+b*.114) > 140 → 어두운 글자, 아니면 흰 글자.
function contrastText(color: string): string {
    const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(color.trim());
    if (!m) return '#fff';
    const r = parseInt(m[1], 16);
    const g = parseInt(m[2], 16);
    const b = parseInt(m[3], 16);
    return r * 0.299 + g * 0.587 + b * 0.114 > BRIGHT_COLOR_THRESHOLD ? '#000' : '#fff';
}

// _admin5.php 테이블 헤더 verbatim(국명 … 국명 = 좌우 양끝 국명 2회).
const COLUMNS = [
    '국명', '국력', '장수', '속령', '기술', '전략', '국고', '병량',
    '평금', '평쌀', '평통', '평무', '평지', '평Lv',
    '보숙', '궁숙', '기숙', '귀숙', '차숙',
    '총병', '인구', '인구율', '농업', '상업', '치안', '성벽', '수비', '국명',
];

export default function Admin5Page() {
    const [data, setData] = useState<AdminNationStatsResponse | null>(null);
    const [type, setType] = useState(0);
    const [type2, setType2] = useState(0);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');

    const fetchData = useCallback(async (t: number, t2: number) => {
        setLoading(true);
        try {
            const d = await api.admin.nationStats(t, t2);
            setData(d);
            setError('');
        } catch (e) {
            // 401(비로그인) / 403(비ADMIN) 포함 — graceful 안내(legacy requireAdminPermissionHTML 등가).
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
        fetchData(type, type2);
    }, [fetchData, type, type2]);

    // 턴 완료 시 통계 갱신(현재 정렬 유지).
    useTurnRefresh(() => fetchData(type, type2));

    const sortOptions = data?.sortOptions ?? [];
    const sortOptions2 = data?.sortOptions2 ?? [];
    const rows = data?.rows ?? [];

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>일제 정보</h1>

            {/* ── 정렬 form (_admin5.php:56-85) ──────────────────────────────────── */}
            <GameCard style={{ marginBottom: 'var(--space-lg)' }}>
                <div style={{ display: 'flex', gap: 'var(--space-md)', alignItems: 'center', flexWrap: 'wrap' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>정렬순서 :</span>
                    <select value={type} onChange={(e) => setType(Number(e.target.value))}>
                        {sortOptions.map((o) => (
                            <option key={o.value} value={o.value}>{o.label}</option>
                        ))}
                    </select>
                    <select value={type2} onChange={(e) => setType2(Number(e.target.value))}>
                        {sortOptions2.map((o) => (
                            <option key={o.value} value={o.value}>{o.label}</option>
                        ))}
                    </select>
                    <button onClick={() => fetchData(type, type2)}>정렬하기</button>
                </div>
            </GameCard>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {/* ── 국가별 통계 테이블 (_admin5.php:103-263) ───────────────────────── */}
            {!error && (
                <GameCard style={{ marginBottom: 'var(--space-xl)' }}>
                    <div style={{ overflowX: 'auto' }}>
                        <table className="game-table" style={{ minWidth: 1200, whiteSpace: 'nowrap' }}>
                            <thead>
                                <tr>
                                    {COLUMNS.map((c, i) => (
                                        <th key={i} style={{ textAlign: 'center' }}>{c}</th>
                                    ))}
                                </tr>
                            </thead>
                            <tbody>
                                {rows.map((n) => {
                                    const fg = contrastText(n.color);
                                    const nameCell = (
                                        <td style={{ textAlign: 'center', color: fg, backgroundColor: n.color }}>
                                            {n.name}
                                        </td>
                                    );
                                    return (
                                        <tr key={n.nationId}>
                                            {nameCell}
                                            <td style={{ textAlign: 'center' }}>{n.power}</td>
                                            <td style={{ textAlign: 'center' }}>{n.genCnt}</td>
                                            <td style={{ textAlign: 'center' }}>{n.cityCnt}</td>
                                            <td style={{ textAlign: 'right' }}>{n.tech.toFixed(1)}</td>
                                            <td style={{ textAlign: 'center' }}>{n.strategicCmdLimit}</td>
                                            <td style={{ textAlign: 'center' }}>{n.gold}</td>
                                            <td style={{ textAlign: 'center' }}>{n.rice}</td>
                                            <td style={{ textAlign: 'right' }}>{n.avgGold}</td>
                                            <td style={{ textAlign: 'right' }}>{n.avgRice}</td>
                                            <td style={{ textAlign: 'right' }}>{n.avgLeadership.toFixed(1)}</td>
                                            <td style={{ textAlign: 'right' }}>{n.avgStrength.toFixed(1)}</td>
                                            <td style={{ textAlign: 'right' }}>{n.avgIntel.toFixed(1)}</td>
                                            <td style={{ textAlign: 'right' }}>{n.avgExpLevel.toFixed(1)}</td>
                                            <td style={{ textAlign: 'right' }}>{n.dex1}</td>
                                            <td style={{ textAlign: 'right' }}>{n.dex2}</td>
                                            <td style={{ textAlign: 'right' }}>{n.dex3}</td>
                                            <td style={{ textAlign: 'right' }}>{n.dex4}</td>
                                            <td style={{ textAlign: 'right' }}>{n.dex5}</td>
                                            {/* legacy `{crew}/{leadership}00` (총병/통솔합00). */}
                                            <td style={{ textAlign: 'right' }}>{n.sumCrew}/{n.sumLeadership}00</td>
                                            <td style={{ textAlign: 'center' }}>{n.pop}/{n.popMax}</td>
                                            <td style={{ textAlign: 'center' }}>{n.popRate.toFixed(1)}%</td>
                                            <td style={{ textAlign: 'center' }}>{n.agri}%</td>
                                            <td style={{ textAlign: 'center' }}>{n.comm}%</td>
                                            <td style={{ textAlign: 'center' }}>{n.secu}%</td>
                                            <td style={{ textAlign: 'center' }}>{n.wall}%</td>
                                            <td style={{ textAlign: 'center' }}>{n.def}%</td>
                                            <td style={{ textAlign: 'center', color: fg, backgroundColor: n.color }}>
                                                {n.name}
                                            </td>
                                        </tr>
                                    );
                                })}
                                {rows.length === 0 && !loading && (
                                    <tr>
                                        <td colSpan={COLUMNS.length} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
                                            통계가 없습니다.
                                        </td>
                                    </tr>
                                )}
                            </tbody>
                        </table>
                    </div>
                </GameCard>
            )}

            {/* ── BLOCKED: 첩보 로그 / 역사 통계 (스키마 원천 부재) ─────────────────── */}
            {!error && (data?.sabotageLogBlocked || data?.historyStatsBlocked) && (
                <GameCard>
                    <p style={{ color: 'var(--text-muted)', margin: 0, fontSize: 'var(--text-sm)', lineHeight: 1.7 }}>
                        {/* legacy _admin5.php:264-268(getSabotageLogRecent) + :270-342(statistic 역사통계)는
                            opensamguk 스키마에 원천(_sabotagelog.txt / statistic 테이블)이 없어 미제공(값 날조 금지). */}
                        첩보 기록·역사 통계는 현재 데이터 원천이 없어 제공되지 않습니다.
                    </p>
                </GameCard>
            )}
        </Shell>
    );
}
