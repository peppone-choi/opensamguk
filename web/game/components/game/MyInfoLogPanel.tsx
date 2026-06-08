'use client';

// MyInfoLogPanel — b_myPage.php의 개인기록/전투기록/전투결과/장수열전 4개 패널을 렌더한다.
// BE API(generalAction/battleDetail/battleResult/generalHistory 로그 read)가 아직 없으므로
// 구조만 마련하고 빈 상태를 안내한다. API가 생기면 fetch 로직 + 데이터 렌더만 추가하면 된다.
//
// PHP b_myPage.php 구조:
//   1) 개인 기록(generalAction log, 24 rows + "이전 로그 불러오기")
//   2) 전투 기록(battleDetail log, 24 rows + "이전 로그 불러오기")
//   3) 장수 열전(generalHistory log, 전체)
//   4) 전투 결과(battleResult log, 24 rows + "이전 로그 불러오기")

import { useState } from 'react';
import GameCard from '../GameCard';
import { formatLog } from '@/lib/utilGame';

type LogType = 'generalAction' | 'battleDetail' | 'battleResult' | 'generalHistory';

interface LogSectionProps {
    title: string;
    titleColor: string;
    logs: string[];
    logType: LogType;
    onLoadMore?: (type: LogType) => void;
    loadingMore?: boolean;
}

function LogSection({ title, titleColor, logs, logType, onLoadMore, loadingMore }: LogSectionProps) {
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
            <div style={{ fontSize: 'var(--text-sm)', lineHeight: 1.7 }}>
                {logs.length === 0 ? (
                    <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>
                        기록이 없습니다.
                    </p>
                ) : (
                    logs.map((item, idx) => (
                        <div
                            key={idx}
                            style={{
                                padding: 'var(--space-xs) 0',
                                borderBottom: '1px solid var(--border-subtle)',
                            }}
                            dangerouslySetInnerHTML={{ __html: formatLog(item) }}
                        />
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
    const [loadingMore, setLoadingMore] = useState<Record<LogType, boolean>>({
        generalAction: false,
        battleDetail: false,
        battleResult: false,
        generalHistory: false,
    });

    // BE API 미구현 — 빈 배열로 초기화. API 생기면 useEffect + fetch 로직 추가.
    const [logs] = useState<Record<LogType, string[]>>({
        generalAction: [],
        battleDetail: [],
        battleResult: [],
        generalHistory: [],
    });

    const handleLoadMore = (type: LogType) => {
        setLoadingMore((prev) => ({ ...prev, [type]: true }));
        // TODO: BE API 구현 시 fetch 로직 추가
        // api.generalLogs(generalId, type, offset).then(...)
        setTimeout(() => {
            setLoadingMore((prev) => ({ ...prev, [type]: false }));
        }, 300);
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
                loadingMore={loadingMore.generalAction}
            />
            <LogSection
                title="전투 기록"
                titleColor="orange"
                logs={logs.battleDetail}
                logType="battleDetail"
                onLoadMore={handleLoadMore}
                loadingMore={loadingMore.battleDetail}
            />
            <LogSection
                title="장수 열전"
                titleColor="skyblue"
                logs={logs.generalHistory}
                logType="generalHistory"
            />
            <LogSection
                title="전투 결과"
                titleColor="orange"
                logs={logs.battleResult}
                logType="battleResult"
                onLoadMore={handleLoadMore}
                loadingMore={loadingMore.battleResult}
            />
        </div>
    );
}
