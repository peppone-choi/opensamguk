'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import React, { type FormEvent, useRef, useState } from 'react';
import AuthGate from '@/components/AuthGate';
import Topbar from '@/components/Topbar';
import { useAuth } from '@/lib/auth-context';
import { changeNickname, changePassword, deleteAccount, deleteProfileIcon, updateProfileIcon, uploadProfileIcon } from '@/lib/client';
import { onPortraitError, portraitUrl } from '@/lib/portrait';
import { normalizeProfileIcon } from '@/lib/profileIcon';

// 규격 밖 이미지는 브라우저에서 128x128로 크롭·축소해 보낸다(lib/profileIcon). 그건 편의일 뿐
// 최종 보안 경계는 서버다 — 우회해도 서버 거부를 성공으로 위장하지 않는다.
const ICON_GUIDE = 'jpg·png·gif·webp·avif 이미지를 올리면 중앙을 정사각형으로 잘라 128x128로 자동 변환합니다. 크기를 미리 맞출 필요는 없습니다.';
// image/* 대신 서버가 받는 타입만 나열한다 — iOS 사진 선택기가 HEIC를 jpeg로 변환해 넘겨준다.
const ICON_ACCEPT = 'image/jpeg,image/png,image/gif,image/webp,image/avif';

// 피드백은 그 액션을 일으킨 컨트롤 옆에서만 뜬다 — 화면 밖 전역 배너로 밀어내지 않는다.
type Scope = 'nickname' | 'password' | 'icon' | 'shared' | 'delete';
type Feedback = { scope: Scope; ok: boolean; text: string };

