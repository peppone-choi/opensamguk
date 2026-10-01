'use client';

import { useState } from 'react';
import { Button, Modal } from '@opensamguk/ui';

// gateway-api AdminController DTO 미러(app/gateway-api …/dto/AdminDto.kt).
//   GET  /admin/users                       → 목록 + 가입/로그인 허용 플래그
//   POST /admin/system/{allow_join|allow_login} {value}
//   POST /admin/users/scrub/{deleted|old}
//   POST /admin/users/{id}/{delete|reset_pw|block|unblock} {param?}
//   POST /admin/ban-email {email}
export interface AdminUserDto {
    readonly id: number;
    readonly username: string;
    readonly email: string | null;
    readonly authType: string | null;
    readonly grade: number | null;
    readonly gradeLabel: string;
    readonly blockUntil: string | null;
    readonly nickname: string | null;
    readonly icon: string | null;
    readonly joinDate: string | null;
    readonly lastLoginAt: string | null;
    readonly deleteAfter: string | null;
    readonly generalNamesByServer: Record<string, string>;
}
export interface AdminUserListResponse {
    readonly users: AdminUserDto[];
    readonly servers: string[];
    readonly allowJoin: boolean;
    readonly allowLogin: boolean;
}
export interface UserCommandResult {
    readonly result: boolean;
    readonly reason?: string | null;
    readonly detail?: string | null;
}

export type MemberState = '차단' | '운영자' | '일반';

/**
 * 상태(설계서 §3.4 U21) — 역할이 USER/ADMIN 뿐이라 삼모 「특별 · 부운영자」 등급은 뺐다. 차단은 만료일이 지나지 않은 것만.
 * 서버가 등급을 못 채우면(역할 합성) gradeLabel 을 본다.
 */
export function memberState(user: AdminUserDto, now = Date.now()): MemberState {
    const blockedUntil = user.blockUntil ? Date.parse(user.blockUntil) : NaN;
    if (user.grade === 0 || (Number.isFinite(blockedUntil) && blockedUntil > now)) return '차단';
    if (user.grade === 6 || user.gradeLabel === '운영자') return '운영자';
    return '일반';
}

/** 날짜는 연도까지 다 보인다(옛 화면은 앞 2자리를 잘랐다, U22). */
export function fullDate(value: string | null): string {
    if (!value) return '-';
    return value.slice(0, 10);
}

/** 차단 대화상자(U37) — 옛 `window.prompt` 대신 칸. 기본 7일, 0 이하는 영구(50년). */
export function BlockDialog({ username, busy, onCancel, onConfirm }: {
    readonly username: string;
    readonly busy: boolean;
    readonly onCancel: () => void;
    readonly onConfirm: (days: number) => void;
}) {
    const [raw, setRaw] = useState('7');
    const days = Number.parseInt(raw, 10);
    const valid = Number.isFinite(days);
    const span = !valid ? '' : days <= 0 ? '영구' : `${days}일`;
    return (
        <Modal ariaLabel={`${username} 차단`} className="admin31-dialog" overlayClassName="admin31-dialog-overlay" onClose={onCancel} closeOnBackdrop={!busy} closeOnEscape={!busy}>
            <div className="gw31-form admin31-dialog__body">
                <h2 className="admin31-dialog__title os-serif">{username} 차단</h2>
                <div className="gw31-field">
                    <label htmlFor="member-block-days">차단 일수</label>
                    <input id="member-block-days" type="number" inputMode="numeric" value={raw} onChange={(e) => setRaw(e.target.value)} aria-describedby="member-block-help" />
                    <span className="gw31-field__help" id="member-block-help">0 이하는 영구(50년)입니다.</span>
                </div>
                {valid && <p className="gw31-card__line">{username} 계정을 차단합니다({span}). 계속할까요?</p>}
                <div className="admin31-dialog__actions">
                    {busy ? <Button disabled reason="처리 중입니다">취소</Button> : <Button onClick={onCancel}>취소</Button>}
                    {busy || !valid
                        ? <Button variant="danger" disabled reason={busy ? '처리 중입니다' : '일수를 숫자로 쓰세요'}>차단</Button>
                        : <Button variant="danger" onClick={() => onConfirm(days)}>차단</Button>}
                </div>
            </div>
        </Modal>
    );
}

/** 임시 비밀번호 결과(U39) — 알림 줄에 남기지 않고 이 창에서만 보이고 복사한다. */
export function TempPasswordDialog({ username, detail, onClose }: {
    readonly username: string;
    readonly detail: string;
    readonly onClose: () => void;
}) {
    const [copied, setCopied] = useState<boolean | null>(null);
    const copy = async () => {
        try {
            await navigator.clipboard.writeText(detail);
            setCopied(true);
        } catch {
            setCopied(false);
        }
    };
    return (
        <Modal ariaLabel={`${username} 임시 비밀번호`} className="admin31-dialog" overlayClassName="admin31-dialog-overlay" onClose={onClose}>
            <div className="gw31-form admin31-dialog__body">
                <h2 className="admin31-dialog__title os-serif">{username} 임시 비밀번호</h2>
                <p className="gw31-card__line">이 창을 닫으면 다시 볼 수 없습니다. 본인에게 안전한 경로로 전하세요.</p>
                <output className="admin31-secret os-num">{detail}</output>
                {copied === true && <p className="admin31-result" role="status">복사했습니다.</p>}
                {copied === false && <p className="gw31-alert" role="alert">복사하지 못했습니다. 직접 골라 복사하세요.</p>}
                <div className="admin31-dialog__actions">
                    <Button onClick={() => void copy()}>복사</Button>
                    <Button variant="primary" onClick={onClose}>닫기</Button>
                </div>
            </div>
        </Modal>
    );
}
