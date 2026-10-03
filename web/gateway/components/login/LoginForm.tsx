'use client';

import { useEffect, useState, type FormEvent } from 'react';
import { Button } from '@opensamguk/ui';
import { useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { login } from '@/lib/client';
import { ACCOUNT_DELETED_NOTICE, AUTH_LABELS } from '@/lib/constants';

/**
 * 로그인 패널(설계서 LG7–LG15). 빈 칸은 제출 전에 막고, 서버 거절은 받은 문장 그대로 보인다(role=alert).
 * 로그인 뒤 `?next=`(같은 사이트 경로만) 또는 로비로 간다.
 */
export default function LoginForm() {
    const router = useRouter();
    const params = useSearchParams();
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [showPassword, setShowPassword] = useState(false);
    const [error, setError] = useState('');
    const [submitting, setSubmitting] = useState(false);
    // 폼 제출 방식: 스크립트가 붙기 전에는 칸 묶음(fieldset)을 네이티브 disabled 로 잠가 클릭 · Enter 로 폼이 제출되지 않게 한다.
    // aria-disabled 는 스크립트가 붙은 뒤에만 막는다. 폼은 method="post" 라 혹시 제출돼도 값이 주소에 실리지 않는다.
    const [hydrated, setHydrated] = useState(false);
    useEffect(() => {
        // 하이드레이션 전에 SSR 입력에 타이핑된 값은 controlled 상태로 덮이며 사라진다(mailbox e2e 실측: 빈 폼 제출) — DOM 값을 상태로 받아들인다.
        const dom = (name: string) => (document.querySelector<HTMLInputElement>(`input[name="${name}"]`)?.value ?? '');
        setUsername((v) => v || dom('username'));
        setPassword((v) => v || dom('password'));
        setHydrated(true);
    }, []);

    async function handleSubmit(e: FormEvent<HTMLFormElement>) {
        e.preventDefault();
        if (submitting) return;
        setError('');
        if (!username.trim()) {
            setError(AUTH_LABELS.emptyUsername);
            return;
        }
        if (!password) {
            setError(AUTH_LABELS.emptyPassword);
            return;
        }
        setSubmitting(true);
        try {
            await login(username.trim(), password);
            const next = params.get('next');
            const safe = next && next.startsWith('/') && !next.startsWith('//') ? next : '/lobby';
            router.push(safe);
            router.refresh();
        } catch (err) {
            setError(err instanceof Error ? err.message : AUTH_LABELS.loginFail);
            setSubmitting(false);
        }
    }

    return (
        <form className="gw31-form" method="post" onSubmit={handleSubmit} noValidate aria-describedby={error ? 'login-error' : undefined}>
            <fieldset className="gw31-form__fields" disabled={!hydrated}>
            {/* 탈퇴하고 넘어온 경우(설계서 §2.5 A31) — 계정 화면에서는 성공 문구가 보일 틈이 없다. */}
            {params.get('notice') === ACCOUNT_DELETED_NOTICE && <p className="gw31-done" role="status">계정을 지웠습니다</p>}
            <div className="gw31-field">
                <label htmlFor="username">{AUTH_LABELS.username}</label>
                <input
                    id="username"
                    name="username"
                    type="text"
                    autoComplete="username"
                    required
                    readOnly={submitting}
                    aria-disabled={submitting || undefined}
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                />
            </div>
            <div className="gw31-field">
                <label htmlFor="password">{AUTH_LABELS.password}</label>
                <div className="gw31-field__row">
                    <input
                        id="password"
                        name="password"
                        type={showPassword ? 'text' : 'password'}
                        autoComplete="current-password"
                        required
                        readOnly={submitting}
                    aria-disabled={submitting || undefined}
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                    />
                    <button
                        type="button"
                        className="os-button os-button--ghost"
                        aria-pressed={showPassword}
                        aria-controls="password"
                        onClick={() => setShowPassword((v) => !v)}
                    >
                        {showPassword ? '숨기기' : '표시'}
                    </button>
                </div>
            </div>
            {error && <div className="gw31-alert" role="alert" id="login-error">{error}</div>}
            {submitting || !hydrated ? (
                <Button type="submit" variant="primary" block disabled reason={submitting ? '로그인 중입니다' : '화면을 준비하는 중입니다'}>{AUTH_LABELS.loginBtn}</Button>
            ) : (
                <Button type="submit" variant="primary" block>{AUTH_LABELS.loginBtn}</Button>
            )}
            </fieldset>
            <Link href="/join" className="gw31-link">{AUTH_LABELS.toJoin}</Link>
        </form>
    );
}
