'use client';

import { useRef, useState, type FormEvent } from 'react';
import { Button, Modal } from '@opensamguk/ui';

export type ReportTarget = { readonly kind: 'post' | 'comment'; readonly id: number };

/**
 * 신고 시트(설계서 §3.2 11–14, 보드 V31K5MBoardPost) — 모바일은 하단 시트, 데스크톱은 가운데 대화상자.
 * 사유가 비면 「신고 접수」는 사유와 함께 잠긴다. 서버 거절(「이미 신고한 게시글입니다.」 등)은 시트 안에 그대로 보인다.
 */
export function ReportSheet({ target, onClose, onSubmit }: {
    readonly target: ReportTarget;
    readonly onClose: () => void;
    readonly onSubmit: (reason: string) => Promise<void>;
}) {
    const [reason, setReason] = useState('');
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const field = useRef<HTMLTextAreaElement>(null);
    const noun = target.kind === 'post' ? '글' : '댓글';
    const submit = async (event: FormEvent) => {
        event.preventDefault();
        if (!reason.trim() || busy) return;
        setBusy(true);
        setError(null);
        try {
            await onSubmit(reason.trim());
        } catch (cause) {
            setError(cause instanceof Error ? cause.message : '신고를 접수하지 못했습니다.');
            setBusy(false);
        }
    };
    const block = busy ? '처리 중입니다' : reason.trim() ? null : '사유를 쓰세요';
    return (
        <Modal ariaLabel={`${noun} 신고`} className="gw31-sheet-card" overlayClassName="gw31-sheet-overlay" onClose={onClose} closeOnBackdrop={!busy} closeOnEscape={!busy} initialFocusRef={field}>
            <form className="gw31-form gw31-report" onSubmit={submit}>
                <h2 className="gw31-report__title os-serif">{noun} 신고</h2>
                <div className="gw31-field">
                    <label htmlFor="board-report-reason">신고 사유</label>
                    <textarea
                        ref={field}
                        id="board-report-reason"
                        className="os-input gw31-textarea"
                        maxLength={200}
                        rows={3}
                        placeholder="무엇이 문제인지 적어 주세요 — 200자까지"
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
                        aria-describedby="board-report-help"
                    />
                    <span className="gw31-field__help" id="board-report-help">운영자가 확인합니다. 이미 신고해 아직 처리되지 않은 {noun}은 다시 신고할 수 없습니다.</span>
                </div>
                {error && <p className="gw31-alert" role="alert">{error}</p>}
                <div className="gw31-report__actions">
                    {busy ? <Button disabled reason="처리 중입니다">취소</Button> : <Button onClick={onClose}>취소</Button>}
                    {block ? <Button variant="danger" disabled reason={block}>신고 접수</Button> : <Button variant="danger" type="submit">신고 접수</Button>}
                </div>
            </form>
        </Modal>
    );
}

/** 댓글 쓰기(설계서 §3.2 26–29) — 2000자, 비면 사유와 함께 잠근다. 서버 거절은 받은 문장 그대로. */
export function CommentForm({ onSubmit }: { readonly onSubmit: (content: string) => Promise<void> }) {
    const [content, setContent] = useState('');
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const submit = async (event: FormEvent) => {
        event.preventDefault();
        const trimmed = content.trim();
        if (!trimmed || busy) return;
        setBusy(true);
        setError(null);
        try {
            await onSubmit(trimmed);
            setContent('');
        } catch (cause) {
            setError(cause instanceof Error ? cause.message : '댓글을 등록하지 못했습니다.');
        } finally {
            setBusy(false);
        }
    };
    const block = busy ? '처리 중입니다' : content.trim() ? null : '댓글 내용을 쓰세요';
    return (
        <form className="gw31-form gw31-post__comment-form" onSubmit={submit}>
            <div className="gw31-field">
                <label htmlFor="board-comment">댓글</label>
                <textarea
                    id="board-comment"
                    className="os-input gw31-textarea"
                    maxLength={2000}
                    rows={3}
                    placeholder="댓글을 남겨보세요 — 2000자까지"
                    value={content}
                    readOnly={busy}
                    onChange={(e) => setContent(e.target.value)}
                />
            </div>
            {error && <p className="gw31-alert" role="alert">{error}</p>}
            <div>
                {block ? <Button variant="primary" disabled reason={block}>{busy ? '등록 중…' : '댓글 등록'}</Button> : <Button variant="primary" type="submit">댓글 등록</Button>}
            </div>
        </form>
    );
}
