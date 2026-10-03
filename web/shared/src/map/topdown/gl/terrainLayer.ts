// 지형 층: 화면 전체를 삼각형 하나로 그리고, 셰이더가 칸 번호를 읽어 키트 타일을 찍는다.
// 세력색 지붕 · 국경 띠 · 행정 경계 · 안개 · 대상 고르기 강조는 구역 표 텍스처로 얹는다(설계서 §2.4).
import { NO_TILE, OUT_OF_SCOPE_LAND, type Camera, type ChunkData, type MapShape, type Viewport } from '../types';
import {
  bindTexture,
  compileProgram,
  createColorTexture,
  createIntegerArrayTexture,
  createIntegerTexture,
  GlError,
  uniformLocations,
  updateIntegerTexture,
} from './glUtil';

const VERTEX = `#version 300 es
void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}`;

const FRAGMENT = `#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
precision highp usampler2DArray;

uniform vec2 uViewport;
uniform float uDpr;
uniform vec2 uCenter;
uniform float uZoom;
uniform ivec2 uMapSize;
uniform int uChunkSize;
uniform usampler2D uChunkTable;
uniform usampler2DArray uChunks;
uniform usampler2D uOverview;
uniform int uOverviewBlock;
uniform int uHasOverview;
uniform int uHasMips;
uniform int uHasProvTable;
uniform int uHasAdmin;
uniform int uForceOverview;
uniform usampler2D uKitIndex;
uniform int uAtlasColumns;
uniform sampler2D uMip8;
uniform sampler2D uMip4;
uniform sampler2D uMip2;
uniform sampler2D uMip1;
uniform sampler2D uPalette;
uniform usampler2D uProvTable;
uniform sampler2D uNationPalette;
uniform usampler2D uAdmin;
uniform float uBandPx;
uniform int uAdminLines;
uniform int uPickMode;
uniform vec3 uBackground;
uniform vec3 uAvailableColor;
uniform vec3 uUnavailableColor;
uniform vec3 uSelectedColor;
out vec4 outColor;

const uint NO_TILE = 65535u;
const int TABLE_WIDTH = 4096;
// 범위 밖 땅(D42): 키트 낮 팔레트의 들판색을 어둡게(types.ts OUT_OF_SCOPE_LAND)
const int OUT_OF_SCOPE_PALETTE = ${OUT_OF_SCOPE_LAND.paletteIndex};
const float OUT_OF_SCOPE_DIM = ${OUT_OF_SCOPE_LAND.dim.toFixed(2)};

// 칸 자료: x 타일, y 구역(0 없음), z 1이면 자료 있음
uvec3 cellData(ivec2 cell) {
  if (cell.x < 0 || cell.y < 0 || cell.x >= uMapSize.x || cell.y >= uMapSize.y) return uvec3(NO_TILE, 0u, 1u);
  if (uForceOverview == 0) {
    ivec2 chunk = cell / uChunkSize;
    uvec4 entry = texelFetch(uChunkTable, chunk, 0);
    if (entry.g == 1u) return uvec3(entry.b, entry.a, 1u);
    if (entry.r > 0u) {
      ivec2 local = cell - chunk * uChunkSize;
      return uvec3(texelFetch(uChunks, ivec3(local, int(entry.r) - 1), 0).rg, 1u);
    }
  }
  if (uHasOverview == 1) return uvec3(texelFetch(uOverview, cell / uOverviewBlock, 0).rg, 1u);
  return uvec3(NO_TILE, 0u, 0u);
}

uvec4 provinceRow(uint province) {
  if (uHasProvTable == 0) return uvec4(0u);
  int p = int(province);
  return texelFetch(uProvTable, ivec2(p % TABLE_WIDTH, p / TABLE_WIDTH), 0);
}

uvec4 adminRow(uint province) {
  if (uHasAdmin == 0) return uvec4(0u);
  int p = int(province);
  return texelFetch(uAdmin, ivec2(p % TABLE_WIDTH, p / TABLE_WIDTH), 0);
}

vec3 nationColor(uint slot, int shade) {
  return texelFetch(uNationPalette, ivec2(int(slot), shade), 0).rgb;
}

vec3 tileColor(uint tile, vec2 f, uint slot, float devicePerCell) {
  int t = int(tile);
  ivec2 origin = ivec2(t % uAtlasColumns, t / uAtlasColumns);
  // 밉이 아직 안 왔으면 16px 원본으로 그린다(작게 보일 때도 색은 맞다)
  if (devicePerCell >= 12.0 || uHasMips == 0) {
    uint v = texelFetch(uKitIndex, origin * 16 + ivec2(floor(f * 16.0)), 0).r;
    uint role = v >> 4u;
    if (role > 0u && slot > 0u) return nationColor(slot, int(role) - 1);
    return texelFetch(uPalette, ivec2(int(v & 15u), 0), 0).rgb;
  }
  if (devicePerCell >= 6.0) return texelFetch(uMip8, origin * 8 + ivec2(floor(f * 8.0)), 0).rgb;
  if (devicePerCell >= 3.0) return texelFetch(uMip4, origin * 4 + ivec2(floor(f * 4.0)), 0).rgb;
  if (devicePerCell >= 1.5) return texelFetch(uMip2, origin * 2 + ivec2(floor(f * 2.0)), 0).rgb;
  return texelFetch(uMip1, origin, 0).rgb;
}

void main() {
  vec2 screen = vec2(gl_FragCoord.x, uViewport.y * uDpr - gl_FragCoord.y) / uDpr;
  vec2 cellF = uCenter + (screen - uViewport * 0.5) / uZoom;
  // 개관(주 보기)에서는 4×4칸 덩이 하나를 한 칸처럼 다룬다
  float unit = uForceOverview == 1 ? float(uOverviewBlock) : 1.0;
  vec2 unitF = cellF / unit;
  ivec2 home = ivec2(floor(unitF));
  vec2 f = unitF - vec2(home);
  ivec2 cell = ivec2(floor(cellF));
  ivec2 probe = uForceOverview == 1 ? home * uOverviewBlock : cell;
  uvec3 here = cellData(probe);
  bool inside = probe.x >= 0 && probe.y >= 0 && probe.x < uMapSize.x && probe.y < uMapSize.y;
  if (here.z == 0u || !inside) {
    outColor = vec4(uBackground, 1.0);
    return;
  }
  // 격자 안의 그리지 않는 칸 = 범위 밖 땅(굽기 분류 V): 바탕색(바다처럼 보였다) 대신 흐린 땅색(D42)
  if (here.x == NO_TILE) {
    outColor = vec4(texelFetch(uPalette, ivec2(OUT_OF_SCOPE_PALETTE, 0), 0).rgb * OUT_OF_SCOPE_DIM, 1.0);
    return;
  }
  uvec4 row = provinceRow(here.y);
  float devicePerCell = uZoom * uDpr;
  vec2 sub = uForceOverview == 1 ? fract(cellF / float(uOverviewBlock)) : fract(cellF);
  vec3 color = tileColor(here.x, sub, row.r, uForceOverview == 1 ? devicePerCell * float(uOverviewBlock) : devicePerCell);

  // 시야: 첩보는 옅게, 안 보임은 어둡게
  if (row.g == 1u) color = mix(color, vec3(0.55), 0.28);
  else if (row.g == 2u) color *= 0.45;

  // 대상 고르기: 후보 밖은 어둡게(α .42). 빗금은 「미정찰」 전용이라 쓰지 않는다(K3 v3.1)
  if (uPickMode == 1 && row.b == 3u) color *= 0.58;

  // 가장자리 판정: 가까운 변만 이웃을 읽는다
  float unitPx = uZoom * unit;
  float band = min(uBandPx / unitPx, 0.5);
  float line = 1.0 / (unitPx * uDpr);
  float outline = min(3.0 / unitPx, 0.5);
  float thin = min(2.0 / unitPx, 0.5);
  float reach = max(max(band, outline), line);
  ivec2 dirs[4] = ivec2[4](ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
  float dist[4] = float[4](f.x, 1.0 - f.x, f.y, 1.0 - f.y);
  uvec4 admin = adminRow(here.y);
  for (int i = 0; i < 4; i++) {
    if (dist[i] >= reach) continue;
    ivec2 next = home + dirs[i];
    uvec3 there = cellData(uForceOverview == 1 ? next * uOverviewBlock : next);
    if (there.y == here.y) continue;
    uvec4 other = provinceRow(there.y);
    // 행정 경계선(레이어를 켰을 때): 구역 1 · 현 2 · 군 4 · 주 8
    uvec4 otherAdmin = adminRow(there.y);
    bool adminEdge = ((uAdminLines & 1) != 0)
      || ((uAdminLines & 2) != 0 && otherAdmin.r != admin.r)
      || ((uAdminLines & 4) != 0 && otherAdmin.g != admin.g)
      || ((uAdminLines & 8) != 0 && otherAdmin.b != admin.b);
    if (adminEdge && here.y > 0u && there.y > 0u && dist[i] < line) color = mix(color, vec3(0.08), 0.75);
    // 국경 띠: 이웃 세력이 다르면 이쪽 세력색 띠 + 바깥 검은 선
    if (row.r > 0u && other.r != row.r && dist[i] < band) {
      color = dist[i] < line ? vec3(0.05) : mix(color, nationColor(row.r, 0), 0.85);
    }
    // 테두리: 고른 곳 초점 3px, 가능 후보 이끼 2px, 불가 후보 적갈 점선 2px
    bool selected = (row.a & 1u) != 0u;
    if (selected && (other.a & 1u) == 0u && dist[i] < outline) color = uSelectedColor;
    else if (uPickMode == 1 && row.b == 1u && other.b != 1u && dist[i] < thin) color = uAvailableColor;
    else if (uPickMode == 1 && row.b == 2u && other.b != 2u && dist[i] < thin
      && mod(screen.x + screen.y, 10.0) < 6.0) color = uUnavailableColor;
  }
  outColor = vec4(color, 1.0);
}`;

