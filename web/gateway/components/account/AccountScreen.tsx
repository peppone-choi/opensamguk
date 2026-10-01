'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState, type FormEvent, type ReactNode } from 'react';
import { Button, ConfirmDialog, Panel, SectionHeader } from '@opensamguk/ui';
import MemberHeader from '@/components/gateway/MemberHeader';
import { useAuth } from '@/lib/auth-context';
import { changeNickname, changePassword, deleteAccount } from '@/lib/client';
import { ACCOUNT_DELETED_NOTICE, AUTH_LABELS, JOIN_RULES } from '@/lib/constants';
import PortraitPanel from './PortraitPanel';
import RepresentativeSection from './RepresentativeSection';

/** 탈퇴 확인 문장 — 브라우저 confirm 에서 옮긴 같은 문장(설계서 §2.5 A30). */
const DELETE_CONFIRM = '계정을 삭제하면 되돌릴 수 없습니다. 현재 비밀번호로 탈퇴하시겠습니까?';

type Result = { ok: boolean; text: string } | null;

function ResultLine({ result }: { readonly result: Result }) {
    if (!result) return null;
    return <p className={`gw31-account__result${result.ok ? '' : ' is-bad'}`} role={result.ok ? 'status' : 'alert'}>{result.text}</p>;
}

function Section({ id, title, className = '', frame, children }: {
    readonly id: string;
    readonly title: string;
    readonly className?: string;
    readonly frame?: 'rust';
    readonly children: ReactNode;
}) {
    return (
        <Panel className={`gw31-account__panel ${className}`.trim()} frame={frame} aria-labelledby={id}>
            <SectionHeader as="h2" id={id} title={title} tone={frame === 'rust' ? 'rust' : 'bronze'} />
            <div className="gw31-account__body">{children}</div>
        </Panel>
    );
}

/** 처리 중이면 사유와 함께 잠근 단추, 아니면 제출 단추. */
function Submit({ busy, block, variant = 'primary', children }: {
    readonly busy: boolean;
    readonly block?: string | null;
    readonly variant?: 'primary' | 'danger';
    readonly children: ReactNode;
}) {
    const reason = busy ? '처리 중입니다' : block;
    return reason
        ? <Button variant={variant} disabled reason={reason}>{children}</Button>
        : <Button variant={variant} type="submit">{children}</Button>;
}

function NicknamePanel() {
    const { user, refresh } = useAuth();
    const [nickname, setNickname] = useState(user?.nickname ?? '');
    const [result, setResult] = useState<Result>(null);
    const [busy, setBusy] = useState(false);
    const submit = async (event: FormEvent) => {
        event.preventDefault();
        setBusy(true);
        setResult(null);
        try {
            const updated = await changeNickname(nickname.trim());
            setNickname(updated.nickname ?? '');
            await refresh(updated);
            setResult({ ok: true, text: '별명을 바꿨습니다.' });
        } catch (e) {
            setResult({ ok: false, text: e instanceof Error ? e.message : '별명을 바꾸지 못했습니다.' });
        } finally {
            setBusy(false);
        }
    };
    return (
        <Section id="account-nickname" title="별명 바꾸기" className="gw31-account__nick">
            <form className="gw31-form" onSubmit={submit}>
                <div className="gw31-field">
                    <label htmlFor="account-nickname-input">{AUTH_LABELS.nickname}</label>
                    <input
                        id="account-nickname-input"
                        autoComplete="nickname"
                        value={nickname}
                        onChange={(e) => setNickname(e.target.value)}
                        minLength={JOIN_RULES.nicknameMin}
                        maxLength={JOIN_RULES.nicknameMax}
                        required
                        aria-describedby="account-nickname-help"
                    />
                    <span className="gw31-field__help" id="account-nickname-help">{JOIN_RULES.nicknameHelp}</span>
                </div>
                <div className="gw31-account__actions">
                    <Submit busy={busy}>별명 바꾸기</Submit>
                    <ResultLine result={result} />
                </div>
            </form>
        </Section>
    );
}

