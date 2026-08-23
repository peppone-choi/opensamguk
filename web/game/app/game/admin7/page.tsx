'use client';

// ── 로그정보 (Admin7) — READ-ONLY 장수 상세 + 로그 패널 + 정렬/대상선택 ────────────────
// Frozen historical UI reference (ADR-LITE-042; not current product authority): legacy hwe/_admin7.php. game-api `GET /api/admin/general-log?gen=&query_type=`
// (AdminReadController, B4a). 전부 READ 렌더.
//
// ADMIN 게이트는 BE 강제: 비ADMIN→403, 비로그인→401. FE는 graceful 안내.
//
// form(_admin7.php:103-118): query_type select(최근턴/최근전투/장수명/전투수) + '정렬하기'(gen 초기화)
// + 대상장수 select(name (turnTimeHm)) + '조회하기'. legacy '정렬하기'는 gen=0으로 첫 장수 선택
// (BE도 gen 미지정시 정렬 첫 장수). 옵션 라벨/정렬키는 BE sortOptions verbatim.
//
// 패널(_admin7.php:122-171): 좌상 장수정보(generalInfo/2 → 본 read는 핵심 스칼라만 평면 노출 —
// 전체 generalInfo HTML 패러티는 별도 상세 API 백로그, BE DTO 주석 참조), 그리고 4개 로그:
//   개인 기록(actionLog) / 전투 기록(battleDetailLog) / 장수 열전(historyLog) / 전투 결과(battleResultLog).
// 각 로그 text는 패러티 로그 원문(색/태그 마크업) → world-log/history와 동일 v-html(dangerouslySetInnerHTML).
//
// EMPTY-SAFE: generalList [] → 빈 select, detail null → 안내. 절대 크래시하지 않는다.

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api } from '../../../lib/api';
import type { AdminGeneralLogResponse } from '../../../lib/api';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';

const panelTitleStyle: React.CSSProperties = {
    textAlign: 'center',
    background: 'var(--bg-elevated)',
    border: '0.5px solid var(--border-medium)',
    padding: 'var(--space-xs) var(--space-sm)',
    fontWeight: 600,
    fontSize: 'var(--text-sm)',
    marginBottom: 'var(--space-sm)',
};

const logRowStyle: React.CSSProperties = {
    fontSize: 'var(--text-sm)',
    lineHeight: 1.7,
    padding: 'var(--space-xs) 0',
    borderBottom: '1px solid var(--border-subtle)',
};

// 로그 패널 1개 — text가 패러티 원문(색/태그)이라 v-html 렌더(world-log/history와 동일).
function LogPanel({ title, lines }: { title: string; lines: string[] }) {
    return (
        <div style={{ flex: '1 1 320px', minWidth: 280 }}>
            <div style={panelTitleStyle}>{title}</div>
            <GameCard>
                {lines.length === 0 ? (
                    <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>기록이 없습니다.</p>
                ) : (
                    <div>
                        {lines.map((line, i) => (
                            <div key={i} style={logRowStyle} dangerouslySetInnerHTML={{ __html: line }} />
                        ))}
                    </div>
                )}
            </GameCard>
        </div>
    );
}

