// 명령 흐름 명령 표가 입력 원장과 어긋나지 않는지 — 원장이 바뀌면(PLANNED → HANDLER_READY 승격 등) 여기서 깨진다.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { FLOW_CATEGORIES, FLOW_COMMANDS, RENAMED, filterCommands, flowCommand, orderForPlace } from '@/lib/command-flow/catalog';

interface CatalogRow { inputId: string; kind: string; displayName?: string; deliveryState: string }
const catalog: CatalogRow[] = JSON.parse(readFileSync(resolve(__dirname, '../../../data/commands/input-catalog.json'), 'utf-8')).inputs;
const actions = catalog.filter(r => r.kind === 'GENERAL_ACTION');

describe('명령 표 ↔ 입력 원장', () => {
    it('직접 행동 43개가 빠짐없이 한 번씩 있다', () => {
        expect(actions).toHaveLength(43);
        expect(FLOW_COMMANDS).toHaveLength(actions.length);
        expect(new Set(FLOW_COMMANDS.map(c => c.inputId))).toEqual(new Set(actions.map(r => r.inputId)));
    });

    it('전달 상태가 원장과 같다', () => {
        for (const row of actions) expect(flowCommand(row.inputId)?.delivery, row.inputId).toBe(row.deliveryState);
    });

    it('이름은 원장 displayName — 사용자 승인으로 바꾼 두 개만 예외', () => {
        for (const row of actions) {
            const renamed = RENAMED[row.inputId];
            if (renamed) {
                expect(row.displayName, `${row.inputId} 원장 이름이 바뀌었으면 RENAMED를 지운다`).toBe(renamed.from);
                expect(flowCommand(row.inputId)?.name).toBe(renamed.to);
            } else {
                expect(flowCommand(row.inputId)?.name, row.inputId).toBe(row.displayName);
            }
        }
    });

    it('화면 이름에 쓰지 않는 말(군량 · 숙련)이 없다', () => {
        for (const c of FLOW_COMMANDS) expect(c.name).not.toMatch(/군량|숙련/);
    });
});

describe('분류 · 찾기', () => {
    it('분류 7개가 43개를 나눠 가진다(설계서 §2.1 표)', () => {
        const counts = Object.fromEntries(FLOW_CATEGORIES.filter(c => c !== '전체').map(c => [c, filterCommands(c, '').length]));
        expect(counts).toEqual({ 내정: 8, 군사: 11, 이동: 3, 인물: 3, 개인: 5, 나라: 8, 물자: 5 });
        expect(filterCommands('전체', '')).toHaveLength(43);
    });

    it('이름 · 초성 · 옛 이름 별칭으로 찾는다', () => {
        expect(filterCommands('전체', '출병').map(c => c.inputId)).toEqual(['action.deploy']);
        expect(filterCommands('전체', 'ㅊㅂ').map(c => c.inputId)).toContain('action.deploy');
        expect(filterCommands('전체', '임관').map(c => c.inputId)).toEqual(['action.enlist']);
        expect(filterCommands('전체', '군량매매').map(c => c.inputId)).toEqual(['action.tradeGrain']);
        expect(filterCommands('이동', '출병')).toEqual([]);
    });

    it('여기로 명령 — 그 장소를 받는 명령을 위로, 나머지는 숨기지 않는다', () => {
        const ordered = orderForPlace(filterCommands('전체', ''), 'province');
        expect(ordered.slice(0, 3).map(c => c.inputId)).toEqual(['action.deploy', 'action.move', 'action.forcedMarch']);
        expect(ordered).toHaveLength(43);
    });
});
