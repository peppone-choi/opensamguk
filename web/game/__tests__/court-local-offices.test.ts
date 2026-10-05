// 지방 관직 읽기(K8-03) — 계약 고정 응답(docs/development/fixtures/court-local-offices.json)을 그대로 읽어 클라이언트 검증 ·
// 보기 모델이 같은 뜻으로 읽는지 본다(표류 검사). 관직 이름표는 사료 목록과, 실효 근거 글자는 서버 enum 과 맞는지도 본다.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ fetchGame: vi.fn<(path: string, init?: RequestInit) => Promise<Response>>() }));
vi.mock('@/lib/api', () => ({ fetchGame: mocks.fetchGame }));

import { OFFICE_EVIDENCE, parseCourtLocalOffices, readCourtLocalOffices } from '@/lib/api/court-local-offices';
import { EVIDENCE_TEXT, OFFICE_LABEL, evidenceRows, localOfficesView, officeLabel, type CountyPlace } from '@/lib/court-local-offices-view';

const ROOT = resolve(__dirname, '../../..');
const FIXTURE = resolve(ROOT, 'docs/development/fixtures/court-local-offices.json');
const fixture = () => JSON.parse(readFileSync(FIXTURE, 'utf8')) as Record<string, unknown>;
const respond = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
type Rec = Record<string, unknown>;
const at = (body: Rec, key: string, i = 0) => (body[key] as Rec[])[i];

async function read(body: unknown, status = 200) {
    mocks.fetchGame.mockImplementationOnce(() => respond(body, status));
    return readCourtLocalOffices(7);
}

beforeEach(() => mocks.fetchGame.mockReset());

describe('readCourtLocalOffices — 계약 고정 응답', () => {
    it('고정 응답을 받는다 — 주소 · no-store', async () => {
        const r = await read(fixture());
        expect(r.ok).toBe(true);
        expect(mocks.fetchGame).toHaveBeenCalledWith('/api/court/local-offices?generalId=7', expect.objectContaining({ cache: 'no-store' }));
        if (!r.ok) return;
        expect(r.offices.localOffices.map((t) => [t.officeId, t.state, t.actualCountyIds.length, t.missing])).toEqual([
            ['office.commandery-prefect', 'EFFECTIVE', 2, []],
            ['office.provincial-inspector', 'NOMINAL', 0, ['SEAT_OWNED']],
        ]);
        expect(r.offices.appointmentOptions[0]).toMatchObject({ available: false, blocked: { code: 'OFFICE_OCCUPIED' } });
        expect(r.offices.pendingOffers[0]).toMatchObject({ offerId: 'offer-1', status: 'PENDING', dueAt: { year: 196, month: 1, phase: 3 } });
    });

    it('401 · 403 · 404 · 500 은 실패로 돌리고 상태를 넘긴다(404 를 서버 대기로 그리는 것은 훅)', async () => {
        for (const status of [401, 403, 404, 500]) expect(await read({ error: { code: 'X', message: 'x' } }, status)).toEqual({ ok: false, httpStatus: status });
    });
});

describe('parseCourtLocalOffices — 계약 밖 모양은 실패(fail closed)', () => {
    const broken = (edit: (body: Rec) => void) => {
        const body = fixture();
        edit(body);
        return parseCourtLocalOffices(body);
    };

    it('대조: 고친 데 없는 고정 응답은 받는다', () => {
        expect(broken(() => {})).not.toBeNull();
    });
    it('now 키가 없으면 실패(null 로 채우지 않는다), now:null 은 받는다', () => {
        expect(broken((b) => { delete b.now; })).toBeNull();
        expect(broken((b) => { b.now = null; })).not.toBeNull();
    });
    it('모르는 상태 · 모르는 실효 근거 코드는 실패', () => {
        expect(broken((b) => { at(b, 'localOffices').state = 'VACANT'; })).toBeNull();
        expect(broken((b) => { at(b, 'localOffices', 1).missing = ['SEAT_LOST']; })).toBeNull();
        expect(broken((b) => { at(b, 'pendingOffers').status = 'EXPIRED'; })).toBeNull();
    });
    it('실효 현은 실효일 때만 — 명목 자리에 실효 현이 오면 실패', () => {
        expect(broken((b) => { at(b, 'localOffices', 1).actualCountyIds = [200]; })).toBeNull();
    });
    it('관할은 행정 축 정규 ID 만 — 내정 commanderyId 문자열이면 실패', () => {
        expect(broken((b) => { at(b, 'localOffices').jurisdictionId = '109'; })).toBeNull();
    });
    it('막힌 선택지는 blocked{code,reason} 가 있어야 하고, 열린 선택지는 blocked 가 없거나 null', () => {
        expect(broken((b) => { delete at(b, 'appointmentOptions').blocked; })).toBeNull();
        expect(broken((b) => { const o = at(b, 'appointmentOptions'); o.available = true; })).toBeNull();
        expect(broken((b) => { const o = at(b, 'appointmentOptions'); o.available = true; o.blocked = null; })).not.toBeNull();
    });
    it('읽지 못함(UNAVAILABLE)은 관직 0개가 아니다 — 값을 함께 싣고 오면 실패, 빈 목록이면 받는다', () => {
        expect(broken((b) => { b.status = 'UNAVAILABLE'; })).toBeNull();
        expect(broken((b) => { b.status = 'UNAVAILABLE'; b.localOffices = []; b.appointmentOptions = []; b.pendingOffers = []; })).not.toBeNull();
    });
});

