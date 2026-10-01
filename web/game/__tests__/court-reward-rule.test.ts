// 상사 규칙 프론트 사본(lib/court-view.ts REWARD_RULE)이 서버와 같은지 — Kotlin 원문에서 잰다(계약판 K4-15 reward-options 가 오기 전까지).
// 서버 값이 바뀌면 이 시험이 빨개진다 → 화면 문구 · 미리 보기 셈을 같이 고친다.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test } from 'vitest';
import { REWARD_RULE, rewardPreview } from '../lib/court-view';

const root = resolve(__dirname, '../../..');
const balance = readFileSync(resolve(root, 'logic/src/main/kotlin/opensamguk/logic/war/CampaignBalance.kt'), 'utf-8');
const executor = readFileSync(resolve(root, 'app/game-engine/src/main/kotlin/opensamguk/engine/campaign/RewardExecutor.kt'), 'utf-8');

test('REWARD_RULE 은 CampaignBalance · RewardExecutor 원문과 같다', () => {
    expect(Number(/REWARD_MONEY_PER_LOYALTY\s*=\s*(\d+)L?/.exec(balance)?.[1])).toBe(REWARD_RULE.moneyPerLoyalty);
    expect(Number(/REWARD_MAX_LOYALTY_GAIN\s*=\s*(\d+)/.exec(balance)?.[1])).toBe(REWARD_RULE.maxGain);
    expect(Number(/loyalty\s*=\s*\(card\.loyalty\s*\+\s*gain\)\.coerceAtMost\((\d+)\)/.exec(executor)?.[1])).toBe(REWARD_RULE.loyaltyCap);
    // 서버는 오른 충성과 상관없이 적은 금 전부를 낸다 — 그래서 화면이 「충성 없이 나가는 금」을 미리 보인다.
    expect(executor).toMatch(/payMoney\([^\n]*request\.money\)/);
});

test('미리 보기 셈 — 상한 · 나머지 · 충성 100', () => {
    expect(rewardPreview(5000, 60)).toEqual({ gain: 10, wasted: 4000 });
    expect(rewardPreview(150, 60)).toEqual({ gain: 1, wasted: 50 });
    expect(rewardPreview(1000, 95)).toEqual({ gain: 5, wasted: 500 });
    expect(rewardPreview(50, 60)).toEqual({ gain: 0, wasted: 50 });
    expect(rewardPreview(300, 100)).toEqual({ gain: 0, wasted: 300 });
});
