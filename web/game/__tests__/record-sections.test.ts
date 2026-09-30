import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test } from 'vitest';
import { RECORD_KIND_SECTION, recordSection } from '../lib/record-sections';

// 두 번째 축: 서버 정본 EventKind.kt 를 직접 읽어 대조한다(화면 표가 스스로를 검사하지 않게).
const EVENT_KIND = resolve(__dirname, '../../../logic/src/main/kotlin/opensamguk/logic/record/EventKind.kt');

function serverTable(): Record<string, string> {
    const src = readFileSync(EVENT_KIND, 'utf-8');
    const out: Record<string, string> = {};
    for (const m of src.matchAll(/^\s+[A-Z_]+\("([a-zA-Z.]+)",\s*([A-Z_]+),/gm)) out[m[1]] = m[2];
    return out;
}

test('화면 표가 서버 EventKind 의 종류 · 분류와 한 줄씩 같다', () => {
    const server = serverTable();
    expect(Object.keys(server).length).toBeGreaterThan(30); // 대조가 실제로 읽혔는지(0건 통과 방지)
    expect(RECORD_KIND_SECTION).toEqual(server);
});

test('모르는 종류는 분류를 지어 넣지 않는다', () => {
    expect(recordSection('march.corps')).toBe('BATTLE');
    expect(recordSection('yuedan.assessed')).toBe('PERSONAL');
    expect(recordSection('county.ownerChanged')).toBe('WORLD');
    expect(recordSection('something.new')).toBeNull();
    expect(recordSection('toString')).toBeNull();
});
