// 옛 게시판(`GET /api/board`, BoardController) → 회의실 보기 모델. #1246(`/api/council`)이 main 에 오면 이 파일을 새 어댑터로 바꾼다.
// 옛 권한은 삼모 officer_level(5 이상 → 기밀실 · 공지)이다 — 화면은 서버가 준 결과(blockedReason · myPermission)만 따른다.
// 옛 직책 글자(officerLevelText)는 삼모 말이라 그리지 않는다(설계서 Q10 · Q20 — 새 자리 원천은 K3-02).

import type { BoardArticle, BoardComment, BoardParticipant, BoardPerson, BoardResponse } from '@/types/game';
import type { CouncilArticle, CouncilComment, CouncilKind, CouncilMember, CouncilPerson, CouncilView } from './council-model';

const person = (p: { readonly generalId: number; readonly name: string; readonly picture?: string | null; readonly imageServer?: number | null }): CouncilPerson => ({
    generalId: p.generalId, name: p.name, picture: p.picture ?? null, imageServer: p.imageServer ?? null,
});

function kindOf(article: BoardArticle): CouncilKind {
    if (article.kind === 'operation') return 'OPERATION';
    if (article.kind === 'notice') return 'NOTICE';
    return 'GENERAL';
}

function comment(c: BoardComment): CouncilComment {
    return { id: c.id, author: person({ generalId: c.authorGeneralId, name: c.authorName, picture: c.authorPicture, imageServer: c.authorImageServer }), text: c.text, createdAt: c.date };
}

function article(a: BoardArticle): CouncilArticle {
    return {
        id: a.id,
        kind: kindOf(a),
        title: a.title,
        contentHtml: a.contentHtml,
        author: person({ generalId: a.authorGeneralId, name: a.authorName, picture: a.authorPicture, imageServer: a.authorImageServer }),
        createdAt: a.date,
        operationId: a.operationId ?? null,
        readers: a.readers ? { read: a.readers.read.map((p: BoardPerson) => person(p)), total: a.readers.total } : null,
        comments: a.comments.map(comment),
        legacyVote: a.kind === 'vote',
    };
}

function member(p: BoardParticipant): CouncilMember {
    return { ...person(p), active: p.active, inSecret: p.chief ?? null };
}

export function councilFromBoard(response: BoardResponse): CouncilView {
    const blocked = response.blockedReason ?? null;
    const myGeneralId = response.myGeneralId ?? null;
    return {
        room: response.secret ? 'SECRET' : 'MEETING',
        // 재야 호출은 서버가 myPermission -1 + 「소속 세력이 없어 …」(BoardController.kt:72-84).
        noAffiliation: response.myPermission === -1,
        access: {
            canRead: blocked === null,
            canWrite: blocked === null && myGeneralId !== null && myGeneralId !== 0,
            canNotice: (response.myPermission ?? 0) >= 2,
            reason: blocked,
        },
        members: (response.participants ?? []).map(member),
        secretMemberCount: response.chiefCount ?? null,
        articles: response.articles.map(article),
        myGeneralId,
    };
}