describe('이름표 · 실효 근거 — 사료 목록 · 서버 enum 과 표류 검사', () => {
    it('관직 이름표 키 = 사료 목록 data/curated/han/local-offices.json 의 id 전부', () => {
        const catalog = JSON.parse(readFileSync(resolve(ROOT, 'data/curated/han/local-offices.json'), 'utf8')) as { rows: { id: string }[] };
        expect(Object.keys(OFFICE_LABEL).sort()).toEqual(catalog.rows.map((r) => r.id).sort());
    });
    it('목록에 없는 관직은 서버 이름을 그대로 쓴다(짓지 않는다)', () => {
        expect(officeLabel('office.commandery-prefect', '太守')).toBe('태수');
        expect(officeLabel('office.sili-colonel', '司隸校尉')).toBe('司隸校尉');
    });
    it('실효 근거 글자 = 서버 OfficeEvidence enum(logic) 순서 그대로', () => {
        const kt = readFileSync(resolve(ROOT, 'logic/src/main/kotlin/opensamguk/logic/office/OfficeCapabilityResolver.kt'), 'utf8');
        const body = /enum class OfficeEvidence \{([^}]*)\}/.exec(kt)?.[1] ?? '';
        const server = body.split(',').map((s) => s.trim()).filter(Boolean);
        expect(server.length).toBeGreaterThan(0);
        expect(OFFICE_EVIDENCE).toEqual(server);
        expect(EVIDENCE_TEXT.map(([code]) => code)).toEqual(server);
    });
});

describe('localOfficesView — 고정 응답을 화면 줄로', () => {
    const places = new Map<number, CountyPlace>([
        [100, { name: '장안현', commandery: '경조윤', region: '사례' }],
        [200, { name: '초현', commandery: '패국', region: '예주' }],
    ]);
    const place = (id: number) => places.get(id) ?? null;

    it('주 → 군국 순, 관할은 치소 현의 지도 이름, 관직은 읽은 이름, 실효 현 · 칩', () => {
        const parsed = parseCourtLocalOffices(fixture())!;
        const view = localOfficesView(parsed, place);
        expect(view.kind).toBe('offices');
        if (view.kind !== 'offices') return;
        expect(view.rows.map((r) => [r.depth, r.jurisdiction, r.seat, r.office, r.holder, r.chip.label, r.effective])).toEqual([
            [1, '경조윤', '치소 장안현', '태수', '장수 예시', '실권 있음', '2곳'],
            [0, '예주', '치소 초현', '자사', '장수 예시 2', '명목', '0곳'],
        ]);
        expect(view.counts).toEqual({ pending: 0, nominal: 1, awaiting: 0 });
        expect(view.options).toEqual([expect.objectContaining({ jurisdiction: '경조윤', office: '태수', candidate: '장수 예시 3', available: false, reason: '이미 해당 관할에 재임자가 있습니다.' })]);
    });

    it('보낸 제안의 후보 · 관할 이름은 같은 응답에서 찾고, 못 찾으면 null(서버 대기) — 사료 ID 꼬리를 쓰지 않는다', () => {
        const parsed = parseCourtLocalOffices(fixture())!;
        const view = localOfficesView(parsed, place);
        expect(view.kind === 'offices' && view.offers[0]).toMatchObject({ candidate: null, jurisdiction: null, office: '태수', due: '196년 1월 하순' });
        // 같은 관할 · 후보가 응답 안에 있으면 그 이름을 쓴다.
        const body = fixture();
        const offer = at(body, 'pendingOffers');
        offer.jurisdictionId = 'hhs-group:109:京兆尹';
        offer.candidateId = 4;
        const linked = localOfficesView(parseCourtLocalOffices(body)!, place);
        expect(linked.kind === 'offices' && linked.offers[0]).toMatchObject({ candidate: '장수 예시 3', jurisdiction: '경조윤' });
    });

    it('지도 이름을 못 받으면 「어느 군국 · 어느 주」, 치소 표시는 없다', () => {
        const view = localOfficesView(parseCourtLocalOffices(fixture())!, () => null);
        expect(view.kind === 'offices' && view.rows.map((r) => [r.jurisdiction, r.seat])).toEqual([['어느 군국', null], ['어느 주', null]]);
    });

    it('UNAVAILABLE 은 「셈 못 함」, 재임이 없으면 빈 상태', () => {
        const none = { status: 'UNAVAILABLE', now: null, localOffices: [], appointmentOptions: [], pendingOffers: [] };
        expect(localOfficesView(parseCourtLocalOffices(none)!, place)).toEqual({ kind: 'unavailable' });
        const empty = { ...none, status: 'READY' };
        expect(localOfficesView(parseCourtLocalOffices(empty)!, place).kind).toBe('empty');
    });

    it('실효 판정 줄 — missing 이면 부족함', () => {
        const parsed = parseCourtLocalOffices(fixture())!;
        const rows = evidenceRows(parsed.localOffices[1]);
        expect(rows.filter((r) => !r.ok).map((r) => r.text)).toEqual(['치소 현을 가졌다']);
        expect(rows).toHaveLength(8);
    });
});
