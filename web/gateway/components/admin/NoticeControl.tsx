'use client';

import { Button, Chip, SectionHeader, StatusView } from '@opensamguk/ui';
import ConfirmModal from '@/components/ConfirmModal';
import { formatNoticeDate } from '@/lib/notices';
import { useAdminNotices } from '@/lib/use-admin-notices';

// 운영 콘솔 「공지」 — gateway-api /admin/notices (ROLE_ADMIN). 목록·작성·수정·고정·soft-delete.
// 위험 등급: 가역 변경(고정/수정) · 파괴적(삭제는 soft-delete 라 목록에 「삭제됨」으로 남는다).
// 상태 · 요청은 useAdminNotices(lib/use-admin-notices → lib/admin-notices)가 맡고, 이 파일은 그리기만 한다(D105 층).

export default function NoticeControl() {
    const {
        notices, error, busy, message, editing, title, setTitle, body, setBody, pinned, setPinned,
        deleteTarget, setDeleteTarget, startEdit, resetForm, submit, togglePin, confirmDelete,
    } = useAdminNotices();

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
                {notices && notices.length === 0 && <StatusView kind="empty" title="등록된 공지가 없습니다." body="위에서 새 공지를 쓰면 여기에 보입니다." />}
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
