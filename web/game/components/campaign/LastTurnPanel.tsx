'use client';

import { Chip, Panel, SectionHeader } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { useHwihaRead } from '@/lib/hwiha-reads';
import { HwihaEmpty, hwihaReadNotice } from './HwihaStates';

/** 사건 종류 → 뱃지 색. 종류 문자열은 서버 `HwihaRecordKind` 정본을 따른다. */
function kindTone(kind: string): 'moss' | 'rust' | 'info' | 'bronze' | 'neutral' {
    if (kind.startsWith('encounter') || kind === 'input.rejected' || kind === 'county.lost') return 'rust';
    if (kind.startsWith('court.')) return 'bronze';
    if (kind.startsWith('renown') || kind.startsWith('yuedan')) return 'bronze';
    if (kind.startsWith('march') || kind.startsWith('deploy')) return 'info';
    if (kind === 'county.captured' || kind.startsWith('enlist')) return 'moss';
    return 'neutral';
}

function kindLabel(kind: string): string {
    const map: Record<string, string> = {
        'march.corps': '행군',
        'march.assignment': '부임',
        'deploy.started': '출병',
        'encounter.pending': '조우',
        'court.dispatchReceived': '발령',
        'court.dispatchIssued': '발령',
        'court.dispatchAccepted': '수락',
        'court.dispatchRefused': '거절',
        'court.dispatchCancelled': '취소',
        'enlist.joined': '출사',
        'enlist.retainerJoined': '출사',
        'input.rejected': '무효',
        'renown.event': '명망',
        'yuedan.assessed': '월단평',
        'yuedan.announced': '월단평',
        'retinue.departureJudged': '이탈',
        'retinue.departed': '이탈',
        'county.captured': '점령',
        'county.lost': '상실',
    };
    return map[kind] ?? '기록';
}

/**
 * 지난 순 — 내 최근 12순을 순마다 묶고, 세력 요약(점령·상실·월단평 발표)을 따로 싣는다.
 * 기록이 없는 순은 서버가 빈 칸으로 보내므로 가장 최근 몇 순만 펼친다.
 */
export default function LastTurnPanel() {
    const read = useHwihaRead((id, signal) => api.hwihaLastTurns(id, signal));
    const notice = hwihaReadNotice(read, read.data?.status);
    const turns = (read.data?.turns ?? []).filter((t) => t.entries.length > 0).slice(0, 6);
    const nation = read.data?.nationSummary ?? [];
    return (
        <Panel style={{ padding: 12 }}>
            <SectionHeader title="지난 순" sub="내 기록 · 세력 요약" />
            {notice ? <HwihaEmpty>{notice}</HwihaEmpty> : null}
            {!notice && turns.length === 0 ? <HwihaEmpty>최근 12순에 남은 기록이 없습니다.</HwihaEmpty> : null}
            <div style={{ display: 'grid', gap: 8, paddingTop: 8 }}>
                {turns.map((t) => (
                    <div key={`${t.year}-${t.month}-${t.phase}`} style={{ borderTop: '1px solid var(--line)', paddingTop: 6 }}>
                        <div className="os-num" style={{ fontSize: 12, color: 'var(--muted)' }}>{`${t.year}년 ${t.month}월 ${t.phaseLabel}`}</div>
                        {t.entries.map((e, i) => (
                            <div key={i} style={{ display: 'grid', gridTemplateColumns: 'auto minmax(0, 1fr)', gap: 8, alignItems: 'baseline', paddingTop: 4 }}>
                                <Chip tone={kindTone(e.kind)}>{kindLabel(e.kind)}</Chip>
                                <span style={{ fontSize: 13 }}>{e.text}</span>
                            </div>
                        ))}
                    </div>
                ))}
            </div>
            {nation.length > 0 ? (
                <div style={{ paddingTop: 10 }}>
                    <div style={{ fontSize: 12, color: 'var(--muted)', paddingBottom: 4 }}>세력 요약</div>
                    {nation.slice(0, 4).map((e, i) => (
                        <div key={i} style={{ display: 'grid', gridTemplateColumns: 'auto minmax(0, 1fr)', gap: 8, alignItems: 'baseline', paddingTop: 4 }}>
                            <Chip tone={kindTone(e.kind)}>{kindLabel(e.kind)}</Chip>
                            <span style={{ fontSize: 13 }}>{e.text}</span>
                        </div>
                    ))}
                </div>
            ) : null}
        </Panel>
    );
}
