'use client';

import { useCallback, useEffect, useState } from 'react';
import ConfirmModal from '@/components/ConfirmModal';

// B2f 회원 관리 탭 — legacy `admin_member.ts` + `admin_userlist.php` 패러티.
// 루트DB(gateway 공유) 유저 관리. 게임 내 general 아님 → gateway-api AdminController 소비.
//   GET  /admin/users                  → 목록 + 서버 + 가입/로그인 허용 플래그 (B2a)
//   POST /admin/system/{allow_*}       → 가입/로그인 전역 토글 (B2b)
//   POST /admin/users/scrub/{deleted|old} → 계정 정리 (B2c). scrub_icon은 CDN 운용이라 N/A.
//   POST /admin/users/{id}/{action}    → 단일 명령(강제탈퇴/암호변경/차단/해제/별도권한) (B2d)
//   POST /admin/ban-email              → 이메일 영구차단 (B2e)
// 라벨/등급매핑/프롬프트 문자열은 legacy verbatim. 등급: 0차단/1일반/4특별/5부운영자/6운영자.

// ===== 백엔드 DTO 미러 (app/gateway-api .../dto/AdminDto.kt) =====
interface AdminUserDto {
    id: number;
    username: string;
    email: string | null;
    authType: string | null;
    grade: number | null;
    gradeLabel: string;
    blockUntil: string | null;
    nickname: string | null;
    icon: string | null;
    joinDate: string | null;
    lastLoginAt: string | null;
    deleteAfter: string | null;
    generalNamesByServer: Record<string, string>;
}
interface AdminUserListResponse {
    users: AdminUserDto[];
    servers: string[];
    allowJoin: boolean;
    allowLogin: boolean;
}
interface SystemFlagResponse {
    allowJoin: boolean;
    allowLogin: boolean;
}
interface ScrubResult {
    affected: number;
}
interface UserCommandResult {
    result: boolean;
    reason?: string | null;
    detail?: string | null;
}
interface BanEmailResult {
    result: boolean;
    reason: string;
}

// 동결된 역사 admin_member.ts convUserGrade 참고(ADR-LITE-042; 현재 제품 정본 아님). 매핑에 없는 등급은 숫자 문자열로 표기.
const USER_GRADE_MAP: Record<number, string> = {
    0: '차단',
    1: '일반',
    4: '특별',
    5: '부운영자',
    6: '운영자',
};
function convUserGrade(grade: number | null, fallbackLabel: string): string {
    // BE가 grade를 못 채우면(role 합성) gradeLabel을 그대로 사용한다.
    if (grade === null) return fallbackLabel;
    return grade in USER_GRADE_MAP ? USER_GRADE_MAP[grade] : grade.toString();
}

// legacy shortDate: 'YY-MM-DD …'에서 앞 2자리(세기) 제거 후 공백→줄바꿈. 빈 값은 '-'.
function shortDate(date: string | null): string {
    if (!date) return '-';
    return date.substring(2);
}

/** 인증 프록시 GET — JSON 파싱. 비-2xx면 throw. */
async function getJson<T>(path: string): Promise<T> {
    const res = await fetch(`/api/proxy/${path}`, { cache: 'no-store' });
    if (!res.ok) throw new Error(`요청 실패 (${res.status})`);
    return (await res.json()) as T;
}

