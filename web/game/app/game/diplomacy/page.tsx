'use client';

import { useEffect, useState, useCallback } from 'react';
import { SectionHeader } from '@opensamguk/ui';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import CommandModal from '../../../components/CommandModal';
import { countHtmlCodePoints, RichTextEditor } from '../../../components/RichTextEditor';
import { SafeHtml } from '../../../components/SafeHtml';
import { api } from '../../../lib/api';
import { submitCommandAndAwaitResult } from '../../../lib/commandSubmit';
import { useFrontInfo } from '../../../hooks/useFrontInfo';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import type {
    DiplomacyLettersResponse,
    DiplomacyLetter,
    DiplomacyLetterParty,
    DiplomacyLetterNation,
} from '../../../lib/types';

// 외교 빠른 명령 (quick-action) — verbatim Korean captions → P6-registered che_ command codes.
// The non-aggression proposal resolves its required duration form from the command catalog; the other
// actions retain their one-nation picker shortcut. All four are registered in CommandRegistry.kt.
const DIPLO_QUICK_ACTIONS: { code: string; label: string }[] = [
    { code: 'che_종전제의', label: '종전 제의' },
    { code: 'che_불가침제의', label: '불가침 제의' },
    { code: 'che_불가침파기제의', label: '불가침 파기 제의' },
    { code: 'che_선전포고', label: '선전 포고' },
];

// Letter state text — verbatim from ts/diplomacy.ts stateText (LetterState).
const STATE_TEXT: Record<string, string> = {
    proposed: '제안됨',
    activated: '승인됨',
    cancelled: '거부됨',
    replaced: '대체됨',
};

// state_opt text — verbatim from ts/diplomacy.ts stateOptionText.
const STATE_OPT_TEXT: Record<string, string> = {
    try_destroy_src: '송신측의 파기 요청',
    try_destroy_dest: '수신측의 파기 요청',
};

// Letter state → war-room StatusBadge variant. proposed = pending(gold),
// activated = active(jade), replaced = neutral(muted). cancelled is skipped upstream.
const STATE_VARIANT: Record<string, 'crimson' | 'gold' | 'jade' | 'muted'> = {
    proposed: 'gold',
    activated: 'jade',
    cancelled: 'crimson',
    replaced: 'muted',
};
const DIPLOMACY_DETAIL_MAX_CODE_POINTS = 500;

