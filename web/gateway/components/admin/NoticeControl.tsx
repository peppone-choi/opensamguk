'use client';

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Button, Chip, EmptyState, SectionHeader } from '@opensamguk/ui';
import ConfirmModal from '@/components/ConfirmModal';
import { formatNoticeDate, type Notice } from '@/lib/notices';

// 운영 콘솔 「공지」 — gateway-api /admin/notices (ROLE_ADMIN). 목록·작성·수정·고정·soft-delete.
// 위험 등급: 가역 변경(고정/수정) · 파괴적(삭제는 soft-delete 라 목록에 「삭제됨」으로 남는다).
async function adminJson<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await fetch(`/api/proxy/admin/notices${path}`, {
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json' },
        ...init,
    });
    if (!res.ok) {
        let message = `요청 실패 (${res.status})`;
        try {
            const body = (await res.json()) as { message?: string; error?: string };
            message = body.message ?? body.error ?? message;
        } catch {
            /* keep default */
        }
        throw new Error(message);
    }
    return (await res.json()) as T;
}

export default function NoticeControl() {
    const [notices, setNotices] = useState<Notice[] | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState<string | null>(null);
    const [editing, setEditing] = useState<Notice | null>(null);
    const [title, setTitle] = useState('');
    const [body, setBody] = useState('');
    const [pinned, setPinned] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<Notice | null>(null);

    const load = useCallback(async () => {
        try {
            const data = await adminJson<{ notices: Notice[] }>('');
            setNotices(data.notices);
            setError(null);
        } catch (e) {
            setError(e instanceof Error ? e.message : '공지를 불러오지 못했습니다.');
        }
    }, []);

    useEffect(() => {
        void load();
    }, [load]);

    function startEdit(n: Notice) {
        setEditing(n);
        setTitle(n.title);
        setBody(n.body);
        setPinned(n.pinned);
        setMessage(null);
    }
    function resetForm() {
        setEditing(null);
        setTitle('');
        setBody('');
        setPinned(false);
    }

    async function submit(e: FormEvent<HTMLFormElement>) {
        e.preventDefault();
        if (busy) return;
        if (!title.trim() || !body.trim()) {
            setMessage('제목과 내용을 입력해 주세요.');
            return;
        }
        setBusy(true);
        setMessage(null);
        try {
            if (editing) {
                await adminJson<Notice>(`/${editing.id}`, { method: 'PUT', body: JSON.stringify({ title, body, pinned }) });
                setMessage('공지를 수정했습니다.');
            } else {
                await adminJson<Notice>('', { method: 'POST', body: JSON.stringify({ title, body, pinned }) });
                setMessage('공지를 등록했습니다.');
            }
            resetForm();
            await load();
        } catch (err) {
            setMessage(err instanceof Error ? err.message : '저장하지 못했습니다.');
        } finally {
            setBusy(false);
        }
    }

    async function togglePin(n: Notice) {
        if (busy) return;
        setBusy(true);
        try {
            await adminJson<Notice>(`/${n.id}/pin`, { method: 'PATCH', body: JSON.stringify({ pinned: !n.pinned }) });
            await load();
        } catch (err) {
            setMessage(err instanceof Error ? err.message : '고정 상태를 바꾸지 못했습니다.');
        } finally {
            setBusy(false);
        }
    }

    async function confirmDelete() {
        if (!deleteTarget || busy) return;
        setBusy(true);
        try {
            await adminJson<Notice>(`/${deleteTarget.id}`, { method: 'DELETE' });
            setMessage('공지를 삭제했습니다(목록에는 삭제됨으로 남습니다).');
            await load();
        } catch (err) {
            setMessage(err instanceof Error ? err.message : '삭제하지 못했습니다.');
        } finally {
            setBusy(false);
            setDeleteTarget(null);
        }
    }

    const busyProps = busy ? ({ disabled: true, reason: '처리 중입니다' } as const) : ({} as const);

    return (
        <div className="notice-control">
            <section className="os-panel os-panel--static" aria-label={editing ? '공지 수정' : '공지 작성'}>
                <SectionHeader title={editing ? `공지 수정 #${editing.id}` : '공지 작성'} sub="위험 등급: 가역 변경" />
                <form className="notice-control__form" onSubmit={submit}>
                    <label className="notice-control__field">
                        제목
                        <input aria-label="공지 제목" value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} disabled={busy} />
                    </label>
                    <label className="notice-control__field">
                        내용
                        <textarea aria-label="공지 내용" value={body} maxLength={4000} rows={5} onChange={(e) => setBody(e.target.value)} disabled={busy} />
                    </label>
                    <label className="notice-control__check">
                        <input type="checkbox" checked={pinned} onChange={(e) => setPinned(e.target.checked)} disabled={busy} /> 상단 고정
                    </label>
                    <div className="notice-control__actions">
                        <Button type="submit" variant="primary" {...busyProps}>{editing ? '수정 저장' : '공지 등록'}</Button>
                        {editing && <Button type="button" variant="ghost" onClick={resetForm} {...busyProps}>취소</Button>}
                    </div>
                    {message && <p className="notice-control__message" role="status">{message}</p>}
                </form>
            </section>
            <section className="os-panel os-panel--static" aria-label="공지 목록">
                <SectionHeader title="공지 목록" sub={notices ? `${notices.length}건 (삭제됨 포함)` : undefined} />
                {error && <p className="notice-control__message" role="alert">{error}</p>}
                {notices && notices.length === 0 && <EmptyState title="등록된 공지가 없습니다." />}
                {notices && notices.length > 0 && (
                    <div className="game-table-wrap">
                        <table className="game-table os-table">
                            <thead>
                                <tr>
                                    <th>게시</th>
                                    <th>제목</th>
                                    <th>상태</th>
                                    <th>동작</th>
                                </tr>
                            </thead>
                            <tbody>
                                {notices.map((n) => (
                                    <tr key={n.id} className={n.deleted ? 'is-deleted' : undefined}>
                                        <td className="os-num">{formatNoticeDate(n.publishedAt)}</td>
                                        <td>{n.title}</td>
                                        <td>
                                            {n.deleted && <Chip tone="rust">삭제됨</Chip>}
                                            {!n.deleted && n.pinned && <Chip tone="bronze">고정</Chip>}
                                        </td>
                                        <td className="notice-control__row-actions">
                                            {n.deleted ? (
                                                <span className="text-muted">-</span>
                                            ) : (
                                                <>
                                                    <Button size="sm" variant="ghost" onClick={() => startEdit(n)} {...busyProps}>수정</Button>
                                                    <Button size="sm" variant="ghost" onClick={() => void togglePin(n)} {...busyProps}>{n.pinned ? '고정 해제' : '고정'}</Button>
                                                    <Button size="sm" variant="danger" onClick={() => setDeleteTarget(n)} {...busyProps}>삭제</Button>
                                                </>
                                            )}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>
            {deleteTarget && (
                <ConfirmModal
                    open
                    title="공지 삭제 확인"
                    message={`「${deleteTarget.title}」 공지를 삭제합니다. 목록에는 삭제됨으로 남고 공개 피드에서는 사라집니다.`}
                    confirmLabel="삭제"
                    danger
                    busy={busy}
                    onConfirm={() => void confirmDelete()}
                    onCancel={() => setDeleteTarget(null)}
                />
            )}
        </div>
    );
}
