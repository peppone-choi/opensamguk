'use client';

import { formatLog } from '@/lib/utilGame';
import type { FrontRecentRecordRow, FrontRecentRecord } from '@/lib/types';

interface RecordFeedProps {
    title: string;
    rows: FrontRecentRecordRow[];
    className: string;
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

function RecordFeed({ title, rows, className }: RecordFeedProps) {
    return (
        <section className={`main-record-feed ${className}`} aria-label={title}>
            <div className="main-record-title">{title}</div>
            <div className="main-record-body">
                {rows.map(([id, text]) => (
                    <div
                        key={id}
                        className="main-record-row"
                        data-record-id={id}
                        dangerouslySetInnerHTML={{ __html: formatLog(text) }}
                    />
                ))}
            </div>
        </section>
    );
}

export default function MainRecordZone({ recentRecord }: { recentRecord: unknown }) {
    const normalized = normalizeRecentRecord(recentRecord);
    return (
        <div className="main-record-zone">
            <RecordFeed title="장수 동향" rows={normalized.global} className="main-record-public" />
            <RecordFeed title="개인 기록" rows={normalized.general} className="main-record-general" />
            <RecordFeed title="중원 정세" rows={normalized.history} className="main-record-world" />
        </div>
    );
}
