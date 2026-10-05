'use client';

// 운영 콘솔 「공지」 훅 — 목록 읽기 · 작성 · 수정 · 고정 · soft-delete 와 폼 상태(D105 층: 화면 → 이 훅 → lib/admin-notices).
// NoticeControl 에 있던 상태 · 처리를 그대로 옮겼다. 요청은 lib/admin-notices 만 부른다.
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { createAdminNotice, deleteAdminNotice, listAdminNotices, setAdminNoticePinned, updateAdminNotice } from './admin-notices';
import type { Notice } from './notices';

export function useAdminNotices() {
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
            setNotices(await listAdminNotices());
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
                await updateAdminNotice(editing.id, { title, body, pinned });
                setMessage('공지를 수정했습니다.');
            } else {
                await createAdminNotice({ title, body, pinned });
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
            await setAdminNoticePinned(n.id, !n.pinned);
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
            await deleteAdminNotice(deleteTarget.id);
            setMessage('공지를 삭제했습니다(목록에는 삭제됨으로 남습니다).');
            await load();
        } catch (err) {
            setMessage(err instanceof Error ? err.message : '삭제하지 못했습니다.');
        } finally {
            setBusy(false);
            setDeleteTarget(null);
        }
    }

    return {
        notices,
        error,
        busy,
        message,
        editing,
        title,
        setTitle,
        body,
        setBody,
        pinned,
        setPinned,
        deleteTarget,
        setDeleteTarget,
        startEdit,
        resetForm,
        submit,
        togglePin,
        confirmDelete,
    };
}
