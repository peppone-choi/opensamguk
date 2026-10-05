// 지방 관직 읽기(K8-03) — 서버(#1406) 응답 5종을 클라이언트 검증 · 보기 모델이 같은 뜻으로 읽는지 본다. 서버가 아직 행을 내지
// 않으므로 행 모양은 계약 문서 예시(docs/development/fixtures/court-local-offices.json)에 ACK 의 새 키(reason · 한글 이름 칸)를 얹어 본다.
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ fetchGame: vi.fn<(path: string, init?: RequestInit) => Promise<Response>>() }));
vi.mock('@/lib/api', () => ({ fetchGame: mocks.fetchGame }));

import { OFFICE_EVIDENCE, parseCourtLocalOffices, readCourtLocalOffices } from '@/lib/api/court-local-offices';
import { EVIDENCE_TEXT, OFFICE_LABEL, evidenceRows, localOfficesView, officeLabel } from '@/lib/court-local-offices-view';

const ROOT = resolve(__dirname, '../../..');
const SERVER = resolve(ROOT, 'app/game-api/src/test/resources/court/local-offices');
type Rec = Record<string, unknown>;
/**
 * 서버 고정 응답 5종(#1406 app/game-api/src/test/resources/court/local-offices/*.json)과 같은 본문. 이 클라이언트를 서버보다 먼저
 * 병합하므로(#1406 리뷰 — 서버가 먼저 나가면 탭이 오류로 바뀐다) 지금은 인라인으로 두고, 서버 파일이 생기면 아래 표류 검사가 둘이
 * 같은지 본다.
 */
const NOW = { year: 201, month: 4, phase: 3 };
const SERVER_BODIES: Readonly<Record<string, Rec>> = {
    'blocked.json': { status: 'UNAVAILABLE', reason: 'JURISDICTION_SNAPSHOT_UNAVAILABLE', now: NOW, localOffices: null, appointmentOptions: null, pendingOffers: null },
    'boundary.json': { status: 'UNAVAILABLE', reason: 'WORLD_UNAVAILABLE', now: null, localOffices: null, appointmentOptions: null, pendingOffers: null },
    'not-seeded.json': { status: 'NOT_SEEDED', reason: 'TENURES_NOT_SEEDED', now: NOW, localOffices: null, appointmentOptions: null, pendingOffers: null },
    'ready.json': { status: 'READY', reason: null, now: NOW, localOffices: [], appointmentOptions: null, pendingOffers: null },
    'unavailable.json': { status: 'UNAVAILABLE', reason: 'TENURES_INVALID', now: NOW, localOffices: null, appointmentOptions: null, pendingOffers: null },
};
const server = (name: string): Rec => structuredClone(SERVER_BODIES[name]) as Rec;
/** 행 모양 예시 — 계약 문서 고정 응답에 ACK 키를 얹는다(이름은 서버 원천이 없으면 null). */
function rows(names: { jurisdictionName?: string | null; seatCountyName?: string | null; officeLabel?: string | null } = {}): Rec {
    const body = JSON.parse(readFileSync(resolve(ROOT, 'docs/development/fixtures/court-local-offices.json'), 'utf8')) as Rec;
    body.reason = null;
    for (const t of body.localOffices as Rec[]) {
        t.officeLabel = names.officeLabel ?? null;
        t.jurisdictionName = names.jurisdictionName ?? null;
        t.seatCountyName = names.seatCountyName ?? null;
    }
    return body;
}
const respond = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
const at = (body: Rec, key: string, i = 0) => (body[key] as Rec[])[i];

async function read(body: unknown, status = 200) {
    mocks.fetchGame.mockImplementationOnce(() => respond(body, status));
    return readCourtLocalOffices(7);
}

beforeEach(() => mocks.fetchGame.mockReset());

describe('readCourtLocalOffices — 서버 응답 5종', () => {
    const names = Object.keys(SERVER_BODIES).sort();
    // 서버 고정 응답 파일이 main 에 들어오면(#1406) 켜진다 — 인라인 본문과 한 글자도 다르지 않아야 한다(표류 검사).
    it.runIf(existsSync(SERVER))('서버 고정 응답 파일 = 인라인 본문(#1406 병합 뒤 표류 검사)', () => {
        expect(readdirSync(SERVER).filter((n) => n.endsWith('.json')).sort()).toEqual(names);
        for (const name of names) expect(JSON.parse(readFileSync(resolve(SERVER, name), 'utf8')), name).toEqual(SERVER_BODIES[name]);
    });
    it.each(names)('%s 를 받는다', async (name) => {
        expect((await read(server(name))).ok).toBe(true);
    });
    it('주소 · no-store', async () => {
        await read(server('ready.json'));
        expect(mocks.fetchGame).toHaveBeenCalledWith('/api/court/local-offices?generalId=7', expect.objectContaining({ cache: 'no-store' }));
    });
    it('401 · 403 · 404 · 500 은 실패로 돌리고 상태를 넘긴다(404 를 서버 대기로 그리는 것은 훅)', async () => {
        for (const status of [401, 403, 404, 500]) expect(await read({ error: { code: 'X', message: 'x' } }, status)).toEqual({ ok: false, httpStatus: status });
    });
});

