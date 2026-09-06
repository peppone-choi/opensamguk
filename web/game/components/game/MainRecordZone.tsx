'use client';

// 지난 순 3탭(장수 동향·개인 기록·중원 정세 ≤15건). 세 구역을 모두 렌더하고 탭은 보이는 것만 바꾼다
// (라벨·region 계약 유지). 기본 탭은 중원 정세(03 아트보드).
import { useState } from 'react';
import { LogText } from '@opensamguk/ui';
import type { FrontRecentRecordRow, FrontRecentRecord } from '@/lib/types';

interface RecordFeedProps {
    title: string;
    rows: FrontRecentRecordRow[];
    className: string;
    active: boolean;
}

const EMPTY_RECENT_RECORD: FrontRecentRecord = {
    history: [],
    global: [],
    general: [],
    flushHistory: 0,
    flushGlobal: 0,
    flushGeneral: 0,
};

function isRecordRow(value: unknown): value is FrontRecentRecordRow {
    return Array.isArray(value) && typeof value[0] === 'number' && typeof value[1] === 'string';
}

function recordRows(value: unknown): FrontRecentRecordRow[] {
    if (!Array.isArray(value)) return [];
    return value.filter(isRecordRow);
}

function normalizeRecentRecord(value: unknown): FrontRecentRecord {
    if (value == null || Array.isArray(value) || typeof value !== 'object') return EMPTY_RECENT_RECORD;
    const record = value as Partial<Record<keyof FrontRecentRecord, unknown>>;
    return {
        history: recordRows(record.history),
        global: recordRows(record.global),
        general: recordRows(record.general),
        flushHistory: record.flushHistory === 1 ? 1 : 0,
        flushGlobal: record.flushGlobal === 1 ? 1 : 0,
        flushGeneral: record.flushGeneral === 1 ? 1 : 0,
    };
}

function RecordFeed({ title, rows, className, active }: RecordFeedProps) {
    return (
        <section className={`main-record-feed ${className}${active ? ' is-active' : ''}`} aria-label={title} data-active={active}>
            <div className="main-record-title">{title}</div>
            <div className="main-record-body os-feed" role="list">
                {rows.length === 0 && <div className="main-record-empty">기록이 없습니다.</div>}
                {rows.map((row) => (
                    <div key={row[0]} className="main-record-row" role="listitem" data-record-id={row[0]}>
                        <LogText className="main-record-text" text={row[1]} />
                    </div>
                ))}
            </div>
        </section>
    );
}

type RecordTab = 'history' | 'global' | 'general';
const TABS: { key: RecordTab; label: string }[] = [
    { key: 'history', label: '중원 정세' },
    { key: 'global', label: '장수 동향' },
    { key: 'general', label: '개인 기록' },
];

export default function MainRecordZone({ recentRecord }: { recentRecord: unknown }) {
    const normalized = normalizeRecentRecord(recentRecord);
    const [tab, setTab] = useState<RecordTab>('history');
    return (
        <div className="main-record-zone">
            <div className="main-record-tabs" role="tablist" aria-label="지난 순 기록">
                {TABS.map((t) => (
                    <button
                        key={t.key}
                        type="button"
                        role="tab"
                        aria-selected={tab === t.key}
                        className={`main-record-tab${tab === t.key ? ' is-on' : ''}`}
                        onClick={() => setTab(t.key)}
                    >
                        {t.label}
                        <span className="os-num main-record-count">{normalized[t.key].length}</span>
                    </button>
                ))}
            </div>
            <RecordFeed title="장수 동향" rows={normalized.global} className="main-record-public" active={tab === 'global'} />
            <RecordFeed title="개인 기록" rows={normalized.general} className="main-record-general" active={tab === 'general'} />
            <RecordFeed title="중원 정세" rows={normalized.history} className="main-record-world" active={tab === 'history'} />
        </div>
    );
}