// isBrightColor (legacy util) — black text on a bright nation color, white otherwise.
function isBrightColor(hex?: string): boolean {
    if (!hex || !/^#?[0-9a-fA-F]{6}$/.test(hex)) return false;
    const h = hex.replace('#', '');
    const r = parseInt(h.slice(0, 2), 16);
    const g = parseInt(h.slice(2, 4), 16);
    const b = parseInt(h.slice(4, 6), 16);
    return r * 0.299 + g * 0.587 + b * 0.114 > 127;
}

export default function DiplomacyPage() {
    const { frontInfo, refresh } = useFrontInfo();
    const generalId = frontInfo?.general.generalId ?? null;
    const ownNationId = frontInfo?.general.nationId;
    const permission = frontInfo?.general.permission ?? 0;
    const [data, setData] = useState<DiplomacyLettersResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');
    // The quick-action whose CommandModal is open (null = closed). nation target picked in-modal.
    const [quickAction, setQuickAction] = useState<{ code: string; label: string } | null>(null);

    // 서신 작성 폼 상태
    const [showWriteForm, setShowWriteForm] = useState(false);
    const [destNationId, setDestNationId] = useState<number>(0);
    const [briefDraft, setBriefDraft] = useState('');
    const [detailDraft, setDetailDraft] = useState('');
    const [prevNo, setPrevNo] = useState<number | null>(null);

    function showToast(msg: string) {
        setToast(msg);
        setTimeout(() => setToast(''), 3000);
    }

    // background=true는 턴 갱신용 — 보고 있던 화면을 로딩/에러로 바꾸지 않는다.
    const fetchData = useCallback(async (background = false) => {
        try {
            const res = await api.diplomacyLetters();
            setData(res);
            setError('');
        } catch {
            // 턴 갱신(background) 실패는 보고 있던 화면을 지우지 않는다.
            if (!background) setError('데이터를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    // 서신/외교 대상국 목록만 재조회 — 작성 중인 서신 폼(draft)은 건드리지 않는다(OPENSAM-196).
    useTurnRefresh(() => {
        fetchData(true);
    });

    const myNationId = data?.myNationID ?? 0;
    // legacy 응답은 nations를 Record<id, NationStaticItem> 맵으로 내려준다 → 값 순회.
    const nations: DiplomacyLetterNation[] = data?.nations ? Object.values(data.nations) : [];
    // cancelled letters are not shown (legacy drawLetter skips state == 'cancelled').
    const letters: DiplomacyLetter[] = (data?.letters ?? []).filter(l => l.state !== 'cancelled');

    // 수뇌부 권한 체크 (permission >= 4 또는 officer_level >= 5)
    const canWrite = permission >= 4;

    // 수신국 후보 (자국·재야 제외)
    const candidateNations = nations.filter(n => n.id !== myNationId && n.id !== 0);
    const detailDraftTooLong = countHtmlCodePoints(detailDraft.trim()) > DIPLOMACY_DETAIL_MAX_CODE_POINTS;

    const handleSendLetter = async () => {
        if (generalId == null) {
            showToast('장수가 없어 외교 서신을 보낼 수 없습니다.');
            return;
        }
        if (destNationId === 0) {
            showToast('수신국을 선택하세요.');
            return;
        }
        if (briefDraft.trim().length === 0) {
            showToast('요약문이 비어있습니다.');
            return;
        }
        const detail = detailDraft.trim();
        if (countHtmlCodePoints(detail) > DIPLOMACY_DETAIL_MAX_CODE_POINTS) {
            showToast(`본문은 ${DIPLOMACY_DETAIL_MAX_CODE_POINTS}자 이내로 입력하세요.`);
            return;
        }
        try {
            const out = await submitCommandAndAwaitResult(() => api.commands.diploSendLetter({
                destNation: destNationId,
                brief: briefDraft.trim(),
                detail,
                prevNo,
            }, generalId));
            if (out.status === 'rejected') {
                showToast(out.reason ?? '외교 서신을 보내는데 실패했습니다.');
                return;
            }
            if (out.status === 'pending') {
                showToast(out.reason);
                return;
            }
            showToast('전송했습니다.');
            setBriefDraft('');
            setDetailDraft('');
            setDestNationId(0);
            setShowWriteForm(false);
            fetchData();
        } catch (e) {
            // 실패 알림 verbatim — legacy `외교 서신을 보내는데 실패했습니다: ${e}`
            showToast('외교 서신을 보내는데 실패했습니다: ' + (e instanceof Error ? e.message : ''));
        }
    };

    const handleRollback = async (letterNo: number) => {
        if (!confirm('회수하시겠습니까?')) return;
        if (generalId == null) return;
        try {
            const out = await submitCommandAndAwaitResult(() => api.commands.diploRollbackLetter({ letterNo }, generalId));
            if (out.status === 'rejected') {
                showToast(out.reason ?? '회수를 실패했습니다.');
                return;
            }
            if (out.status === 'pending') {
                showToast(out.reason);
                return;
            }
            showToast('회수 했습니다.');
            fetchData();
        } catch (e) {
            showToast('회수를 실패했습니다: ' + (e instanceof Error ? e.message : ''));
        }
    };

    const handleDestroy = async (letterNo: number) => {
        // confirm verbatim — legacy ts/diplomacy.ts destroyLetter 클릭 핸들러
        if (!confirm('본 문서를 파기하겠습니까? (상호 동의 필요)')) return;
        if (generalId == null) return;
        try {
            const out = await submitCommandAndAwaitResult(() => api.commands.diploDestroyLetter({ letterNo }, generalId));
            if (out.status === 'rejected') {
                showToast(out.reason ?? '파기를 실패했습니다.');
                return;
            }
            if (out.status === 'pending') {
                showToast(out.reason);
                return;
            }
            showToast('파기 요청을 처리했습니다.');
            fetchData();
        } catch (e) {
            // 실패 알림 verbatim — legacy destroyLetter catch는 (복붙 버그로) 회수 문구를 쓴다. the frozen historical PHP baseline is retained here.
            showToast('회수를 실패했습니다: ' + (e instanceof Error ? e.message : ''));
        }
    };

    const handleRespond = async (letterNo: number, isAgree: boolean, reason = '') => {
        if (generalId == null) return;
        const actionText = isAgree ? '승인' : '거부';
        try {
            const out = await submitCommandAndAwaitResult(() =>
                api.commands.diploRespondLetter({ letterNo, isAgree, reason }, generalId),
            );
            if (out.status === 'rejected') {
                showToast(out.reason ?? `${actionText}에 실패했습니다.`);
                return;
            }
            if (out.status === 'pending') {
                showToast(out.reason);
                return;
            }
            showToast(`${actionText}했습니다.`);
            fetchData();
        } catch (e) {
            showToast(`${actionText}에 실패했습니다: ` + (e instanceof Error ? e.message : ''));
        }
    };

    // 페이지 접근 권한 게이트 — legacy t_diplomacy.php:28-30
    // checkSecretPermission($me) < 1 → "권한이 부족합니다. 수뇌부가 아니거나 사관년도가 부족합니다."
    // frontInfo가 아직 로드되지 않은 경우(permission=0 기본값)도 동일하게 차단된다.
    if (permission < 1) {
        return (
            <Shell>
                <PageHead title="외교부" />
                <GameCard>
                    <p className="text-secondary">권한이 부족합니다. 수뇌부가 아니거나 사관년도가 부족합니다.</p>
                </GameCard>
            </Shell>
        );
    }

    return (
        <Shell>
            <PageHead title="외교부" />

            <div className="dip-toolbar">
                <button onClick={() => void fetchData()}>새로고침</button>
            </div>

            {/* 외교 빠른 명령 — route each through CommandModal (nation-target SelectNationField). */}
            <GameCard className="stack-card">
                <SectionHeader as="h2" title="외교 명령" />
                <div className="dip-chips">
                    {DIPLO_QUICK_ACTIONS.map(act => (
                        <button
                            key={act.code}
                            onClick={() => {
                                if (generalId == null) {
                                    showToast('장수가 없어 외교 명령을 내릴 수 없습니다.');
                                    return;
                                }
                                setQuickAction(act);
                            }}
                        >
                            {act.label}
                        </button>
                    ))}
                </div>
            </GameCard>

            {/* 서신 작성 폼 — 수뇌부 전용 */}
            {canWrite && (
                <GameCard className="stack-card">
                    <div className="dip-form__head">
                        <SectionHeader as="h2" title="외교 서신 작성" />
                        <button onClick={() => setShowWriteForm(!showWriteForm)}>
                            {showWriteForm ? '접기' : '펼치기'}
                        </button>
                    </div>
                    {showWriteForm && (
                        <div className="dip-form__body">
                            <div>
                                <label className="dip-label">수신국</label>
                                <select
                                    value={destNationId}
                                    onChange={(e) => setDestNationId(Number(e.target.value))}
                                    className="dip-control"
                                >
                                    <option value={0}>선택하세요</option>
                                    {candidateNations.map(n => (
                                        <option key={n.id} value={n.id}>
                                            {n.name} (Lv.{n.level})
                                        </option>
                                    ))}
                                </select>
                            </div>
                            <div>
                                <label className="dip-label">이전 문서</label>
                                <select
                                    value={prevNo ?? ''}
                                    onChange={(e) => {
                                        const v = e.target.value;
                                        setPrevNo(v === '' ? null : Number(v));
                                    }}
                                    className="dip-control"
                                >
                                    <option value="">신규</option>
                                    {letters
                                        .filter(l => l.state === 'activated')
                                        .map(l => (
                                            <option key={l.no} value={l.no}>
                                                #{l.no} — {l.brief}
                                            </option>
                                        ))}
                                </select>
                            </div>
                            <div>
                                <label className="dip-label">요약</label>
                                <input
                                    type="text"
                                    value={briefDraft}
                                    onChange={(e) => setBriefDraft(e.target.value)}
                                    placeholder="요약문을 입력하세요"
                                    className="dip-control"
                                />
                            </div>
                            <div>
                                <label className="dip-label">본문</label>
                                <div className="dip-form__editor">
                                    <RichTextEditor
                                        ariaLabel="외교 서신 본문"
                                        maxTextLength={DIPLOMACY_DETAIL_MAX_CODE_POINTS}
                                        onChange={setDetailDraft}
                                        value={detailDraft}
                                    />
                                </div>
                            </div>
                            <div className="dip-form__actions">
                                <button
                                    disabled={briefDraft.trim().length === 0 || detailDraftTooLong || destNationId === 0}
                                    onClick={handleSendLetter}
                                >
                                    발송
                                </button>
                            </div>
                        </div>
                    )}
                </GameCard>
            )}

            {loading && <p className="text-muted">로딩 중...</p>}
            {error && <p className="page-error">{error}</p>}

            {toast && (
                <div className="toast toast--pinned">
                    {toast}
                </div>
            )}

            {!loading && !error && (
                <>
                    {/* 외교 대상 국가 — candidate counter-nations (excludes self & 재야). */}
                    <GameCard className="stack-card">
                        <SectionHeader as="h2" title="외교 대상 국가" />
                        {nations.length === 0 ? (
                            <p className="text-muted">외교 대상 국가가 없습니다.</p>
                        ) : (
                            <div className="dip-chips">
                                {nations.map(n => {
                                    const textColor = isBrightColor(n.color) ? '#000000' : '#ffffff';
                                    return (
                                        <span
                                            key={n.id}
                                            className="status-badge"
                                            style={{ backgroundColor: n.color, color: textColor, border: 'none' }}
                                        >
                                            {n.name}
                                        </span>
                                    );
                                })}
                            </div>
                        )}
                    </GameCard>

                    {/* 외교 서신 — letter list, newest-first. */}
                    <SectionHeader as="h2" title="외교 서신" />
                    {letters.length === 0 ? (
                        <GameCard>
                            <p className="text-muted">주고받은 외교 서신이 없습니다.</p>
                        </GameCard>
                    ) : (
                        <div className="dip-letters">
                            {letters.map(letter => (
                                <LetterCard
                                    key={letter.no}
                                    letter={letter}
                                    myNationId={myNationId}
                                    canWrite={canWrite}
                                    onRollback={handleRollback}
                                    onDestroy={handleDestroy}
                                    onRespond={handleRespond}
                                />
                            ))}
                        </div>
                    )}
                </>
            )}

            {/* 외교 명령 — CommandModal pinned to a che_ diplomacy code. The non-aggression proposal
                resolves its server-owned compound form; the remaining actions pick one destination nation. */}
            {quickAction && generalId != null && (
                <CommandModal
                    onClose={() => setQuickAction(null)}
                    onToast={(msg) => showToast(msg)}
                    generalId={generalId}
                    nationId={ownNationId}
                    pinnedCommand={quickAction.code}
                    pinnedLabel={quickAction.label}
                    pinnedArgType="nation"
                    resolvePinnedFromCatalog={quickAction.code === 'che_불가침제의'}
                    onReserved={() => { refresh(); fetchData(); }}
                    isNationCommand
                />
            )}
        </Shell>
    );
}

function LetterCard({
    letter,
    myNationId,
    canWrite,
    onRollback,
    onDestroy,
    onRespond,
}: {
    letter: DiplomacyLetter;
    myNationId: number;
    canWrite: boolean;
    onRollback: (letterNo: number) => void;
    onDestroy: (letterNo: number) => void;
    onRespond: (letterNo: number, isAgree: boolean, reason?: string) => void;
}) {
    // Header shows the counter-party (the OTHER side relative to my nation) — legacy targetNation.
    const counter = letter.src.nationID === myNationId ? letter.dest : letter.src;
    const headerBg = counter.nationColor || 'var(--bg-hover)';
    const headerColor = isBrightColor(counter.nationColor) ? '#000000' : '#ffffff';

    const stateText = STATE_TEXT[letter.state] ?? letter.state;
    const variant = STATE_VARIANT[letter.state] ?? 'muted';
    const stateOptText = letter.state_opt ? STATE_OPT_TEXT[letter.state_opt] ?? null : null;

    // 회수 가능: proposed 상태 && 내가 송신국 — legacy drawLetter (state=='proposed' && src.nationID==myNationID)
    const canRollback = canWrite && letter.state === 'proposed' && letter.src.nationID === myNationId;
    const canRespond = canWrite && letter.state === 'proposed' && letter.src.nationID !== myNationId;
    // 파기 버튼 노출: activated 상태 && 내가 양측 중 하나 — legacy drawLetter (state=='activated' 일 때 .btnDestroy show)
    const showDestroy = canWrite && letter.state === 'activated' &&
        (letter.src.nationID === myNationId || letter.dest.nationID === myNationId);
    // 내가 이미 파기 요청한 측이면 버튼은 노출하되 비활성(disabled) — legacy:
    //   (src==my && state_opt=='try_destroy_src') || (dest==my && state_opt=='try_destroy_dest')
    const destroyDisabled =
        (letter.src.nationID === myNationId && letter.state_opt === 'try_destroy_src') ||
        (letter.dest.nationID === myNationId && letter.state_opt === 'try_destroy_dest');

    const approve = () => {
        if (!confirm('승인하시겠습니까?')) return;
        onRespond(letter.no, true, '');
    };

    const reject = () => {
        const reason = prompt('거부 사유를 입력하세요.', '');
        if (reason == null) return;
        onRespond(letter.no, false, reason.slice(0, 50));
    };

    return (
        <GameCard className="dip-letter">
            <div className="dip-letter__head" style={{ backgroundColor: headerBg, color: headerColor }}>
                <span className="dip-letter__nation">{counter.nationName}</span>
                <span className="dip-letter__meta">
                    #{letter.no} · {letter.date}
                </span>
            </div>

            <div className="dip-letter__body">
                <div className="dip-letter__row">
                    <StatusBadge variant={variant}>{stateText}</StatusBadge>
                    {stateOptText && (
                        <span className="dip-letter__opt">({stateOptText})</span>
                    )}
                    {/* 이전 문서 — legacy t_diplomacy.php th '이전 문서' / value '#N' 또는 '신규' */}
                    <span className="dip-letter__prev">
                        이전 문서: {letter.prev_no != null ? `#${letter.prev_no}` : '신규'}
                    </span>
                </div>

                {/* 송신 → 수신 서명인 — legacy t_diplomacy.php .letterSrc/.letterDest:
                   signerImg(generalIcon) + signerNation(nationName) + signerName(generalName).
                   미서명 수신측(generalName 부재)은 nation만 표시(legacy `'generalName' in dest` 분기). */}
                <div className="dip-letter__row dip-letter__row--sign">
                    <Signer party={letter.src} />
                    <span className="text-muted">→</span>
                    <Signer party={letter.dest} />
                </div>

                {/* 내용(국가 내 공개) — legacy t_diplomacy.php th, 항상 표시(평문, 개행 보존) */}
                <div className={letter.detail ? 'dip-letter__section' : undefined}>
                    <span className="dip-label">내용(국가 내 공개)</span>
                    <div className="dip-letter__text">
                        <SafeHtml html={letter.brief} />
                    </div>
                </div>

                {/* 내용(외교권자 전용) — legacy th. detail은 permission<3 시 BE에서 '(권한이 부족합니다)'로 마스킹됨 */}
                {letter.detail && (
                    <div
                        className="dip-letter__secret"
                    >
                        <span className="dip-label">내용(외교권자 전용)</span>
                        <div
                            className="dip-letter__text dip-letter__text--secret"
                        >
                            <SafeHtml html={letter.detail} />
                        </div>
                    </div>
                )}

                {(canRollback || canRespond || showDestroy) && (
                    <div className="dip-letter__actions">
                        {canRollback && (
                            <button
                                onClick={() => onRollback(letter.no)}
                                className="dip-btn"
                            >
                                회수
                            </button>
                        )}
                        {canRespond && (
                            <>
                                <button
                                    onClick={approve}
                                    className="dip-btn"
                                >
                                    승인
                                </button>
                                <button
                                    onClick={reject}
                                    className="dip-btn"
                                >
                                    거부
                                </button>
                            </>
                        )}
                        {showDestroy && (
                            <button
                                onClick={() => onDestroy(letter.no)}
                                disabled={destroyDisabled}
                                className="dip-btn"
                            >
                                파기
                            </button>
                        )}
                    </div>
                )}
            </div>
        </GameCard>
    );
}

// 서신 1 당사자 서명인 — legacy t_diplomacy.php .letterSrc/.letterDest 렌더 패러티.
//   signerImg img.generalIcon(서명 장수 초상) + signerNation(nationName) + signerName(generalName).
// generalName 부재(미서명 수신측)면 nation명만 표시한다(legacy `'generalName' in letterObj.dest` 분기).
function Signer({ party }: { party: DiplomacyLetterParty }) {
    const nationColor = party.nationColor || 'var(--text-secondary)';
    return (
        <span className="dip-signer">
            {party.generalIcon && (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                    src={party.generalIcon}
                    alt=""
                    className="generalIcon dip-signer__icon"
                />
            )}
            <span style={{ color: nationColor, fontWeight: 500 }}>{party.nationName}</span>
            {party.generalName && (
                <span style={{ color: nationColor }}>{party.generalName}</span>
            )}
        </span>
    );
}
