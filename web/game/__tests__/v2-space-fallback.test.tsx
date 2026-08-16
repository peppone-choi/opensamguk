// OPENSAM-41 [G0-C] G0C-f / T1-G11 — WebGL 불가 fallback이 같은 read model을 쓴다.
//
// jsdom에는 WebGL 컨텍스트가 없으므로 SpaceProof3D의 실제 감지 경로가 그대로 돈다
// (강제 플래그로 우회하지 않는다 — 그러면 감지 로직 자체가 검증되지 않는다).

import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import SpaceProof3D from '@/components/v2/SpaceProof3D';
import { demoCamera, demoScene } from '@/lib/v2/terrain/fixtures';
import { project } from '@/lib/v2/terrain/projection';

const VIEWPORT = { width: 1024, height: 768 };

function renderScene() {
  const model = demoScene();
  const camera = demoCamera();
  render(<SpaceProof3D model={model} camera={camera} viewport={VIEWPORT} lod="FULL_SCENE" />);
  return { model, camera };
}

describe('G0C-f WebGL 불가 fallback', () => {
  it('WebGL이 없으면 canvas 대신 정보 표현으로 대체된다', () => {
    renderScene();
    expect(screen.getByTestId('v2-space-fallback')).toBeInTheDocument();
    expect(screen.queryByTestId('v2-space-canvas')).not.toBeInTheDocument();
  });

  it('T1-G11: 같은 read model·같은 좌표다 — 별도 simulation이 없다', () => {
    const { model, camera } = renderScene();
    for (const place of model.places) {
      const row = screen.getByTestId('v2-space-fallback').querySelector(`[data-place-id="${place.placeId}"]`);
      expect(row, place.placeId).not.toBeNull();
      const s = project(place.position, camera, VIEWPORT);
      expect(within(row as HTMLElement).getByText(`${Math.round(s.sx)}, ${Math.round(s.sy)}`)).toBeInTheDocument();
    }
  });

  it('T1-G12: 개연 위치를 확정 사료처럼 표시하지 않는다', () => {
    const { model } = renderScene();
    const plausible = model.places.find((p) => p.reconstructionStatus === 'PLAUSIBLE')!;
    const row = screen
      .getByTestId('v2-space-fallback')
      .querySelector(`[data-place-id="${plausible.placeId}"]`) as HTMLElement;
    expect(row.querySelector('[data-status="PLAUSIBLE"]')?.textContent).toMatch(/개연 · 반경 약/);

    const observed = model.places.find((p) => p.reconstructionStatus === 'OBSERVED')!;
    const observedRow = screen
      .getByTestId('v2-space-fallback')
      .querySelector(`[data-place-id="${observed.placeId}"]`) as HTMLElement;
    expect(observedRow.querySelector('[data-status="OBSERVED"]')?.textContent).toBe('사료 확정');
  });

  it('fallback에서도 picking identity가 살아 있다 (선택 기능이 사라지지 않는다)', async () => {
    const { model } = renderScene();
    await userEvent.click(screen.getByRole('button', { name: model.places[1].name }));
    expect(globalThis.__v2Space).toMatchObject({
      mode: 'FALLBACK_TABLE',
      lastPickedPlaceId: model.places[1].placeId,
    });
  });
});