describe('localOfficesView — 서버 상태를 화면 상태로', () => {
    const view = (name: string) => localOfficesView(parseCourtLocalOffices(server(name))!, () => null);
    it('READY [] 는 확인된 빈 결과, NOT_SEEDED 는 원천 없음, 관할 미연결은 snapshot, 그 밖의 UNAVAILABLE 은 이유째', () => {
        expect(view('ready.json')).toEqual({ kind: 'empty', options: [], offers: [] });
        expect(view('not-seeded.json')).toEqual({ kind: 'not-seeded' });
        expect(view('blocked.json')).toEqual({ kind: 'snapshot' });
        expect(view('unavailable.json')).toEqual({ kind: 'unavailable', reason: 'TENURES_INVALID' });
        expect(view('boundary.json')).toEqual({ kind: 'unavailable', reason: 'WORLD_UNAVAILABLE' });
        expect(parseCourtLocalOffices(server('boundary.json'))!.now).toBeNull();
    });
});

describe('parseCourtLocalOffices — 계약 밖 모양은 실패(fail closed)', () => {
    const broken = (base: Rec, edit: (body: Rec) => void) => {
        edit(base);
        return parseCourtLocalOffices(base);
    };
    it('대조: 고친 데 없는 서버 고정 응답 · 행 예시는 받는다', () => {
        expect(broken(server('ready.json'), () => {})).not.toBeNull();
        expect(broken(rows(), () => {})).not.toBeNull();
    });
    it('root 키(reason · now · 목록 셋)가 빠지면 실패 — null 로 채우지 않는다', () => {
        for (const key of ['reason', 'now', 'localOffices', 'appointmentOptions', 'pendingOffers']) {
            expect(broken(server('not-seeded.json'), (b) => { delete b[key]; }), key).toBeNull();
        }
    });
    it('모르는 상태 · 모르는 이유는 실패', () => {
        expect(broken(server('ready.json'), (b) => { b.status = 'PARTIAL'; })).toBeNull();
        expect(broken(server('unavailable.json'), (b) => { b.reason = 'SOMETHING_NEW'; })).toBeNull();
    });
    it('READY 는 이유가 없고 재임은 배열, 원천 없음 · 셈 못 함은 이유가 있고 목록이 모두 null', () => {
        expect(broken(server('ready.json'), (b) => { b.reason = 'TENURES_INVALID'; })).toBeNull();
        expect(broken(server('ready.json'), (b) => { b.localOffices = null; })).toBeNull();
        expect(broken(server('not-seeded.json'), (b) => { b.localOffices = []; })).toBeNull();
        expect(broken(server('unavailable.json'), (b) => { b.pendingOffers = []; })).toBeNull();
        expect(broken(server('unavailable.json'), (b) => { b.reason = null; })).toBeNull();
        expect(broken(server('not-seeded.json'), (b) => { b.reason = 'WORLD_UNAVAILABLE'; })).toBeNull();
        expect(broken(server('unavailable.json'), (b) => { b.reason = 'TENURES_NOT_SEEDED'; })).toBeNull();
    });
    it('READY 의 선택지 · 보낸 제안은 null(원천 없음)을 받는다 — 빈 배열과 구분', () => {
        const parsed = parseCourtLocalOffices(server('ready.json'))!;
        expect([parsed.appointmentOptions, parsed.pendingOffers]).toEqual([null, null]);
    });
    it('행의 한글 이름 칸은 키가 있어야 하고 글자 또는 null', () => {
        expect(broken(rows(), (b) => { delete at(b, 'localOffices').jurisdictionName; })).toBeNull();
        expect(broken(rows(), (b) => { at(b, 'localOffices').seatCountyName = 3; })).toBeNull();
        expect(broken(rows(), (b) => { at(b, 'localOffices').officeLabel = ''; })).toBeNull();
    });
    it('모르는 상태 · 근거 코드, 실효 아닌 자리의 실효 현, 정규 ID 아닌 관할, 막힌 이유 없는 선택지는 실패', () => {
        expect(broken(rows(), (b) => { at(b, 'localOffices').state = 'VACANT'; })).toBeNull();
        expect(broken(rows(), (b) => { at(b, 'localOffices', 1).missing = ['SEAT_LOST']; })).toBeNull();
        expect(broken(rows(), (b) => { at(b, 'localOffices', 1).actualCountyIds = [200]; })).toBeNull();
        expect(broken(rows(), (b) => { at(b, 'localOffices').jurisdictionId = '109'; })).toBeNull();
        expect(broken(rows(), (b) => { delete at(b, 'appointmentOptions').blocked; })).toBeNull();
        expect(broken(rows(), (b) => { at(b, 'pendingOffers').status = 'EXPIRED'; })).toBeNull();
    });
});

