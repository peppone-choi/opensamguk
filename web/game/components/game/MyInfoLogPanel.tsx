'use client';

import { useCallback, useEffect, useState } from 'react';
import GameCard from '../GameCard';
import { LogText } from '@opensamguk/ui';
import { api, type GeneralLogType } from '@/lib/api';

type LogType = GeneralLogType;

interface LogEntry {
    id: number;
    text: string;
}

type LogState = Record<LogType, LogEntry[]>;
type LogErrorState = Partial<Record<LogType, string>>;

interface LogSectionProps {
    title: string;
    titleColor: string;
    logs: LogEntry[];
    logType: LogType;
    onLoadMore?: (type: LogType) => void;
    loadingMore?: boolean;
    loading?: boolean;
    error?: string;
}

function LogSection({ title, titleColor, logs, logType, onLoadMore, loadingMore, loading, error }: LogSectionProps) {
    return (
        <GameCard style={{ marginBottom: 'var(--space-md)' }}>
            <div
                style={{
                    textAlign: 'center',
                    fontWeight: 700,
                    fontSize: 'var(--text-sm)',
                    color: titleColor,
                    marginBottom: 'var(--space-sm)',
                    padding: 'var(--space-xs) 0',
                    background: 'var(--bg-elevated)',
                }}
            >
                {title}
            </div>
            {error && (
                <p role="alert" style={{ color: 'var(--crimson)', textAlign: 'center', margin: 0 }}>
                    {error}
                </p>
            )}
            <div style={{ fontSize: 'var(--text-sm)', lineHeight: 1.7 }}>
                {loading ? (
                    <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>
                        불러오는 중...
                    </p>
                ) : logs.length === 0 ? (
                    <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>
                        기록이 없습니다.
                    </p>
                ) : (
                    logs.map((item) => (
                        <div
                            key={item.id}
                            style={{
                                padding: 'var(--space-xs) 0',
                                borderBottom: '1px solid var(--border-subtle)',
                            }}
                        >
                            <LogText text={item.text} />
                        </div>
                    ))
                )}
            </div>
            {onLoadMore && (
                <div style={{ textAlign: 'center', marginTop: 'var(--space-sm)' }}>
                    <button
                        onClick={() => onLoadMore(logType)}
                        disabled={loadingMore}
                        style={{ fontSize: 'var(--text-xs)' }}
                    >
                        {loadingMore ? '불러오는 중...' : '이전 로그 불러오기'}
                    </button>
                </div>
            )}
        </GameCard>
    );
}

interface MyInfoLogPanelProps {
    generalId: number;
}

export default function MyInfoLogPanel({ generalId }: MyInfoLogPanelProps) {
    const [loading, setLoading] = useState<Record<LogType, boolean>>({
        generalAction: true,
        battleDetail: true,
        battleResult: true,
        generalHistory: true,
    });
    const [loadingMore, setLoadingMore] = useState<Record<LogType, boolean>>({
        generalAction: false,
        battleDetail: false,
        battleResult: false,
        generalHistory: false,
    });
    const [errors, setErrors] = useState<LogErrorState>({});

    const [logs, setLogs] = useState<LogState>({
        generalAction: [],
        battleDetail: [],
        battleResult: [],
        generalHistory: [],
    });

    const loadLog = useCallback(async (type: LogType, reqTo?: number) => {
        const more = reqTo != null;
        if (more) {
            setLoadingMore((prev) => ({ ...prev, [type]: true }));
        } else {
            setLoading((prev) => ({ ...prev, [type]: true }));
        }
        try {
            const res = await api.generalLog(generalId, type, reqTo);
            if (!res.result) {
                setErrors((prev) => ({ ...prev, [type]: res.reason ?? '로그를 불러올 수 없습니다.' }));
                return;
            }
            const next = Object.entries(res.log ?? {})
                .map(([id, text]) => ({ id: Number(id), text }))
                .sort((a, b) => b.id - a.id);
            setLogs((prev) => ({
                ...prev,
                [type]: more ? [...prev[type], ...next] : next,
            }));
            setErrors((prev) => {
                const { [type]: _removed, ...rest } = prev;
                return rest;
            });
        } catch (e) {
            setErrors((prev) => ({
                ...prev,
                [type]: e instanceof Error ? e.message : '로그를 불러올 수 없습니다.',
            }));
        } finally {
            if (more) {
                setLoadingMore((prev) => ({ ...prev, [type]: false }));
            } else {
                setLoading((prev) => ({ ...prev, [type]: false }));
            }
        }
    }, [generalId]);

    useEffect(() => {
        void loadLog('generalAction');
        void loadLog('battleDetail');
        void loadLog('battleResult');
        void loadLog('generalHistory');
    }, [loadLog]);

    const handleLoadMore = (type: LogType) => {
        const lastId = logs[type].at(-1)?.id;
        if (lastId == null) {
            void loadLog(type);
            return;
        }
        void loadLog(type, lastId);
    };

    return (
        <div
            style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(400px, 1fr))',
                gap: 'var(--space-md)',
            }}
        >
            <LogSection
                title="개인 기록"
                titleColor="skyblue"
                logs={logs.generalAction}
                logType="generalAction"
                onLoadMore={handleLoadMore}
                loading={loading.generalAction}
                loadingMore={loadingMore.generalAction}
                error={errors.generalAction}
            />
            <LogSection
                title="전투 기록"
                titleColor="orange"
                logs={logs.battleDetail}
                logType="battleDetail"
                onLoadMore={handleLoadMore}
                loading={loading.battleDetail}
                loadingMore={loadingMore.battleDetail}
                error={errors.battleDetail}
            />
            <LogSection
                title="장수 열전"
                titleColor="skyblue"
                logs={logs.generalHistory}
                logType="generalHistory"
                loading={loading.generalHistory}
                error={errors.generalHistory}
            />
            <LogSection
                title="전투 결과"
                titleColor="orange"
                logs={logs.battleResult}
                logType="battleResult"
                onLoadMore={handleLoadMore}
                loading={loading.battleResult}
                loadingMore={loadingMore.battleResult}
                error={errors.battleResult}
            />
        </div>
    );
}
