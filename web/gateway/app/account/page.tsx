'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import React, { type FormEvent, useRef, useState } from 'react';
import AuthGate from '@/components/AuthGate';
import Topbar from '@/components/Topbar';
import { useAuth } from '@/lib/auth-context';
import { changeNickname, changePassword, deleteAccount, deleteProfileIcon, updateProfileIcon, uploadProfileIcon } from '@/lib/client';
import PortraitCropEditor from '@/components/account/PortraitCropEditor';
import type { PortraitCrops } from '@/lib/portraitCrop';
import RepresentativeSection from '@/components/account/RepresentativeSection';
import { Portrait } from '@opensamguk/ui';

const ICON_GUIDE = '원본을 올린 뒤 히어로·카드·아이콘의 구도를 각각 조절하세요. jpg·png·webp, 최대 8MB. 원본은 보관되어 다시 편집할 수 있습니다.';
const ICON_ACCEPT = 'image/jpeg,image/png,image/webp';

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
    const [crops, setCrops] = useState<PortraitCrops | null>(null);
    const [savedCrops, setSavedCrops] = useState<PortraitCrops | undefined>();
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
            await refresh(updated);
        }, '닉네임을 변경했습니다.');
    };

    const submitUpload = async (event: FormEvent) => {
        event.preventDefault();
        if (!file) {
            setFeedback({ scope: 'icon', ok: false, text: '업로드할 이미지를 선택하세요.' });
            return;
        }
        if (!crops) {
            setFeedback({ scope: 'icon', ok: false, text: '원본을 불러온 뒤 세 구도를 확인하세요.' });
            return;
        }
        await run('icon', async () => {
            const updated = await uploadProfileIcon(file, crops);
            // preview·상태는 서버 canonical 값에서만 갱신한다(클라이언트 파일명 아님).
            setPicture(updated.picture ?? '');
            setImgsvr(updated.imageServer ?? 0);
            setFile(null);
            setCrops(null);
            setSavedCrops(undefined);
            if (fileInputRef.current) fileInputRef.current.value = '';
            await refresh();
        }, '전콘을 업로드했습니다.');
    };

    const editSaved = async () => {
        await run('icon', async () => {
            const [sourceResponse, cropsResponse] = await Promise.all([
                fetch('/api/account/profile-icon/source', { cache: 'no-store' }),
                fetch('/api/account/profile-icon/crops', { cache: 'no-store' }),
            ]);
            for (const response of [sourceResponse, cropsResponse]) {
                if (!response.ok) {
                    const error = await response.json().catch(() => null);
                    throw new Error(error?.error ?? '보관된 원본을 불러오지 못했습니다.');
                }
            }
            if (!sourceResponse.headers.get('X-Portrait-Id') || sourceResponse.headers.get('X-Portrait-Id') !== cropsResponse.headers.get('X-Portrait-Id')) throw new Error('다른 창에서 전콘이 변경됐습니다. 원본을 다시 불러오세요.');
            const original = await sourceResponse.blob();
            setSavedCrops(await cropsResponse.json() as PortraitCrops);
            setCrops(null);
            setFile(new File([original], 'original', { type: original.type }));
        }, '원본을 불러왔습니다. 세 구도를 조절한 뒤 업로드하세요.');
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

    return (
        <main className="lobby-main fade-in">
            <div className="lobby-section-title-row">
                <h1 className="lobby-section-title">계정 설정</h1>
                <Link className="btn-ghost" href="/lobby">로비로</Link>
            </div>
            <section className="game-panel">
                <h2>닉네임 변경</h2>
                <form className="account-form" onSubmit={submitNickname}>
                    <label className="account-field">닉네임<input aria-label="닉네임" value={nickname} onChange={(event) => setNickname(event.target.value)} minLength={2} maxLength={20} required /></label>
                    <p>2~20자, 다른 사용자와 겹칠 수 없습니다.</p>
                    <button className="btn-primary" type="submit" disabled={busy}>닉네임 변경</button>
                    {message('nickname')}
                </form>
            </section>
            <section className="game-panel">
                <h2>비밀번호 변경</h2>
                <form className="account-form" onSubmit={submitPassword}>
                    <label className="account-field">현재 비밀번호<input aria-label="현재 비밀번호" type="password" value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} required /></label>
                    <label className="account-field">새 비밀번호<input aria-label="새 비밀번호" type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} minLength={6} required /></label>
                    <button className="btn-primary" type="submit" disabled={busy}>변경</button>
                    {message('password')}
                </form>
            </section>
            <section className="game-panel">
                <h2>전콘</h2>
                <Portrait picture={picture.trim() || null} imageServer={imgsvr} size="card-126" alt="현재 전콘" />
                <form className="account-form" onSubmit={submitUpload}>
                    <p>{ICON_GUIDE}</p>
                    <label className="account-field">이미지 파일<input ref={fileInputRef} aria-label="전콘 이미지 파일" type="file" accept={ICON_ACCEPT} disabled={busy} onChange={(e) => { setSavedCrops(undefined); setCrops(null); setFeedback(null); setFile(e.target.files?.[0] ?? null); }} /></label>
                    {file && <PortraitCropEditor file={file} initial={savedCrops} disabled={busy} onChange={setCrops} />}
                    {imgsvr === 1 && /^[0-9a-f]{8}\.portrait$/.test(picture) && <button className="btn-ghost" type="button" disabled={busy} onClick={() => void editSaved()}>보관된 원본으로 다시 편집</button>}
                    <button className="btn-primary" type="submit" disabled={busy || (!!file && !crops)}>업로드</button>
                    <button className="btn-ghost" type="button" onClick={() => void removeUpload()} disabled={busy || imgsvr !== 1}>삭제</button>
                    {message('icon')}
                </form>
                <form className="account-form" onSubmit={submitShared}>
                    <label className="account-field">공유 전콘 파일명<input aria-label="전콘 파일명" value={picture} onChange={(e) => setPicture(e.target.value)} placeholder="icon.png" /></label>
                    <label className="account-field">이미지 서버<select aria-label="이미지 서버" value={imgsvr} onChange={(e) => setImgsvr(Number(e.target.value))}><option value={0}>공유</option><option value={1}>업로드</option></select></label>
                    <button className="btn-primary" type="submit" disabled={busy}>저장</button>
                    {message('shared')}
                </form>
            </section>
            <RepresentativeSection />
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
                <Topbar current="account" />
                <AccountSettings />
            </div>
        </AuthGate>
    );
}
