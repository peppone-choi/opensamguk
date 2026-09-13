/** World-coordinate rectangle, including the surface's pixel-aligned edges. */
export interface SurfaceBounds { x: number; y: number; width: number; height: number }
interface View { width: number; height: number; panX: number; panY: number; scale: number; dpr: number }
interface Surface { canvas: HTMLCanvasElement; bounds: SurfaceBounds; tiles: number }
const MAX_PIXELS = 4_194_304; // 16 MiB RGBA, one surface per mounted map.
const MAX_SIDE = 4096;

/** A bounded, viewport-sized terrain surface. Markers and hit targets stay live. */
export class MapSurfaceCache {
  private surface: Surface | null = null;
  private ratio = 0;
  private scale = 0;
  constructor(private readonly createCanvas = () => document.createElement('canvas')) {}

  get(view: View, render: (context: CanvasRenderingContext2D, bounds: SurfaceBounds) => number): Surface | null {
    const ratio = view.scale * view.dpr;
    const left = -view.panX * view.dpr;
    const top = -view.panY * view.dpr;
    const width = Math.ceil(view.width * view.dpr);
    const height = Math.ceil(view.height * view.dpr);
    if (!(ratio > 0) || width <= 0 || height <= 0
      || width * height > MAX_PIXELS || width > MAX_SIDE || height > MAX_SIDE) return null;
    const old = this.surface;
    if (old && this.ratio === ratio && this.scale === view.scale) {
      const b = old.bounds;
      if (left >= b.x * ratio && top >= b.y * ratio
        && left + width <= (b.x + b.width) * ratio
        && top + height <= (b.y + b.height) * ratio) return old;
    }
    const factor = Math.min(1.5, Math.sqrt(MAX_PIXELS / (width * height)), MAX_SIDE / width, MAX_SIDE / height);
    const pixelWidth = Math.floor(width * factor);
    const pixelHeight = Math.floor(height * factor);
    const bounds = {
      x: (-view.panX * view.dpr - Math.floor((pixelWidth - width) / 2)) / ratio,
      y: (-view.panY * view.dpr - Math.floor((pixelHeight - height) / 2)) / ratio,
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
    this.surface = { canvas, bounds, tiles };
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
