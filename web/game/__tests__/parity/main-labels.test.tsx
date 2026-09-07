// S2 대조표(ADR-LITE-049 · docs/design/ui-redesign-2026-09/src/Parity.body.html) — 현행 화면의 라벨 전부가
// 작전실 한 화면에 렌더된다. 하나라도 빠지면 빨갛다. 라벨 문자열은 현행 컴포넌트의 것을 그대로 쓴다.
import { render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import GameChrome from '@/components/game/GameChrome';
import { FRONT_INFO_FULL } from '../fixtures/front-info.full';

const mocks = vi.hoisted(() => ({ useFrontInfo: vi.fn(), reservedCommands: vi.fn(), mailbox: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn() }), usePathname: () => '/game/s1' }));
vi.mock('@/hooks/useFrontInfo', () => ({ useFrontInfo: mocks.useFrontInfo }));
vi.mock('@/lib/api', () => ({
    api: {
        reservedCommands: mocks.reservedCommands,
        mailbox: mocks.mailbox,
        commandQueue: { push: vi.fn(), repeat: vi.fn() },
        commands: { sendMessage: vi.fn() },
    },
    pollCommandResult: vi.fn(),
}));
vi.mock('@/components/game/MapViewer', () => ({ default: () => <div data-testid="map" /> }));
vi.mock('@/components/CommandModal', () => ({ default: () => null }));
vi.mock('@/components/Toast', () => ({ default: () => null }));

const GAME_INFO_13 = [
    /황건적의 난/, /NPC 90명/, 'NPC선택: 선택 생성', '토너먼트: 경기당 3분', '기타 설정: 상성 사실',
    '현재: 197年 5月 중순 (120분 턴 서버)', '전체 접속자 수: 12명', '턴당 갱신횟수: 20회', /등록 장수: 유저 30 \/ 120/,
    /전력전/, '동작 시각: 12:40:03', '2건 거래 진행중', /설문 진행 중:/,
];
const STATUS_3 = ['접속중인 국가: 위, 촉', '【 접속자 】 3', '【 국가방침 】'];
const GENERAL_20 = ['통솔', '무력', '지력', '정치', '매력', 'Lv', '관직', '소속', '명마', '무기', '서적', '도구', '병종', '성격', '자금', '군량', '병사', '훈련', '사기', '특기', '연령', '호칭', '공헌', '삭턴', '부상'];
const NATION_16 = ['성향', '군주', '군주대리', '총 주민', '총 병사', '국고', '병량', '지급률', '세율', '속령', '장수', '국력', '기술력', '외교', '임관', '전쟁'];
const CITY = ['주민', '민심', '농업', '상업', '치안', '수비', '성벽', '시세', '태수', '군사', '종사'];
const RESERVED = ['명령 목록', '당기기/미루기', '반복', '명령 추가 · 편집'];
const RECORD_TABS = ['장수 동향', '개인 기록', '중원 정세'];
const MESSAGE_TABS = ['국가 메시지', '전체 메시지', '개인 메시지'];

describe('S2 대조표 — 작전실 한 화면에 현행 라벨 전부', () => {
    it('renders every current label on the 작전실 main board', { timeout: 20000 }, async () => {
        mocks.useFrontInfo.mockReturnValue({ frontInfo: FRONT_INFO_FULL, constData: { maxTurn: 12 }, menu: [], loading: false, error: null, refreshKey: 0, refresh: vi.fn() });
        mocks.reservedCommands.mockResolvedValue({ result: true, slots: [{ turnIdx: 0, brief: '농지개간' }], year: 197, month: 5, turnPhase: 2, turnTime: '2026-09-06 13:00:00', turnTerm: 120 });
        mocks.mailbox.mockResolvedValue([]);
        const { container } = render(<GameChrome />);
        await waitFor(() => expect(mocks.reservedCommands).toHaveBeenCalled());

        const missing: string[] = [];
        const check = (label: string | RegExp, scope: HTMLElement = container) => {
            const found = typeof label === 'string' ? within(scope).queryAllByText(label, { exact: false }) : within(scope).queryAllByText(label);
            if (found.length === 0) missing.push(String(label));
        };
        GAME_INFO_13.forEach((l) => check(l));
        STATUS_3.forEach((l) => check(l));
        const generalCard = screen.getByRole('region', { name: '장수 정보' });
        GENERAL_20.forEach((l) => check(l, generalCard));
        const nationCard = screen.getByRole('region', { name: '국가 정보' });
        NATION_16.forEach((l) => check(l, nationCard));
        const cityCard = screen.getByRole('region', { name: '도시 정보' });
        CITY.forEach((l) => check(l, cityCard));
        const reserved = screen.getByRole('region', { name: '명령 목록' });
        RESERVED.forEach((l) => check(l, reserved));
        expect(within(reserved).getAllByRole('button', { name: /턴 명령 편집/ }).length).toBeGreaterThanOrEqual(12);
        RECORD_TABS.forEach((l) => expect(screen.getByRole('tab', { name: new RegExp(l) })).toBeInTheDocument());
        MESSAGE_TABS.forEach((l) => expect(screen.getByRole('tab', { name: l })).toBeInTheDocument());
        expect(screen.getByPlaceholderText('서신을 입력하세요')).toBeInTheDocument();
        const subject = screen.getByRole('region', { name: '현재 조작 대상' });
        expect(subject).toHaveTextContent('조작 대상');
        expect(subject).toHaveTextContent('본인');
        expect(missing, `S2 대조표에서 빠진 라벨: ${missing.join(', ')}`).toEqual([]);
    });
});