/** 인증 프록시 POST(JSON body) — JSON 파싱. 비-2xx여도 본문(reason 등)을 그대로 반환. */
async function postJson<T>(path: string, body?: unknown): Promise<T> {
    const res = await fetch(`/api/proxy/${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    return (await res.json()) as T;
}

// 단일 명령 → AdminController 경로/바디 매핑. legacy j_set_userlist.php action 대응.
const USER_ACTION_PATH: Record<string, string> = {
    delete: 'delete',
    reset_pw: 'reset_pw',
    block: 'block',
    unblock: 'unblock',
    set_userlevel: 'set_userlevel',
};

/** Y/N 라디오 토글 — legacy radios_allow_join / radios_allow_login. 변경 시 BE 토글 후 동기화. */
function AllowToggle({
    label,
    name,
    value,
    disabled,
    onChange,
}: {
    label: string;
    name: string;
    value: boolean;
    disabled: boolean;
    onChange: (next: boolean) => void;
}) {
    return (
        <div className="member-allow">
            <span className="member-allow-label">{label}</span>
            <div className="member-radio-group" role="radiogroup" aria-label={label}>
                <label className={`member-radio${value ? ' active' : ''}`}>
                    <input
                        type="radio"
                        name={name}
                        checked={value}
                        disabled={disabled}
                        onChange={() => onChange(true)}
                    />
                    Y
                </label>
                <label className={`member-radio${!value ? ' active' : ''}`}>
                    <input
                        type="radio"
                        name={name}
                        checked={!value}
                        disabled={disabled}
                        onChange={() => onChange(false)}
                    />
                    N
                </label>
            </div>
        </div>
    );
}

/** "회원 관리" 탭 — 가입/로그인 토글 + 계정정리 + 회원 테이블 + 행당 명령. */
export default function MemberControl() {
    const [data, setData] = useState<AdminUserListResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [toggling, setToggling] = useState(false);
    const [notice, setNotice] = useState<string | null>(null);
    // 비가역 명령(강제탈퇴/차단/영구차단)은 ConfirmModal로 한 번 더 확인.
    const [confirm, setConfirm] = useState<{
        title: string;
        message: React.ReactNode;
        run: () => Promise<void>;
        danger?: boolean;
    } | null>(null);
    const [busy, setBusy] = useState(false);

    const reload = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const res = await getJson<AdminUserListResponse>('admin/users');
            setData(res);
        } catch {
            setError('회원 목록을 불러오지 못했습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        void reload();
    }, [reload]);

    // B2b 가입/로그인 전역 허용 토글.
    async function toggleSystem(scope: 'allow_join' | 'allow_login', next: boolean) {
        setToggling(true);
        setNotice(null);
        try {
            const res = await postJson<SystemFlagResponse>(`admin/system/${scope}`, { value: next });
            setData((prev) =>
                prev ? { ...prev, allowJoin: res.allowJoin, allowLogin: res.allowLogin } : prev,
            );
        } catch {
            setNotice('설정 변경에 실패했습니다.');
        } finally {
            setToggling(false);
        }
    }

    // B2c 계정 정리(탈퇴/미접속). legacy scrub_deleted / scrub_old_user. affected 건수 알림.
    function scrub(scope: 'deleted' | 'old', label: string) {
        setConfirm({
            title: '계정 정리',
            message: `${label}을 진행합니다. 계속할까요?`,
            run: async () => {
                try {
                    const res = await postJson<ScrubResult>(`admin/users/scrub/${scope}`);
                    setNotice(`${res.affected ?? 0}건이 처리되었습니다.`);
                    await reload();
                } catch {
                    setNotice('계정 정리에 실패했습니다.');
                }
            },
        });
    }

    // B2d 단일 명령. legacy changeUserStatus. block/set_userlevel은 prompt로 param 수집.
    function userCommand(user: AdminUserDto, action: keyof typeof USER_ACTION_PATH) {
        let param: number | undefined;

        if (action === 'set_userlevel') {
            const raw = window.prompt(
                '원하는 등급을 입력해주세요.(1:일반, 4:특별, 5:부운영자, 6:운영자)',
                '1',
            );
            if (raw === null) return;
            param = parseInt(raw, 10);
            if (Number.isNaN(param) || param < 1 || param > 6) {
                window.alert('올바르지 않습니다.');
                return;
            }
        }

        if (action === 'block') {
            const raw = window.prompt('블록 기간을 입력해주세요. <= 0은 반영구(50년)입니다.', '7');
            if (raw === null) return;
            param = parseInt(raw, 10);
            if (Number.isNaN(param)) param = 7;
        }

        // legacy 확인 문구: `{유저명}에 대해서 {action}{, param}을 진행합니다.`
        const paramText = param !== undefined && param ? `, ${param}` : '';
        const danger = action === 'delete' || action === 'block';
        setConfirm({
            title: '회원 명령',
            danger,
            message: `${user.username}에 대해서 ${action}${paramText}을 진행합니다.`,
            run: async () => {
                try {
                    const res = await postJson<UserCommandResult>(
                        `admin/users/${user.id}/${USER_ACTION_PATH[action]}`,
                        param !== undefined ? { param } : {},
                    );
                    if (!res.result) {
                        setNotice(res.reason ?? '실패했습니다.');
                        return;
                    }
                    // reset_pw는 임시 비밀번호 안내(detail)를 그대로 노출(legacy `:210`).
                    setNotice(res.detail ? `완료되었습니다: ${res.detail}` : '완료되었습니다.');
                    await reload();
                } catch {
                    setNotice('명령 실행에 실패했습니다.');
                }
            },
        });
    }

    // B2e 이메일 영구차단. legacy banEmailAddress.
    function banEmail(user: AdminUserDto) {
        const email = user.email;
        if (!email) {
            setNotice('이메일이 없는 유저입니다.');
            return;
        }
        setConfirm({
            title: '이메일 영구차단',
            danger: true,
            message: `${email}에 대해서 영구차단을 진행합니다. 계속할까요?`,
            run: async () => {
                try {
                    const res = await postJson<BanEmailResult>('admin/ban-email', { email });
                    setNotice(res.result ? '완료되었습니다.' : (res.reason ?? '실패했습니다.'));
                } catch {
                    setNotice('영구차단에 실패했습니다.');
                }
            },
        });
    }

    if (loading) {
        return (
            <div className="center-inline">
                <div className="spinner" />
            </div>
        );
    }
    if (error || !data) {
        return <p className="deploy-result fail">{error ?? '데이터가 없습니다.'}</p>;
    }

    return (
        <div className="member-control">
            {/* 상단 컨트롤: 가입/로그인 토글 + 계정 정리 버튼군 (legacy card-body). */}
            <div className="member-toolbar">
                <AllowToggle
                    label="가입 허용"
                    name="allow_join"
                    value={data.allowJoin}
                    disabled={toggling}
                    onChange={(next) => toggleSystem('allow_join', next)}
                />
                <AllowToggle
                    label="로그인 허용"
                    name="allow_login"
                    value={data.allowLogin}
                    disabled={toggling}
                    onChange={(next) => toggleSystem('allow_login', next)}
                />
                <div className="member-scrub">
                    <button
                        type="button"
                        className="btn-ghost"
                        onClick={() => scrub('deleted', '탈퇴 계정 정리(1개월+)')}
                    >
                        탈퇴 계정 정리(1개월+)
                    </button>
                    <button
                        type="button"
                        className="btn-ghost"
                        onClick={() => scrub('old', '오래된 계정 정리(6개월+)')}
                    >
                        오래된 계정 정리(6개월+)
                    </button>
                </div>
            </div>

            {notice && <p className="member-notice">{notice}</p>}

            <h3 className="lobby-section-title">회원 목록</h3>
            <div className="game-table-wrap">
                <table className="game-table member-table">
                    <thead>
                        <tr>
                            <th scope="col">코드</th>
                            <th scope="col">유저명</th>
                            <th scope="col">EMAIL</th>
                            <th scope="col">등급</th>
                            <th scope="col">닉네임</th>
                            <th scope="col">전콘</th>
                            <th scope="col">장수명</th>
                            <th scope="col">
                                가입
                                <br />
                                일자
                            </th>
                            <th scope="col">
                                최근
                                <br />
                                로그인
                            </th>
                            <th scope="col">
                                탈퇴
                                <br />
                                신청
                            </th>
                            <th scope="col">명령</th>
                        </tr>
                    </thead>
                    <tbody>
                        {data.users.map((user) => (
                            <tr key={user.id}>
                                <th scope="row">{user.id}</th>
                                <td>{user.username}</td>
                                <td className="member-small">
                                    {/* legacy emailFunc: '@'를 줄바꿈으로. */}
                                    {(user.email ?? '-').split('@').map((part, i, arr) => (
                                        <span key={i}>
                                            {part}
                                            {i < arr.length - 1 ? '@' : ''}
                                            {i < arr.length - 1 && <br />}
                                        </span>
                                    ))}
                                    <br />({user.authType ?? '-'})
                                </td>
                                <td>
                                    {convUserGrade(user.grade, user.gradeLabel)}
                                    {/* 차단 등급(0)이면 차단 만료일 보조 표기. */}
                                    {user.blockUntil && (
                                        <p className="member-small member-block-until">
                                            {shortDate(user.blockUntil)}
                                        </p>
                                    )}
                                </td>
                                <td>{user.nickname ?? '-'}</td>
                                <td>
                                    {user.icon ? (
                                        // eslint-disable-next-line @next/next/no-img-element
                                        <img
                                            className="member-icon"
                                            src={user.icon}
                                            width={64}
                                            height={64}
                                            alt=""
                                        />
                                    ) : (
                                        '-'
                                    )}
                                </td>
                                <td className="member-small">
                                    {/* 장수명(서버별) — 루트DB엔 general이 없어 항상 비어있음(BE 주석). */}
                                    {data.servers.length === 0
                                        ? '-'
                                        : data.servers.map((srv) => (
                                              <span key={srv} className="member-general">
                                                  {user.generalNamesByServer[srv] ?? ''}
                                              </span>
                                          ))}
                                </td>
                                <td className="member-small">{shortDate(user.joinDate)}</td>
                                <td className="member-small">{shortDate(user.lastLoginAt)}</td>
                                <td className="member-small">{shortDate(user.deleteAfter)}</td>
                                <td>
                                    <div className="member-cmd-group" role="group">
                                        <button
                                            type="button"
                                            className="btn-danger member-cmd-btn"
                                            onClick={() => userCommand(user, 'delete')}
                                        >
                                            강제
                                            <br />
                                            탈퇴
                                        </button>
                                        <button
                                            type="button"
                                            className="btn-ghost member-cmd-btn"
                                            onClick={() => userCommand(user, 'reset_pw')}
                                        >
                                            암호
                                            <br />
                                            변경
                                        </button>
                                        <button
                                            type="button"
                                            className="btn-ghost member-cmd-btn member-cmd-warn"
                                            onClick={() => userCommand(user, 'block')}
                                        >
                                            유저
                                            <br />
                                            차단
                                        </button>
                                        <button
                                            type="button"
                                            className="btn-ghost member-cmd-btn"
                                            onClick={() => userCommand(user, 'unblock')}
                                        >
                                            차단
                                            <br />
                                            해제
                                        </button>
                                        <button
                                            type="button"
                                            className="btn-danger member-cmd-btn"
                                            onClick={() => banEmail(user)}
                                        >
                                            영구
                                            <br />
                                            차단
                                        </button>
                                        <button
                                            type="button"
                                            className="btn-primary member-cmd-btn"
                                            onClick={() => userCommand(user, 'set_userlevel')}
                                        >
                                            별도
                                            <br />
                                            권한
                                        </button>
                                    </div>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            <ConfirmModal
                open={confirm !== null}
                title={confirm?.title ?? ''}
                danger={confirm?.danger}
                busy={busy}
                message={confirm?.message ?? ''}
                onConfirm={async () => {
                    if (!confirm) return;
                    setBusy(true);
                    try {
                        await confirm.run();
                    } finally {
                        setBusy(false);
                        setConfirm(null);
                    }
                }}
                onCancel={() => setConfirm(null)}
            />
        </div>
    );
}
