import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HanMapCanvas, type IsoView } from '@opensamguk/ui';
import { CHE_OVERLAYS_FIXTURE, CHE_TILES_FIXTURE } from './fixtures/che-tiles';

function canvasContextStub() {
  const gradient = { addColorStop: vi.fn() };
  return {
    canvas: document.createElement('canvas'),
    createImageData: (width: number, height: number) => ({ data: new Uint8ClampedArray(width * height * 4) }),
    putImageData: vi.fn(), setTransform: vi.fn(), clearRect: vi.fn(), save: vi.fn(), restore: vi.fn(),
    transform: vi.fn(), drawImage: vi.fn(), beginPath: vi.fn(), moveTo: vi.fn(), lineTo: vi.fn(),
    stroke: vi.fn(), fill: vi.fn(), fillRect: vi.fn(), strokeRect: vi.fn(), arc: vi.fn(), closePath: vi.fn(),
    createRadialGradient: () => gradient, fillText: vi.fn(), strokeText: vi.fn(),
    imageSmoothingEnabled: true, strokeStyle: '', fillStyle: '', lineWidth: 1,
    font: '', textAlign: 'start', textBaseline: 'alphabetic', globalAlpha: 1,
  } as unknown as CanvasRenderingContext2D;
}

describe('shared HanMapCanvas viewport interaction', () => {
  beforeEach(() => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => canvasContextStub());
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(40);
  });

  it('changes the view for zoom controls and pointer panning', () => {
    const views: IsoView[] = [];
    render(<HanMapCanvas mapCode="che" tiles={CHE_TILES_FIXTURE} onViewChange={(view) => views.push({ ...view })} />);

    const initial = views.at(-1)!;
    fireEvent.click(screen.getByRole('button', { name: '지도 확대' }));
    const zoomed = views.at(-1)!;
    expect(zoomed.scale).toBeGreaterThan(initial.scale);

    const canvas = screen.getByRole('img', { name: 'che 아이소 타일 지도' });
    fireEvent.pointerDown(canvas, { clientX: 100, clientY: 100, pointerId: 1 });
    fireEvent.pointerMove(canvas, { clientX: 130, clientY: 115, pointerId: 1 });
    expect(views.at(-1)).not.toEqual(zoomed);
  });

  it('focuses and activates canvas city markers from the keyboard', () => {
    const onCityActivate = vi.fn();
    render(
      <HanMapCanvas
        mapCode="che"
        tiles={CHE_TILES_FIXTURE}
        cities={CHE_OVERLAYS_FIXTURE}
        sourceSize={{ width: 200, height: 120 }}
        onCityActivate={onCityActivate}
      />,
    );
    const canvas = screen.getByRole('img', { name: 'che 아이소 타일 지도' });
    fireEvent.focus(canvas);
    fireEvent.keyDown(canvas, { key: 'Enter' });
    expect(onCityActivate).toHaveBeenCalledWith(
      expect.objectContaining(CHE_OVERLAYS_FIXTURE[0]),
      { pointerType: 'keyboard' },
    );
  });
});