function AccountSettings() {
    const router = useRouter();
    const { user, refresh, logout } = useAuth();
    const [nickname, setNickname] = useState(user?.nickname ?? '');
    const [currentPassword, setCurrentPassword] = useState('');
    const [newPassword, setNewPassword] = useState('');
    const [picture, setPicture] = useState(user?.picture ?? '');
    const [imgsvr, setImgsvr] = useState(user?.imageServer ?? 0);
    const [file, setFile] = useState<File | null>(null);
    const fileInputRef = useRef<HTMLInputElement>(null);
    const [feedback, setFeedback] = useState<Feedback | null>(null);
    const [busy, setBusy] = useState(false);

    const message = (scope: Scope) => (feedback && feedback.scope === scope
        ? <p role={feedback.ok ? 'status' : 'alert'}>{feedback.text}</p>
        : null);

    const run = async (scope: Scope, action: () => Promise<void>, success: string) => {
        setBusy(true);
        setFeedback(null);
        try {
            await action();
            setFeedback({ scope, ok: true, text: success });
        } catch (e) {
            setFeedback({ scope, ok: false, text: e instanceof Error ? e.message : '변경에 실패했습니다.' });
        } finally {
            setBusy(false);
        }
    };

    const submitPassword = async (event: FormEvent) => {
        event.preventDefault();
        await run('password', async () => {
            await changePassword(currentPassword, newPassword);
            setCurrentPassword('');
            setNewPassword('');
        }, '비밀번호를 변경했습니다.');
    };

    const submitNickname = async (event: FormEvent) => {
        event.preventDefault();
        await run('nickname', async () => {
            const updated = await changeNickname(nickname.trim());
            setNickname(updated.nickname ?? '');
            await refresh();
        }, '닉네임을 변경했습니다.');
    };

    const submitUpload = async (event: FormEvent) => {
        event.preventDefault();
        if (!file) {
            setFeedback({ scope: 'icon', ok: false, text: '업로드할 이미지를 선택하세요.' });
            return;
        }
        await run('icon', async () => {
            // 규격 밖이면 128x128로 변환, 이미 맞으면 원본 그대로. 실패는 throw로 드러난다.
            const updated = await uploadProfileIcon(await normalizeProfileIcon(file));
            // preview·상태는 서버 canonical 값에서만 갱신한다(클라이언트 파일명 아님).
            setPicture(updated.picture ?? '');
            setImgsvr(updated.imageServer ?? 0);
            setFile(null);
            if (fileInputRef.current) fileInputRef.current.value = '';
            await refresh();
        }, '전콘을 업로드했습니다.');
    };

    const removeUpload = async () => {
        await run('icon', async () => {
            await deleteProfileIcon();
            // 삭제 성공 → 기존 검증된 default portrait로 수렴, stale 업로드 URL 유지 안 함.
            setPicture('');
            setImgsvr(0);
            await refresh();
        }, '전콘을 삭제했습니다.');
    };

    const submitShared = async (event: FormEvent) => {
        event.preventDefault();
        await run('shared', async () => {
            const updated = await updateProfileIcon(picture.trim() || null, imgsvr);
            setPicture(updated.picture ?? '');
            setImgsvr(updated.imageServer ?? 0);
            await refresh();
        }, '전콘을 저장했습니다.');
    };

    const submitDelete = async () => {
        if (!window.confirm('계정을 삭제하면 되돌릴 수 없습니다. 현재 비밀번호로 탈퇴하시겠습니까?')) return;
        await run('delete', async () => {
            await deleteAccount(currentPassword);
            await logout();
            router.replace('/');
        }, '계정을 삭제했습니다.');
    };

    const preview = portraitUrl(picture.trim() || null, imgsvr);
    return (
        <main className="lobby-main fade-in">
            <div className="lobby-section-title-row">
                <h1 className="lobby-section-title">계정 설정</h1>
                <Link className="btn-ghost" href="/lobby">로비로</Link>
            </div>
            <section className="game-panel">
                <h2>닉네임 변경</h2>
                <form onSubmit={submitNickname}>
                    <label>닉네임<input aria-label="닉네임" value={nickname} onChange={(event) => setNickname(event.target.value)} minLength={2} maxLength={20} required /></label>
                    <p>2~20자, 다른 사용자와 겹칠 수 없습니다.</p>
                    <button className="btn-primary" type="submit" disabled={busy}>닉네임 변경</button>
                    {message('nickname')}
                </form>
            </section>
            <section className="game-panel">
                <h2>비밀번호 변경</h2>
                <form onSubmit={submitPassword}>
                    <label>현재 비밀번호<input aria-label="현재 비밀번호" type="password" value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} required /></label>
                    <label>새 비밀번호<input aria-label="새 비밀번호" type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} minLength={6} required /></label>
                    <button className="btn-primary" type="submit" disabled={busy}>변경</button>
                    {message('password')}
                </form>
            </section>
            <section className="game-panel">
                <h2>전콘</h2>
                <img src={preview} onError={onPortraitError} alt="현재 전콘" width={96} height={96} style={{ objectFit: 'contain', borderRadius: 4 }} />
                <form onSubmit={submitUpload}>
                    <p>{ICON_GUIDE}</p>
                    <label>이미지 파일<input ref={fileInputRef} aria-label="전콘 이미지 파일" type="file" accept={ICON_ACCEPT} onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>
                    <button className="btn-primary" type="submit" disabled={busy}>업로드</button>
                    <button className="btn-ghost" type="button" onClick={() => void removeUpload()} disabled={busy || imgsvr !== 1}>삭제</button>
                    {message('icon')}
                </form>
                <form onSubmit={submitShared}>
                    <label>공유 전콘 파일명<input aria-label="전콘 파일명" value={picture} onChange={(e) => setPicture(e.target.value)} placeholder="icon.png" /></label>
                    <label>이미지 서버<select aria-label="이미지 서버" value={imgsvr} onChange={(e) => setImgsvr(Number(e.target.value))}><option value={0}>공유</option><option value={1}>업로드</option></select></label>
                    <button className="btn-primary" type="submit" disabled={busy}>저장</button>
                    {message('shared')}
                </form>
            </section>
            <section className="game-panel">
                <h2>계정 탈퇴</h2>
                <p>탈퇴하려면 현재 비밀번호를 입력하세요.</p>
                <button className="btn-danger" type="button" onClick={() => void submitDelete()} disabled={busy}>계정 삭제</button>
                {message('delete')}
            </section>
        </main>
    );
}

export default function AccountPage() {
    return (
        <AuthGate>
            <div className="lobby-shell">
                <Topbar />
                <AccountSettings />
            </div>
        </AuthGate>
    );
}
