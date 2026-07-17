'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import CommandModal from '../../../components/CommandModal';
import { api, isIntakeQueued, isIntakeDenied } from '../../../lib/api';
import { useFrontInfo } from '../../../hooks/useFrontInfo';
import type {
    DiplomacyLettersResponse,
    DiplomacyLetter,
    DiplomacyLetterParty,
    DiplomacyLetterNation,
} from '../../../lib/types';

// 외교 빠른 명령 (quick-action) — verbatim Korean captions → P6-registered che_ command codes.
// nation-target via the modal's SelectNationField (pinnedArgType 'nation'). All four confirmed in
// CommandRegistry.kt: che_종전제의 / che_불가침제의 / che_불가침파기제의 / che_선전포고.
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

    const fetchData = useCallback(async () => {
        try {
            const res = await api.diplomacyLetters();
            setData(res);
            setError('');
        } catch {
            setError('데이터를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    useEffect(() => {
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => fetchData());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchData]);

    const myNationId = data?.myNationID ?? 0;
    // legacy 응답은 nations를 Record<id, NationStaticItem> 맵으로 내려준다 → 값 순회.
    const nations: DiplomacyLetterNation[] = data?.nations ? Object.values(data.nations) : [];
    // cancelled letters are not shown (legacy drawLetter skips state == 'cancelled').
    const letters: DiplomacyLetter[] = (data?.letters ?? []).filter(l => l.state !== 'cancelled');

    // 수뇌부 권한 체크 (permission >= 4 또는 officer_level >= 5)
    const canWrite = permission >= 4;

    // 수신국 후보 (자국·재야 제외)
    const candidateNations = nations.filter(n => n.id !== myNationId && n.id !== 0);

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
        try {
            // 인테이크 결과 가드 — 202 큐잉만 성공, 200 BLOCKED/UNKNOWN deny는 그대로 노출(P0-04/06).
            // 무조건 성공 토스트 위조 금지: typed wrapper로 IntakeOutcome을 받아 분기한다.
            const out = await api.commands.diploSendLetter({
                destNation: destNationId,
                brief: briefDraft.trim(),
                detail: detailDraft.trim(),
                prevNo,
            }, generalId);
            if (isIntakeDenied(out)) {
                showToast(out.reason ?? '외교 서신을 보내는데 실패했습니다.');
                return;
            }
            if (!isIntakeQueued(out)) {
                showToast('처리 중 오류가 발생했습니다.');
                return;
            }
            // 성공(접수) 알림 verbatim — legacy ts/diplomacy.ts submitLetter alert('전송했습니다.')
            showToast('전송했습니다.');
            setBriefDraft('');
            setDetailDraft('');
            setDestNationId(0);
            setShowWriteForm(false);
            fetchData();
        } catch (e: any) {
            // 실패 알림 verbatim — legacy `외교 서신을 보내는데 실패했습니다: ${e}`
            showToast('외교 서신을 보내는데 실패했습니다: ' + (e?.message || ''));
        }
    };

    const handleRollback = async (letterNo: number) => {
        if (!confirm('회수하시겠습니까?')) return;
        if (generalId == null) return;
        try {
            // 인테이크 결과 가드 — 무조건 성공 토스트 위조 금지(P0-04/06).
            const out = await api.commands.diploRollbackLetter({ letterNo }, generalId);
            if (isIntakeDenied(out)) {
                showToast(out.reason ?? '회수를 실패했습니다.');
                return;
            }
            if (!isIntakeQueued(out)) {
                showToast('처리 중 오류가 발생했습니다.');
                return;
            }
            showToast('회수 했습니다.');
            fetchData();
        } catch (e: any) {
            showToast('회수를 실패했습니다: ' + (e?.message || ''));
        }
    };

    const handleDestroy = async (letterNo: number) => {
        // confirm verbatim — legacy ts/diplomacy.ts destroyLetter 클릭 핸들러
        if (!confirm('본 문서를 파기하겠습니까? (상호 동의 필요)')) return;
        if (generalId == null) return;
        try {
            // 인테이크 결과 가드 — 무조건 성공 토스트 위조 금지(P0-04/06).
            // 주의: 레거시의 1단계(요청)/2단계(완료) 구분은 응답의 state 필드에 의존했으나
            // IntakeOutcome은 state를 싣지 않는다(인테이크는 비동기 큐잉 — 동기 {state} 계약 불가).
            // BE가 보내지 않는 state를 날조하지 않는다: 단일 접수 토스트만 표시하고, 갱신된
            // 서신 상태(state_opt try_destroy_*)는 fetchData() 후 카드 렌더로 드러난다.
            const out = await api.commands.diploDestroyLetter({ letterNo }, generalId);
            if (isIntakeDenied(out)) {
                showToast(out.reason ?? '파기를 실패했습니다.');
                return;
            }
            if (!isIntakeQueued(out)) {
                showToast('처리 중 오류가 발생했습니다.');
                return;
            }
            showToast('파기 요청을 접수했습니다.');
            fetchData();
        } catch (e: any) {
            // 실패 알림 verbatim — legacy destroyLetter catch는 (복붙 버그로) 회수 문구를 쓴다. PHP wins.
            showToast('회수를 실패했습니다: ' + (e?.message || ''));
        }
    };

    // 페이지 접근 권한 게이트 — legacy t_diplomacy.php:28-30
    // checkSecretPermission($me) < 1 → "권한이 부족합니다. 수뇌부가 아니거나 사관년도가 부족합니다."
    // frontInfo가 아직 로드되지 않은 경우(permission=0 기본값)도 동일하게 차단된다.
    if (permission < 1) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>외교부</h1>
                <GameCard>
                    <p style={{ color: 'var(--text-secondary)' }}>권한이 부족합니다. 수뇌부가 아니거나 사관년도가 부족합니다.</p>
                </GameCard>
            </Shell>
        );
    }

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>외교부</h1>

            <div style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                <button onClick={fetchData}>새로고침</button>
            </div>

            {/* 외교 빠른 명령 — route each through CommandModal (nation-target SelectNationField). */}
            <GameCard style={{ marginBottom: 'var(--space-md)' }}>
                <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>외교 명령</h2>
                <div style={{ display: 'flex', gap: 'var(--space-sm)', flexWrap: 'wrap' }}>
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
                <GameCard style={{ marginBottom: 'var(--space-md)' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-sm)' }}>
                        <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600 }}>외교 서신 작성</h2>
                        <button onClick={() => setShowWriteForm(!showWriteForm)}>
                            {showWriteForm ? '접기' : '펼치기'}
                        </button>
                    </div>
                    {showWriteForm && (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)' }}>
                            <div>
                                <label style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>수신국</label>
                                <select
                                    value={destNationId}
                                    onChange={(e) => setDestNationId(Number(e.target.value))}
                                    style={{ width: '100%', marginTop: 'var(--space-xs)' }}
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
                                <label style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>이전 문서</label>
                                <select
                                    value={prevNo ?? ''}
                                    onChange={(e) => {
                                        const v = e.target.value;
                                        setPrevNo(v === '' ? null : Number(v));
                                    }}
                                    style={{ width: '100%', marginTop: 'var(--space-xs)' }}
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
                                <label style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>요약</label>
                                <input
                                    type="text"
                                    value={briefDraft}
                                    onChange={(e) => setBriefDraft(e.target.value)}
                                    placeholder="요약문을 입력하세요"
                                    style={{ width: '100%', marginTop: 'var(--space-xs)' }}
                                />
                            </div>
                            <div>
                                <label style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>본문</label>
                                <textarea
                                    value={detailDraft}
                                    onChange={(e) => setDetailDraft(e.target.value)}
                                    placeholder="본문을 입력하세요"
                                    rows={4}
                                    style={{ width: '100%', marginTop: 'var(--space-xs)', resize: 'vertical' }}
                                />
                            </div>
                            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                                <button
                                    disabled={briefDraft.trim().length === 0 || destNationId === 0}
                                    onClick={handleSendLetter}
                                >
                                    발송
                                </button>
                            </div>
                        </div>
                    )}
                </GameCard>
            )}

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {toast && (
                <div className="toast" style={{ position: 'fixed', top: 'var(--space-md)', right: 'var(--space-md)', zIndex: 200 }}>
                    {toast}
                </div>
            )}

            {!loading && !error && (
                <>
                    {/* 외교 대상 국가 — candidate counter-nations (excludes self & 재야). */}
                    <GameCard style={{ marginBottom: 'var(--space-md)' }}>
                        <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>외교 대상 국가</h2>
                        {nations.length === 0 ? (
                            <p style={{ color: 'var(--text-muted)' }}>외교 대상 국가가 없습니다.</p>
                        ) : (
                            <div style={{ display: 'flex', gap: 'var(--space-sm)', flexWrap: 'wrap' }}>
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
                    <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>외교 서신</h2>
                    {letters.length === 0 ? (
                        <GameCard>
                            <p style={{ color: 'var(--text-muted)' }}>주고받은 외교 서신이 없습니다.</p>
                        </GameCard>
                    ) : (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
                            {letters.map(letter => (
                                <LetterCard
                                    key={letter.no}
                                    letter={letter}
                                    myNationId={myNationId}
                                    canWrite={canWrite}
                                    onRollback={handleRollback}
                                    onDestroy={handleDestroy}
                                />
                            ))}
                        </div>
                    )}
                </>
            )}

            {/* 외교 명령 — CommandModal pinned to a che_ diplomacy code (nation-target SelectNationField).
                The destination nation is picked in-modal; own nation excluded via nationId prop. */}
            {quickAction && generalId != null && (
                <CommandModal
                    onClose={() => setQuickAction(null)}
                    onToast={(msg) => showToast(msg)}
                    generalId={generalId}
                    nationId={ownNationId}
                    pinnedCommand={quickAction.code}
                    pinnedLabel={quickAction.label}
                    pinnedArgType="nation"
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
}: {
    letter: DiplomacyLetter;
    myNationId: number;
    canWrite: boolean;
    onRollback: (letterNo: number) => void;
    onDestroy: (letterNo: number) => void;
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
    // 파기 버튼 노출: activated 상태 && 내가 양측 중 하나 — legacy drawLetter (state=='activated' 일 때 .btnDestroy show)
    const showDestroy = canWrite && letter.state === 'activated' &&
        (letter.src.nationID === myNationId || letter.dest.nationID === myNationId);
    // 내가 이미 파기 요청한 측이면 버튼은 노출하되 비활성(disabled) — legacy:
    //   (src==my && state_opt=='try_destroy_src') || (dest==my && state_opt=='try_destroy_dest')
    const destroyDisabled =
        (letter.src.nationID === myNationId && letter.state_opt === 'try_destroy_src') ||
        (letter.dest.nationID === myNationId && letter.state_opt === 'try_destroy_dest');

    return (
        <GameCard style={{ padding: 0, overflow: 'hidden' }}>
            <div
                style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 'var(--space-sm)',
                    padding: 'var(--space-sm) var(--space-md)',
                    backgroundColor: headerBg,
                    color: headerColor,
                    flexWrap: 'wrap',
                }}
            >
                <span style={{ fontWeight: 600 }}>{counter.nationName}</span>
                <span style={{ fontSize: 'var(--text-sm)', opacity: 0.85 }}>
                    #{letter.no} · {letter.date}
                </span>
            </div>

            <div style={{ padding: 'var(--space-md)' }}>
                <div style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'center', flexWrap: 'wrap', marginBottom: 'var(--space-sm)' }}>
                    <StatusBadge variant={variant}>{stateText}</StatusBadge>
                    {stateOptText && (
                        <span style={{ fontSize: 'var(--text-sm)', color: 'var(--gold)' }}>({stateOptText})</span>
                    )}
                    {/* 이전 문서 — legacy t_diplomacy.php th '이전 문서' / value '#N' 또는 '신규' */}
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)' }}>
                        이전 문서: {letter.prev_no != null ? `#${letter.prev_no}` : '신규'}
                    </span>
                </div>

                {/* 송신 → 수신 서명인 — legacy t_diplomacy.php .letterSrc/.letterDest:
                   signerImg(generalIcon) + signerNation(nationName) + signerName(generalName).
                   미서명 수신측(generalName 부재)은 nation만 표시(legacy `'generalName' in dest` 분기). */}
                <div style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'center', flexWrap: 'wrap', marginBottom: 'var(--space-sm)', fontSize: 'var(--text-sm)' }}>
                    <Signer party={letter.src} />
                    <span style={{ color: 'var(--text-muted)' }}>→</span>
                    <Signer party={letter.dest} />
                </div>

                {/* 내용(국가 내 공개) — legacy t_diplomacy.php th, 항상 표시(평문, 개행 보존) */}
                <div style={{ marginBottom: letter.detail ? 'var(--space-sm)' : 0 }}>
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>내용(국가 내 공개)</span>
                    <p style={{ whiteSpace: 'pre-wrap', margin: 'var(--space-xs) 0 0' }}>{letter.brief}</p>
                </div>

                {/* 내용(외교권자 전용) — legacy th. detail은 permission<3 시 BE에서 '(권한이 부족합니다)'로 마스킹됨 */}
                {letter.detail && (
                    <div
                        style={{
                            borderTop: '1px solid var(--border-subtle)',
                            paddingTop: 'var(--space-sm)',
                        }}
                    >
                        <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>내용(외교권자 전용)</span>
                        <p
                            style={{
                                whiteSpace: 'pre-wrap',
                                color: 'var(--text-secondary)',
                                fontSize: 'var(--text-sm)',
                                margin: 'var(--space-xs) 0 0',
                            }}
                        >
                            {letter.detail}
                        </p>
                    </div>
                )}

                {/* 동작 버튼 — legacy t_diplomacy.php .btnRollback '회수' / .btnDestroy '파기' (정적 라벨) */}
                {(canRollback || showDestroy) && (
                    <div style={{ display: 'flex', gap: 'var(--space-sm)', marginTop: 'var(--space-sm)', justifyContent: 'flex-end' }}>
                        {canRollback && (
                            <button
                                onClick={() => onRollback(letter.no)}
                                style={{ fontSize: 'var(--text-sm)' }}
                            >
                                회수
                            </button>
                        )}
                        {showDestroy && (
                            <button
                                onClick={() => onDestroy(letter.no)}
                                disabled={destroyDisabled}
                                style={{ fontSize: 'var(--text-sm)' }}
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
        <span style={{ display: 'inline-flex', gap: 'var(--space-xs)', alignItems: 'center' }}>
            {party.generalIcon && (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                    src={party.generalIcon}
                    alt=""
                    className="generalIcon"
                    style={{ width: 20, height: 20, borderRadius: 2, objectFit: 'contain' }}
                />
            )}
            <span style={{ color: nationColor, fontWeight: 500 }}>{party.nationName}</span>
            {party.generalName && (
                <span style={{ color: nationColor }}>{party.generalName}</span>
            )}
        </span>
    );
}