describe('이름표 · 실효 근거 — 사료 목록 · 서버 enum 과 표류 검사', () => {
    it('관직 이름표 키 = 사료 목록 data/curated/han/local-offices.json 의 id 전부', () => {
        const catalog = JSON.parse(readFileSync(resolve(ROOT, 'data/curated/han/local-offices.json'), 'utf8')) as { rows: { id: string }[] };
        expect(Object.keys(OFFICE_LABEL).sort()).toEqual(catalog.rows.map((r) => r.id).sort());
    });
    it('서버 officeLabel 이 먼저, 없으면 이름표, 목록에도 없으면 서버 사료 표기', () => {
        expect(officeLabel('office.commandery-prefect', '군 태수', '太守')).toBe('군 태수');
        expect(officeLabel('office.commandery-prefect', null, '太守')).toBe('태수');
        expect(officeLabel('office.sili-colonel', null, '司隸校尉')).toBe('司隸校尉');
    });
    it('실효 근거 글자 = 서버 OfficeEvidence enum(logic) 순서 그대로', () => {
        const kt = readFileSync(resolve(ROOT, 'logic/src/main/kotlin/opensamguk/logic/office/OfficeCapabilityResolver.kt'), 'utf8');
        const body = /enum class OfficeEvidence \{([^}]*)\}/.exec(kt)?.[1] ?? '';
        const enumNames = body.split(',').map((s) => s.trim()).filter(Boolean);
        expect(enumNames.length).toBeGreaterThan(0);
        expect(OFFICE_EVIDENCE).toEqual(enumNames);
        expect(EVIDENCE_TEXT.map(([code]) => code)).toEqual(enumNames);
    });
});

describe('localOfficesView — 행(서버가 행을 낼 때)', () => {
    it('관할 이름은 서버 jurisdictionName, 없으면 「어느 군국 · 어느 주」 — 지도로 짐작하지 않는다', () => {
        const unnamed = localOfficesView(parseCourtLocalOffices(rows())!, () => '지도 이름');
        expect(unnamed.kind === 'offices' && unnamed.rows.map((r) => [r.depth, r.jurisdiction, r.office, r.chip.label, r.effective])).toEqual([
            [0, '어느 주', '자사', '명목', '0곳'],
            [1, '어느 군국', '태수', '실권 있음', '2곳'],
        ]);
        const named = localOfficesView(parseCourtLocalOffices(rows({ jurisdictionName: '경조윤', seatCountyName: '장안현' }))!, () => null);
        expect(named.kind === 'offices' && named.rows[1]).toMatchObject({ jurisdiction: '경조윤', seat: '치소 장안현' });
    });
    it('치소 현은 서버 seatCountyName, 없으면 같은 현 id 의 지도 이름, 둘 다 없으면 치소 표시 없음', () => {
        const byMap = localOfficesView(parseCourtLocalOffices(rows())!, (id) => (id === 100 ? '경조윤 장안현' : null));
        expect(byMap.kind === 'offices' && byMap.rows.map((r) => r.seat)).toEqual([null, '치소 경조윤 장안현']);
    });
    it('선택지 · 보낸 제안의 이름은 같은 응답 안에서만 — 못 찾으면 보낸 제안은 null(서버 대기)', () => {
        const view = localOfficesView(parseCourtLocalOffices(rows({ jurisdictionName: '경조윤' }))!, () => null);
        if (view.kind !== 'offices') throw new Error('offices');
        expect(view.options[0]).toMatchObject({ jurisdiction: '경조윤', office: '태수', candidate: '장수 예시 3', available: false });
        expect(view.offers[0]).toMatchObject({ candidate: null, jurisdiction: null, office: '태수', due: '196년 1월 하순' });
        expect(view.counts).toEqual({ pending: 0, nominal: 1, awaiting: 0 });
    });
    it('실효 판정 줄 — missing 이면 부족함', () => {
        const parsed = parseCourtLocalOffices(rows())!;
        const nominal = parsed.localOffices!.find((t) => t.state === 'NOMINAL')!;
        expect(evidenceRows(nominal).filter((r) => !r.ok).map((r) => r.text)).toEqual(['치소 현을 가졌다']);
    });
});
