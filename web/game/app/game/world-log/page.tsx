'use client';

// ── 전황 (World-Log) — READ-ONLY 월드 글로벌 이력 뷰어 ──────────────────────────
// game-api `GET /api/world-log`(WorldLogController) → {entries:[{id,year,month,phase,phaseText,text}]}.
// log_entry SYSTEM 스코프(정복/멸망/건국/작위 등) 글로벌 이력 최신순 30건을 그대로 렌더한다.
// 헤더 셀에만 노출되던 전황을 전용 페이지로 분리(W4 read surface). 연감(history/page.tsx)과
// 동일한 레이아웃/스타일을 따른다.
//
// `text`는 서버가 내려준 패러티 로그 원문(devsam 색/태그 마크업 포함)이라, 연감의
// v-html="formatLog(item)" 패턴 그대로 dangerouslySetInnerHTML로 렌더한다.
//
// EMPTY-SAFE: 신선 시드면 entries === [] → 빈-상태 안내. 절대 크래시하지 않는다.
// (개인 전투 기록 / 장수 행동 로그(general_record)는 백엔드에 테이블이 없어 범위 밖 — 미구현 갭.)

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api } from '../../../lib/api';
import type { WorldLogResponse } from '../../../lib/api';

const sectionBarStyle: React.CSSProperties = {
    textAlign: 'center',
    border: '0.5px solid var(--border-medium)',
    background: 'var(--bg-elevated)',
    padding: 'var(--space-xs) var(--space-sm)',
    fontWeight: 600,
    fontSize: 'var(--text-sm)',
    marginBottom: 'var(--space-sm)',
    marginTop: 'var(--space-lg)',
};

const logRowStyle: React.CSSProperties = {
    fontSize: 'var(--text-sm)',
    lineHeight: 1.7,
    padding: 'var(--space-xs) 0',
    borderBottom: '1px solid var(--border-subtle)',
    display: 'flex',
    gap: 'var(--space-sm)',
};

// 연월 라벨 — devsam 전황 표기와 동일하게 "{year}년 {month}월". 0/음수면 라벨 생략(빈 셀).
const yearMonthLabelStyle: React.CSSProperties = {
    flex: '0 0 auto',
    minWidth: '6.5rem',
    color: 'var(--text-secondary)',
    whiteSpace: 'nowrap',
};

function logDateLabel(item: { year: number; month: number; phaseText?: string | null }): string {
    if (item.year <= 0) return '';
    return `${item.year}년 ${item.month}월${item.phaseText ? ` ${item.phaseText}` : ''}`;
}

export default function WorldLogPage() {
    const [data, setData] = useState<WorldLogResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');

    useEffect(() => {
        let alive = true;
        (async () => {
            try {
                const d = await api.worldLog();
                if (alive) {
                    setData(d);
                    setError('');
                }
            } catch {
                if (alive) setError('데이터를 불러올 수 없습니다.');
            } finally {
                if (alive) setLoading(false);
            }
        })();
        return () => {
            alive = false;
        };
    }, []);

    const entries = data?.entries ?? [];

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>전황</h1>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {!loading && !error && (
                <>
                    {/* ── 중원 정세 (log_entry SYSTEM 이력, 최신순 30건) ──────────────── */}
                    <div style={sectionBarStyle}>중원 정세</div>
                    <GameCard>
                        {entries.length === 0 ? (
                            <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>기록이 없습니다.</p>
                        ) : (
                            <div>
                                {entries.map((item) => (
                                    <div key={item.id} style={logRowStyle}>
                                        <span style={yearMonthLabelStyle}>
                                            {logDateLabel(item)}
                                        </span>
                                        {/* text는 서버 패러티 로그 원문(색/태그) — 연감과 동일 v-html 렌더. */}
                                        <span style={{ flex: '1 1 auto' }} dangerouslySetInnerHTML={{ __html: item.text }} />
                                    </div>
                                ))}
                            </div>
                        )}
                    </GameCard>
                </>
            )}
        </Shell>
    );
}
