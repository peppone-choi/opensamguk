'use client';

// 회의실 · 기밀실(P-Q01) — K5 설계서 §6.1, 보드 V31K5Council · CouncilSecret · MCouncil · CouncilDenied. 19장 14 그대로, 권한 원천만 바꾼다.
// 화면은 보기 모델(lib/council-model)만 본다. 지금 원천은 옛 게시판(`/api/board` + board 명령) — #1246(`/api/council`)이 오면 어댑터만 바꾼다.
// 표결 글은 새로 만들지 않고 옛 글은 본문만(Q13d). 「작전」은 세력 작전(K6-06) 전이라 연결 없이 쓴다(Q14). 「내 자리」는 K3-02 전이라 그리지 않는다.

import { useMemo, useState, type ReactNode } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { Button, Chip, ConfirmDialog, Flag, Modal, Panel, PillTabs, Portrait, PortraitStack, ReasonTooltip, SectionHeader, Seg, StatusView, useViewportClass } from '@opensamguk/ui';
import { RichTextEditor } from '@/components/RichTextEditor';
import { SafeHtml } from '@/components/SafeHtml';
import { useCouncil } from '@/hooks/useCouncil';
import { isArticleBodyBlank } from '@/lib/articleBody';
import { useGameSession } from '@/lib/campaign-session';
import { postArticle, postComment } from '@/lib/council-api';
import { KIND_LABEL, ROOM_LABEL, type CouncilArticle, type CouncilKind, type CouncilPerson, type CouncilRoom, type CouncilView } from '@/lib/council-model';
import type { CommandSubmitResult } from '@/lib/commandSubmit';
import { COMMUNITY_HREF } from '@/lib/gatewayLinks';
import styles from './council.module.css';

const TITLE_MAX = 250;
const BODY_MAX = 65_535;
const COMMENT_MAX = 250;
const KIND_TONE: Readonly<Record<CouncilKind, 'bronze' | 'rust' | 'neutral'>> = { NOTICE: 'bronze', OPERATION: 'rust', GENERAL: 'neutral' };
const NOTICE_REASON = '공지는 기밀실 참여자만 쓸 수 있습니다';

type KindFilter = 'ALL' | CouncilKind;
type Notice = { readonly tone: 'ok' | 'err'; readonly text: string } | null;

