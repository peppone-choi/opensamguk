'use client';
// ── 전황 (World-Log) — READ-ONLY 월드 글로벌 이력 뷰어 · 12 기록 「편년체」 레이아웃 ─────────────
// game-api `GET /api/world-log`(WorldLogController) → {entries:[{id,year,month,phase,phaseText,text}]}.
// log_entry SYSTEM 스코프(정복/멸망/건국/작위 등) 글로벌 이력 최신순 30건을 (연·월·순) 단위로 묶어 그린다.
// `text`는 서버가 내려준 패러티 로그 원문(devsam 색/태그 토큰)이라 연감·작전실과 같은 LogText(토큰→팔레트 span, innerHTML 없음)로 렌더한다.
//
// EMPTY-SAFE: 신선 시드면 entries === [] → 빈-상태 안내. 절대 크래시하지 않는다.
// (개인 전투 기록 / 장수 행동 로그(general_record)는 백엔드에 테이블이 없어 범위 밖 — 미구현 갭.)
import { useCallback, useEffect, useState } from 'react';
import { LogText, Panel, SectionHeader } from '@opensamguk/ui';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import RecordsTabs from '../../../components/records/RecordsTabs';
import { api } from '../../../lib/api';
import type { WorldLogEntry, WorldLogResponse } from '../../../lib/api';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';

interface PhaseGroup {
    key: string;
    year: number;
    month: number;
    phaseText: string | null;
    items: WorldLogEntry[];
}

/** 연·월·순이 같은 연속 항목을 한 묶음으로(서버 정렬 최신순 유지). */
function groupByPhase(entries: WorldLogEntry[]): PhaseGroup[] {
    const groups: PhaseGroup[] = [];
    for (const item of entries) {
        const phaseText = item.phaseText ?? null;
        const key = `${item.year}-${item.month}-${item.phase ?? phaseText ?? ''}`;
        const last = groups[groups.length - 1];
        if (last && last.key === key) last.items.push(item);
        else groups.push({ key, year: item.year, month: item.month, phaseText, items: [item] });
    }
    return groups;
}

export default function WorldLogPage() {
    const [data, setData] = useState<WorldLogResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');

    const fetchData = useCallback(async () => {
        const d = await api.worldLog();
        setData(d);
        setError('');
    }, []);

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

    useTurnRefresh(() => {
        fetchData().catch(() => setError('데이터를 불러올 수 없습니다.'));
    });

    const entries = data?.entries ?? [];
    const groups = groupByPhase(entries);
    const range = groups.length > 0
        ? `${groups[groups.length - 1].year}年 ${groups[groups.length - 1].month}月 ~ ${groups[0].year}年 ${groups[0].month}月`
        : undefined;

    return (
        <Shell>
            <PageHead title="전황" tabs={<RecordsTabs />} />
            {loading && <p className="text-muted">로딩 중...</p>}
            {error && <p role="alert" style={{ color: 'var(--rust)' }}>{error}</p>}
            {!loading && !error && (
                <Panel className="chron record-panel">
                    <SectionHeader title="중원 정세 · 편년체" sub={range} />
                    <div className="chron__body">
                        {groups.length === 0 && <p className="record-empty">기록이 없습니다.</p>}
                        {groups.map((g) => (
                            <section key={g.key} className="chron__group" aria-label={`${g.year}年 ${g.month}月${g.phaseText ? ` ${g.phaseText}` : ''}`}>
                                <div className="chron__when">
                                    <span className="chron__month">{g.month}月{g.phaseText ? ` ${g.phaseText}` : ''}</span>
                                    <span className="chron__year">{g.year}年</span>
                                </div>
                                <div className="chron__items">
                                    {g.items.map((item) => (
                                        <div key={item.id} className="chron__item"><LogText text={item.text} /></div>
                                    ))}
                                </div>
                            </section>
                        ))}
                    </div>
                </Panel>
            )}
        </Shell>
    );
}
