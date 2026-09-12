import { describe, expect, it, vi } from 'vitest';
import { attachMapGestures } from '../iso/mapGestures';

function setup() {
  const canvas = document.createElement('canvas');
  canvas.setPointerCapture = vi.fn();
  canvas.hasPointerCapture = vi.fn(() => true);
  canvas.releasePointerCapture = vi.fn();
  const pan = vi.fn();
  const zoom = vi.fn();
  const pick = vi.fn();
  const gestures = attachMapGestures(canvas, { pan, zoom });
  canvas.addEventListener('click', pick);
  const pointer = (type: string, id: number, x: number, y: number) => {
    const event = new MouseEvent(type, { clientX: x, clientY: y, bubbles: true, cancelable: true, button: 0 });
    Object.defineProperties(event, { pointerId: { value: id }, pointerType: { value: 'touch' } });
    canvas.dispatchEvent(event);
  };
  return { canvas, pan, zoom, pick, gestures, pointer };
}

describe('map touch gestures', () => {
  it('pans with one finger and does not pick a city after dragging', () => {
    const { canvas, pan, pick, pointer } = setup();
    pointer('pointerdown', 1, 20, 30);
    pointer('pointermove', 1, 60, 70);
    pointer('pointerup', 1, 60, 70);
    canvas.click();
    expect(pan).toHaveBeenCalledWith(40, 40);
    expect(pick).not.toHaveBeenCalled();
  });
  it('pinches around the midpoint and continues panning with the remaining finger', () => {
    const { pan, zoom, pointer, gestures } = setup();
    pointer('pointerdown', 1, 0, 50);
    pointer('pointerdown', 2, 100, 50);
    pointer('pointermove', 2, 200, 50);
    expect(zoom).toHaveBeenCalledWith(2, 50, 50);
    expect(pan).toHaveBeenCalledWith(50, 0);
    pointer('pointerup', 2, 200, 50);
    expect(gestures.active).toBe(true);
    pointer('pointermove', 1, 10, 60);
    expect(pan).toHaveBeenLastCalledWith(10, 10);
  });
  it('allows a fresh tap after a drag, and releases cancelled pointers', () => {
    const { canvas, pointer, gestures, pick } = setup();
    pointer('pointerdown', 1, 0, 0);
    pointer('pointercancel', 1, 0, 0);
    expect(gestures.active).toBe(false);
    canvas.click();
    expect(pick).not.toHaveBeenCalled();
    pointer('pointerdown', 2, 10, 10);
    pointer('pointerup', 2, 10, 10);
    canvas.click();
    expect(pick).toHaveBeenCalledOnce();
  });
  it('cleans up listeners and pointer captures on teardown', () => {
    const { canvas, pointer, gestures, pan } = setup();
    pointer('pointerdown', 1, 0, 0);
    gestures.dispose();
    pointer('pointermove', 1, 50, 0);
    expect(pan).not.toHaveBeenCalled();
    expect(canvas.releasePointerCapture).toHaveBeenCalledWith(1);
  });
});
