'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import type {
    DiplomacyLettersResponse,
    DiplomacyLetter,
    DiplomacyLetterNation,
} from '../../../lib/types';

// 외교부 (page 1) — letter-management view. Mirrors j_diplomacy_get_letter.php +
// ts/diplomacy.ts drawLetter(). READ-ONLY this wave: letter cards render the
// state/parties/brief/detail verbatim; no mutation wiring (승인/거부/회수/파기 deferred).

// Letter state text — verbatim from ts/diplomacy.ts stateText (LetterState).
const STATE_TEXT: Record<string, string> = {
    proposed: '제안됨',
    activated: '승인됨',
    cancelled: '거부됨',
    replaced: '대체됨',
};

// state_opt text — verbatim from ts/diplomacy.ts stateOptionText.
const STATE_OPT_TEXT: Record<string, string> = {
    try_destroy_src: '송신측의 파기 요청',
    try_destroy_dest: '수신측의 파기 요청',
};

// Letter state → war-room StatusBadge variant. proposed = pending(gold),
// activated = active(jade), replaced = neutral(muted). cancelled is skipped upstream.
const STATE_VARIANT: Record<string, 'crimson' | 'gold' | 'jade' | 'muted'> = {
    proposed: 'gold',
    activated: 'jade',
    cancelled: 'crimson',
    replaced: 'muted',
};

// isBrightColor (legacy util) — black text on a bright nation color, white otherwise.
function isBrightColor(hex?: string): boolean {
    if (!hex || !/^#?[0-9a-fA-F]{6}$/.test(hex)) return false;
    const h = hex.replace('#', '');
    const r = parseInt(h.slice(0, 2), 16);
    const g = parseInt(h.slice(2, 4), 16);
    const b = parseInt(h.slice(4, 6), 16);
    return r * 0.299 + g * 0.587 + b * 0.114 > 127;
}

export default function DiplomacyPage() {
    const [data, setData] = useState<DiplomacyLettersResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');

    const fetchData = useCallback(async () => {
        try {
            const res = await api.diplomacyLetters();
            setData(res);
            setError('');
        } catch {
            setError('데이터를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    useEffect(() => {
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => fetchData());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchData]);

    const myNationId = data?.myNationId ?? 0;
    const nations: DiplomacyLetterNation[] = data?.nations ?? [];
    // cancelled letters are not shown (legacy drawLetter skips state == 'cancelled').
    const letters: DiplomacyLetter[] = (data?.letters ?? []).filter(l => l.state !== 'cancelled');

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>외교부</h1>

            <div style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                <button onClick={fetchData}>새로고침</button>
            </div>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {!loading && !error && (
                <>
                    {/* 외교 대상 국가 — candidate counter-nations (excludes self & 재야). */}
                    <GameCard style={{ marginBottom: 'var(--space-md)' }}>
                        <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>외교 대상 국가</h2>
                        {nations.length === 0 ? (
                            <p style={{ color: 'var(--text-muted)' }}>외교 대상 국가가 없습니다.</p>
                        ) : (
                            <div style={{ display: 'flex', gap: 'var(--space-sm)', flexWrap: 'wrap' }}>
                                {nations.map(n => {
                                    const textColor = isBrightColor(n.color) ? '#000000' : '#ffffff';
                                    return (
                                        <span
                                            key={n.nationId}
                                            className="status-badge"
                                            style={{ backgroundColor: n.color, color: textColor, border: 'none' }}
                                        >
                                            {n.name}
                                        </span>
                                    );
                                })}
                            </div>
                        )}
                    </GameCard>

                    {/* 외교 서신 — letter list, newest-first. */}
                    <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>외교 서신</h2>
                    {letters.length === 0 ? (
                        <GameCard>
                            <p style={{ color: 'var(--text-muted)' }}>주고받은 외교 서신이 없습니다.</p>
                        </GameCard>
                    ) : (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
                            {letters.map(letter => (
                                <LetterCard key={letter.no} letter={letter} myNationId={myNationId} />
                            ))}
                        </div>
                    )}
                </>
            )}
        </Shell>
    );
}

function LetterCard({ letter, myNationId }: { letter: DiplomacyLetter; myNationId: number }) {
    // Header shows the counter-party (the OTHER side relative to my nation) — legacy targetNation.
    const counter = letter.src.nationId === myNationId ? letter.dest : letter.src;
    const headerBg = counter.color || 'var(--bg-hover)';
    const headerColor = isBrightColor(counter.color) ? '#000000' : '#ffffff';

    const stateText = STATE_TEXT[letter.state] ?? letter.state;
    const variant = STATE_VARIANT[letter.state] ?? 'muted';
    const stateOptText = letter.stateOpt ? STATE_OPT_TEXT[letter.stateOpt] ?? null : null;

    return (
        <GameCard style={{ padding: 0, overflow: 'hidden' }}>
            <div
                style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 'var(--space-sm)',
                    padding: 'var(--space-sm) var(--space-md)',
                    backgroundColor: headerBg,
                    color: headerColor,
                    flexWrap: 'wrap',
                }}
            >
                <span style={{ fontWeight: 600 }}>{counter.name}</span>
                <span style={{ fontSize: 'var(--text-sm)', opacity: 0.85 }}>
                    #{letter.no} · {letter.date}
                </span>
            </div>

            <div style={{ padding: 'var(--space-md)' }}>
                <div style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'center', flexWrap: 'wrap', marginBottom: 'var(--space-sm)' }}>
                    <StatusBadge variant={variant}>{stateText}</StatusBadge>
                    {stateOptText && (
                        <span style={{ fontSize: 'var(--text-sm)', color: 'var(--gold)' }}>({stateOptText})</span>
                    )}
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)' }}>
                        {letter.prevNo != null ? `이전 서신 #${letter.prevNo}` : '신규'}
                    </span>
                </div>

                {/* 송신 → 수신 parties */}
                <div style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'center', flexWrap: 'wrap', marginBottom: 'var(--space-sm)', fontSize: 'var(--text-sm)' }}>
                    <span style={{ color: letter.src.color || 'var(--text-secondary)', fontWeight: 500 }}>{letter.src.name}</span>
                    <span style={{ color: 'var(--text-muted)' }}>→</span>
                    <span style={{ color: letter.dest.color || 'var(--text-secondary)', fontWeight: 500 }}>{letter.dest.name}</span>
                </div>

                {/* brief (요약) — always present; plain text, newlines preserved. */}
                <p style={{ whiteSpace: 'pre-wrap', marginBottom: letter.detail ? 'var(--space-sm)' : 0 }}>{letter.brief}</p>

                {/* detail (본문) — may be masked to '(권한이 부족합니다)' when permission < 3. */}
                {letter.detail && (
                    <p
                        style={{
                            whiteSpace: 'pre-wrap',
                            color: 'var(--text-secondary)',
                            fontSize: 'var(--text-sm)',
                            borderTop: '1px solid var(--border-subtle)',
                            paddingTop: 'var(--space-sm)',
                        }}
                    >
                        {letter.detail}
                    </p>
                )}
            </div>
        </GameCard>
    );
}
