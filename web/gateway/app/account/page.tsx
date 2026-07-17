'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import React, { type FormEvent, useState } from 'react';
import AuthGate from '@/components/AuthGate';
import { useAuth } from '@/lib/auth-context';
import { changePassword, deleteAccount, updateProfileIcon } from '@/lib/client';
import { IMAGE_CDN_BASE } from '@/lib/constants';

function iconPath(imageServer: number, picture: string | null): string | null {
    if (!picture) return null;
    return `${IMAGE_CDN_BASE}/${imageServer ? 'd_pic' : 'd_shared'}/${picture}`;
}

function AccountSettings() {
    const router = useRouter();
    const { user, refresh, logout } = useAuth();
    const [currentPassword, setCurrentPassword] = useState('');
    const [newPassword, setNewPassword] = useState('');
    const [picture, setPicture] = useState(user?.picture ?? '');
    const [imgsvr, setImgsvr] = useState(user?.imageServer ?? 0);
    const [notice, setNotice] = useState('');
    const [error, setError] = useState('');
    const [busy, setBusy] = useState(false);

    const run = async (action: () => Promise<void>, success: string) => {
        setBusy(true);
        setError('');
        setNotice('');
        try {
            await action();
            setNotice(success);
        } catch (e) {
            setError(e instanceof Error ? e.message : '변경에 실패했습니다.');
        } finally {
            setBusy(false);
        }
    };

    const submitPassword = async (event: FormEvent) => {
        event.preventDefault();
        await run(async () => {
            await changePassword(currentPassword, newPassword);
            setCurrentPassword('');
            setNewPassword('');
        }, '비밀번호를 변경했습니다.');
    };

    const submitPicture = async (event: FormEvent) => {
        event.preventDefault();
        await run(async () => {
            await updateProfileIcon(picture.trim() || null, imgsvr);
            await refresh();
        }, '전콘을 저장했습니다.');
    };

    const removePicture = async () => {
        await run(async () => {
            await updateProfileIcon(null, 0);
            setPicture('');
            setImgsvr(0);
            await refresh();
        }, '전콘을 삭제했습니다.');
    };

    const submitDelete = async () => {
        if (!window.confirm('계정을 삭제하면 되돌릴 수 없습니다. 현재 비밀번호로 탈퇴하시겠습니까?')) return;
        await run(async () => {
            await deleteAccount(currentPassword);
            await logout();
            router.replace('/');
        }, '계정을 삭제했습니다.');
    };

    const preview = iconPath(imgsvr, picture.trim() || null);
    return (
        <main className="lobby-main fade-in">
            <div className="lobby-section-title-row">
                <h1 className="lobby-section-title">계정 설정</h1>
                <Link className="btn-ghost" href="/lobby">로비로</Link>
            </div>
            {notice && <p role="status">{notice}</p>}
            {error && <p role="alert">{error}</p>}
            <section className="game-panel">
                <h2>비밀번호 변경</h2>
                <form onSubmit={submitPassword}>
                    <label>현재 비밀번호<input aria-label="현재 비밀번호" type="password" value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} required /></label>
                    <label>새 비밀번호<input aria-label="새 비밀번호" type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} minLength={6} required /></label>
                    <button className="btn-primary" type="submit" disabled={busy}>변경</button>
                </form>
            </section>
            <section className="game-panel">
                <h2>전콘</h2>
                {preview && <img src={preview} alt="현재 전콘" width={96} height={96} style={{ objectFit: 'contain', borderRadius: 4 }} />}
                <form onSubmit={submitPicture}>
                    <label>파일명<input aria-label="전콘 파일명" value={picture} onChange={(e) => setPicture(e.target.value)} placeholder="icon.png" /></label>
                    <label>이미지 서버<select aria-label="이미지 서버" value={imgsvr} onChange={(e) => setImgsvr(Number(e.target.value))}><option value={0}>공유</option><option value={1}>업로드</option></select></label>
                    <button className="btn-primary" type="submit" disabled={busy}>저장</button>
                    <button className="btn-ghost" type="button" onClick={() => void removePicture()} disabled={busy}>삭제</button>
                </form>
            </section>
            <section className="game-panel">
                <h2>계정 탈퇴</h2>
                <p>탈퇴하려면 현재 비밀번호를 입력하세요.</p>
                <button className="btn-danger" type="button" onClick={() => void submitDelete()} disabled={busy}>계정 삭제</button>
            </section>
        </main>
    );
}

export default function AccountPage() {
    return <AuthGate><AccountSettings /></AuthGate>;
}
