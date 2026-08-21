'use client';

import { useState, type FormEvent } from 'react';
import { Brand } from '@opensamguk/ui';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { register } from '@/lib/client';
import { AUTH_LABELS, FOOTER_LINKS } from '@/lib/constants';

// 계정 회원가입 전용 — POST /auth/register 1:1 매핑 (F0 스펙 §4a).
// 장수 생성/캐릭터 생성은 별도 P7 game-web 페이지이므로 여기서 다루지 않는다.
const USERNAME_MIN = 3;
const USERNAME_MAX = 50;
const PASSWORD_MIN = 6;
const NICKNAME_MIN = 2;
const NICKNAME_MAX = 20;

export default function JoinPage() {
    const router = useRouter();

    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [passwordConfirm, setPasswordConfirm] = useState('');
    const [nickname, setNickname] = useState('');
    const [email, setEmail] = useState('');

    const [error, setError] = useState('');
    const [submitting, setSubmitting] = useState(false);

    async function handleSubmit(e: FormEvent<HTMLFormElement>) {
        e.preventDefault();
        if (submitting) return;
        setError('');

        // 클라이언트 검증 (verbatim 메시지는 @/lib/constants 라벨맵 사용; backend = grand truth).
        if (!username.trim()) {
            setError(AUTH_LABELS.emptyUsername);
            return;
        }
        if (username.length < USERNAME_MIN) {
            setError(AUTH_LABELS.usernameTooShort(USERNAME_MIN));
            return;
        }
        if (username.length > USERNAME_MAX) {
            setError(AUTH_LABELS.usernameTooLong(USERNAME_MAX));
            return;
        }
        if (!password) {
            setError(AUTH_LABELS.emptyPassword);
            return;
        }
        if (password.length < PASSWORD_MIN) {
            setError(AUTH_LABELS.passwordTooShort(PASSWORD_MIN));
            return;
        }
        if (password !== passwordConfirm) {
            setError(AUTH_LABELS.passwordMismatch);
            return;
        }
        // 별명은 게시판 등 공개 표시 이름이므로 필수다(AuthDto.kt @NotBlank / Size(2,20)).
        if (!nickname.trim()) {
            setError(AUTH_LABELS.emptyNickname);
            return;
        }
        if (nickname.trim().length < NICKNAME_MIN) {
            setError(AUTH_LABELS.usernameTooShort(NICKNAME_MIN));
            return;
        }

        setSubmitting(true);
        try {
            // register는 쿠키를 통해 자동 로그인된다 → 성공 시 곧장 로비로 이동.
            await register({
                username: username.trim(),
                password,
                email: email.trim() || undefined,
                nickname: nickname.trim(),
            });
            router.push('/lobby');
            router.refresh();
        } catch (err) {
            // 서버 중복 에러(아이디/이메일)는 throw된 메시지를 그대로 노출(레거시 verbatim 패러티).
            setError(err instanceof Error ? err.message : '회원가입에 실패했습니다.');
            setSubmitting(false);
        }
    }

    return (
        <div className="gw-shell">
            <header className="gw-navbar">
                <Brand size="large" />
            </header>

            <main className="gw-center fade-in">
                <div className="auth-card">
                    <h1 className="auth-title">{AUTH_LABELS.joinTitle}</h1>

                    <form className="auth-form" onSubmit={handleSubmit} noValidate>
                        <div className="field">
                            <label htmlFor="username">{AUTH_LABELS.username}</label>
                            <input
                                id="username"
                                name="username"
                                type="text"
                                autoComplete="username"
                                minLength={USERNAME_MIN}
                                maxLength={USERNAME_MAX}
                                required
                                disabled={submitting}
                                value={username}
                                onChange={(e) => setUsername(e.target.value)}
                            />
                            <span className="field-hint">{AUTH_LABELS.usernameRule}</span>
                        </div>

                        <div className="field">
                            <label htmlFor="password">{AUTH_LABELS.password}</label>
                            <input
                                id="password"
                                name="password"
                                type="password"
                                autoComplete="new-password"
                                minLength={PASSWORD_MIN}
                                required
                                disabled={submitting}
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                            />
                            <span className="field-hint">{AUTH_LABELS.passwordRule}</span>
                        </div>

                        <div className="field">
                            <label htmlFor="passwordConfirm">{AUTH_LABELS.passwordConfirm}</label>
                            <input
                                id="passwordConfirm"
                                name="passwordConfirm"
                                type="password"
                                autoComplete="new-password"
                                required
                                disabled={submitting}
                                value={passwordConfirm}
                                onChange={(e) => setPasswordConfirm(e.target.value)}
                            />
                        </div>

                        <div className="field">
                            <label htmlFor="nickname">{AUTH_LABELS.nickname}</label>
                            <input
                                id="nickname"
                                name="nickname"
                                type="text"
                                autoComplete="nickname"
                                minLength={NICKNAME_MIN}
                                maxLength={NICKNAME_MAX}
                                required
                                disabled={submitting}
                                value={nickname}
                                onChange={(e) => setNickname(e.target.value)}
                            />
                            <span className="field-hint">{AUTH_LABELS.nicknameRule}</span>
                        </div>

                        <div className="field">
                            <label htmlFor="email">{AUTH_LABELS.email}</label>
                            <input
                                id="email"
                                name="email"
                                type="email"
                                autoComplete="email"
                                disabled={submitting}
                                value={email}
                                onChange={(e) => setEmail(e.target.value)}
                            />
                        </div>

                        {error && <div className="auth-error">{error}</div>}

                        <button type="submit" className="btn-primary btn-block" disabled={submitting}>
                            {AUTH_LABELS.registerBtn}
                        </button>
                    </form>

                    <p className="auth-switch">
                        <Link href="/login">{AUTH_LABELS.toLogin}</Link>
                    </p>
                </div>
            </main>

            <footer className="gw-footer">
                {FOOTER_LINKS.map((label) => (
                    <span key={label}>{label}</span>
                ))}
            </footer>
        </div>
    );
}
