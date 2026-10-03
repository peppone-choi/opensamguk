import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MapPreviewResponse } from '../lib/types';

// 실지도 결함 5: 작전실 · 기록이 새 지도를 그리면서도 옛 지도판 몫(지형 · 州 색인 · 省 PNG — 운영 24,666,640 B)을 받았다.
// 새 지도(교체 스위치 + 서버 bakeId)면 미리보기만 받는다. 요청 주소를 세어 본다.
const mocks = vi.hoisted(() => ({ switchOn: true, preview: null as MapPreviewResponse | null, asked: [] as string[] }));
vi.mock('@opensamguk/ui/map/topdown', async () => {
    const actual = await vi.importActual<typeof import('@opensamguk/ui/map/topdown')>('@opensamguk/ui/map/topdown');
    return { ...actual, topdownScreensEnabled: () => mocks.switchOn };
});
vi.mock('@/lib/api', () => ({ api: { mapPreview: async () => mocks.preview } }));
import { useCampaignWorldMap } from '../lib/campaign-map';

const SHA = 'a'.repeat(64);
function preview(bake: boolean): MapPreviewResponse {
    return {
        serverName: 't', year: 200, month: 3, mapCode: 'han-world-v3', width: 700, height: 610,
        strategicTopology: { worldId: 1, mapCode: 'han-world-v3', topologyRevision: 't', topologyHash: 'b'.repeat(64),
            baseTilesSha256: SHA, cols: 768, rows: 669 },
        nations: [{ id: 1, name: '조조', color: '#c9a656' }],
        cities: [{ id: 10, name: '양적현', level: 5, nationId: 1, x: 350, y: 300, state: 0, supply: true, isCapital: false,
            commanderyName: '영천군', isCommanderySeat: true }],
        ...(bake ? { topdownBakeId: 'c'.repeat(64) } : {}),
    } as MapPreviewResponse;
}

beforeEach(() => {
    mocks.asked.length = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        mocks.asked.push(new URL(url, 'http://local').pathname.replace(/^.*\/api\/map\//, ''));
        if (url.includes('/terrain?')) {
            return { ok: true, headers: { get: () => `"sha256-${SHA}"` },
                json: async () => ({ _meta: { cols: 768, rows: 669 }, juns: [{ name: '영천군', col: 390, row: 330 }] }) };
        }
        return { ok: false, status: 404, headers: { get: () => null }, json: async () => ({}) };
    }));
});

describe('작전실 · 기록 지도 훅: 새 지도면 미리보기만', () => {
    it('교체 스위치 + bakeId 면 지형 · 州 색인 · 省 그림을 한 번도 청하지 않는다', async () => {
        mocks.switchOn = true;
        mocks.preview = preview(true);
        const { result } = renderHook(() => useCampaignWorldMap());
        await waitFor(() => expect(result.current.kind).not.toBe('loading'));
        // 省 그림은 지형 뒤에 따로 청하니 한 틱 더 기다린 뒤 센다
        await new Promise((resolve) => setTimeout(resolve, 0));
        expect(mocks.asked, '새 지도인데 옛 지도판 자료를 청했다').toEqual([]);
        expect(result.current.kind).toBe('preview');
        if (result.current.kind !== 'preview') throw new Error('not preview');
        expect(result.current.legend.map((entry) => entry.name)).toEqual(['조조']);
    });

    it('bakeId 가 없거나 스위치가 꺼져 있으면 옛 지도판 자료를 그대로 받는다(양성 대조)', async () => {
        for (const [switchOn, bake] of [[true, false], [false, true]] as const) {
            mocks.switchOn = switchOn;
            mocks.preview = preview(bake);
            mocks.asked.length = 0;
            const { result, unmount } = renderHook(() => useCampaignWorldMap());
            await waitFor(() => expect(result.current.kind).toBe('ready'));
            expect(mocks.asked, `스위치 ${switchOn} · bake ${bake}`).toEqual(expect.arrayContaining(['terrain', 'ju']));
            unmount();
        }
    });
});
