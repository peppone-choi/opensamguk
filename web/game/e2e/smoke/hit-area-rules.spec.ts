// 「누를 영역 44」 공용 도우미(web/shared/e2e/hitArea.ts)의 적색 프로브 — 서버 없이 page.setContent 로 띄운 고정 화면.
// 뜻(K0 2026-10-02): 누를 영역은 상자 크기가 아니라 가운데에서 바깥으로 훑은 elementFromPoint 적중 범위다.
// 넓힌 단추는 통과하고, 겹쳐 줄어든 영역은 실패해야 한다. 라벨 있는 입력은 라벨까지 잰다. 첫 화면 위치만 보지 않는다.
import { expect, test } from '@playwright/test';
import { BOTH, coveredTargets, smallTouchTargets } from '../support/parity';

const PAGE = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  body { margin: 0; font: 14px sans-serif }
  main { padding: 16px 16px 120px; display: grid; gap: 24px; justify-items: start }
  button { padding: 0; border: 0 }
  input { margin: 0 }
  .wide { position: relative; width: 30px; height: 30px }
  .wide::before { content: ''; position: absolute; inset: -7px }
  .pair { position: relative; width: 48px; height: 48px }
  .pair > div { position: absolute; top: 0; right: 0; width: 20px; height: 48px }
  .cover { position: relative; width: 200px; height: 48px }
  .cover > div { position: absolute; inset: 0 }
  nav { position: fixed; left: 0; right: 0; bottom: 0; height: 64px; background: #222 }
  nav a { display: inline-block; width: 64px; height: 64px; color: #fff }
</style></head><body><main>
  <button type="button" class="wide" aria-label="넓힌 단추">+</button>
  <div class="pair"><button type="button" style="width:48px;height:48px" aria-label="겹쳐 줄어든 단추">겹침</button><div></div></div>
  <label style="display:inline-flex;align-items:center;gap:8px;min-height:44px;padding:0 8px"><input type="checkbox" aria-label="라벨 44" style="width:20px;height:20px">라벨 44</label>
  <label style="display:inline-flex;align-items:center;gap:8px;height:30px;padding:0 8px"><input type="checkbox" aria-label="라벨 30" style="width:20px;height:20px">라벨 30</label>
  <div style="display:flex;align-items:center;gap:8px"><input type="checkbox" id="big" aria-label="큰 입력" style="width:44px;height:44px"><label for="big" style="display:inline-block;width:120px;height:20px">넓은 라벨</label></div>
  <label style="position:relative;display:block;width:160px;height:48px"><input type="checkbox" aria-label="꾸민 체크" style="position:absolute;inset:0;width:100%;height:100%;margin:0;opacity:0"><span style="position:absolute;inset:0;background:#ddd">꾸민 체크</span></label>
  <button type="button" style="position:absolute;left:180px;top:calc(100vh - 52px);width:120px;height:44px" aria-label="탭 밑 단추">탭 밑</button>
  <button type="button" style="position:absolute;left:16px;top:calc(100vh - 100px);width:120px;height:48px" aria-label="탭에 걸친 단추">걸침</button>
  <div style="height:1400px"></div>
  <div class="cover"><button type="button" style="width:200px;height:48px" aria-label="아래 덮인 단추">아래</button><div></div></div>
  <div style="height:200px"></div>
</main><nav aria-label="아래 탭"><a href="#" aria-label="탭">탭</a></nav></body></html>`;

test('누를 영역: 넓힌 단추 · 라벨 44 는 통과, 겹쳐 줄어든 것 · 라벨 30 은 44 미만', { tag: [BOTH] }, async ({ page }) => {
  await page.setContent(PAGE);
  const small = await smallTouchTargets(page, 'main');
  expect(small.filter((s) => s.includes('넓힌 단추')), '::before 로 44 로 넓힌 단추는 통과').toEqual([]);
  expect(small.filter((s) => s.includes('라벨 44')), '라벨 44 + 입력 20 은 통과').toEqual([]);
  expect(small.filter((s) => s.includes('큰 입력')), '44×44 입력 + 120×20 for 라벨은 통과(넓이만 보고 라벨을 고르지 않는다, 리뷰 #1212)').toEqual([]);
  expect(small.filter((s) => s.includes('꾸민 체크')), '제 라벨에 덮인 투명 입력은 라벨(160×48)로 잰다').toEqual([]);
  // 가운데(탭 윗변보다 12px 위)는 맞지만 아래 12px 가 고정 아래 탭에 걸려 첫 화면에서는 짧게 잡힌다 — 가운데로 들여 다시 재면 48(외교 「천하 지도 보기」 오탐).
  expect(small.filter((s) => s.includes('탭에 걸친 단추')), '가장자리가 고정 탭에 걸린 것은 들여 다시 재면 통과').toEqual([]);
  expect(small.some((s) => s.includes('겹쳐 줄어든 단추')), `겹쳐 줄어든 영역은 44 미만: ${JSON.stringify(small)}`).toBe(true);
  expect(small.some((s) => s.includes('라벨 30')), `라벨 30 은 44 미만: ${JSON.stringify(small)}`).toBe(true);
  expect(small).toHaveLength(2);
});

test('덮임: 첫 화면 아래 투명 상자 덮임은 잡고, 아래 고정 탭에 첫 화면에서만 걸린 것은 세지 않는다', { tag: [BOTH] }, async ({ page }) => {
  await page.setContent(PAGE);
  const covered = await coveredTargets(page.locator('main'), 'button, input');
  expect(covered, '덮인 것은 아래 덮인 단추 하나(탭 밑 단추는 스크롤하면 맞으니 세지 않음, 제 라벨에 덮인 꾸민 체크도 세지 않음)').toEqual([expect.stringContaining('아래 덮인 단추')]);
  // 재고 나서 스크롤을 되돌린다.
  expect(await page.evaluate(() => scrollY)).toBe(0);
});
