// Review contact sheet: full cells side by side on a checkerboard, nearest-neighbour scaled,
// with the foot anchor marked by a single magenta pixel block. Review aid only, not an export.
export function contactSheet(cells, { width, height, anchor, scale = 2 }) {
  const W = cells.length * width * scale;
  const H = height * scale;
  const out = new Uint8Array(W * H * 4);
  cells.forEach((rgba, n) => {
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        const s = (y * width + x) * 4;
        const a = rgba[s + 3] / 255;
        const bg = ((x >> 3) + (y >> 3)) % 2 ? 200 : 235;
        const isAnchor = x === anchor.x && y === anchor.y;
        const px = isAnchor ? [255, 0, 255] : [0, 1, 2].map((c) => Math.round(rgba[s + c] * a + bg * (1 - a)));
        for (let dy = 0; dy < scale; dy++) {
          for (let dx = 0; dx < scale; dx++) {
            const d = ((y * scale + dy) * W + (n * width + x) * scale + dx) * 4;
            out[d] = px[0];
            out[d + 1] = px[1];
            out[d + 2] = px[2];
            out[d + 3] = 255;
          }
        }
      }
    }
  });
  return { width: W, height: H, rgba: out };
}
