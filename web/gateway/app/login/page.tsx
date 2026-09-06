'use client';

import { Suspense, useState, type FormEvent } from 'react';
import { Brand, Button, Chip } from '@opensamguk/ui';
import { useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { login } from '@/lib/client';
import { AUTH_LABELS, FOOTER_LINKS } from '@/lib/constants';
import ServerBoard from '@/components/ServerBoard';
import NoticeBoard from '@/components/NoticeBoard';

// 01 로그인(ADR-LITE-049): 좌 히어로(워드마크 280~340 · 공개 알파 · 문구 · 회원 가입) / 우 로그인 카드 /
// 아래 서버 현황(서버 탭 · 지도 · 세력 현황 · 최근 사건) + 공지 / 푸터. 라벨·오류 문자열은 AUTH_LABELS 그대로.
function LoginForm() {
    const router = useRouter();
    const params = useSearchParams();
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [showPassword, setShowPassword] = useState(false);
    const [error, setError] = useState('');
    const [submitting, setSubmitting] = useState(false);

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
        <div className="auth-card os-panel os-panel--static os-frame--bronze">
            <div className="auth-card__head">
                <h1 className="auth-title os-serif">{AUTH_LABELS.loginTitle}</h1>
                <p className="auth-switch">
                    <Link href="/join">{AUTH_LABELS.toJoin}</Link>
                </p>
            </div>
            <form className="auth-form" onSubmit={handleSubmit} noValidate>
                <div className="field">
                    <label htmlFor="username">{AUTH_LABELS.username}</label>
                    <input
                        id="username"
                        name="username"
                        type="text"
                        autoComplete="username"
                        placeholder={AUTH_LABELS.username}
                        required
                        disabled={submitting}
                        value={username}
                        onChange={(e) => setUsername(e.target.value)}
                    />
                </div>
                <div className="field">
                    <label htmlFor="password">{AUTH_LABELS.password}</label>
                    <div className="field__row">
                        <input
                            id="password"
                            name="password"
                            type={showPassword ? 'text' : 'password'}
                            autoComplete="current-password"
                            placeholder={AUTH_LABELS.password}
                            required
                            disabled={submitting}
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                        />
                        <button
                            type="button"
                            className="os-button os-button--ghost os-button--sm"
                            aria-pressed={showPassword}
                            onClick={() => setShowPassword((v) => !v)}
                        >
                            {showPassword ? '숨기기' : '표시'}
                        </button>
                    </div>
                </div>
                {error && <div className="auth-error" role="alert">{error}</div>}
                {submitting ? (
                    <Button type="submit" variant="primary" block disabled reason="로그인 중입니다">{AUTH_LABELS.loginBtn}</Button>
                ) : (
                    <Button type="submit" variant="primary" block className="btn-primary btn-block">{AUTH_LABELS.loginBtn}</Button>
                )}
            </form>
        </div>
    );
}

export default function LoginPage() {
    return (
        <div className="gw-shell login-page">
            <header className="os-topbar gw-navbar" aria-label="상단바">
                <div className="os-topbar__left">
                    <Brand size="large" className="os-topbar__brand" />
                </div>
                <div className="os-topbar__right">
                    <Link href="/join" className="os-button os-button--ghost os-button--sm">{AUTH_LABELS.registerBtn}</Link>
                </div>
            </header>
            <main className="login-main fade-in">
                <section className="login-hero" aria-label="소개">
                    <Brand size="large" className="login-hero__wordmark" />
                    <div className="login-hero__row">
                        <Chip tone="bronze">공개 알파</Chip>
                    </div>
                    <h2 className="login-hero__title os-serif">한 명의 장수에서 천하까지.</h2>
                    <p className="login-hero__lead">사건을 읽고, 명령을 계획하고, 동시에 봉인된 전투를 지켜본다. 살아 있는 편년체 위에서 벌어지는 비동기 전략.</p>
                </section>
                <section className="login-panel" aria-label="로그인">
                    <Suspense fallback={<div className="spinner" />}>
                        <LoginForm />
                    </Suspense>
                </section>
                <section className="login-status" aria-label="서버 현황과 공지">
                    <ServerBoard />
                    <NoticeBoard />
                </section>
            </main>
            <footer className="gw-footer">
                {FOOTER_LINKS.map((label) => (
                    <span key={label}>{label}</span>
                ))}
            </footer>
        </div>
    );
}
