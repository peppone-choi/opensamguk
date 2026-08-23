'use client';

// 설문 조사 (Vote) — F4 page 5. READ + MUTATION (F4 Wave 투표).
// 그랜드 트루스: legacy hwe/ts/PageVote.vue + hwe/sammo/API/Vote/{GetVoteList,GetVoteDetail,
//   NewVote,Vote,AddComment}.php + hwe/ts/utilGame/formatVoteColor.ts.
//
// READ는 그대로 유지한다 — api.votes()(목록) + api.vote(id)(detail+results+myVote+userCnt).
// 목록은 newest-first(legacy가 Object.entries(votes)를 reverse), 이전 설문 행을 누르면 그 detail을
// 다시 로드한다. 결과 표는 read-only다.
//
// MUTATION은 board/troop 슬라이스와 동일하게 기존 CommandModal(pinnedCommand + extraArgs,
// pinnedArgType=null)로 배선한다. 네 작업 모두 인테이크 코드는 CommandWireMapper.intakeCodes의
// 그대로다(아래 인테이크 contract 참조). 모든 args는 extraArgs에 실린다(서브 폼 없음):
//  - 개설 (newVote, admin):  { title, options:string[], multipleOptions:int, endDate?, keepOldVote? }.
//      options는 legacy textarea(엔터 구분, 빈 줄 제거)를 그대로 따른다. endDate는 폼에 없으므로
//      전송하지 않는다(legacy newVoteInfo.endDate=undefined). multipleOptions 클램프/기본값(?? 1,
//      <0→0, valueFit 0..count)은 엔진(frozen historical PHP baseline (ADR-LITE-042; not current product authority))이 적용한다.
//  - 투표 (voteCast):        { voteId, selection:int[] }. selection은 단일(radio→[idx]) 또는
//      다중(checkbox→[…]) — multipleOptions===1이면 단일, else 다중(legacy submitVote).
//  - 댓글 (voteComment):     { voteId, text }. text 200자 절단은 엔진이 한다(mb_substr).
//  - 마감 (voteClose, admin):{ voteId }. endDate가 비어 있으면 now()로 채워 마감(이미 있으면 no-op).
//
// CommandModal contract는 호출자 자신의 generalId를 요구한다 — board와 동일하게 api.frontInfo()
// (general.generalId)에서 가져온다. admin 게이트는 front-info.global.vote(legacy isVoteAdmin이
// 구동하는 vote 메뉴 show 플래그)로 근사한다 — read DTO가 노출하는 가장 근접한 신호.
//
// canVote(legacy computed) = 로그인 && currentVote && !myVote && (endDate 없음 || now <= endDate).
//   canVote면 결과 표 좌측에 radio/checkbox + 투표 버튼을 렌더하고, 아니면 read-only 결과 표를 렌더한다.
//   다중 선택 초과 시 multipleOptions(0이 아닐 때) 경고 토스트로 막는다(legacy watch(myMultiPick)).
//
// 제출은 진행 중(in-flight) 동안 비활성화한다(CommandModal이 자체 loading을 가지며, 페이지의
//   submit 버튼도 모달이 열려 있는 동안 잠근다).
//
// Verbatim labels (PageVote.vue): 설문 조사 / 설문 제목 / 게시자 / [SYSTEM] / (N개 선택 가능 ) /
//   명 / (N.N%) / 투표 / 결산 / 투표율 / 이전 설문 조사 / # · 국가명 · 장수명 · 댓글 · 일시 / 댓글 달기 /
//   새 설문 조사 열기 / 설문 제목 / 설문 대상(엔터로 구분) / 동시 응답 수(0=모두) / 제출.
//
// EMPTY-SAFE: votes {} → '설문 조사가 없습니다.'; detail의 빈 votes[] → 분포 전부 0, 0.0%.

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import CommandModal from '../../../components/CommandModal';
import { api } from '../../../lib/api';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import type {
    VoteListResponse,
    VoteDetailResponse,
    VoteInfo,
} from '../../../lib/types';