const UNIFORMS = [
  'uViewport', 'uDpr', 'uCenter', 'uZoom', 'uMapSize', 'uChunkSize', 'uChunkTable', 'uChunks', 'uOverview',
  'uOverviewBlock', 'uHasOverview', 'uForceOverview', 'uKitIndex', 'uAtlasColumns', 'uMip8', 'uMip4', 'uMip2',
  'uMip1', 'uHasMips', 'uHasProvTable', 'uHasAdmin', 'uPalette', 'uProvTable', 'uNationPalette', 'uAdmin', 'uBandPx', 'uAdminLines', 'uPickMode',
  'uBackground', 'uAvailableColor', 'uUnavailableColor', 'uSelectedColor',
] as const;

export interface KitTextures {
  /** kit-index.png decoded: value = palette index + 16 × role. */
  index: { width: number; height: number; data: Uint8Array };
  /** 나중에 setMips로 줘도 된다(첫 그림에는 필요 없다). */
  mips?: Record<8 | 4 | 2 | 1, TexImageSource | { width: number; height: number; data: Uint8Array }>;
  /** 16 × RGBA day palette. */
  palette: Uint8Array;
  atlasColumns: number;
}

export interface TerrainDrawOptions {
  /** 州 보기에서는 개관 격자만 읽는다. */
  forceOverview: boolean;
  /** Nation border band width in CSS px (縣 3 · 郡 4 · 州 5). */
  bandPx: number;
  /** bit 1 province · 2 county · 4 commandery · 8 州. */
  adminLines: number;
  pickMode: boolean;
  background: readonly [number, number, number];
  /** K3 v3.1 토큰: 가능 후보 이끼 #8fa77a, 불가 후보 적갈 #e08a7c, 고른 곳 초점 #ffd36d. */
  availableColor: readonly [number, number, number];
  unavailableColor: readonly [number, number, number];
  selectedColor: readonly [number, number, number];
}

