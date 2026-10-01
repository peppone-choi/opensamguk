'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Chip, ConfirmDialog, Modal, Panel, SectionHeader, Seg } from '@opensamguk/ui';
import StateLine from '@/components/status/StateLine';
import {
    BlockDialog,
    TempPasswordDialog,
    fullDate,
    memberState,
    type AdminUserDto,
    type AdminUserListResponse,
    type MemberState,
    type UserCommandResult,
} from './memberParts';

async function getJson<T>(path: string): Promise<T> {
    const res = await fetch(`/api/proxy/${path}`, { cache: 'no-store' });
    if (!res.ok) throw new Error(`요청 실패 (${res.status})`);
    return (await res.json()) as T;
}
/** 비-2xx 여도 본문(reason 등)을 그대로 돌려준다. */
async function postJson<T>(path: string, body?: unknown): Promise<T> {
    const res = await fetch(`/api/proxy/${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    return (await res.json()) as T;
}

type Confirm = { title: string; message: string; label: string; danger?: boolean; run: () => Promise<void> };
type Action = 'reset_pw' | 'block' | 'unblock' | 'ban_email' | 'delete';
const STATE_FILTERS = ['전체', '일반', '운영자', '차단'] as const;
type StateFilter = (typeof STATE_FILTERS)[number];
const BUSY = '처리 중입니다';

function StateChip({ state, user }: { readonly state: MemberState; readonly user: AdminUserDto }) {
    if (state === '차단') return <Chip tone="rust">차단{user.blockUntil ? ` · ${fullDate(user.blockUntil)}까지` : ''}</Chip>;
    if (state === '운영자') return <Chip tone="bronze">운영자</Chip>;
    return <Chip>일반</Chip>;
}

/**
 * 회원(설계서 §3.4 U1–U40, 보드 V31K5AdminMembers · MAdminMembers). 기능은 옛 화면(legacy admin_member)대로 옮기고
 * 문구 · 등급은 바꿨다: 「특별 · 부운영자」 등급과 「별도 권한」은 뺐다(역할이 USER/ADMIN 뿐이라 효과가 없다).
 * 모든 조치는 쉬운 말 확인을 거치고, 임시 비밀번호는 결과 창에서만 보인다.
 */
export default function MemberControl() {
    const [data, setData] = useState<AdminUserListResponse | null>(null);
    const [error, setError] = useState(false);
    const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null);
    const [busy, setBusy] = useState(false);
    const [confirm, setConfirm] = useState<Confirm | null>(null);
    const [acting, setActing] = useState<AdminUserDto | null>(null);
    const [blocking, setBlocking] = useState<AdminUserDto | null>(null);
    const [tempPassword, setTempPassword] = useState<{ username: string; detail: string } | null>(null);
    const [query, setQuery] = useState('');
    const [filter, setFilter] = useState<StateFilter>('전체');

    const reload = useCallback(async () => {
        setError(false);
        try {
            setData(await getJson<AdminUserListResponse>('admin/users'));
        } catch {
            setError(true);
        }
    }, []);
    useEffect(() => { void reload(); }, [reload]);

    const run = async (work: () => Promise<void>) => {
        setBusy(true);
        setNotice(null);
        try {
            await work();
        } finally {
            setBusy(false);
            setConfirm(null);
            setBlocking(null);
        }
    };

    const setFlag = (scope: 'allow_join' | 'allow_login', next: boolean) => {
        const name = scope === 'allow_join' ? '새 가입' : '로그인';
        const apply = () => run(async () => {
            try {
                const res = await postJson<{ allowJoin: boolean; allowLogin: boolean }>(`admin/system/${scope}`, { value: next });
                setData((prev) => (prev ? { ...prev, allowJoin: res.allowJoin, allowLogin: res.allowLogin } : prev));
                setNotice({ ok: true, text: `${name}을 ${next ? '받습니다' : '막았습니다'}.` });
            } catch {
                setNotice({ ok: false, text: '설정을 바꾸지 못했습니다.' });
            }
        });
        // 끌 때(막을 때)만 확인을 받는다(설계서 U1–U2).
        if (next) void apply();
        else setConfirm({ title: `${name} 막기`, message: `${name}을 막습니다. 다시 「받음」으로 바꾸기 전까지 막힙니다. 계속할까요?`, label: '막기', danger: true, run: apply });
    };

    const scrub = (scope: 'deleted' | 'old', label: string) => setConfirm({
        title: '계정 정리',
        message: `${label}을 진행합니다. 지운 계정은 되돌릴 수 없습니다. 계속할까요?`,
        label: '정리',
        danger: true,
        run: () => run(async () => {
            try {
                const res = await postJson<{ affected: number }>(`admin/users/scrub/${scope}`);
                setNotice({ ok: true, text: `${res.affected ?? 0}건을 정리했습니다.` });
                await reload();
            } catch {
                setNotice({ ok: false, text: '계정 정리에 실패했습니다.' });
            }
        }),
    });

    const command = (user: AdminUserDto, path: 'delete' | 'reset_pw' | 'block' | 'unblock', param?: number) => run(async () => {
        try {
            const res = await postJson<UserCommandResult>(`admin/users/${user.id}/${path}`, param !== undefined ? { param } : {});
            if (!res.result) {
                setNotice({ ok: false, text: res.reason ?? '처리하지 못했습니다.' });
                return;
            }
            if (path === 'reset_pw' && res.detail) setTempPassword({ username: user.username, detail: res.detail });
            else setNotice({ ok: true, text: `${user.username} — 처리했습니다.` });
            await reload();
        } catch {
            setNotice({ ok: false, text: '조치를 실행하지 못했습니다.' });
        }
    });

    const choose = (user: AdminUserDto, action: Action) => {
        setActing(null);
        if (action === 'block') { setBlocking(user); return; }
        if (action === 'reset_pw') {
            setConfirm({ title: '임시 비밀번호 발급', message: `${user.username} 계정에 임시 비밀번호를 발급합니다. 지금 비밀번호는 쓸 수 없게 됩니다. 계속할까요?`, label: '발급', run: () => command(user, 'reset_pw') });
        } else if (action === 'unblock') {
            setConfirm({ title: '차단 풀기', message: `${user.username} 계정의 차단을 풉니다. 계속할까요?`, label: '차단 풀기', run: () => command(user, 'unblock') });
        } else if (action === 'delete') {
            setConfirm({ title: '강제 탈퇴', message: `${user.username} 계정을 강제로 탈퇴시킵니다. 되돌릴 수 없습니다. 계속할까요?`, label: '강제 탈퇴', danger: true, run: () => command(user, 'delete') });
        } else if (action === 'ban_email' && user.email) {
            const email = user.email;
            setConfirm({
                title: '이메일 영구 차단',
                message: `${email} 이메일을 영구 차단합니다. 계속할까요?`,
                label: '영구 차단',
                danger: true,
                run: () => run(async () => {
                    try {
                        const res = await postJson<{ result: boolean; reason: string }>('admin/ban-email', { email });
                        setNotice(res.result ? { ok: true, text: `${email} — 영구 차단했습니다.` } : { ok: false, text: res.reason ?? '처리하지 못했습니다.' });
                    } catch {
                        setNotice({ ok: false, text: '영구 차단에 실패했습니다.' });
                    }
                }),
            });
        }
    };

    const users = useMemo(() => {
        const q = query.trim().toLowerCase();
        return (data?.users ?? []).filter((u) => (filter === '전체' || memberState(u) === filter)
            && (!q || u.username.toLowerCase().includes(q) || (u.nickname ?? '').toLowerCase().includes(q)));
    }, [data, query, filter]);

    if (error) return <StateLine kind="error" title="회원 목록을 불러오지 못했습니다" onRetry={() => void reload()} />;
    if (!data) return <StateLine kind="loading" title="회원 목록을 불러오는 중" />;

    const flag = (scope: 'allow_join' | 'allow_login', label: string, value: boolean) => (
        <div className="admin31-row">
            <span className="gw31-field__label">{label}</span>
            <Seg label={label} options={[{ value: 'on', label: '받음' }, { value: 'off', label: '막음' }]} value={value ? 'on' : 'off'} onChange={(v) => { if (!busy && (v === 'on') !== value) setFlag(scope, v === 'on'); }} />
        </div>
    );

    return (
        <div className="admin31-stack">
            <Panel className="admin31-panel" aria-label="가입 · 로그인 · 계정 정리">
                <SectionHeader as="h2" title="가입 · 로그인" />
                <div className="admin31-body">
                    <div className="admin31-row admin31-row--spread">
                        {flag('allow_join', '새 가입 받기', data.allowJoin)}
                        {flag('allow_login', '로그인 받기', data.allowLogin)}
                    </div>
                    <div className="admin31-row">
                        {busy ? <Button variant="danger" disabled reason={BUSY}>탈퇴 계정 정리(1개월+)</Button> : <Button variant="danger" onClick={() => scrub('deleted', '탈퇴 계정 정리(1개월+)')}>탈퇴 계정 정리(1개월+)</Button>}
                        {busy ? <Button variant="danger" disabled reason={BUSY}>오래된 계정 정리(6개월+)</Button> : <Button variant="danger" onClick={() => scrub('old', '오래된 계정 정리(6개월+)')}>오래된 계정 정리(6개월+)</Button>}
                    </div>
                    {notice && <p className={notice.ok ? 'admin31-result' : 'gw31-alert'} role={notice.ok ? 'status' : 'alert'}>{notice.text}</p>}
                </div>
            </Panel>
            <Panel className="admin31-panel" aria-label="회원 목록">
                <SectionHeader as="h2" title="회원 목록" sub={`${users.length} / ${data.users.length}명`} />
                <div className="admin31-body">
                    <div className="admin31-row">
                        <input type="search" className="os-input admin31-search" aria-label="계정명 · 별명으로 찾기" placeholder="계정명 · 별명으로 찾기" value={query} onChange={(e) => setQuery(e.target.value)} />
                        <Seg label="상태 거르기" options={STATE_FILTERS.map((f) => ({ value: f, label: f }))} value={filter} onChange={setFilter} scroll />
                    </div>
                    {users.length === 0 ? (
                        <StateLine kind="empty" title={data.users.length === 0 ? '회원이 없습니다.' : '조건에 맞는 회원이 없습니다.'} />
                    ) : (
                        <table className="admin31-table">
                            <thead>
                                <tr>
                                    <th>번호</th><th>계정명</th><th>이메일</th><th>상태</th><th>별명</th><th>초상</th><th>서버별 장수</th>
                                    <th>가입일</th><th>최근 로그인</th><th>탈퇴 예정</th><th><span className="sr-only">조치</span></th>
                                </tr>
                            </thead>
                            <tbody>
                                {users.map((u) => {
                                    const generals = Object.entries(u.generalNamesByServer ?? {}).filter(([, name]) => name);
                                    return (
                                        <tr key={u.id}>
                                            <td data-label="번호" className="os-num">{u.id}</td>
                                            <td data-label="계정명"><b>{u.username}</b></td>
                                            <td data-label="이메일">{u.email ?? '-'}{u.authType && <small className="admin31-code">{u.authType}</small>}</td>
                                            <td data-label="상태"><StateChip state={memberState(u)} user={u} /></td>
                                            <td data-label="별명">{u.nickname ?? '-'}</td>
                                            <td data-label="초상">{u.icon ? <img className="admin31-face" src={u.icon} width={32} height={32} alt="" /> : '-'}</td>
                                            <td data-label="서버별 장수">
                                                {generals.length > 0 ? generals.map(([srv, name]) => <span key={srv} className="admin31-code">{srv} · {name}</span>) : <Chip tone="info">서버 대기</Chip>}
                                            </td>
                                            <td data-label="가입일" className="os-num">{fullDate(u.joinDate)}</td>
                                            <td data-label="최근 로그인" className="os-num">{fullDate(u.lastLoginAt)}</td>
                                            <td data-label="탈퇴 예정" className="os-num">{fullDate(u.deleteAfter)}</td>
                                            <td>
                                                {busy ? <Button size="sm" disabled reason={BUSY}>조치</Button> : <Button size="sm" onClick={() => setActing(u)}>조치</Button>}
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    )}
                    <p className="gw31-card__line gw31-card__line--muted">「특별 · 부운영자」 등급과 「별도 권한」은 뺐습니다(역할이 일반 · 운영자뿐이라 효과가 없습니다). 서버별 장수는 원천이 비어 있습니다(K5-14 서버 대기).</p>
                </div>
            </Panel>
            {acting && <ActionSheet user={acting} onClose={() => setActing(null)} onChoose={(a) => choose(acting, a)} />}
            {blocking && <BlockDialog username={blocking.username} busy={busy} onCancel={() => setBlocking(null)} onConfirm={(days) => void command(blocking, 'block', days)} />}
            {tempPassword && <TempPasswordDialog username={tempPassword.username} detail={tempPassword.detail} onClose={() => setTempPassword(null)} />}
            <ConfirmDialog
                open={confirm !== null}
                title={confirm?.title ?? ''}
                message={confirm?.message ?? ''}
                confirmLabel={confirm?.label}
                danger={confirm?.danger}
                busy={busy}
                onCancel={() => setConfirm(null)}
                onConfirm={() => void confirm?.run()}
            />
        </div>
    );
}

/** 조치 목록(보드: 데스크톱은 떠 있는 패널, 모바일은 하단 시트). 이메일이 없으면 영구 차단은 사유와 함께 잠긴다. */
function ActionSheet({ user, onClose, onChoose }: {
    readonly user: AdminUserDto;
    readonly onClose: () => void;
    readonly onChoose: (action: Action) => void;
}) {
    const state = memberState(user);
    const row = (action: Action, name: string, sub: string, block?: string) => (block
        ? <Button key={action} className="os-opt admin31-action" disabled reason={block}><span className="os-opt__text"><span className="os-opt__name">{name}</span><span className="os-opt__sub">{block}</span></span></Button>
        : <button key={action} type="button" className="os-opt admin31-action" onClick={() => onChoose(action)}><span className="os-opt__text"><span className="os-opt__name">{name}</span>{sub && <span className="os-opt__sub">{sub}</span>}</span></button>);
    return (
        <Modal ariaLabel={`${user.username} 조치`} className="admin31-dialog" overlayClassName="admin31-dialog-overlay" onClose={onClose}>
            <div className="admin31-dialog__body">
                <h2 className="admin31-dialog__title os-serif">{user.username} 조치</h2>
                <div className="admin31-actions" role="group" aria-label="조치">
                    {row('reset_pw', '임시 비밀번호 발급', '결과 창에서 복사한다')}
                    {state === '차단' ? row('unblock', '차단 풀기', '') : row('block', '차단', '일수를 정한다 — 0 이하는 영구')}
                    {row('ban_email', '이메일 영구 차단', '', user.email ? undefined : '이메일이 없는 계정입니다')}
                    {row('delete', '강제 탈퇴', '되돌릴 수 없다')}
                </div>
                <div className="admin31-dialog__actions"><Button onClick={onClose}>닫기</Button></div>
            </div>
        </Modal>
    );
}
