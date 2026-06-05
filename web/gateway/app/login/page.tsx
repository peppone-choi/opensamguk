'use client';

import { Suspense, useState, type FormEvent } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { login } from '@/lib/client';
import { AUTH_LABELS, BRAND, FOOTER_LINKS } from '@/lib/constants';
import ServerBoard from '@/components/ServerBoard';

// 레거시 index.php: 네비바(brand) + 로그인 카드 + 현황 맵 프리뷰 + 푸터 링크.
function LoginForm() {
    const router = useRouter();
    const params = useSearchParams();

    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
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
            router.push(params.get('next') || '/lobby');
            router.refresh();
        } catch (err) {
            setError(err instanceof Error ? err.message : AUTH_LABELS.loginFail);
            setSubmitting(false);
        }
    }

    return (
        <div className="auth-card">
            <h1 className="auth-title">{AUTH_LABELS.loginTitle}</h1>

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
                    <input
                        id="password"
                        name="password"
                        type="password"
                        autoComplete="current-password"
                        placeholder={AUTH_LABELS.password}
                        required
                        disabled={submitting}
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                    />
                </div>

                {error && <div className="auth-error">{error}</div>}

                <button type="submit" className="btn-primary btn-block" disabled={submitting}>
                    {AUTH_LABELS.loginBtn}
                </button>
            </form>

            <p className="auth-switch">
                <Link href="/join">{AUTH_LABELS.toJoin}</Link>
            </p>
        </div>
    );
}

export default function LoginPage() {
    return (
        <div className="gw-shell">
            <header className="gw-navbar">
                <span className="gw-brand">{BRAND}</span>
            </header>
            <main className="gw-center fade-in">
                <Suspense fallback={<div className="spinner" />}>
                    <LoginForm />
                </Suspense>
                <ServerBoard />
            </main>
            <footer className="gw-footer">
                {FOOTER_LINKS.map((label) => (
                    <span key={label}>{label}</span>
                ))}
            </footer>
        </div>
    );
}