interface Slot { key: string; used: number }

/** Owns terrain textures and the program. One instance per GL context. */
export class TerrainLayer {
  private readonly gl: WebGL2RenderingContext;
  private readonly program: WebGLProgram;
  private readonly uniforms: Record<(typeof UNIFORMS)[number], WebGLUniformLocation | null>;
  private readonly vao: WebGLVertexArrayObject;
  private readonly gridCols: number;
  private readonly gridRows: number;
  private readonly tableData: Uint16Array;
  private readonly chunkTable: WebGLTexture;
  private readonly chunkArray: WebGLTexture;
  private readonly slots: (Slot | null)[];
  private readonly slotOf = new Map<string, number>();
  private readonly scratch: Uint16Array;
  private overview: WebGLTexture | null = null;
  private overviewBlock = 4;
  private kitIndex: WebGLTexture | null = null;
  private mips: Record<number, WebGLTexture> = {};
  private palette: WebGLTexture | null = null;
  private atlasColumns = 32;
  private provinceTable: WebGLTexture | null = null;
  private nationPalette: WebGLTexture | null = null;
  private admin: WebGLTexture | null = null;
  private clock = 0;
  private dummy: { uint: WebGLTexture; colour: WebGLTexture } | null = null;

  constructor(gl: WebGL2RenderingContext, readonly shape: MapShape, readonly chunkSize: number, capacity = 64) {
    this.gl = gl;
    this.program = compileProgram(gl, VERTEX, FRAGMENT);
    this.uniforms = uniformLocations(gl, this.program, UNIFORMS);
    const vao = gl.createVertexArray();
    if (!vao) throw new GlError('createVertexArray failed');
    this.vao = vao;
    this.gridCols = Math.ceil(shape.cols / chunkSize);
    this.gridRows = Math.ceil(shape.rows / chunkSize);
    this.tableData = new Uint16Array(this.gridCols * this.gridRows * 4);
    this.chunkTable = createIntegerTexture(gl, 'RGBA16UI', this.gridCols, this.gridRows, this.tableData);
    this.chunkArray = createIntegerArrayTexture(gl, 'RG16UI', chunkSize, chunkSize, capacity);
    this.slots = new Array<Slot | null>(capacity).fill(null);
    this.scratch = new Uint16Array(chunkSize * chunkSize * 2);
  }