// formatVoteColor.ts: css-color-names for red·orange·yellow·green·blue·navy·purple, cyclic by index.
import { VOTE_COLORS, BRIGHT_COLOR_THRESHOLD, TOAST_DURATION_MS } from '../../../lib/constants';
function formatVoteColor(idx: number): string {
    return VOTE_COLORS[idx % VOTE_COLORS.length];
}

// legacy isBrightColor: perceived-luminance threshold (r*.299 + g*.587 + b*.114) > 140 → black text.
function isBrightColor(color: string): boolean {
    const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(color.trim());
    if (!m) return false;
    const r = parseInt(m[1], 16);
    const g = parseInt(m[2], 16);
    const b = parseInt(m[3], 16);
    return r * 0.299 + g * 0.587 + b * 0.114 > BRIGHT_COLOR_THRESHOLD;
}

// PageVote.vue header: multipleOptions !== 1 shows "(N개 선택 가능 )", N = options.length when 0 else N.
function selectableLabel(info: VoteInfo): string {
    if (info.multipleOptions === 1) return '';
    const n = info.multipleOptions === 0 ? info.options.length : info.multipleOptions;
    return `(${n}개 선택 가능 )`;
}

// legacy comment date: date.substring(5, 5 + 5 + 1 + 5) === substring(5, 16) → MM-DD HH:MM.
function shortDate(date: string): string {
    return (date ?? '').slice(5, 16);
}

