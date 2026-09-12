/** Pointer-based map gestures, shared by Canvas2D and the orthographic renderer. */
export function attachMapGestures(canvas: HTMLCanvasElement, callbacks: {
  pan: (dx: number, dy: number) => void;
  zoom: (factor: number, x: number, y: number) => void;
}) {
  type Point = { x: number; y: number; startX: number; startY: number };
  const pointers = new Map<number, Point>();
  let moved = false;
  let pointerType = 'mouse';
  const oldTouchAction = canvas.style.touchAction;
  canvas.style.touchAction = 'none';
  const down = (event: PointerEvent) => {
    if (event.button !== 0) return;
    if (pointers.size === 0) moved = false;
    pointerType = event.pointerType || 'mouse';
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY, startX: event.clientX, startY: event.clientY });
    if (pointers.size > 1) moved = true;
    canvas.setPointerCapture(event.pointerId);
    canvas.style.cursor = 'grabbing';
  };
  const move = (event: PointerEvent) => {
    const point = pointers.get(event.pointerId);
    if (!point) return;
    if (event.cancelable) event.preventDefault();
    const previous = [...pointers.values()].slice(0, 2).map(p => ({ ...p }));
    const dx = event.clientX - point.x;
    const dy = event.clientY - point.y;
    point.x = event.clientX;
    point.y = event.clientY;
    if (Math.hypot(point.x - point.startX, point.y - point.startY) > 6) moved = true;
    const current = [...pointers.values()].slice(0, 2);
    if (current.length === 1) callbacks.pan(dx, dy);
    else {
      const [a, b] = previous;
      const [c, d] = current;
      const oldDistance = Math.hypot(b.x - a.x, b.y - a.y);
      const distance = Math.hypot(d.x - c.x, d.y - c.y);
      const x = (a.x + b.x) / 2;
      const y = (a.y + b.y) / 2;
      const rect = canvas.getBoundingClientRect();
      if (oldDistance > 0 && distance > 0) callbacks.zoom(distance / oldDistance, x - rect.left, y - rect.top);
      callbacks.pan((c.x + d.x) / 2 - x, (c.y + d.y) / 2 - y);
    }
  };
  const up = (event: PointerEvent) => {
    if (!pointers.has(event.pointerId)) return;
    if (event.type !== 'pointerup') moved = true;
    pointers.delete(event.pointerId);
    if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
    canvas.style.cursor = pointers.size ? 'grabbing' : 'grab';
  };
  const click = (event: MouseEvent) => {
    if (!moved) return;
    event.preventDefault();
    event.stopImmediatePropagation();
  };
  canvas.addEventListener('pointerdown', down);
  canvas.addEventListener('pointermove', move, { passive: false });
  canvas.addEventListener('pointerup', up);
  canvas.addEventListener('pointercancel', up);
  canvas.addEventListener('lostpointercapture', up);
  canvas.addEventListener('click', click, true);
  return {
    get active() { return pointers.size > 0; },
    get pointerType() { return pointerType; },
    dispose() {
      canvas.removeEventListener('pointerdown', down);
      canvas.removeEventListener('pointermove', move);
      canvas.removeEventListener('pointerup', up);
      canvas.removeEventListener('pointercancel', up);
      canvas.removeEventListener('lostpointercapture', up);
      canvas.removeEventListener('click', click, true);
      for (const id of pointers.keys()) if (canvas.hasPointerCapture(id)) canvas.releasePointerCapture(id);
      pointers.clear();
      canvas.style.touchAction = oldTouchAction;
    },
  };
}
