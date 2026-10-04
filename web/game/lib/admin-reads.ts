// 게임 관리(P-A03) 운영자 읽기 — 계약판 K5-13 중 main 에 있는 둘.
//  - GET /api/admin/nations — 세력 개요(AdminCampaignDirectoryController · CampaignDirectoryReader.adminNations).
//    행 모양은 세력 요약(`/api/nation/summary`)과 같다(NationSummary). `stockTotal` 은 다스리는 모든 城 창고의 합이다.
//  - GET /api/admin/people — 사람 고르기(같은 reader 의 adminPeople, 모든 인물 · 커서 100명씩).
// 비운영자는 403, 비로그인은 401 이다(프록시가 sam_access 를 Bearer 로 붙인다).
// 서버가 아직 주지 않는 것(조치 쓰기 · 운영자 사건 읽기 · 외교 전체판 · 사람/NPC · 차단 · 다음 개인 턴)은 화면이 서버 대기로 그린다.

import type { DirectoryPerson, NationSummary, PeoplePage } from './directory-reads';
import { httpStatusOf } from './records-reads';

/** `READY` · `PARTIAL`(일부 행의 창고 · 병력을 못 읽음) · `UNAVAILABLE`(처리 월드 · 행정 縣 산출물 없음). */
export type AdminDirectoryStatus = 'READY' | 'PARTIAL' | 'UNAVAILABLE' | (string & {});

export interface AdminNationDirectory {
    readonly status: AdminDirectoryStatus;
    readonly nations: readonly NationSummary[];
}

/** 이어 받는 쪽 수 상한 — 5,000명. 넘으면 받은 만큼만 쓰고 알린다. */
export const ADMIN_PEOPLE_MAX_PAGES = 50;

export { ADMIN_PEOPLE_PAGE_LIMIT, adminPeoplePath } from './directory-paths';

export interface AdminPeopleLoad {
    /** `READY` · `UNAVAILABLE` · `TRUNCATED`(상한까지만 받음). */
    readonly status: 'READY' | 'TRUNCATED' | (string & {});
    readonly people: readonly DirectoryPerson[];
}

/**
 * 모든 인물을 커서로 끝까지 받는다(사람 고르기는 받은 목록 안에서 이름 · 초성으로 찾는다).
 * 받는 사이 명부가 바뀌면 서버가 409 를 준다 — 처음부터 한 번 다시 받는다. 두 번째 409 는 그대로 던진다.
 */
export async function readAllAdminPeople(
    readPage: (cursor: string | null, signal?: AbortSignal) => Promise<PeoplePage>,
    signal?: AbortSignal,
): Promise<AdminPeopleLoad> {
    for (let attempt = 0; ; attempt += 1) {
        try {
            return await readPages(readPage, signal);
        } catch (error) {
            if (attempt === 0 && httpStatusOf(error) === 409) continue;
            throw error;
        }
    }
}

async function readPages(
    readPage: (cursor: string | null, signal?: AbortSignal) => Promise<PeoplePage>,
    signal?: AbortSignal,
): Promise<AdminPeopleLoad> {
    const people: DirectoryPerson[] = [];
    let cursor: string | null = null;
    for (let page = 0; page < ADMIN_PEOPLE_MAX_PAGES; page += 1) {
        const got: PeoplePage = await readPage(cursor, signal);
        if (got.status !== 'READY') return { status: got.status, people };
        people.push(...got.people);
        cursor = got.nextCursor;
        if (cursor === null) return { status: 'READY', people };
    }
    return { status: 'TRUNCATED', people };
}