  setKit(kit: KitTextures): void {
    const gl = this.gl;
    this.kitIndex = createIntegerTexture(gl, 'R8UI', kit.index.width, kit.index.height, kit.index.data);
    this.palette = createColorTexture(gl, { width: 16, height: 1, data: kit.palette });
    this.atlasColumns = kit.atlasColumns;
    if (kit.mips) this.setMips(kit.mips);
  }

  setMips(mips: KitTextures['mips'] & object): void {
    for (const size of [8, 4, 2, 1] as const) this.mips[size] = createColorTexture(this.gl, mips[size]);
  }

  setOverview(cols: number, rows: number, block: number, data: ChunkData): void {
    const pairs = new Uint16Array(cols * rows * 2);
    for (let i = 0; i < cols * rows; i += 1) {
      pairs[i * 2] = data.tiles[i];
      pairs[i * 2 + 1] = data.provinces[i];
    }
    if (this.overview) this.gl.deleteTexture(this.overview);
    this.overview = createIntegerTexture(this.gl, 'RG16UI', cols, rows, pairs);
    this.overviewBlock = block;
  }

  /** RGBA8 province table (see buildProvinceTable) and 256 × 2 nation palette (main row 0, shade row 1). */
  setProvinceTable(width: number, height: number, bytes: Uint8Array, nationPalette: Uint8Array): void {
    const gl = this.gl;
    if (this.provinceTable) gl.deleteTexture(this.provinceTable);
    this.provinceTable = createIntegerTexture(gl, 'RGBA8UI', width, height, bytes);
    const rows = new Uint8Array(256 * 2 * 4);
    for (let slot = 0; slot < 256; slot += 1) {
      rows.set(nationPalette.subarray(slot * 8, slot * 8 + 4), slot * 4);
      rows.set(nationPalette.subarray(slot * 8 + 4, slot * 8 + 8), (256 + slot) * 4);
    }
    if (this.nationPalette) gl.deleteTexture(this.nationPalette);
    this.nationPalette = createColorTexture(gl, { width: 256, height: 2, data: rows });
  }

