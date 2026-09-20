/** World-coordinate rectangle, including the surface's pixel-aligned edges. */
export interface SurfaceBounds { x: number; y: number; width: number; height: number }
interface View { width: number; height: number; panX: number; panY: number; scale: number; dpr: number }
interface Surface { canvas: HTMLCanvasElement; bounds: SurfaceBounds; tiles: number; pixelRatio: number }
const MAX_PIXELS = 4_194_304; // 16 MiB RGBA, one surface per mounted map.
const MAX_SIDE = 4096;
const OVERSCAN = 1.5;

/** A bounded, viewport-sized terrain surface. Markers and hit targets stay live. */
export class MapSurfaceCache {
  private surface: Surface | null = null;
  private ratio = 0;
  private scale = 0;
  constructor(private readonly createCanvas = () => document.createElement('canvas')) {}

  get(view: View, render: (context: CanvasRenderingContext2D, bounds: SurfaceBounds) => number): Surface | null {
    if (![view.width, view.height, view.scale, view.dpr].every(value => Number.isFinite(value) && value > 0)
      || !Number.isFinite(view.panX) || !Number.isFinite(view.panY)) return null;
    // Reserve panning space even on high-DPI displays. Lower terrain density
    // rather than falling back to a full terrain redraw on every frame.
    const pixelRatio = Math.min(view.dpr,
      Math.sqrt(MAX_PIXELS / view.width / view.height) / OVERSCAN,
      MAX_SIDE / view.width / OVERSCAN, MAX_SIDE / view.height / OVERSCAN);
    const ratio = view.scale * pixelRatio;
    const left = -view.panX * pixelRatio;
    const top = -view.panY * pixelRatio;
    const width = view.width * pixelRatio;
    const height = view.height * pixelRatio;
    const old = this.surface;
    if (old && this.ratio === ratio && this.scale === view.scale) {
      const b = old.bounds;
      if (left >= b.x * ratio && top >= b.y * ratio
        && left + width <= (b.x + b.width) * ratio
        && top + height <= (b.y + b.height) * ratio) return old;
    }
    const pixelWidth = Math.max(1, Math.floor(width * OVERSCAN));
    const pixelHeight = Math.max(1, Math.floor(height * OVERSCAN));
    const bounds = {
      x: (left - Math.floor((pixelWidth - width) / 2)) / ratio,
      y: (top - Math.floor((pixelHeight - height) / 2)) / ratio,
      width: pixelWidth / ratio,
      height: pixelHeight / ratio,
    };
    const canvas = old?.canvas ?? this.createCanvas();
    const context = canvas.getContext('2d');
    if (!context) return null;
    canvas.width = pixelWidth;
    canvas.height = pixelHeight;
    // Opaque background preserves the existing color-blend backdrop at tile edges.
    context.fillStyle = '#0c0f0e';
    context.fillRect(0, 0, pixelWidth, pixelHeight);
    context.setTransform(ratio, 0, 0, ratio, -bounds.x * ratio, -bounds.y * ratio);
    context.imageSmoothingEnabled = view.scale < 1;
    const tiles = render(context, bounds);
    this.ratio = ratio;
    this.scale = view.scale;
    this.surface = { canvas, bounds, tiles, pixelRatio };
    return this.surface;
  }

  dispose(): void {
    if (this.surface) {
      this.surface.canvas.width = 0;
      this.surface.canvas.height = 0;
    }
    this.surface = null;
  }
}
