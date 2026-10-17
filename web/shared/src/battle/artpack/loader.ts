// 아트 팩 받기(브라우저) — 목록(manifest.json) 검증 → 아틀라스 PNG 를 그림으로. 기준 URL 마다 한 번만 받는다.
// 'procedural' 은 임시 절차 타일 팩(받지 않고 코드로 만든다).
import { ARTPACK_MANIFEST_FILE, parseArtpackManifest, type ArtpackManifest } from './manifest';
import { proceduralArtpack, type RgbaAtlas } from './procedural';

export const PROCEDURAL_ARTPACK_SOURCE = 'procedural';

export interface LoadedArtpack {
  manifest: ArtpackManifest;
  /** Atlas pictures in manifest order. */
  atlases: CanvasImageSource[];
}

const packs = new Map<string, Promise<LoadedArtpack>>();

function rgbaCanvas(atlas: RgbaAtlas): OffscreenCanvas {
  const canvas = new OffscreenCanvas(atlas.width, atlas.height);
  canvas.getContext('2d')!.putImageData(new ImageData(new Uint8ClampedArray(atlas.rgba), atlas.width, atlas.height), 0, 0);
  return canvas;
}

async function sha256Hex(bytes: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

async function fetchPack(baseUrl: string): Promise<LoadedArtpack> {
  const base = baseUrl.replace(/\/+$/, '');
  const response = await fetch(`${base}/${ARTPACK_MANIFEST_FILE}`);
  if (!response.ok) throw new Error(`${base}/${ARTPACK_MANIFEST_FILE}: HTTP ${response.status}`);
  const manifest = parseArtpackManifest(await response.json());
  const atlases = await Promise.all(
    manifest.atlases.map(async (atlas) => {
      const file = await fetch(`${base}/${atlas.file}`);
      if (!file.ok) throw new Error(`${base}/${atlas.file}: HTTP ${file.status}`);
      const bytes = await file.arrayBuffer();
      if (atlas.sha256 && (await sha256Hex(bytes)) !== atlas.sha256) throw new Error(`${atlas.file}: sha256 differs from the manifest`);
      const picture = await createImageBitmap(new Blob([bytes], { type: 'image/png' }));
      if (picture.width !== atlas.width || picture.height !== atlas.height) throw new Error(`${atlas.file}: ${picture.width}×${picture.height}, manifest says ${atlas.width}×${atlas.height}`);
      return picture;
    }),
  );
  return { manifest, atlases };
}

/** `source` = 'procedural' or a pack base URL (holding manifest.json and its atlases). */
export function loadArtpack(source: string): Promise<LoadedArtpack> {
  const known = packs.get(source);
  if (known) return known;
  const load =
    source === PROCEDURAL_ARTPACK_SOURCE
      ? Promise.resolve().then(() => {
          const pack = proceduralArtpack();
          return { manifest: pack.manifest, atlases: pack.atlases.map(rgbaCanvas) };
        })
      : fetchPack(source);
  packs.set(source, load);
  load.catch(() => packs.delete(source));
  return load;
}
