'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import React, { type FormEvent, useRef, useState } from 'react';
import AuthGate from '@/components/AuthGate';
import { useAuth } from '@/lib/auth-context';
import { changePassword, deleteAccount, deleteProfileIcon, updateProfileIcon, uploadProfileIcon } from '@/lib/client';
import { onPortraitError, portraitUrl } from '@/lib/portrait';

// gateway-api ProfileIconDecoder와 동일한 경계(50KiB, 64~128px 정사각형). 브라우저 사전검증은 편의일 뿐
// 최종 보안 경계는 서버다 — 우회해도 서버 거부를 성공으로 위장하지 않는다.
const MAX_ICON_BYTES = 51200;
const ICON_GUIDE = '50KB 이하, 64~128px 정사각형 (jpg·png·gif·webp·avif)';

async function prevalidateIcon(file: File): Promise<string | null> {
    if (file.size > MAX_ICON_BYTES) return '프로필 아이콘은 50KB 이하여야 합니다.';
    let bitmap: ImageBitmap;
    try {
        bitmap = await createImageBitmap(file);
    } catch {
        return '이미지를 읽을 수 없습니다. jpg·png·gif·webp·avif 파일을 선택하세요.';
    }
    const { width, height } = bitmap;
    bitmap.close?.();
    if (width !== height || width < 64 || width > 128) {
        return '프로필 아이콘은 64~128px 정사각형이어야 합니다.';
    }
    return null;
}

function AccountSettings() {
    const router = useRouter();
    const { user, refresh, logout } = useAuth();
    const [currentPassword, setCurrentPassword] = useState('');
    const [newPassword, setNewPassword] = useState('');
    const [picture, setPicture] = useState(user?.picture ?? '');
    const [imgsvr, setImgsvr] = useState(user?.imageServer ?? 0);
    const [file, setFile] = useState<File | null>(null);
    const fileInputRef = useRef<HTMLInputElement>(null);
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

    const submitUpload = async (event: FormEvent) => {
        event.preventDefault();
        if (!file) {
            setNotice('');
            setError('업로드할 이미지를 선택하세요.');
            return;
        }
        const problem = await prevalidateIcon(file);
        if (problem) {
            setNotice('');
            setError(problem);
            return;
        }
        await run(async () => {
            const updated = await uploadProfileIcon(file);
            // preview·상태는 서버 canonical 값에서만 갱신한다(클라이언트 파일명 아님).
            setPicture(updated.picture ?? '');
            setImgsvr(updated.imageServer ?? 0);
            setFile(null);
            if (fileInputRef.current) fileInputRef.current.value = '';
            await refresh();
        }, '전콘을 업로드했습니다.');
    };

    const removeUpload = async () => {
        await run(async () => {
            await deleteProfileIcon();
            // 삭제 성공 → 기존 검증된 default portrait로 수렴, stale 업로드 URL 유지 안 함.
            setPicture('');
            setImgsvr(0);
            await refresh();
        }, '전콘을 삭제했습니다.');
    };

    const submitShared = async (event: FormEvent) => {
        event.preventDefault();
        await run(async () => {
            const updated = await updateProfileIcon(picture.trim() || null, imgsvr);
            setPicture(updated.picture ?? '');
            setImgsvr(updated.imageServer ?? 0);
            await refresh();
        }, '전콘을 저장했습니다.');
    };

    const submitDelete = async () => {
        if (!window.confirm('계정을 삭제하면 되돌릴 수 없습니다. 현재 비밀번호로 탈퇴하시겠습니까?')) return;
        await run(async () => {
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
                <img src={preview} onError={onPortraitError} alt="현재 전콘" width={96} height={96} style={{ objectFit: 'contain', borderRadius: 4 }} />
                <form onSubmit={submitUpload}>
                    <p>{ICON_GUIDE}</p>
                    <label>이미지 파일<input ref={fileInputRef} aria-label="전콘 이미지 파일" type="file" accept="image/*" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>
                    <button className="btn-primary" type="submit" disabled={busy}>업로드</button>
                    <button className="btn-ghost" type="button" onClick={() => void removeUpload()} disabled={busy || imgsvr !== 1}>삭제</button>
                </form>
                <form onSubmit={submitShared}>
                    <label>공유 전콘 파일명<input aria-label="전콘 파일명" value={picture} onChange={(e) => setPicture(e.target.value)} placeholder="icon.png" /></label>
                    <label>이미지 서버<select aria-label="이미지 서버" value={imgsvr} onChange={(e) => setImgsvr(Number(e.target.value))}><option value={0}>공유</option><option value={1}>업로드</option></select></label>
                    <button className="btn-primary" type="submit" disabled={busy}>저장</button>
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
