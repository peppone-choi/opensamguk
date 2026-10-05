// 회의실 · 기밀실(P-Q01) 읽기 · 쓰기 — 화면 부품은 이 함수들만 부른다(직접 fetch 하지 않는다).
// 지금 원천: 읽기 `GET /api/board?secret=`, 쓰기 공통 인테이크 명령 boardArticle · boardComment · boardRead(휘하에서도 받는다 —
// CommandReserveService 공통 인테이크 예외). #1246(`/api/council`)이 오면 이 파일의 원천만 바꾼다.

import { api } from './api';
import { councilFromBoard } from './council-board-adapter';
import type { CouncilKind, CouncilRoom, CouncilView } from './council-model';
import { submitCommandAndAwaitResult, type CommandSubmitResult } from './commandSubmit';

export async function readCouncil(room: CouncilRoom): Promise<CouncilView> {
    return councilFromBoard(await api.board(room === 'SECRET'));
}

const BOARD_KIND: Readonly<Record<CouncilKind, string>> = { GENERAL: 'general', OPERATION: 'operation', NOTICE: 'notice' };

export interface ArticleDraft {
    readonly room: CouncilRoom;
    readonly kind: CouncilKind;
    readonly title: string;
    readonly html: string;
}

/** 글 쓰기 — 엔진이 권한을 다시 본다(거절은 서버 사유 그대로). */
export function postArticle(generalId: number, draft: ArticleDraft): Promise<CommandSubmitResult> {
    return submitCommandAndAwaitResult(() => api.command('boardArticle', {
        isSecret: draft.room === 'SECRET', title: draft.title, text: draft.html, kind: BOARD_KIND[draft.kind],
    }, generalId));
}

export function postComment(generalId: number, articleId: number, text: string): Promise<CommandSubmitResult> {
    return submitCommandAndAwaitResult(() => api.command('boardComment', { articleNo: articleId, text }, generalId));
}

/** 기밀실 열람 기록 — 글을 연 사람만(세션당 한 번). */
export function markRead(generalId: number, articleId: number): Promise<CommandSubmitResult> {
    return submitCommandAndAwaitResult(() => api.command('boardRead', { articleNo: articleId }, generalId));
}
