// 상사 규칙은 서버 상사 선택지(reward-options rule · cards · preview)가 정한다 — 프론트에는 규칙 사본이 없다.
// 예전에는 lib/court-view.ts 의 REWARD_RULE 사본을 Kotlin 원문과 맞춰 쟀다(계약판 K4-15 전). 이제는 계약 응답의 값이
// 바뀌면 화면 문장 · 막는 까닭이 그대로 따라 바뀌는지, 프론트가 규칙 셈을 다시 들이지 않았는지를 잰다.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test } from 'vitest';
import { parseRewardOptions } from '../lib/api/court-reward';
import * as courtView from '../lib/court-view';
import { rewardPanelView } from '../lib/court-reward-view';
import { RULE, card, readyBody, type Wire } from './fixtures/court-reward';

const web = resolve(__dirname, '..');
const source = (rel: string) => readFileSync(resolve(web, rel), 'utf-8');

test('프론트 규칙 사본 · 셈이 없다 — court-view 는 상사 규칙을 내보내지 않는다', () => {
    expect(Object.keys(courtView).filter((k) => /reward/i.test(k))).toEqual([]);
    for (const rel of ['lib/court-view.ts', 'lib/court-reward-view.ts', 'components/court/CourtParts.tsx', 'components/court/CourtScreen.tsx', 'hooks/useCourtReward.ts']) {
        expect(source(rel), rel).not.toMatch(/REWARD_RULE|rewardPreview|rewardMaxMoney|rewardTargets/);
    }
    // 화면이 보이는 상한 · 충성 상한 수치는 서버 rule · 카드 값에서만 온다(문장에 박힌 100 · 10 · 1000 없음).
    const view = source('lib/court-reward-view.ts').replace(/\/\/.*$|\/\*[\s\S]*?\*\//gm, '');
    expect(view).not.toMatch(/[`'"][^`'"]*\b(100|1,?000|10)\b[^`'"]*[`'"]/);
});

test('규칙 문장 · 막는 까닭은 계약 응답 값을 따른다 — 다른 rule · 상한이면 다른 문장', () => {
    const rule = { ...RULE, moneyPerLoyalty: 200, maxLoyaltyGainPerReward: 5, minimumMoney: 200, inputMaximumMoney: 5000, loyaltyCap: 90 };
    // 서버가 정한 카드 상한(700)이 있으면 화면은 다른 식으로 다시 셈하지 않는다.
    const cards = [card(4, 85, { loyaltyRoom: 5, maximumMoney: 700 })];
    const at = (money: number) => {
        const query = { generalId: 7, retainerId: 4, money };
        const body: Wire = { ...readyBody({ cards }, { ...query, money: String(money) }), rule };
        const verdict = money < rule.minimumMoney ? 'TOO_SMALL' : 'REWARD_OVER_CAP';
        body.preview = { ...(body.preview as Wire), verdict, loyaltyGain: null, loyaltyAfter: null, moneyWithoutGain: null, usableMoney: null, debitPlan: null };
        const options = parseRewardOptions(body, query);
        if (options?.status !== 'READY' || !options.preview) throw new Error('계약 응답이 아니다');
        return rewardPanelView({ options, retinue: null, selected: 4, money, preview: { state: 'ready', preview: options.preview } });
    };
    expect(at(150).rule).toBe('금 200당 충성 +1 · 한 번에 최대 +5 · 충성은 90까지 — 충성을 올릴 수 있는 만큼까지만 냅니다.');
    expect(at(150).blocked).toBe('금 200 이상이어야 충성이 오릅니다.');
    expect(at(800).blocked).toBe('이번에 충성을 올릴 수 있는 금은 최대 700입니다.');
});

test('미리 보기의 오를 충성 · 충성 없이 나가는 금은 서버 필드 그대로 — 화면이 셈하지 않는다', () => {
    const cards = [card(4, 60)];
    const query = { generalId: 7, retainerId: 4, money: 150 };
    const body = readyBody({ cards }, { ...query, money: '150' });
    // 서버가 다른 값을 주면(계약 안에서) 화면도 그 값을 보인다 — 금 100당 +1 을 화면이 다시 셈하지 않는다는 증거.
    body.rule = { ...RULE, moneyPerLoyalty: 50 };
    body.preview = { ...(body.preview as Wire), loyaltyGain: 3, loyaltyAfter: 63, moneyWithoutGain: 0 };
    const options = parseRewardOptions(body, query);
    if (options?.status !== 'READY' || !options.preview) throw new Error('계약 응답이 아니다');
    const view = rewardPanelView({ options, retinue: null, selected: 4, money: 150, preview: { state: 'ready', preview: options.preview } });
    expect(view.effect?.text).toBe('충성 +3 · 상사 뒤 충성 63');
});
