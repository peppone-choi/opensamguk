// 장수 만들기 고정 자료(fixture) — 서버 #1137(draft, head 862c6cc1f) `GeneralCreationDto.kt` 모양 그대로.
// 단위 시험 · 스모크가 쓴다. 화면 코드는 이 파일을 들여오지 않는다(서버가 오면 연결만 그대로 쓴다).
// 값은 예시다 — 계약에 없는 칸(역할 · 주인 · 본관 등)은 넣지 않는다.

import type { CreationAccepted, CreationResult, GeneralCreationOptions, HistoricalCreationPage, HistoricalCreationPerson } from './creation-contract';

export const FIXTURE_WORLD_ID = 1;

const person = (id: number, name: string, nationId: number | null, over: Partial<HistoricalCreationPerson> = {}): HistoricalCreationPerson => ({
    historicalGeneralId: id,
    name,
    nameCh: null,
    portrait: null,
    stats: { leadership: 70, strength: 65, intel: 60, politics: 55, charm: 50 },
    nationId,
    appeared: true,
    available: true,
    unavailableCode: null,
    ...over,
});

export const HISTORICAL_PAGE_1: HistoricalCreationPage = {
    schemaVersion: 1,
    worldId: FIXTURE_WORLD_ID,
    people: [
        person(101, '하후돈', 1, { stats: { leadership: 90, strength: 89, intel: 58, politics: 70, charm: 80 } }),
        person(102, '순욱', 1, { available: false, unavailableCode: 'HISTORICAL_PERSON_UNAVAILABLE' }),
        person(103, '허저', null, { stats: { leadership: 65, strength: 96, intel: 36, politics: 20, charm: 59 } }),
    ],
    nextCursor: '103',
};

export const HISTORICAL_PAGE_2: HistoricalCreationPage = {
    schemaVersion: 1,
    worldId: FIXTURE_WORLD_ID,
    people: [person(104, '제갈량', null, { appeared: false, available: false, unavailableCode: 'HISTORICAL_PERSON_NOT_APPEARED' })],
    nextCursor: null,
};

export const ACCEPTED: CreationAccepted = { schemaVersion: 1, status: 'ACCEPTED', requestId: 'req-1', worldId: FIXTURE_WORLD_ID };

export const RESULT_PENDING: CreationResult = { schemaVersion: 1, requestId: 'req-1', worldId: FIXTURE_WORLD_ID, status: 'PENDING', generalId: null, error: null };
export const RESULT_CREATED: CreationResult = { schemaVersion: 1, requestId: 'req-1', worldId: FIXTURE_WORLD_ID, status: 'CREATED', generalId: 501, error: null };
export const RESULT_REJECTED: CreationResult = {
    schemaVersion: 1, requestId: 'req-1', worldId: FIXTURE_WORLD_ID, status: 'REJECTED', generalId: null,
    error: { code: 'HISTORICAL_PERSON_UNAVAILABLE', message: '이 인물은 지금 선택할 수 없습니다. 목록을 다시 읽어 주세요.' },
};

/** 지도 미리보기 세력표(이름 · 색) — 후보는 세력 id 만 준다. */
export const MAP_NATIONS = [{ id: 1, name: '조조', color: '#4f7fbf' }, { id: 2, name: '원소', color: '#b05a4a' }];

/** 생성 옵션(K5-02) — 본관 현 넷(셋은 고를 수 있음, 하나는 지도 밖이라 불가). */
export const OPTIONS: GeneralCreationOptions = {
    schemaVersion: 1,
    worldId: FIXTURE_WORLD_ID,
    statRule: { min: 20, max: 85, total: 300 },
    nameRule: {
        minimumCodePoints: 1, maximumCodePoints: 12, normalization: 'NFC_TRIM',
        allowedCharacters: 'HANGUL_HAN_LATIN_LETTERS_INTERNAL_SINGLE_SPACE_OR_MIDDLE_DOT', uniquenessScope: 'WORLD_NFC_ROOT_CASEFOLD',
    },
    policy: { customAllowed: true, historicalAllowed: true, reason: null },
    modes: [{ kind: 'CUSTOM', allowed: true, reason: null }, { kind: 'HISTORICAL', allowed: true, reason: null }],
    ideologies: [{ id: 'kingly', label: '왕도' }, { id: 'hegemon', label: '패도' }],
    traits: [{ id: 'discipline', label: '규율' }, { id: 'naval', label: '수전' }],
    nativeCounties: [
        { cityId: 11, name: '허현', commanderyId: 'yingchuan', commanderyName: '영천군', provinceName: '예주', cellCol: 120, cellRow: 80, available: true, reason: null },
        { cityId: 12, name: '장사현', commanderyId: 'yingchuan', commanderyName: '영천군', provinceName: '예주', cellCol: 118, cellRow: 76, available: true, reason: null },
        { cityId: 21, name: '업현', commanderyId: 'wei', commanderyName: '위군', provinceName: '기주', cellCol: 130, cellRow: 40, available: true, reason: null },
        { cityId: 99, name: '마피영', commanderyId: 'yingchuan', commanderyName: '영천군', provinceName: '예주', cellCol: null, cellRow: null, available: false, reason: 'INVALID_NATIVE_COUNTY' },
    ],
};