function PasswordPanel() {
    const [current, setCurrent] = useState('');
    const [next, setNext] = useState('');
    const [confirm, setConfirm] = useState('');
    const [result, setResult] = useState<Result>(null);
    const [busy, setBusy] = useState(false);
    const mismatch = confirm.length > 0 && confirm !== next;
    // 가입과 같은 검사(화면에서만, A10). 서버가 최종 판정한다.
    const block = !current ? '현재 비밀번호를 쓰세요'
        : !next ? '새 비밀번호를 쓰세요'
            : next.length < JOIN_RULES.passwordMin ? AUTH_LABELS.passwordTooShort(JOIN_RULES.passwordMin)
                : next !== confirm ? AUTH_LABELS.passwordMismatch : null;
    const submit = async (event: FormEvent) => {
        event.preventDefault();
        if (block) return;
        setBusy(true);
        setResult(null);
        try {
            await changePassword(current, next);
            setCurrent('');
            setNext('');
            setConfirm('');
            setResult({ ok: true, text: '비밀번호를 바꿨습니다.' });
        } catch (e) {
            setResult({ ok: false, text: e instanceof Error ? e.message : '비밀번호를 바꾸지 못했습니다.' });
        } finally {
            setBusy(false);
        }
    };
    return (
        <Section id="account-password" title="비밀번호 바꾸기" className="gw31-account__pw">
            <form className="gw31-form" onSubmit={submit}>
                <div className="gw31-field">
                    <label htmlFor="account-password-current">현재 비밀번호</label>
                    <input id="account-password-current" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
                </div>
                <div className="gw31-account__pair">
                    <div className="gw31-field">
                        <label htmlFor="account-password-new">새 비밀번호</label>
                        <input id="account-password-new" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} aria-describedby="account-password-help" />
                        <span className="gw31-field__help" id="account-password-help">{JOIN_RULES.passwordHelp}</span>
                    </div>
                    <div className="gw31-field">
                        <label htmlFor="account-password-confirm">새 {AUTH_LABELS.passwordConfirm}</label>
                        <input
                            id="account-password-confirm"
                            type="password"
                            autoComplete="new-password"
                            value={confirm}
                            onChange={(e) => setConfirm(e.target.value)}
                            aria-invalid={mismatch || undefined}
                            aria-describedby={mismatch ? 'account-password-mismatch' : undefined}
                            className={mismatch ? 'is-bad' : undefined}
                        />
                        {mismatch && <span className="gw31-field__error" id="account-password-mismatch">{AUTH_LABELS.passwordMismatch}</span>}
                    </div>
                </div>
                <div className="gw31-account__actions">
                    <Submit busy={busy} block={block}>바꾸기</Submit>
                    <ResultLine result={result} />
                </div>
            </form>
        </Section>
    );
}

function QuitPanel() {
    const router = useRouter();
    const [password, setPassword] = useState('');
    const [confirming, setConfirming] = useState(false);
    const [result, setResult] = useState<Result>(null);
    const [busy, setBusy] = useState(false);
    const quit = async () => {
        setBusy(true);
        setResult(null);
        try {
            // 탈퇴 라우트가 인증 쿠키를 지운다(로그아웃과 같은 일). logout() 은 `/login` 으로 강제 이동해 알림 표지를 버린다.
            await deleteAccount(password);
            router.replace(`/login?notice=${ACCOUNT_DELETED_NOTICE}`);
        } catch (e) {
            setConfirming(false);
            setResult({ ok: false, text: e instanceof Error ? e.message : '계정을 지우지 못했습니다.' });
            setBusy(false);
        }
    };
    return (
        <Section id="account-quit" title="계정 탈퇴" className="gw31-account__quit" frame="rust">
            <form className="gw31-form" onSubmit={(e) => { e.preventDefault(); if (password && !busy) setConfirming(true); }}>
                <div className="gw31-field">
                    <label htmlFor="account-quit-password">현재 비밀번호</label>
                    <div className="gw31-field__row">
                        <input id="account-quit-password" type="password" autoComplete="current-password" placeholder="탈퇴하려면 입력" value={password} onChange={(e) => setPassword(e.target.value)} aria-describedby="account-quit-help" />
                        <Submit busy={busy} block={password ? null : '현재 비밀번호를 쓰세요'} variant="danger">계정 삭제</Submit>
                    </div>
                    <span className="gw31-field__help" id="account-quit-help">계정을 지우면 되돌릴 수 없습니다.</span>
                </div>
            </form>
            <ResultLine result={result} />
            <ConfirmDialog
                open={confirming}
                title="계정 탈퇴"
                message={DELETE_CONFIRM}
                confirmLabel="계정 삭제"
                danger
                busy={busy}
                onCancel={() => setConfirming(false)}
                onConfirm={() => void quit()}
            />
        </Section>
    );
}

/**
 * P-G05 계정(설계서 §2.5, 보드 V31K5Account · MAccount). 게이트웨이 회원 셸. 화면 순서는 모바일 쌓기 순서
 * (초상 → 별명 → 비밀번호 → 대표 장수 → 탈퇴)이고, 데스크톱은 왼쪽 열 400 에 나머지를, 오른쪽에 초상을 둔다.
 */
export default function AccountScreen() {
    return (
        <div className="gw31-page">
            <MemberHeader current="account" />
            <main className="gw31-account">
                <div className="gw31-account__head">
                    <h1 className="gw31-account__title os-serif">계정 설정</h1>
                    <Link className="os-button os-button--ghost os-button--sm" href="/lobby">로비로</Link>
                </div>
                <div className="gw31-account__cols">
                    <PortraitPanel />
                    <div className="gw31-account__side">
                        <NicknamePanel />
                        <PasswordPanel />
                        <RepresentativeSection />
                        <QuitPanel />
                    </div>
                </div>
            </main>
        </div>
    );
}