// legacy formatTime(new Date()): 'YYYY-MM-DD HH:mm:ss' 로컬 문자열. endDate 문자열 비교(now > endDate)
// 에 쓰인다 — canVote가 종료일 경과를 판정할 때 PHP/Vue와 동일한 사전식(lexicographic) 비교를 유지한다.
function formatNow(): string {
    const d = new Date();
    const p = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

// 한 개의 열린 vote CommandModal spec. argType은 항상 null(args는 extraArgs에 실린다).
type VoteModalSpec = { command: string; label: string; extraArgs?: Record<string, unknown> };

export default function VotePage() {
    const [list, setList] = useState<VoteListResponse | null>(null);
    const [currentId, setCurrentId] = useState<number | null>(null);
    const [detail, setDetail] = useState<VoteDetailResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');

    // ── mutation state ──────────────────────────────────────────────────────────
    // board와 동일: CommandModal이 호출자 자신의 generalId를 요구한다 → front-info에서 한 번 가져온다.
    const [myGeneralId, setMyGeneralId] = useState(0);
    // legacy isVoteAdmin 근사: front-info.global.vote(vote 메뉴 show 플래그). 개설/마감 표면을 게이트한다.
    const [isVoteAdmin, setIsVoteAdmin] = useState(false);
    // legacy v_vote.php:30 voteReward = develcost*5 — 페이지 제목의 "(N금 + 추첨 유니크템)" 안내. 부재 시 null.
    const [voteReward, setVoteReward] = useState<number | null>(null);
    // 투표 선택 draft (currentId로 keying — 다른 vote로 전환 시 비워진다). 단일=숫자, 다중=숫자 배열.
    const [singlePick, setSinglePick] = useState<number | null>(null);
    const [multiPick, setMultiPick] = useState<number[]>([]);
    // 댓글 draft + 개설 폼 draft.
    const [commentDraft, setCommentDraft] = useState('');
    const [newTitle, setNewTitle] = useState('');
    const [newOptionsText, setNewOptionsText] = useState('');
    const [newMultiple, setNewMultiple] = useState(1);
    const [showNewVote, setShowNewVote] = useState(false);
    const [modal, setModal] = useState<VoteModalSpec | null>(null);
    const [toast, setToast] = useState<string | null>(null);

    // OPENSAM-196: background=true면 로딩 스피너를 건너뛴다 — 결과표/댓글은 `!loading`으로
    // 게이팅되어 있어, 아니면 턴 갱신마다 화면이 잠깐 사라진다.
    const fetchList = useCallback(async (background = false) => {
        if (!background) setLoading(true);
        try {
            const d = await api.votes();
            setList(d);
            setError('');
            // newest-first (legacy reverses entries); auto-select the first vote if none chosen yet.
            const ids = Object.keys(d.votes ?? {})
                .map(Number)
                .sort((a, b) => b - a);
            setCurrentId((prev) => (prev != null ? prev : ids.length > 0 ? ids[0] : null));
        } catch {
            setList(null);
            setError('설문 목록을 불러올 수 없습니다.');
        } finally {
            if (!background) setLoading(false);
        }
    }, []);

    const fetchDetail = useCallback((id: number) => {
        api.vote(id)
            .then((d) => setDetail(d.result ? d : null))
            .catch(() => setDetail(null));
    }, []);

    useEffect(() => {
        fetchList();
    }, [fetchList]);

    // 행위 주체 장수 자신의 id + isVoteAdmin 플래그 — write 경로를 위해 한 번 가져온다.
    useEffect(() => {
        api.frontInfo()
            .then((info) => {
                setMyGeneralId(info.general?.generalId ?? 0);
                setIsVoteAdmin(info.global?.vote ?? false);
                setVoteReward(info.global?.voteReward ?? null);
            })
            .catch(() => {
                setMyGeneralId(0);
                setIsVoteAdmin(false);
                setVoteReward(null);
            });
    }, []);

    useEffect(() => {
        if (currentId == null) {
            setDetail(null);
            return;
        }
        let on = true;
        api.vote(currentId)
            .then((d) => {
                if (on) setDetail(d.result ? d : null);
            })
            .catch(() => {
                if (on) setDetail(null);
            });
        // legacy reloadVoteDetail: vote 전환 시 픽/댓글 draft 초기화.
        setSinglePick(null);
        setMultiPick([]);
        setCommentDraft('');
        return () => {
            on = false;
        };
    }, [currentId]);

    // OPENSAM-196: 전용 EventSource 대신 Shell의 단일 SSE를 공유하는 턴 완료 신호로 갈아탄다.
    // 목록만 백그라운드로 다시 읽는다 — 상세(fetchDetail)는 currentId effect가 이미 재조회하며,
    // 투표/댓글/개설 draft는 fetchList가 건드리지 않으므로 보존된다.
    useTurnRefresh(() => {
        fetchList(true);
    });

    const votesMap = list?.votes ?? {};
    // 이전 설문 조사 list — newest-first (legacy Object.entries(...).reverse()).
    const voteListEntries = Object.entries(votesMap)
        .map(([id, info]) => [Number(id), info] as [number, VoteInfo])
        .sort((a, b) => b[0] - a[0]);

    // result tally → per-option distribution + total (legacy watch(currentVote)).
    const info = detail?.voteInfo ?? null;
    const distribution: Record<number, number> = {};
    let total = 0;
    if (info) {
        for (let i = 0; i < info.options.length; i++) distribution[i] = 0;
        for (const [selection, count] of detail?.votes ?? []) {
            total += count;
            for (const optIdx of selection) {
                distribution[optIdx] = (distribution[optIdx] ?? 0) + count;
            }
        }
    }
    const userCnt = detail?.userCnt ?? 0;

    // canVote (legacy computed): 로그인(=myGeneralId≠0) && currentVote && !myVote && 미종료.
    const isSingle = info?.multipleOptions === 1;
    const notEnded =
        !info?.endDate || formatNow() <= info.endDate; // legacy: endDate 없음 || now <= endDate
    const canVote = !!info && myGeneralId !== 0 && detail?.myVote == null && notEnded;
    // 모달이 열려 있는 동안 페이지 submit 버튼을 잠근다(중복 제출 방지). 모달 자체도 in-flight를 잠근다.
    const submitting = modal != null;

    // 다중 선택 토글 — legacy watch(myMultiPick): multipleOptions(≠0) 초과 시 경고 토스트 후 무시.
    function toggleMulti(idx: number) {
        if (!info) return;
        setMultiPick((prev) => {
            if (prev.includes(idx)) return prev.filter((v) => v !== idx);
            const next = [...prev, idx];
            const max = info.multipleOptions;
            if (max !== 0 && next.length > max) {
                setToast(`${max}개까지만 선택할 수 있습니다.`);
                return prev;
            }
            return next;
        });
    }

    // 투표 제출(voteCast): selection = 단일 ? [singlePick] : multiPick. 빈 선택은 막는다(legacy 클라 가드).
    function openVote() {
        if (!info) return;
        const selection = isSingle ? (singlePick == null ? [] : [singlePick]) : [...multiPick].sort((a, b) => a - b);
        if (selection.length === 0) {
            setToast('선택한 항목이 없습니다.');
            return;
        }
        setModal({
            command: 'voteCast',
            label: '투표',
            extraArgs: { voteId: info.id, selection },
        });
    }

    // 개설 제출(newVote, admin): options는 legacy textarea(엔터 구분, 빈 줄 제거).
    function openNewVote() {
        const options = newOptionsText.split('\n').filter((v) => v.length > 0);
        setModal({
            command: 'newVote',
            label: '설문 조사 개설',
            // endDate는 폼에 없으므로 전송하지 않는다(엔진이 ?? null로 무기한 처리).
            extraArgs: { title: newTitle, options, multipleOptions: newMultiple },
        });
    }

    const newOptionsCount = newOptionsText.split('\n').filter((v) => v.length > 0).length;

    return (
        <Shell>
            {/* legacy PageVote.vue:4 — "설문 조사({voteReward}금과 추첨으로 유니크템 증정!)". reward 부재 시 안내 생략. */}
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>
                설문 조사{voteReward != null && `(${voteReward.toLocaleString()}금과 추첨으로 유니크템 증정!)`}
            </h1>

            <div
                className="control-bar"
                style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}
            >
                <button onClick={() => fetchList()}>새로고침</button>
            </div>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {/* ── current vote: result + comments ─────────────────────────────── */}
            {!loading && !error && info && (
                <>
                    <GameCard style={{ marginBottom: 'var(--space-lg)' }}>
                        <div style={{ overflowX: 'auto' }}>
                            <table className="game-table" style={{ width: '100%' }}>
                                <thead>
                                    <tr>
                                        <th colSpan={3} style={{ textAlign: 'right' }}>설문 제목</th>
                                        <th>
                                            {info.title}
                                            {info.multipleOptions !== 1 && ` ${selectableLabel(info)}`}
                                        </th>
                                    </tr>
                                    <tr>
                                        <th colSpan={3} style={{ textAlign: 'right' }}>게시자</th>
                                        <th>{info.opener ?? '[SYSTEM]'}</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {info.options.map((option, idx) => {
                                        const count = distribution[idx] ?? 0;
                                        const percent = ((count / Math.max(1, total)) * 100).toFixed(1);
                                        const color = formatVoteColor(idx);
                                        return (
                                            <tr key={idx}>
                                                {/* canVote → 좌측에 radio/checkbox(투표 입력); 아니면 read-only 색상 인덱스 셀. */}
                                                {canVote ? (
                                                    <td style={{ textAlign: 'center' }}>
                                                        {isSingle ? (
                                                            <input
                                                                type="radio"
                                                                name="vote-single"
                                                                checked={singlePick === idx}
                                                                onChange={() => setSinglePick(idx)}
                                                                disabled={submitting}
                                                            />
                                                        ) : (
                                                            <input
                                                                type="checkbox"
                                                                checked={multiPick.includes(idx)}
                                                                onChange={() => toggleMulti(idx)}
                                                                disabled={submitting}
                                                            />
                                                        )}
                                                    </td>
                                                ) : (
                                                    <td
                                                        style={{
                                                            textAlign: 'right',
                                                            backgroundColor: color,
                                                            color: isBrightColor(color) ? '#000' : '#fff',
                                                        }}
                                                    >
                                                        {idx + 1}.
                                                    </td>
                                                )}
                                                <td style={{ textAlign: 'right' }}>{count}명</td>
                                                <td style={{ textAlign: 'right' }}>({percent}%)</td>
                                                <td>{option}</td>
                                            </tr>
                                        );
                                    })}
                                    {info.options.length === 0 && (
                                        <tr>
                                            <td colSpan={4} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
                                                항목이 없습니다.
                                            </td>
                                        </tr>
                                    )}
                                </tbody>
                                <tfoot>
                                    <tr>
                                        {/* canVote → 투표 버튼 footer; 아니면 결산 라벨(read-only). */}
                                        {canVote ? (
                                            <>
                                                <td style={{ textAlign: 'center' }}>투표</td>
                                                <td colSpan={2}>
                                                    <button
                                                        onClick={openVote}
                                                        disabled={submitting}
                                                        style={{ width: '100%' }}
                                                    >
                                                        투표
                                                    </button>
                                                </td>
                                            </>
                                        ) : (
                                            <td colSpan={3} style={{ textAlign: 'center' }}>결산</td>
                                        )}
                                        <td>
                                            투표율: {total} / {userCnt} ({' '}
                                            {((total / Math.max(1, userCnt)) * 100).toFixed(1)}%)
                                        </td>
                                    </tr>
                                    {/* 마감 (voteClose, admin) — endDate가 아직 없을 때만 노출(이미 마감은 no-op). */}
                                    {isVoteAdmin && myGeneralId !== 0 && !info.endDate && (
                                        <tr>
                                            <td colSpan={4} style={{ textAlign: 'right' }}>
                                                <button
                                                    onClick={() =>
                                                        setModal({
                                                            command: 'voteClose',
                                                            label: '설문 마감',
                                                            extraArgs: { voteId: info.id },
                                                        })
                                                    }
                                                    disabled={submitting}
                                                >
                                                    설문 마감
                                                </button>
                                            </td>
                                        </tr>
                                    )}
                                </tfoot>
                            </table>
                        </div>
                    </GameCard>

                    {/* comments */}
                    <GameCard style={{ marginBottom: 'var(--space-lg)' }}>
                        <div style={{ overflowX: 'auto' }}>
                            <table className="game-table" style={{ width: '100%' }}>
                                <thead>
                                    <tr>
                                        <th style={{ width: '5ch', textAlign: 'right' }}>#</th>
                                        <th style={{ textAlign: 'center' }}>국가명</th>
                                        <th style={{ textAlign: 'center' }}>장수명</th>
                                        <th>댓글</th>
                                        <th style={{ textAlign: 'center' }}>일시</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {(detail?.comments ?? []).map((comment, idx) => (
                                        <tr key={comment.id ?? idx}>
                                            <td style={{ textAlign: 'right' }}>{idx + 1}.</td>
                                            <td style={{ textAlign: 'center' }}>{comment.nationName}</td>
                                            <td style={{ textAlign: 'center' }}>{comment.generalName}</td>
                                            <td style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{comment.text}</td>
                                            <td style={{ textAlign: 'center', fontSize: 'var(--text-xs)' }}>
                                                {shortDate(comment.date)}
                                            </td>
                                        </tr>
                                    ))}
                                    {(detail?.comments?.length ?? 0) === 0 && (
                                        <tr>
                                            <td colSpan={5} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
                                                댓글이 없습니다.
                                            </td>
                                        </tr>
                                    )}
                                </tbody>
                                <tfoot>
                                    {/* 댓글 달기 (voteComment) — text는 input → extraArgs.text, voteId = info.id.
                                        myGeneralId가 있을 때만 활성화(CommandModal contract). */}
                                    <tr>
                                        <td></td>
                                        <td colSpan={2}>
                                            <button
                                                style={{ width: '100%' }}
                                                disabled={myGeneralId === 0 || commentDraft.trim().length === 0 || submitting}
                                                onClick={() =>
                                                    setModal({
                                                        command: 'voteComment',
                                                        label: '댓글',
                                                        extraArgs: { voteId: info.id, text: commentDraft },
                                                    })
                                                }
                                            >
                                                댓글 달기
                                            </button>
                                        </td>
                                        <td colSpan={2}>
                                            <input
                                                type="text"
                                                value={commentDraft}
                                                onChange={(e) => setCommentDraft(e.target.value)}
                                                placeholder="새 댓글 내용"
                                                maxLength={200}
                                                disabled={myGeneralId === 0 || submitting}
                                                style={{ width: '100%' }}
                                            />
                                        </td>
                                    </tr>
                                </tfoot>
                            </table>
                        </div>
                    </GameCard>
                </>
            )}

            {/* ── 이전 설문 조사 list ──────────────────────────────────────────── */}
            {!loading && !error && (
                <>
                    <div
                        className="section-title"
                        style={{ background: 'var(--bg2, #2a2a2a)', color: 'var(--text-primary)', textAlign: 'center', fontSize: 'var(--text-lg)', fontWeight: 600, padding: 'var(--space-xs) var(--space-sm)', marginBottom: 'var(--space-sm)' }}
                    >
                        이전 설문 조사
                    </div>
                    <GameCard>
                        {voteListEntries.length > 0 ? (
                            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                                {voteListEntries.map(([id, vInfo]) => (
                                    <div key={id}>
                                        <button
                                            onClick={() => setCurrentId(id)}
                                            style={{
                                                background: 'none',
                                                border: 'none',
                                                padding: 0,
                                                color: id === currentId ? 'var(--gold)' : 'var(--jade)',
                                                cursor: 'pointer',
                                                textAlign: 'left',
                                            }}
                                        >
                                            {vInfo.title}
                                        </button>
                                        <span style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>
                                            {' '}({vInfo.startDate})
                                        </span>
                                    </div>
                                ))}
                            </div>
                        ) : (
                            <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>
                                설문 조사가 없습니다.
                            </p>
                        )}
                    </GameCard>
                </>
            )}

            {/* ── 새 설문 조사 개설 (newVote, admin) — legacy isVoteAdmin 패널 ──────── */}
            {!loading && !error && isVoteAdmin && myGeneralId !== 0 && (
                <GameCard style={{ marginTop: 'var(--space-lg)' }}>
                    <button
                        onClick={() => setShowNewVote((v) => !v)}
                        style={{
                            background: 'none',
                            border: 'none',
                            padding: 0,
                            color: 'var(--jade)',
                            cursor: 'pointer',
                        }}
                    >
                        새 설문 조사 열기
                    </button>
                    {showNewVote && (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)', marginTop: 'var(--space-sm)' }}>
                            <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                                <span>설문 제목</span>
                                <input
                                    type="text"
                                    value={newTitle}
                                    onChange={(e) => setNewTitle(e.target.value)}
                                />
                            </label>
                            <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                                <span>설문 대상(엔터로 구분) ({newOptionsCount}건)</span>
                                <textarea
                                    rows={Math.max(2, newOptionsCount + 1)}
                                    value={newOptionsText}
                                    onChange={(e) => setNewOptionsText(e.target.value)}
                                    style={{ resize: 'vertical' }}
                                />
                            </label>
                            <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                                <span>동시 응답 수(0=모두)</span>
                                <input
                                    type="number"
                                    min={0}
                                    max={newOptionsCount}
                                    value={newMultiple}
                                    onChange={(e) => setNewMultiple(Number(e.target.value))}
                                    style={{ width: '120px' }}
                                />
                            </label>
                            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                                <button
                                    onClick={openNewVote}
                                    disabled={newTitle.trim().length === 0 || newOptionsCount === 0 || submitting}
                                >
                                    제출
                                </button>
                            </div>
                        </div>
                    )}
                </GameCard>
            )}

            {/* Vote CommandModal (pinnedCommand + extraArgs; pinnedArgType=null — args는 extraArgs에 실린다). */}
            {modal && myGeneralId !== 0 && (
                <CommandModal
                    onClose={() => setModal(null)}
                    onToast={(msg) => setToast(msg)}
                    generalId={myGeneralId}
                    pinnedCommand={modal.command}
                    pinnedLabel={modal.label}
                    pinnedArgType={null}
                    extraArgs={modal.extraArgs}
                    onReserved={() => {
                        // 성공 → draft 초기화 + 현재 vote detail + 목록 새로고침.
                        setSinglePick(null);
                        setMultiPick([]);
                        setCommentDraft('');
                        setNewTitle('');
                        setNewOptionsText('');
                        setNewMultiple(1);
                        setShowNewVote(false);
                        if (currentId != null) fetchDetail(currentId);
                        fetchList();
                    }}
                />
            )}

            {toast && (
                <div
                    role="status"
                    style={{
                        position: 'fixed',
                        bottom: 16,
                        left: '50%',
                        transform: 'translateX(-50%)',
                        background: 'var(--surface-raised)',
                        padding: '8px 16px',
                        borderRadius: 8,
                    }}
                    onClick={() => setToast(null)}
                >
                    {toast}
                </div>
            )}
        </Shell>
    );
}