  /** Province plane value → county · commandery · 州 ids (RGBA16UI, width 4096; 0 = none). */
  setAdmin(width: number, height: number, ids: Uint16Array): void {
    if (this.admin) this.gl.deleteTexture(this.admin);
    this.admin = createIntegerTexture(this.gl, 'RGBA16UI', width, height, ids);
  }

  hasChunk(cx: number, cy: number): boolean {
    const at = (cy * this.gridCols + cx) * 4;
    return this.tableData[at] > 0 || this.tableData[at + 1] === 1;
  }

  putChunk(cx: number, cy: number, data: ChunkData): void {
    const key = `${cx}_${cy}`;
    const tiles = data.tiles;
    const uniform = tiles.every((t) => t === tiles[0]) && data.provinces.every((p) => p === data.provinces[0]);
    const at = (cy * this.gridCols + cx) * 4;
    if (uniform) {
      this.dropChunk(cx, cy);
      this.tableData.set([0, 1, tiles[0], data.provinces[0]], at);
    } else {
      let slot = this.slotOf.get(key);
      if (slot === undefined) slot = this.claimSlot(key);
      for (let i = 0; i < tiles.length; i += 1) {
        this.scratch[i * 2] = tiles[i];
        this.scratch[i * 2 + 1] = data.provinces[i];
      }
      const gl = this.gl;
      gl.bindTexture(gl.TEXTURE_2D_ARRAY, this.chunkArray);
      gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
      gl.texSubImage3D(gl.TEXTURE_2D_ARRAY, 0, 0, 0, slot, this.chunkSize, this.chunkSize, 1,
        gl.RG_INTEGER, gl.UNSIGNED_SHORT, this.scratch);
      this.tableData.set([slot + 1, 0, 0, 0], at);
    }
    updateIntegerTexture(this.gl, this.chunkTable, 'RGBA16UI', cx, cy, 1, 1, this.tableData.subarray(at, at + 4));
  }

  /** Marks chunks as recently drawn so the slot allocator keeps them. */
  touch(keys: Iterable<string>): void {
    this.clock += 1;
    for (const key of keys) {
      const slot = this.slotOf.get(key);
      if (slot !== undefined) this.slots[slot]!.used = this.clock;
    }
  }

  dropChunk(cx: number, cy: number): void {
    const key = `${cx}_${cy}`;
    const slot = this.slotOf.get(key);
    if (slot !== undefined) {
      this.slots[slot] = null;
      this.slotOf.delete(key);
    }
    const at = (cy * this.gridCols + cx) * 4;
    this.tableData.fill(0, at, at + 4);
    updateIntegerTexture(this.gl, this.chunkTable, 'RGBA16UI', cx, cy, 1, 1, this.tableData.subarray(at, at + 4));
  }

  private claimSlot(key: string): number {
    let slot = this.slots.indexOf(null);
    if (slot < 0) {
      let oldest = 0;
      for (let i = 1; i < this.slots.length; i += 1) if (this.slots[i]!.used < this.slots[oldest]!.used) oldest = i;
      const [cx, cy] = this.slots[oldest]!.key.split('_').map(Number);
      this.dropChunk(cx, cy);
      slot = oldest;
    }
    this.slots[slot] = { key, used: this.clock };
    this.slotOf.set(key, slot);
    return slot;
  }

  /** 첫 그림에 필요한 것: 키트 색인과 팔레트뿐. 밉 · 개관 · 구역 표 · 행정 표는 오는 대로 쓴다. */
  ready(): boolean {
    return this.kitIndex !== null && this.palette !== null;
  }

  private placeholders(): { uint: WebGLTexture; colour: WebGLTexture } {
    if (!this.dummy) {
      this.dummy = {
        uint: createIntegerTexture(this.gl, 'RGBA8UI', 1, 1, new Uint8Array(4)),
        colour: createColorTexture(this.gl, { width: 1, height: 1, data: new Uint8Array(4) }),
      };
    }
    return this.dummy;
  }

