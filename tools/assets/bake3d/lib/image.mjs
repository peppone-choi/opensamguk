// Pixel helpers on raw RGBA8 buffers (row-major, top row first).

// Smallest rectangle containing every pixel with alpha > 0, or null for an empty image.
export function alphaBBox(width, height, rgba) {
  let minX = width;
  let minY = height;
  let maxX = -1;
  let maxY = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (rgba[(y * width + x) * 4 + 3] === 0) continue;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  return maxX < 0 ? null : { x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1 };
}

// True when the opaque area reaches the outermost row/column of the cell, i.e. the model may
// have been clipped by the camera frustum.
export function touchesCellEdge(width, height, bbox) {
  return bbox.x === 0 || bbox.y === 0 || bbox.x + bbox.w === width || bbox.y + bbox.h === height;
}

export function crop(width, rgba, bbox) {
  const out = new Uint8Array(bbox.w * bbox.h * 4);
  for (let y = 0; y < bbox.h; y++) {
    const src = ((bbox.y + y) * width + bbox.x) * 4;
    out.set(rgba.subarray(src, src + bbox.w * 4), y * bbox.w * 4);
  }
  return out;
}

// Team-colour mask: inside material-id pixels listed in `teamIds` it keeps the lit colour's
// brightness as an equal-channel grey with full alpha; everywhere else fully transparent.
// The material-id pass stores id + 1 in the red channel (0 = background).
export function teamMask(color, matid, teamIds) {
  const team = new Set(teamIds);
  const out = new Uint8Array(color.length);
  for (let i = 0; i < color.length; i += 4) {
    if (matid[i + 3] === 0 || !team.has(matid[i])) continue;
    const g = Math.round(0.2126 * color[i] + 0.7152 * color[i + 1] + 0.0722 * color[i + 2]);
    out[i] = g;
    out[i + 1] = g;
    out[i + 2] = g;
    out[i + 3] = 255;
  }
  return out;
}

// Pixel-wise comparison for determinism evidence: number of differing pixels and the largest
// absolute channel difference.
export function diffStats(a, b) {
  if (a.length !== b.length) return { comparable: false, diffPixels: null, maxAbsDiff: null };
  let diffPixels = 0;
  let maxAbsDiff = 0;
  for (let i = 0; i < a.length; i += 4) {
    let pixelDiff = false;
    for (let c = 0; c < 4; c++) {
      const d = Math.abs(a[i + c] - b[i + c]);
      if (d) pixelDiff = true;
      if (d > maxAbsDiff) maxAbsDiff = d;
    }
    if (pixelDiff) diffPixels++;
  }
  return { comparable: true, diffPixels, maxAbsDiff };
}
