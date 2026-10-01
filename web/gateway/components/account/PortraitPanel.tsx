'use client';

import { useRef, useState } from 'react';
import { Button, ConfirmDialog, Panel, Portrait, ReasonTooltip, SectionHeader } from '@opensamguk/ui';
import { useAuth } from '@/lib/auth-context';
import { deleteProfileIcon, getCurrentUser, uploadProfileIcon } from '@/lib/client';
import type { PortraitCrops } from '@/lib/portraitCrop';
import PortraitCropEditor, { type CropEditorState } from './PortraitCropEditor';

const GUIDE = '원본을 올린 뒤 큰 그림 · 카드 · 아이콘의 구도를 각각 조절하세요. 원본은 보관되어 다시 편집할 수 있습니다.';
const ACCEPT = 'image/jpeg,image/png,image/webp';
/** 보관 원본이 있는 업로드 초상 — 이때만 「보관된 원본으로 다시 편집」을 보인다. */
const SAVED_SOURCE = /^[0-9a-f]{8}\.portrait$/;

type Reason = { reason: string; title?: string; recovery?: string };

/**
 * 초상 패널(설계서 §2.5 A13–A25 · P1–P12, 보드 V31K5Account · MAccount). 공유 초상 파일명 · 이미지 서버 칸은 뺐다(A22–A25) —
 * 이미 저장된 공유 초상은 계속 보인다. 미리보기 · 상태는 서버 canonical 응답에서만 갱신한다.
 */
export default function PortraitPanel() {
    const { user, refresh } = useAuth();
    const [picture, setPicture] = useState(user?.picture ?? '');
    const [imgsvr, setImgsvr] = useState(user?.imageServer ?? 0);
    const [file, setFile] = useState<File | null>(null);
    const [crops, setCrops] = useState<PortraitCrops | null>(null);
    const [savedCrops, setSavedCrops] = useState<PortraitCrops | undefined>();
    const [editor, setEditor] = useState<CropEditorState>({ kind: 'loading' });
    const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
    const [busy, setBusy] = useState(false);
    const [confirming, setConfirming] = useState(false);
    const fileInput = useRef<HTMLInputElement>(null);

    const run = async (action: () => Promise<void>, success: string) => {
        setBusy(true);
        setResult(null);
        try {
            await action();
            setResult({ ok: true, text: success });
        } catch (e) {
            setResult({ ok: false, text: e instanceof Error ? e.message : '초상을 바꾸지 못했습니다.' });
        } finally {
            setBusy(false);
        }
    };

    const upload = () => run(async () => {
        if (!file || !crops) return;
        const updated = await uploadProfileIcon(file, crops);
        setPicture(updated.picture ?? '');
        setImgsvr(updated.imageServer ?? 0);
        setFile(null);
        setCrops(null);
        setSavedCrops(undefined);
        if (fileInput.current) fileInput.current.value = '';
        // 인자 없는 refresh() 는 세션을 「불러오는 중」으로 돌려 AuthGate 가 화면을 통째로 내린다(결과 줄도 사라진다) — 서버 canonical 값을 넘긴다.
        await refresh(updated);
    }, '초상을 올렸습니다.');

    const editSaved = () => run(async () => {
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
        const id = sourceResponse.headers.get('X-Portrait-Id');
        if (!id || id !== cropsResponse.headers.get('X-Portrait-Id')) throw new Error('다른 창에서 초상이 바뀌었습니다. 원본을 다시 불러오세요.');
        const original = await sourceResponse.blob();
        setSavedCrops(await cropsResponse.json() as PortraitCrops);
        setCrops(null);
        setFile(new File([original], 'original', { type: original.type }));
    }, '원본을 불러왔습니다. 세 구도를 조절한 뒤 올리세요.');

    const remove = () => run(async () => {
        setConfirming(false);
        await deleteProfileIcon();
        // 지우면 검증된 기본 실루엣으로 수렴한다 — 낡은 업로드 주소를 붙들지 않는다.
        setPicture('');
        setImgsvr(0);
        // 지우기 응답엔 사용자가 없다 — 세션을 조용히 다시 읽어 넘긴다(위와 같은 이유로 인자 없는 refresh() 금지).
        const me = await getCurrentUser().catch(() => null);
        if (me) await refresh(me);
    }, '초상을 지웠습니다.');

    const uploadBlock: Reason | null = busy ? { reason: '처리 중입니다' }
        : !file ? { reason: '올릴 이미지 파일을 고르세요' }
            : editor.kind === 'loading' ? { reason: '원본을 불러오는 중…' }
                : editor.kind === 'error' ? { reason: editor.message }
                    : !editor.checked || !crops ? {
                        title: '올리기 — 아직 할 수 없습니다',
                        reason: '세 구도를 확인하세요',
                        recovery: '큰 그림 · 카드 · 아이콘을 한 번씩 눌러 구도를 맞추면 올릴 수 있습니다.',
                    } : null;
    const removeBlock = busy ? '처리 중입니다' : imgsvr !== 1 ? '올린 초상이 없습니다' : null;

    return (
        <Panel className="gw31-account__panel gw31-account__portrait" aria-labelledby="account-portrait">
            <SectionHeader as="h2" title={<span id="account-portrait">초상</span>} sub="jpg · png · webp · 최대 8MB" />
            <div className="gw31-account__portrait-top">
                <Portrait picture={picture.trim() || null} imageServer={imgsvr} size="card-126" alt="지금 초상" />
                <div className="gw31-account__portrait-side">
                    <p className="gw31-card__line">{GUIDE}</p>
                    <label className="gw31-field">
                        이미지 파일
                        <input
                            ref={fileInput}
                            aria-label="초상 이미지 파일"
                            type="file"
                            accept={ACCEPT}
                            disabled={busy}
                            onChange={(e) => { setSavedCrops(undefined); setCrops(null); setResult(null); setFile(e.target.files?.[0] ?? null); }}
                        />
                    </label>
                    <div className="gw31-account__actions">
                        {imgsvr === 1 && SAVED_SOURCE.test(picture) && (busy
                            ? <Button disabled reason="처리 중입니다">보관된 원본으로 다시 편집</Button>
                            : <Button onClick={() => void editSaved()}>보관된 원본으로 다시 편집</Button>)}
                        {uploadBlock ? (
                            <ReasonTooltip reason={uploadBlock.reason} title={uploadBlock.title} recovery={uploadBlock.recovery}>
                                <button type="button" className="os-button os-button--primary os-button--disabled" aria-disabled data-reason={uploadBlock.reason} onClick={(e) => e.preventDefault()}>올리기</button>
                            </ReasonTooltip>
                        ) : <Button variant="primary" onClick={() => void upload()}>올리기</Button>}
                        {removeBlock
                            ? <Button variant="danger" disabled reason={removeBlock}>지우기</Button>
                            : <Button variant="danger" onClick={() => setConfirming(true)}>지우기</Button>}
                    </div>
                    {result && <p className={`gw31-account__result${result.ok ? '' : ' is-bad'}`} role={result.ok ? 'status' : 'alert'}>{result.text}</p>}
                </div>
            </div>
            {file && (
                <div className="gw31-account__editor">
                    <PortraitCropEditor file={file} initial={savedCrops} disabled={busy} onChange={setCrops} onState={setEditor} />
                </div>
            )}
            <ConfirmDialog
                open={confirming}
                title="초상 지우기"
                message="올린 초상을 지우면 기본 실루엣으로 돌아갑니다."
                confirmLabel="지우기"
                danger
                busy={busy}
                onCancel={() => setConfirming(false)}
                onConfirm={() => void remove()}
            />
        </Panel>
    );
}
