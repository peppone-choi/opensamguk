import { render, screen } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import CountyPanel from '../components/campaign/CountyPanel';
import type { FrontCityInfo } from '../lib/types';

// 내 세력 — 시험마다 바꾼다(재야는 null).
let myNation: { id: number } | null = { id: 1 };
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, frontInfo: { global: { year: 200, month: 3, turnPhase: 1 }, nation: myNation } }) }));
vi.mock('../lib/api', () => ({ api: { campaignCounty: vi.fn(async () => ({ status: 'READY', specialties: [
    { resource: 'iron', label: '철', monthly: 37, ledgerMonthly: 120 },
    { resource: 'horses', label: '말', monthly: 5, ledgerMonthly: null },
] })) } }));

// 이 시험은 특산 칩만 본다 — 지표 칸은 비워 둔 일부 모양.
const city = (nationId: number) => ({ id: 3, name: '고장현', level: 2, nationId, nationName: '조조', region: 0, regionName: null }) as unknown as FrontCityInfo;

beforeEach(() => { myNation = { id: 1 }; });

test('우리 현 — 이번 달 실제 몫을 그대로', async () => {
    render(<CountyPanel city={city(1)} />);
    expect(await screen.findByText('철 37/월')).toBeInTheDocument();
    expect(screen.getByText('말 5/월')).toBeInTheDocument();
});

test.each([
    ['남의 현에 서 있을 때', { id: 1 }, 2],
    ['재야(세력 없음)일 때', null, 1],
])('%s — 특산은 설계값만, 실제 몫은 그리지 않는다(사용자 결정 D40)', async (_, nation, cityNation) => {
    myNation = nation;
    render(<CountyPanel city={city(cityNation)} />);
    expect(await screen.findByText('철 설계 120/월')).toBeInTheDocument();
    expect(screen.getByText('말 설계 ?/월')).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('37');
    expect(document.body).not.toHaveTextContent('5/월');
});