export default function Admin7Page() {
    const [data, setData] = useState<AdminGeneralLogResponse | null>(null);
    // queryType/gen은 사용자가 명시 선택하면 보내고, null/0이면 BE 기본(첫 정렬키/첫 장수)에 위임한다.
    const [queryType, setQueryType] = useState<string | null>(null);
    const [gen, setGen] = useState<number>(0);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');

    // background=true는 턴 갱신용 — 보고 있던 로그를 로딩 표시로 지우지 않는다.
    const fetchData = useCallback(async (g: number, qt: string | null, background = false) => {
        if (!background) setLoading(true);
        try {
            const d = await api.admin.generalLog(g, qt ?? undefined);
            setData(d);
            // BE가 정규화한 실제 선택값으로 동기화(정렬 변경 → 첫 장수, 기본 queryType 확정).
            setQueryType(d.queryType);
            setGen(d.gen);
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
            if (!background) setLoading(false);
        }
    }, []);

    useTurnRefresh(() => void fetchData(gen, queryType, true));

    // 초기 1회: BE 기본(첫 정렬키 + 첫 장수)으로 로드.
    useEffect(() => {
        fetchData(0, null);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // 정렬 변경(legacy '정렬하기' = gen 초기화 후 정렬 첫 장수).
    const onSortChange = (qt: string) => {
        setQueryType(qt);
        fetchData(0, qt);
    };

    // 대상장수 변경(legacy '조회하기').
    const onGenChange = (g: number) => {
        setGen(g);
        fetchData(g, queryType);
    };

    const sortOptions = data?.sortOptions ?? [];
    const generalList = data?.generalList ?? [];
    const detail = data?.detail ?? null;

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>로그 정보</h1>

            {/* ── 정렬/대상선택 form (_admin7.php:103-118) ───────────────────────── */}
            <GameCard style={{ marginBottom: 'var(--space-lg)' }}>
                <div style={{ display: 'flex', gap: 'var(--space-md)', alignItems: 'center', flexWrap: 'wrap' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>정렬순서 :</span>
                    <select
                        value={data?.queryType ?? ''}
                        onChange={(e) => onSortChange(e.target.value)}
                    >
                        {sortOptions.map((o) => (
                            <option key={o.queryType} value={o.queryType}>{o.label}</option>
                        ))}
                    </select>
                    <span style={{ color: 'var(--text-secondary)' }}>대상장수 :</span>
                    <select
                        value={data?.gen ?? 0}
                        onChange={(e) => onGenChange(Number(e.target.value))}
                    >
                        {generalList.map((g) => (
                            <option key={g.no} value={g.no}>
                                {g.name}{g.turnTimeHm ? ` (${g.turnTimeHm})` : ''}
                            </option>
                        ))}
                    </select>
                </div>
            </GameCard>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {/* ── 장수 정보 (스칼라 평면 노출 — generalInfo HTML 패러티는 백로그) ───── */}
            {!error && detail && (
                <GameCard style={{ marginBottom: 'var(--space-lg)' }}>
                    <div style={panelTitleStyle}>장 수 정 보</div>
                    <table className="game-table" style={{ width: '100%' }}>
                        <tbody>
                            <tr>
                                <th style={{ textAlign: 'left', width: '12ch' }}>장수명</th>
                                <td>{detail.name}{detail.npc >= 2 ? ' (NPC)' : ''}</td>
                                <th style={{ textAlign: 'left', width: '12ch' }}>관직</th>
                                <td>{detail.officerLevel}</td>
                            </tr>
                            <tr>
                                <th style={{ textAlign: 'left' }}>통솔 / 무력 / 지력 / 정치 / 매력</th>
                                <td>{detail.leadership} / {detail.strength} / {detail.intel} / {detail.politics ?? '-'} / {detail.charm ?? '-'}</td>
                                <th style={{ textAlign: 'left' }}>최근 턴</th>
                                <td>{detail.turnTime ?? '-'}</td>
                            </tr>
                        </tbody>
                    </table>
                </GameCard>
            )}
            {!error && !loading && !detail && (
                <GameCard style={{ marginBottom: 'var(--space-lg)' }}>
                    <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>대상 장수가 없습니다.</p>
                </GameCard>
            )}

            {/* ── 4개 로그 패널 (_admin7.php:139-170) ───────────────────────────── */}
            {!error && detail && (
                <div style={{ display: 'flex', gap: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'flex-start' }}>
                    <LogPanel title="개인 기록" lines={detail.actionLog} />
                    <LogPanel title="전투 기록" lines={detail.battleDetailLog} />
                    <LogPanel title="장수 열전" lines={detail.historyLog} />
                    <LogPanel title="전투 결과" lines={detail.battleResultLog} />
                </div>
            )}
        </Shell>
    );
}