/** 「09.30 21:10」 — 현실 시각(게임 날짜는 API 에 없다). */
export function stamp(iso: string): string {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '';
    const p = (n: number) => String(n).padStart(2, '0');
    return `${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 쓰기 결과 → 결과 띠 문장(설계서 Q25). */
export function outcomeNotice(out: CommandSubmitResult): Notice {
    if (out.status === 'applied') return { tone: 'ok', text: '등록되었습니다.' };
    if (out.status === 'reserved' || out.status === 'pending') return { tone: 'ok', text: '접수됨 — 반영 대기' };
    return { tone: 'err', text: out.reason || '등록에 실패했습니다.' };
}

function Face({ person, size }: { readonly person: CouncilPerson; readonly size: 'icon-40' | 'icon-28' | 'icon-20' }) {
    return <Portrait picture={person.picture} imageServer={person.imageServer} size={size} alt={`${person.name} 초상`} />;
}

function ReadersButton({ article }: { readonly article: CouncilArticle }) {
    const [open, setOpen] = useState(false);
    if (!article.readers) return null;
    const { read, total } = article.readers;
    return (
        <>
            <button type="button" className={`os-button os-button--ghost ${styles.readers}`} aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen(true)}>
                {`열람 ${read.length}${total !== null ? ` / ${total}` : ''}`}
                <PortraitStack>{read.slice(0, 3).map((p) => <Face key={p.generalId} person={p} size="icon-20" />)}</PortraitStack>
            </button>
            {open ? (
                <Modal ariaLabel="열람한 사람" onClose={() => setOpen(false)}>
                    <div className={styles.sheetHead}>
                        <h3 className={styles.sheetTitle}>열람한 사람</h3>
                        <button type="button" className="os-button os-button--sm" onClick={() => setOpen(false)}>닫기</button>
                    </div>
                    {read.length === 0
                        ? <StatusView kind="empty" title="아직 열람한 사람이 없습니다" body="기밀실 참여자가 글을 열면 여기에 이름이 남습니다." />
                        : <ul className={styles.names} aria-label="열람한 사람 목록">{read.map((p) => <li key={p.generalId}><Face person={p} size="icon-28" />{p.name}</li>)}</ul>}
                </Modal>
            ) : null}
        </>
    );
}

function ArticleCard({ article, secret, canWrite, generalId, onDone }: {
    readonly article: CouncilArticle; readonly secret: boolean; readonly canWrite: boolean; readonly generalId: number | null;
    readonly onDone: (out: CommandSubmitResult) => void;
}) {
    const [text, setText] = useState('');
    const [confirming, setConfirming] = useState(false);
    const [busy, setBusy] = useState(false);
    const send = async () => {
        if (generalId === null) return;
        setBusy(true);
        const out = await postComment(generalId, article.id, text.trim()).catch((e: unknown) => ({ status: 'rejected' as const, reason: e instanceof Error ? e.message : undefined }));
        setBusy(false);
        setConfirming(false);
        if (out.status !== 'rejected') setText('');
        onDone(out);
    };
    const commentReason = !canWrite ? '이 방에 글을 쓸 수 없습니다' : busy ? '등록하는 중입니다' : text.trim() === '' ? '댓글을 쓰세요' : null;
    return (
        <Panel as="article" className={`${styles.card}${secret ? ` ${styles.cardSecret}` : ''}`} aria-label={article.title}>
            <div className={styles.cardHead}>
                <Face person={article.author} size="icon-40" />
                <div className={styles.cardWho}>
                    <span className={styles.cardWhoLine}><b>{article.author.name}</b><Chip tone={KIND_TONE[article.kind]}>{KIND_LABEL[article.kind]}</Chip></span>
                    <span className={styles.time}>{stamp(article.createdAt)}</span>
                </div>
                <span className={styles.cardEnd}><ReadersButton article={article} /></span>
            </div>
            <h3 className={styles.cardTitle}>{article.title}</h3>
            <SafeHtml html={article.contentHtml} className={styles.cardBody} />
            {article.legacyVote ? <p className={styles.muted}>옛 표결 글입니다 — 표결은 보이지 않습니다.</p> : null}
            {article.comments.length > 0 ? (
                <ul className={styles.comments} aria-label="댓글">
                    {article.comments.map((c) => (
                        <li key={c.id} className={styles.comment}>
                            <Face person={c.author} size="icon-28" />
                            <span><b>{c.author.name}</b> <SafeHtml html={c.text} className={styles.commentText} /></span>
                            <span className={styles.time}>{stamp(c.createdAt)}</span>
                        </li>
                    ))}
                </ul>
            ) : null}
            <div className={styles.commentRow}>
                <input type="text" className="os-input" aria-label={`${article.title}에 댓글 달기`} placeholder={`댓글 달기 — ${COMMENT_MAX}자까지`}
                    maxLength={COMMENT_MAX} value={text} onChange={(e) => setText(e.target.value)} />
                {commentReason
                    ? <Button variant="ghost" disabled reason={commentReason}>등록</Button>
                    : <Button variant="ghost" onClick={() => setConfirming(true)}>등록</Button>}
            </div>
            <ConfirmDialog open={confirming} title="댓글" message="등록하시겠습니까?" confirmLabel={busy ? '처리 중...' : '등록'} busy={busy}
                onConfirm={() => void send()} onCancel={() => setConfirming(false)} />
        </Panel>
    );
}

function Compose({ room, canNotice, generalId, onClose, onDone }: {
    readonly room: CouncilRoom; readonly canNotice: boolean; readonly generalId: number; readonly onClose: () => void; readonly onDone: (out: CommandSubmitResult) => void;
}) {
    const [kind, setKind] = useState<CouncilKind>('GENERAL');
    const [title, setTitle] = useState('');
    const [html, setHtml] = useState('');
    const [confirming, setConfirming] = useState(false);
    const [busy, setBusy] = useState(false);
    const blank = title.trim() === '' || isArticleBodyBlank(html);
    const send = async () => {
        setBusy(true);
        const out = await postArticle(generalId, { room, kind, title: title.trim(), html }).catch((e: unknown) => ({ status: 'rejected' as const, reason: e instanceof Error ? e.message : undefined }));
        setBusy(false);
        setConfirming(false);
        onDone(out);
        // 거절이면 시트와 쓴 글을 그대로 둔다(사유는 결과 띠).
        if (out.status !== 'rejected') onClose();
    };
    return (
        <div className={styles.compose}>
            <div role="group" aria-label="종류" className={styles.kinds}>
                {(['GENERAL', 'OPERATION', 'NOTICE'] as const).map((k) => (k === 'NOTICE' && !canNotice
                    ? <ReasonTooltip key={k} reason={NOTICE_REASON}><button type="button" className="os-button" aria-pressed={false} aria-disabled="true">{KIND_LABEL[k]}</button></ReasonTooltip>
                    : <button key={k} type="button" className={`os-button${kind === k ? ` ${styles.kindOn}` : ''}`} aria-pressed={kind === k} onClick={() => setKind(k)}>{KIND_LABEL[k]}</button>))}
            </div>
            <label className={styles.field}>
                <span className={styles.fieldLabel}>제목</span>
                <input type="text" className="os-input" maxLength={TITLE_MAX} value={title} onChange={(e) => setTitle(e.target.value)} />
            </label>
            <div className={styles.field}>
                <span className={styles.fieldLabel}>본문</span>
                <RichTextEditor value={html} onChange={setHtml} maxTextLength={BODY_MAX} ariaLabel="본문" />
            </div>
            {/* 확인은 시트 안에서 — 시트(Modal) 안에 확인 창(Modal)을 겹치면 닫힐 때 배경 잠금이 풀리지 않는다. */}
            {confirming ? (
                <div className={styles.confirm} role="group" aria-label="등록 확인">
                    <span>{`${ROOM_LABEL[room]} 글쓰기 — 등록하시겠습니까?`}</span>
                    <Button variant="ghost" onClick={() => setConfirming(false)}>취소</Button>
                    {busy ? <Button variant="primary" disabled reason="처리 중입니다">처리 중...</Button>
                        : <Button variant="primary" onClick={() => void send()}>등록</Button>}
                </div>
            ) : (
                <div className={styles.composeActions}>
                    <Button variant="ghost" onClick={onClose}>그만두기</Button>
                    {blank ? <Button variant="primary" disabled reason="제목이나 내용을 쓰세요">등록</Button>
                        : <Button variant="primary" onClick={() => setConfirming(true)}>등록</Button>}
                </div>
            )}
        </div>
    );
}

function Rail({ view }: { readonly view: CouncilView }) {
    const secret = view.room === 'SECRET';
    let top: ReactNode;
    if (secret) {
        const members = view.members.filter((m) => m.inSecret);
        top = (
            <Panel className={styles.rail} aria-label="기밀실 참여">
                <SectionHeader title="기밀실 참여" sub={`${view.secretMemberCount ?? members.length}명 · 열람 기록 남음`} />
                <ul className={styles.names}>{members.map((m) => <li key={m.generalId}><Face person={m} size="icon-28" />{m.name}</li>)}</ul>
                <p className={styles.muted}>자리가 바뀌면 곧바로 볼 수 없게 되고, 이전 열람 기록은 남습니다. 주소를 직접 쳐도 들어올 수 없습니다.</p>
            </Panel>
        );
    } else {
        const active = view.members.filter((m) => m.active === true).length;
        const quiet = view.members.filter((m) => m.active === false).length;
        top = (
            <Panel className={styles.rail} aria-label="회의실 참여">
                <SectionHeader title="회의실 참여" sub="최근 순 · NPC 제외" />
                {view.members.length === 0 ? <StatusView kind="empty" title="소속 장수가 없습니다" body="같은 세력에 사람 장수가 들면 여기에 보입니다." /> : (
                    <ul className={styles.names}>
                        {view.members.map((m) => (
                            <li key={m.generalId}><Face person={m} size="icon-28" />{m.name}{m.active === null ? null : <Chip tone={m.active ? 'moss' : 'neutral'}>{m.active ? '활동' : '침묵'}</Chip>}</li>
                        ))}
                    </ul>
                )}
                <p className={styles.muted}>{`활동 ${active} · 침묵 ${quiet}`}</p>
            </Panel>
        );
    }
    return (
        <aside className={styles.railCol}>
            {top}
            <Panel className={styles.rail} aria-label="세 공간의 경계">
                <SectionHeader title="세 공간의 경계" />
                <ul className={styles.bounds}>
                    <li><b>커뮤니티</b> — 서버 밖, 모든 계정</li>
                    <li><b>회의실</b> — 게임 안, 같은 세력 장수</li>
                    <li><b>기밀실</b> — 게임 안, 기밀실 참여자만 · 열람 기록 남음</li>
                </ul>
            </Panel>
        </aside>
    );
}

export default function CouncilScreen() {
    const params = useSearchParams();
    const router = useRouter();
    const pathname = usePathname() ?? '';
    const room: CouncilRoom = params?.get('room') === 'secret' || params?.get('secret') === '1' ? 'SECRET' : 'MEETING';
    const session = useGameSession();
    const generalId = session.generalId;
    const nation = session.frontInfo?.nation ?? null;
    const { state, reload } = useCouncil(room, generalId);
    const [kind, setKind] = useState<KindFilter>('ALL');
    const [composing, setComposing] = useState(false);
    const [notice, setNotice] = useState<Notice>(null);
    const mobile = useViewportClass() === 'mobile';

    const view = state.kind === 'ready' ? state.view : null;
    const counts = useMemo(() => {
        const c: Record<KindFilter, number> = { ALL: 0, GENERAL: 0, OPERATION: 0, NOTICE: 0 };
        for (const a of view?.articles ?? []) { c.ALL += 1; c[a.kind] += 1; }
        return c;
    }, [view]);

    const setRoom = (next: CouncilRoom) => {
        const q = new URLSearchParams(params?.toString() ?? '');
        q.delete('secret');
        if (next === 'SECRET') q.set('room', 'secret'); else q.delete('room');
        setKind('ALL');
        setNotice(null);
        router.replace(`${pathname}${q.toString() ? `?${q.toString()}` : ''}`, { scroll: false });
    };
    const done = (out: CommandSubmitResult) => {
        setNotice(outcomeNotice(out));
        if (out.status === 'applied') reload();
    };

    const tabs = (
        <div className={styles.tabsRow}>
            <PillTabs<CouncilRoom> label="방" tabs={[{ key: 'MEETING', label: '회의실' }, { key: 'SECRET', label: '기밀실' }]} value={room} onChange={setRoom} />
            <span className={styles.tabsActions}>
                <Button variant="ghost" onClick={reload}>새로고침</Button>
                <a className="os-button os-button--ghost" href={COMMUNITY_HREF}>커뮤니티(서버 밖)</a>
            </span>
        </div>
    );

    let body: ReactNode;
    if (state.kind === 'loading') body = <StatusView kind="loading" rows={3} />;
    else if (state.kind === 'error') body = <StatusView kind="error" title="회의실을 불러오지 못했습니다" body={state.error.message} onRetry={reload} />;
    else if (view && view.noAffiliation) {
        body = <StatusView kind="denied" title="세력에 속하면 회의실을 쓸 수 있습니다" howTo="출사하거나 거병해 세력에 속하면 같은 세력 장수들과 글을 나눌 수 있습니다." />;
    } else if (view && !view.access.canRead) {
        body = <StatusView kind="denied" title="기밀실 참여자만 볼 수 있습니다" howTo="기밀실 참여자로 지정되면 열립니다. 지금은 글 목록 · 쓰기 · 댓글이 모두 닫혀 있습니다." />;
    } else if (view) {
        const secret = view.room === 'SECRET';
        const shown = kind === 'ALL' ? view.articles : view.articles.filter((a) => a.kind === kind);
        const writeButton = view.access.canWrite && generalId !== null
            ? <Button variant="primary" onClick={() => setComposing(true)} className={mobile ? styles.fab : undefined}>{mobile ? '글쓰기' : '새 글 쓰기'}</Button>
            : <Button variant="primary" disabled reason="이 방에 글을 쓸 수 없습니다" className={mobile ? styles.fab : undefined}>{mobile ? '글쓰기' : '새 글 쓰기'}</Button>;
        const head = (
            <div className={styles.head}>
                <div className={styles.headLine}>
                    {nation ? <Flag color={nation.color} size={14} label={`${nation.name} 깃발`} /> : null}
                    <b className={styles.headTitle}>{nation ? `${nation.name} · ${ROOM_LABEL[view.room]}` : ROOM_LABEL[view.room]}</b>
                    <Chip>{secret ? `참여 ${view.secretMemberCount ?? view.members.filter((m) => m.inSecret).length}명 · 열람 기록 남음` : `같은 세력 장수 ${view.members.length}명`}</Chip>
                    {secret ? <Chip tone="rust">참여자만</Chip> : null}
                </div>
                <div className={styles.headLine}>
                    {mobile ? null : writeButton}
                    <Seg<KindFilter> label="글 종류" options={(['ALL', 'GENERAL', 'OPERATION', 'NOTICE'] as const).map((k) => ({ value: k, label: k === 'ALL' ? '전체' : KIND_LABEL[k], count: counts[k] }))}
                        value={kind} onChange={setKind} scroll />
                </div>
                {notice ? <p className={notice.tone === 'ok' ? styles.okLine : styles.errLine} role="status">{notice.text}</p> : null}
                {state.kind === 'ready' && state.refreshError ? (
                    <p className={styles.errLine} role="status">새로 읽지 못했습니다 — 지금 보이는 글은 앞서 받은 것입니다. 잠시 뒤 새로고침하세요.</p>
                ) : null}
            </div>
        );
        const list = shown.length === 0
            ? <StatusView kind="empty" title="게시물이 없습니다" body={kind === 'ALL' ? '첫 글을 써 보세요.' : '다른 종류를 골라 보세요.'} />
            : shown.map((a) => <ArticleCard key={a.id} article={a} secret={secret} canWrite={view.access.canWrite} generalId={generalId} onDone={done} />);
        body = (
            <div className={styles.columns}>
                <div className={styles.main}>{head}{list}</div>
                <Rail view={view} />
                {mobile ? writeButton : null}
                {composing && generalId !== null ? (
                    <Modal ariaLabel="새 글 쓰기" onClose={() => setComposing(false)} overlayClassName={mobile ? styles.sheetBottom : undefined}>
                        <div className={styles.sheetHead}>
                            <h3 className={styles.sheetTitle}>{`${ROOM_LABEL[view.room]} 새 글`}</h3>
                            <button type="button" className="os-button os-button--sm" onClick={() => setComposing(false)}>닫기</button>
                        </div>
                        <Compose room={view.room} canNotice={view.access.canNotice} generalId={generalId} onClose={() => setComposing(false)} onDone={done} />
                    </Modal>
                ) : null}
            </div>
        );
    }

    return <div className={styles.screen}>{tabs}{body}</div>;
}
