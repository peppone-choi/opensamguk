import { render, screen } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import CountyPanel from '../components/campaign/CountyPanel';
import type { FrontCityInfo } from '../lib/types';

vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, frontInfo: null }) }));
vi.mock('../lib/api', () => ({ api: { campaignCounty: vi.fn(async () => ({ status: 'READY', specialties: [] })) } }));

// 이 시험은 부제 줄만 본다 — 지표 칸은 비워 둔 일부 모양.
const city = (regionName: string | null) => ({ id: 3, name: '고장현', level: 2, nationId: 1, nationName: '조조', region: 0, regionName }) as unknown as FrontCityInfo;

test.each([
    ['량주', '서량 · 조조'],
    ['사예', '사례 · 조조'],
    ['양주', '양주 · 조조'],
])('縣 카드 부제의 州 — 서버 키 %s 는 화면 표기로(원장 D25)', async (key, shown) => {
    render(<CountyPanel city={city(key)} />);
    expect(await screen.findByText(shown)).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(key === '양주' ? '양주(涼)' : key);
});
