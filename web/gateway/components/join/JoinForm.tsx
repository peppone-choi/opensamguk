'use client';

import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Button, Chip } from '@opensamguk/ui';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { register } from '@/lib/client';
import { AUTH_LABELS, JOIN_RULES } from '@/lib/constants';

type FieldKey = 'username' | 'password' | 'passwordConfirm' | 'nickname' | 'email';
type FieldError = { readonly field: FieldKey | 'server'; readonly message: string };

/** 제출 전 검사(설계서 J11 — 지금 순서 그대로, 문구는 쉬운 말). 서버(AuthDto)가 최종 판정이다. */
export function checkJoin(input: { username: string; password: string; passwordConfirm: string; nickname: string }): FieldError | null {
    const username = input.username.trim();
    const nickname = input.nickname.trim();
    if (!username) return { field: 'username', message: AUTH_LABELS.emptyUsername };
    if (username.length < JOIN_RULES.usernameMin) return { field: 'username', message: AUTH_LABELS.usernameTooShort(JOIN_RULES.usernameMin) };
    if (username.length > JOIN_RULES.usernameMax) return { field: 'username', message: AUTH_LABELS.usernameTooLong(JOIN_RULES.usernameMax) };
    if (!input.password) return { field: 'password', message: AUTH_LABELS.emptyPassword };
    if (input.password.length < JOIN_RULES.passwordMin) return { field: 'password', message: AUTH_LABELS.passwordTooShort(JOIN_RULES.passwordMin) };
    if (input.password !== input.passwordConfirm) return { field: 'passwordConfirm', message: AUTH_LABELS.passwordMismatch };
    if (!nickname) return { field: 'nickname', message: AUTH_LABELS.emptyNickname };
    if (nickname.length < JOIN_RULES.nicknameMin) return { field: 'nickname', message: AUTH_LABELS.nicknameTooShort(JOIN_RULES.nicknameMin) };
    if (nickname.length > JOIN_RULES.nicknameMax) return { field: 'nickname', message: AUTH_LABELS.nicknameTooLong(JOIN_RULES.nicknameMax) };
    return null;
}

/**
 * 가입 패널(설계서 P-G03 J2–J15). 입력칸 아래에 그 칸의 오류(role=alert), 서버가 거절한 문장은 받은 그대로 제출 단추 위에.
 * 성공하면 자동 로그인 → 로비. 장수는 서버마다 따로 만든다(이 화면에 없다).
 */
export default function JoinForm() {
    const router = useRouter();
    const [values, setValues] = useState({ username: '', password: '', passwordConfirm: '', nickname: '', email: '' });
    const [showPassword, setShowPassword] = useState(false);
    const [error, setError] = useState<FieldError | null>(null);
    const [submitting, setSubmitting] = useState(false);
    // 하이드레이션 전 클릭의 네이티브 제출을 막는다(로그인과 같은 규약).
    const [hydrated, setHydrated] = useState(false);
    useEffect(() => {
        // 하이드레이션 전에 SSR 입력에 타이핑된 값을 controlled 상태로 받아들인다(로그인과 같은 규약).
        const dom = (name: string) => (document.querySelector<HTMLInputElement>(`input[name="${name}"]`)?.value ?? '');
        setValues((v) => ({
            username: v.username || dom('username'),
            password: v.password || dom('password'),
            passwordConfirm: v.passwordConfirm || dom('passwordConfirm'),
            nickname: v.nickname || dom('nickname'),
            email: v.email || dom('email'),
        }));
        setHydrated(true);
    }, []);

    async function handleSubmit(e: FormEvent<HTMLFormElement>) {
        e.preventDefault();
        if (submitting) return;
        const problem = checkJoin(values);
        setError(problem);
        if (problem) {
            document.getElementById(`join-${problem.field}`)?.focus();
            return;
        }
        setSubmitting(true);
        try {
            // 가입은 쿠키로 곧장 로그인된다 → 로비로.
            await register({
                username: values.username.trim(),
                password: values.password,
                email: values.email.trim() || undefined,
                nickname: values.nickname.trim(),
            });
            router.push('/lobby');
            router.refresh();
        } catch (err) {
            // 서버 거절(이미 쓰는 계정명 · 별명 · 가입 금지 등)은 받은 문장 그대로.
            setError({ field: 'server', message: err instanceof Error ? err.message : '가입하지 못했습니다.' });
            setSubmitting(false);
        }
    }

    const field = (key: FieldKey, label: ReactNode, input: ReactNode, help?: string) => {
        const bad = error?.field === key;
        return (
            <div className="gw31-field">
                <label htmlFor={`join-${key}`}>{label}</label>
                {input}
                {help && !bad && <span className="gw31-field__help" id={`join-${key}-help`}>{help}</span>}
                {bad && <span className="gw31-field__error" role="alert" id={`join-${key}-error`}>{error.message}</span>}
            </div>
        );
    };
    const inputProps = (key: FieldKey, extra: { type?: string; autoComplete?: string } = {}) => {
        const bad = error?.field === key;
        return {
            id: `join-${key}`,
            name: key,
            type: extra.type ?? 'text',
            autoComplete: extra.autoComplete,
            value: values[key],
            readOnly: submitting,
            'aria-disabled': submitting || undefined,
            'aria-invalid': bad || undefined,
            'aria-describedby': bad ? `join-${key}-error` : `join-${key}-help`,
            className: bad ? 'is-bad' : undefined,
            onChange: (e: React.ChangeEvent<HTMLInputElement>) => setValues((v) => ({ ...v, [key]: e.target.value })),
        };
    };
    const passwordType = showPassword ? 'text' : 'password';

    return (
        <form className="gw31-form" onSubmit={handleSubmit} noValidate>
            {field('username', AUTH_LABELS.username, <input {...inputProps('username', { autoComplete: 'username' })} required />, JOIN_RULES.usernameHelp)}
            {field('password', AUTH_LABELS.password, (
                <div className="gw31-field__row">
                    <input {...inputProps('password', { type: passwordType, autoComplete: 'new-password' })} required />
                    <button
                        type="button"
                        className="os-button os-button--ghost"
                        aria-pressed={showPassword}
                        aria-controls="join-password join-passwordConfirm"
                        onClick={() => setShowPassword((v) => !v)}
                    >
                        {showPassword ? '숨기기' : '표시'}
                    </button>
                </div>
            ), JOIN_RULES.passwordHelp)}
            {field('passwordConfirm', AUTH_LABELS.passwordConfirm, <input {...inputProps('passwordConfirm', { type: passwordType, autoComplete: 'new-password' })} required />)}
            {field('nickname', AUTH_LABELS.nickname, <input {...inputProps('nickname', { autoComplete: 'nickname' })} required />, JOIN_RULES.nicknameHelp)}
            {field('email', <span className="gw31-field__label">{AUTH_LABELS.email}<Chip>선택</Chip></span>, <input {...inputProps('email', { type: 'email', autoComplete: 'email' })} />)}
            {error?.field === 'server' && <div className="gw31-alert" role="alert">{error.message}</div>}
            {submitting || !hydrated ? (
                <Button type="submit" variant="primary" block disabled reason={submitting ? '가입하는 중입니다' : '화면을 준비하는 중입니다'}>{AUTH_LABELS.registerBtn}</Button>
            ) : (
                <Button type="submit" variant="primary" block>{AUTH_LABELS.registerBtn}</Button>
            )}
            <Link href="/login" className="gw31-link">{AUTH_LABELS.toLogin}</Link>
        </form>
    );
}