  draw(cam: Camera, viewport: Viewport, options: TerrainDrawOptions): void {
    const gl = this.gl;
    if (!this.ready()) return;
    const u = this.uniforms;
    gl.viewport(0, 0, Math.round(viewport.width * viewport.dpr), Math.round(viewport.height * viewport.dpr));
    gl.useProgram(this.program);
    gl.bindVertexArray(this.vao);
    gl.uniform2f(u.uViewport, viewport.width, viewport.height);
    gl.uniform1f(u.uDpr, viewport.dpr);
    gl.uniform2f(u.uCenter, cam.center.col, cam.center.row);
    gl.uniform1f(u.uZoom, cam.zoom);
    gl.uniform2i(u.uMapSize, this.shape.cols, this.shape.rows);
    gl.uniform1i(u.uChunkSize, this.chunkSize);
    gl.uniform1i(u.uOverviewBlock, this.overviewBlock);
    gl.uniform1i(u.uHasOverview, this.overview ? 1 : 0);
    const hasMips = [8, 4, 2, 1].every((size) => this.mips[size]);
    gl.uniform1i(u.uHasMips, hasMips ? 1 : 0);
    gl.uniform1i(u.uHasProvTable, this.provinceTable && this.nationPalette ? 1 : 0);
    gl.uniform1i(u.uHasAdmin, this.admin ? 1 : 0);
    const { uint, colour } = this.placeholders();
    gl.uniform1i(u.uForceOverview, options.forceOverview && this.overview ? 1 : 0);
    gl.uniform1i(u.uAtlasColumns, this.atlasColumns);
    gl.uniform1f(u.uBandPx, options.bandPx);
    gl.uniform1i(u.uAdminLines, options.adminLines);
    gl.uniform1i(u.uPickMode, options.pickMode ? 1 : 0);
    gl.uniform3f(u.uBackground, ...options.background);
    gl.uniform3f(u.uAvailableColor, ...options.availableColor);
    gl.uniform3f(u.uUnavailableColor, ...options.unavailableColor);
    gl.uniform3f(u.uSelectedColor, ...options.selectedColor);
    const bind: [keyof typeof u, number, WebGLTexture | null][] = [
      ['uChunkTable', gl.TEXTURE_2D, this.chunkTable],
      ['uChunks', gl.TEXTURE_2D_ARRAY, this.chunkArray],
      ['uOverview', gl.TEXTURE_2D, this.overview ?? this.chunkTable],
      ['uKitIndex', gl.TEXTURE_2D, this.kitIndex],
      ['uMip8', gl.TEXTURE_2D, this.mips[8] ?? colour],
      ['uMip4', gl.TEXTURE_2D, this.mips[4] ?? colour],
      ['uMip2', gl.TEXTURE_2D, this.mips[2] ?? colour],
      ['uMip1', gl.TEXTURE_2D, this.mips[1] ?? colour],
      ['uPalette', gl.TEXTURE_2D, this.palette],
      ['uProvTable', gl.TEXTURE_2D, this.provinceTable ?? uint],
      ['uNationPalette', gl.TEXTURE_2D, this.nationPalette ?? colour],
      ['uAdmin', gl.TEXTURE_2D, this.admin ?? uint],
    ];
    bind.forEach(([name, target, texture], unit) => {
      bindTexture(gl, unit, target, texture);
      gl.uniform1i(u[name], unit);
    });
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  dispose(): void {
    const gl = this.gl;
    for (const texture of [this.chunkTable, this.chunkArray, this.overview, this.kitIndex, this.palette,
      this.provinceTable, this.nationPalette, this.admin, this.dummy?.uint ?? null, this.dummy?.colour ?? null, ...Object.values(this.mips)]) {
      if (texture) gl.deleteTexture(texture);
    }
    gl.deleteVertexArray(this.vao);
    gl.deleteProgram(this.program);
  }
}

export { NO_TILE };
