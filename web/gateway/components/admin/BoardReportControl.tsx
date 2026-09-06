'use client';
// 운영 콘솔 · 게시판 관리 — 커뮤니티 신고 처리(ADR-LITE-049 13). 열린 신고를 처리/기각한다. 위험 등급: 가역.
import React, { useCallback, useEffect, useState } from 'react';
import { Chip, Panel, SectionHeader } from '@opensamguk/ui';
import { fetchBoardReports, handleBoardReport, type BoardReport, type BoardReportStatus } from '@/lib/board';

const STATUS_LABEL: Record<BoardReportStatus, string> = { OPEN: '열림', HANDLED: '처리', DISMISSED: '기각' };

export default function BoardReportControl() {
    const [status, setStatus] = useState<BoardReportStatus>('OPEN');
    const [reports, setReports] = useState<readonly BoardReport[] | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState<number | null>(null);

    const load = useCallback(async (next: BoardReportStatus) => {
        setError(null);
        try {
            setReports(await fetchBoardReports(next));
        } catch (cause) {
            setReports(null);
            setError(cause instanceof Error ? cause.message : '신고 목록을 불러오지 못했습니다.');
        }
    }, []);
    useEffect(() => { void load(status); }, [load, status]);

    async function decide(report: BoardReport, next: 'HANDLED' | 'DISMISSED'): Promise<void> {
        setBusy(report.id);
        setError(null);
        try {
            await handleBoardReport(report.id, next);
            await load(status);
        } catch (cause) {
            setError(cause instanceof Error ? cause.message : '신고를 처리하지 못했습니다.');
        } finally {
            setBusy(null);
        }
    }

    return (
        <Panel className="admin-report" aria-label="신고 처리">
            <SectionHeader
                title="신고 처리"
                sub="위험 등급: 가역"
                tone="rust"
                actions={
                    <select aria-label="신고 상태" value={status} onChange={(event) => setStatus(event.target.value as BoardReportStatus)}>
                        <option value="OPEN">열림</option>
                        <option value="HANDLED">처리됨</option>
                        <option value="DISMISSED">기각됨</option>
                    </select>
                }
            />
            {error ? <p className="field-error" role="alert">{error}</p> : null}
            {reports === null && !error ? <p className="admin-report__empty" role="status">신고를 불러오는 중…</p> : null}
            {reports && reports.length === 0 ? <p className="admin-report__empty">{STATUS_LABEL[status]} 신고가 없습니다.</p> : null}
            {reports && reports.length > 0 && (
                <div className="os-table-wrap">
                    <table className="os-table">
                        <thead>
                            <tr><th scope="col">대상</th><th scope="col">사유</th><th scope="col">신고자</th><th scope="col">시각</th><th scope="col">상태</th><th scope="col">조치</th></tr>
                        </thead>
                        <tbody>
                            {reports.map((report) => (
                                <tr key={report.id}>
                                    <td>
                                        {report.postId != null
                                            ? <a href={`/board/posts/${report.postId}`}>글 · {report.targetSummary ?? '(삭제됨)'}</a>
                                            : <span>댓글 · {report.targetSummary ?? '(삭제됨)'}</span>}
                                    </td>
                                    <td>{report.reason}</td>
                                    <td>{report.reporterName}</td>
                                    <td className="os-num">{report.createdAt.slice(0, 16).replace('T', ' ')}</td>
                                    <td><Chip tone={report.status === 'OPEN' ? 'rust' : report.status === 'HANDLED' ? 'moss' : 'neutral'}>{STATUS_LABEL[report.status]}</Chip></td>
                                    <td>
                                        {report.status === 'OPEN' ? (
                                            <span className="admin-report__actions">
                                                <button type="button" className="os-button os-button--sm" disabled={busy === report.id} onClick={() => void decide(report, 'HANDLED')}>처리</button>
                                                <button type="button" className="os-button os-button--sm os-button--ghost" disabled={busy === report.id} onClick={() => void decide(report, 'DISMISSED')}>기각</button>
                                            </span>
                                        ) : <span className="os-num">{report.handledAt?.slice(0, 16).replace('T', ' ') ?? '-'}</span>}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </Panel>
    );
}
