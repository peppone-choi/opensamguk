'use client';

// 천하 지도 3D 아이소 렌더러 — iso3d glTF 애셋 + DEM 높낮이.
//
// 애셋 계약(web/game/public/models/iso3d/manifest.json):
//   · 지형 타일은 XZ 평면 1×1, Y 가 위. 아이소 다이아몬드는 카메라가 만든다.
//   · 지형 타일은 **윗면만** 있다. 같은 높이 이웃이 이음매 없이 이어지게 하려는 것이다.
//   · 흙벽은 terrain/skirt.gltf 한 장뿐이고, 렌더러가 단차·맵 가장자리에만 세운다.
//   · 정점색만 쓴다. 세력색은 런타임이 재질에 **곱한다**(instanceColor).
//
// 높낮이는 tools/map/build_elevation_grid.py 가 NOAA ETOPO1 에서 뽑은 DEM 단차(0..6)다.
// 3D 는 타일마다 정수 단(段)으로만 올린다 — 애셋 윗면이 평평해서 타일 안 경사를 표현할
// 수단이 없다. 타일 안 경사(mask)는 2D 스프라이트 렌더러 쪽 개념이다.

import { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import {
  CAMERA_AZIMUTH_RAD,
  CAMERA_ELEVATION_RAD,
  HEIGHT_STEP_WORLD,
  SEAT_ONLY_TILE_PIXELS,
  TERRAIN,
  TERRAIN_ASSET_NAME,
  cityLabelBox,
  drawBattlefieldMark,
  drawCityFlag,
  drawCityName,
  drawCityRing,
  dropOverlappingLabels,
  firstPickableCity,
  indexTint,
  isExternalPlace,
  isWater,
  luminancePreserving,
  markerScale,
  mixToward,
  normaliseNationColor,
  type IsoMapData,
  type IsoBattlefieldMarker,
  type PlacedCity,
  type Rgb,
  type TintMode,
} from '@opensamguk/ui';

const MODEL_BASE = '/models/iso3d';

/**
 * 城 등급 → 건물 모델. **1:1, 11 단이다.** 정본 대응표는 두 곳에 같은 내용으로 있고
 * (`models/iso3d/manifest.json` 의 `cityLevelTiers`, `sprites/iso2d/manifest.json` 의 같은 키)
 * 이 표는 그 사본이다.
 *
 * 예전 표는 등급 숫자를 크기 순서로 읽었다. 그게 틀렸다 — 9 京·10 영현·11 장현은 한나라
 * 세계용으로 뒤에 덧붙인 값이고 10·11 은 縣, 즉 가장 작은 단위다. `capital` 에 9..11 을
 * 주는 바람에 774 성 중 604 개(78%)가 도성으로 그려졌다. 1 수(水)·2 진(鎭)·3 관(關)·
 * 4 이(夷)는 크기가 아니라 종류다 — 水寨·營寨·關城·이민족 야영이고, 엔진도 그렇게 본다
 * (`WarUnitCity.kt:50-51` 이 1·3 등급에만 훈련 보너스를 준다).
 */
const BUILDING_TIERS: { name: string; from: number; to: number }[] = [
  { name: 'water', from: 1, to: 1 },
  { name: 'garrison', from: 2, to: 2 },
  { name: 'pass', from: 3, to: 3 },
  { name: 'tribal', from: 4, to: 4 },
  { name: 'commandery', from: 5, to: 5 },
  { name: 'commandery-mid', from: 6, to: 6 },
  { name: 'commandery-major', from: 7, to: 7 },
  { name: 'commandery-grand', from: 8, to: 8 },
  { name: 'capital', from: 9, to: 9 },
  { name: 'county', from: 10, to: 10 },
  { name: 'county-small', from: 11, to: 11 },
];

export type { TintMode };

const EMPTY_CITIES: readonly PlacedCity[] = [];
const EMPTY_BATTLEFIELDS: readonly IsoBattlefieldMarker[] = [];

export interface IsoMap3DProps {
  data: IsoMapData;
  /** 세력색 합성 세기 0..1. 0 이면 지형만 보인다. */
  tintStrength: number;
  tintMode: TintMode;
  /** 국가색이 실제로 있을 때 쓰는 표. 郡 인덱스 → 자유 hex. 없으면 랩 색으로 떨어진다. */
  nationColorByOwner?: Record<number, string>;
  /** 격자에 앉힌 게임 도시. 이게 있어야 눌러서 도시로 들어갈 수 있다. */
  cities?: readonly PlacedCity[];
  hideCityNames?: boolean;
  currentCityId?: number | null;
  selectedCityId?: number | null;
  onPickTile?: (tile: { col: number; row: number } | null) => void;
  /** 城 을 눌렀을 때. 붙어 있으면 城 이 지형보다 먼저 집힌다. */
  /** pointerType 은 직전 pointerdown 의 것이다 — 2D 판과 같은 계약이다. */
  onPickCity?: (city: PlacedCity, activation: { pointerType: string }) => void;
  /**
   * 城 위에 마우스를 **얹었을 때**. 좌표는 겹판 왼위 기준 화면 좌표다.
   * 城 밖으로 나가면 null 로 한 번 더 부른다. 툴팁은 이걸로 뜬다 — 2D 판과 같은 계약이다.
   */
  onHoverCity?: (city: PlacedCity | null, at: { x: number; y: number }) => void;
  /** 전장. 城 위에 마름모로 얹고 城 보다 먼저 집힌다. */
  battlefields?: readonly IsoBattlefieldMarker[];
  onPickBattlefield?: (target: IsoBattlefieldMarker) => void;
  /** 렌더 통계(타일·흙벽·治所 수)를 캔버스 왼쪽 아래에 띄운다. 랩 전용이다. */
  showStats?: boolean;
  className?: string;
}

interface Loaded {
  terrain: Map<number, THREE.BufferGeometry>;
  skirt: THREE.BufferGeometry;
  building: Map<string, THREE.BufferGeometry>;
}

async function loadGeometries(signal: AbortSignal): Promise<Loaded> {
  const loader = new GLTFLoader();
  const load = (path: string) => new Promise<THREE.BufferGeometry>((resolve, reject) => {
    loader.load(
      `${MODEL_BASE}/${path}`,
      (gltf) => {
        let found: THREE.BufferGeometry | null = null;
        gltf.scene.traverse((node) => {
          if (!found && (node as THREE.Mesh).isMesh) found = (node as THREE.Mesh).geometry;
        });
        if (found) {
          // 애셋에 NORMAL 이 없다(POSITION·COLOR_0 뿐이다). 법선 속성이 비면 WebGL 이
          // 기본값 (0,0,1) 을 물려서 모든 면이 같은 방향을 보고, 램버트 조명이 새까맣게 죽는다.
          // 여기서 면에서 직접 계산해 준다.
          const geometry = found as THREE.BufferGeometry;
          if (!geometry.getAttribute('normal')) geometry.computeVertexNormals();
          resolve(geometry);
        } else reject(new Error(`${path} 안에 메시가 없다`));
      },
      undefined,
      () => reject(new Error(`${path} 를 못 불러왔다`)),
    );
  });

  const terrainCodes = Object.values(TERRAIN).filter((code) => code !== TERRAIN.OUT_OF_SCOPE);
  const [terrainGeoms, skirt, buildingGeoms] = await Promise.all([
    Promise.all(terrainCodes.map((code) => load(`terrain/${TERRAIN_ASSET_NAME[code]}.gltf`))),
    load('terrain/skirt.gltf'),
    Promise.all(BUILDING_TIERS.map((tier) => load(`building/${tier.name}.gltf`))),
  ]);
  if (signal.aborted) throw new Error('중단됨');

  return {
    terrain: new Map(terrainCodes.map((code, i) => [code, terrainGeoms[i]])),
    skirt,
    building: new Map(BUILDING_TIERS.map((tier, i) => [tier.name, buildingGeoms[i]])),
  };
}

function rgbCss({ r, g, b }: Rgb): string {
  const to = (v: number) => Math.round(Math.max(0, Math.min(1, v)) * 255);
  return `rgb(${to(r)} ${to(g)} ${to(b)})`;
}

function tileMaterial(): THREE.MeshLambertMaterial {
  // Lambert 로 충분하다 — 애셋에 텍스처가 없고 금속·거칠기 표현이 필요 없다.
  // vertexColors 와 instanceColor 가 함께 곱해진다(three color_vertex).
  return new THREE.MeshLambertMaterial({ vertexColors: true });
}

export function IsoMap3D({
  data,
  tintStrength,
  tintMode,
  nationColorByOwner,
  cities = EMPTY_CITIES,
  hideCityNames = false,
  currentCityId = null,
  selectedCityId = null,
  onPickTile,
  onPickCity,
  onHoverCity,
  battlefields = EMPTY_BATTLEFIELDS,
  onPickBattlefield,
  showStats = false,
  className,
}: IsoMap3DProps) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [stats, setStats] = useState<
    { tiles: number; skirts: number; seats: number; counties: number } | null
  >(null);

  // 색 갱신만 따로 할 수 있게 씬 핸들을 남긴다.
  const tintRef = useRef<{
    apply: (strength: number, mode: TintMode) => void;
    // 국경은 세력색으로 가르므로 색이 바뀌면 같이 다시 구워야 한다.
    rebuildBorders: () => void;
  } | null>(null);
  // 색 인자는 씬을 다시 짓지 않는다 — 그래서 주 effect 의 의존성에 없다. 클로저로 읽으면
  // 씬을 지을 때의 옛 값에 붙박이고, 세력색이 지형보다 늦게 도착하면(IsoWorldMap 은
  // 그럴 수 있다) 국가색 대신 郡 인덱스 색이 그대로 남는다. 항상 최신 것을 ref 로 읽는다.
  const paintRef = useRef(nationColorByOwner);
  paintRef.current = nationColorByOwner;
  const tintPropsRef = useRef({ tintStrength, tintMode });
  tintPropsRef.current = { tintStrength, tintMode };
  // 캔버스 위 +/−/전체 단추가 쥐는 손잡이. 휠이 없는 손가락 조작에서 유일한 확대 수단이다.
  const zoomRef = useRef<{ by: (factor: number) => void; fit: () => void } | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return undefined;

    const controller = new AbortController();
    let renderer: THREE.WebGLRenderer | null = null;
    let frame = 0;
    const disposables: { dispose: () => void }[] = [];

    (async () => {
      const canvas = document.createElement('canvas');
      let context: WebGLRenderingContext | WebGL2RenderingContext | null = null;
      try {
        context = canvas.getContext('webgl2') ?? canvas.getContext('webgl');
      } catch {
        context = null;
      }
      if (!context) throw new Error('이 브라우저에서 WebGL 을 쓸 수 없다');

      const loaded = await loadGeometries(controller.signal);
      if (controller.signal.aborted) return;

      const { grid, owner, parentOwner } = data;
      // 높이는 DEM 단(level)을 그대로 쓴다. 2D 가 쓰는 baseHeight 는 스프라이트 계약
      // 때문에 격자 전체를 1-립시츠로 눌러 놓은 값이라 급애가 남지 않는다.
      // glTF 쪽은 윗면만 있는 타일에 벽을 임의 높이로 세우므로 그 제약이 없다.
      const { cols, rows, code, level: baseHeight, playable } = grid;
      // 지도 밖 타일을 배경 쪽으로 눌러 두는 곱셈 색. 2D 스프라이트 사본과 같은 세기다.
      const OUT_OF_PLAY: Rgb = { r: 0.38, g: 0.38, b: 0.38 };
      const halfCols = (cols - 1) / 2;
      const halfRows = (rows - 1) / 2;
      const y = (level: number) => level * HEIGHT_STEP_WORLD;

      const scene = new THREE.Scene();
      scene.background = new THREE.Color(0x0c0f0e); // 팔레트 --bg

      // ── 지형 인스턴스 ────────────────────────────────────────────────
      const byCode = new Map<number, number[]>();
      for (let i = 0; i < code.length; i += 1) {
        const list = byCode.get(code[i]);
        if (list) list.push(i);
        else byCode.set(code[i], [i]);
      }

      // 縣 경계는 축소 상태에서 끈다 — 縣 건물이 사라지는 배율에서 금만 남으면 지저분하다.
      let countyBorders: THREE.LineSegments | null = null;
      let commanderyBorders: THREE.Mesh | null = null;
      // 국경은 세력색이 정해져야 그을 수 있는데 색은 지형보다 늦게 올 수 있다(IsoWorldMap).
      // 그래서 한 그룹에 담아 두고 색이 바뀌면 통째로 다시 굽는다.
      const borderGroup = new THREE.Group();

      const material = tileMaterial();
      disposables.push(material);
      const dummy = new THREE.Object3D();
      const tileMeshes: { mesh: THREE.InstancedMesh; tiles: number[] }[] = [];
      let tileTotal = 0;

      const addTiles = (
        geometry: THREE.BufferGeometry,
        instances: { i: number; rotY: number }[],
      ) => {
        if (instances.length === 0) return;
        const mesh = new THREE.InstancedMesh(geometry, material, instances.length);
        mesh.frustumCulled = false;
        for (let n = 0; n < instances.length; n += 1) {
          const { i, rotY } = instances[n];
          dummy.position.set(
            (i % cols) - halfCols, y(baseHeight[i]), ((i / cols) | 0) - halfRows,
          );
          dummy.rotation.set(0, rotY, 0);
          dummy.scale.set(1, 1, 1);
          dummy.updateMatrix();
          mesh.setMatrixAt(n, dummy.matrix);
        }
        mesh.instanceMatrix.needsUpdate = true;
        scene.add(mesh);
        tileMeshes.push({ mesh, tiles: instances.map((x) => x.i) });
        tileTotal += instances.length;
      };

      // 강줄기는 **물길 방향으로 돌려서** 놓는다.
      //
      // river.gltf 는 X 축(동서)으로 파인 수로 한 벌뿐이다(실측: 둑이 z ±0.3..±0.5 에서
      // 솟고 가운데 |z| < 0.3 만 −0.04 로 내려앉는다). 전부 같은 각으로 깔면 남북으로
      // 흐르는 강이 동서 도랑 여럿으로 끊겨 「막혀 보인다」(2026-09-09 지적).
      //
      // 굽이·합류 조각은 없다. 둑이 X 축용 한 벌뿐이라 두 각을 겹쳐 놓으면 둑의 합집합이
      // 네 변을 둘러싸 **웅덩이**가 된다 — 막힌 것처럼 보이는 그 모양이 바로 그것이다.
      // 그래서 꺾이는 칸에는 둑이 없는 lake 조각을 깐다. 물이 그대로 지나간다.
      const riverGeometry = loaded.terrain.get(TERRAIN.RIVER);
      const lakeGeometry = loaded.terrain.get(TERRAIN.LAKE);
      const water = (c: number, r: number) => c >= 0 && c < cols && r >= 0 && r < rows
        && isWater(code[r * cols + c]);

      for (const [terrainCode, tiles] of byCode) {
        const geometry = loaded.terrain.get(terrainCode);
        if (!geometry) continue;
        if (terrainCode === TERRAIN.RIVER && riverGeometry && lakeGeometry) {
          const straight: { i: number; rotY: number }[] = [];
          const junction: { i: number; rotY: number }[] = [];
          for (const i of tiles) {
            const c = i % cols;
            const r = (i / cols) | 0;
            const ew = water(c - 1, r) || water(c + 1, r);
            const ns = water(c, r - 1) || water(c, r + 1);
            if (ew && ns) junction.push({ i, rotY: 0 });
            else straight.push({ i, rotY: ns ? Math.PI / 2 : 0 });
          }
          addTiles(riverGeometry, straight);
          addTiles(lakeGeometry, junction);
          continue;
        }
        addTiles(geometry, tiles.map((i) => ({ i, rotY: 0 })));
      }

      // ── 흙벽(skirt) ──────────────────────────────────────────────────
      // 이웃보다 높은 변, 그리고 맵 가장자리에만 세운다. 벽 하나가 단차 전체를 덮도록
      // Y 로 늘린다(계단마다 한 장씩 쌓으면 같은 그림에 드로우콜만 는다).
      //
      // 회전각 주의. skirt.gltf 의 감김이 [0,2,1] 이라 앞면 법선이 로컬 −Z 다.
      // 회전을 반대로 주면 카메라를 향하는 남·동 벽이 전부 후면 컬링돼 사라지고,
      // 그 자리에 배경(#0c0f0e)이 비쳐 단차가 검은 띠로 나온다.
      const EDGES: { dc: number; dr: number; rotY: number }[] = [
        { dc: 0, dr: -1, rotY: 0 },              // 북 — 앞면 −Z
        { dc: 1, dr: 0, rotY: -Math.PI / 2 },    // 동 — 앞면 +X
        { dc: 0, dr: 1, rotY: Math.PI },         // 남 — 앞면 +Z
        { dc: -1, dr: 0, rotY: Math.PI / 2 },    // 서 — 앞면 −X
      ];
      // 물 윗면은 타일 평면보다 조금 내려가 있다(lake.gltf −0.05 · river.gltf −0.04).
      // 그 0.05 만큼도 벽으로 막아야 한다. 안 그러면 같은 단인 뭍과 물 사이에
      // 웅덩이 안쪽 면이 빈 채로 남아, 카메라 쪽을 향하는 북·서 물가마다 배경색
      // 실선이 그어진다(실측: 1280×740 안에 배경 픽셀 1,255 개).
      const WATER_SINK = 0.05;
      const surface = (i: number) => y(baseHeight[i]) - (isWater(code[i]) ? WATER_SINK : 0);
      const walls: { i: number; edge: number; top: number; height: number }[] = [];
      for (let r = 0; r < rows; r += 1) {
        for (let c = 0; c < cols; c += 1) {
          const i = r * cols + c;
          const top = surface(i);
          for (let e = 0; e < 4; e += 1) {
            const nc = c + EDGES[e].dc;
            const nr = r + EDGES[e].dr;
            const inside = nc >= 0 && nc < cols && nr >= 0 && nr < rows;
            const bottom = inside ? surface(nr * cols + nc) : -HEIGHT_STEP_WORLD;
            const height = top - bottom;
            if (height > 1e-4) walls.push({ i, edge: e, top, height });
          }
        }
      }
      const skirtMesh = new THREE.InstancedMesh(loaded.skirt, material, Math.max(walls.length, 1));
      skirtMesh.frustumCulled = false;
      for (let n = 0; n < walls.length; n += 1) {
        const { i, edge, top, height } = walls[n];
        const c = i % cols;
        const r = (i / cols) | 0;
        dummy.position.set(
          c - halfCols + EDGES[edge].dc * 0.5,
          top - height,
          r - halfRows + EDGES[edge].dr * 0.5,
        );
        dummy.rotation.set(0, EDGES[edge].rotY, 0);
        dummy.scale.set(1, height, 1);
        dummy.updateMatrix();
        skirtMesh.setMatrixAt(n, dummy.matrix);
      }
      skirtMesh.count = walls.length;
      skirtMesh.instanceMatrix.needsUpdate = true;
      // 흙벽은 세력색을 받지 않는다. 지도 안팎만 한 번 칠하고 그대로 둔다 —
      // 윗면만 누르고 벽을 놔두면 지도 밖 산이 밝은 벽으로 다시 튀어나온다.
      {
        const wallColor = new THREE.Color();
        for (let n = 0; n < walls.length; n += 1) {
          const dim = playable[walls[n].i] === 0;
          wallColor.setRGB(
            dim ? OUT_OF_PLAY.r : 1, dim ? OUT_OF_PLAY.g : 1, dim ? OUT_OF_PLAY.b : 1,
          );
          skirtMesh.setColorAt(n, wallColor);
        }
        if (skirtMesh.instanceColor) skirtMesh.instanceColor.needsUpdate = true;
      }
      scene.add(skirtMesh);

      // ── 縣·郡·국가 경계 ──────────────────────────────────────────────
      //
      // 색만으로는 어디까지가 한 세력인지 안 읽힌다(2026-09-09 지적). 2D 판과 같은 판정을
      // 쓰되 여기서는 씬에 **한 번** 굽고 그대로 둔다 — 프레임마다 32,064 칸을 화면으로
      // 투영하면 끌기가 무거워진다.
      //
      // 선 굵기(linewidth)는 WebGL 에서 항상 1px 이라 등급이 안 갈린다. 그래서 국가·郡 는
      // 띠(삼각형 두 장)로 깔고 縣 만 선으로 둔다. 띠는 타일 윗면보다 살짝 띄운다.
      scene.add(borderGroup);
      const rebuildBorders = () => {
        for (const child of borderGroup.children.slice()) {
          borderGroup.remove(child);
          const mesh = child as THREE.Mesh;
          (mesh.geometry as THREE.BufferGeometry | undefined)?.dispose();
          (mesh.material as THREE.Material | undefined)?.dispose();
        }
        countyBorders = null;
        commanderyBorders = null;
        const LIFT = 0.02;
        const nationBand: number[] = [];
        const commanderyBand: number[] = [];
        const countyLine: number[] = [];
        const paint = paintRef.current;
        // 세력 판정은 2D 와 같다 — 색 문자열이 같으면 같은 나라다(같은 나라의 두 縣
        // 사이에는 국경이 안 서고 郡 경계가 남는다).
        const nationKey = (i: number): string | null => {
          if (playable[i] === 0 || isWater(code[i])) return null;
          const o = owner[i];
          if (o < 0) return '';
          return paint?.[o] ?? '';
        };
        // 축에 나란한 변 하나를 폭 w 의 띠로 편다.
        const band = (out: number[], x1: number, z1: number, x2: number, z2: number,
          h: number, w: number) => {
          const dx = x2 - x1;
          const dz = z2 - z1;
          const len = Math.hypot(dx, dz) || 1;
          const nx = (-dz / len) * (w / 2);
          const nz = (dx / len) * (w / 2);
          const ax = x1 - nx; const az = z1 - nz;
          const bx = x1 + nx; const bz = z1 + nz;
          const cx = x2 + nx; const cz = z2 + nz;
          const ex = x2 - nx; const ez = z2 - nz;
          out.push(ax, h, az, bx, h, bz, cx, h, cz, ax, h, az, cx, h, cz, ex, h, ez);
        };
        for (let r = 0; r < rows; r += 1) {
          for (let c = 0; c < cols; c += 1) {
            const i = r * cols + c;
            const key = nationKey(i);
            if (key === null) continue;
            const wx = c - halfCols;
            const wz = r - halfRows;
            const edges: [number, number, number, number, number][] = [];
            // (c+1, r) 과 나누는 변은 x = wx+0.5 위의 세로 금이다.
            if (c + 1 < cols) edges.push([i + 1, wx + 0.5, wz - 0.5, wx + 0.5, wz + 0.5]);
            // (c, r+1) 과 나누는 변은 z = wz+0.5 위의 가로 금이다.
            if (r + 1 < rows) edges.push([i + cols, wx - 0.5, wz + 0.5, wx + 0.5, wz + 0.5]);
            for (const [j, x1, z1, x2, z2] of edges) {
              const other = nationKey(j);
              if (other === null) continue;
              const h = Math.max(surface(i), surface(j)) + LIFT;
              if (other !== key) band(nationBand, x1, z1, x2, z2, h, 0.16);
              else if (parentOwner[j] !== parentOwner[i]) {
                band(commanderyBand, x1, z1, x2, z2, h, 0.08);
              } else if (owner[j] !== owner[i]) {
                countyLine.push(x1, h, z1, x2, h, z2);
              }
            }
          }
        }
        const bandMesh = (data3: number[], color: number, opacity: number) => {
          if (data3.length === 0) return null;
          const geometry = new THREE.BufferGeometry();
          geometry.setAttribute('position', new THREE.Float32BufferAttribute(data3, 3));
          const bandMaterial = new THREE.MeshBasicMaterial({
            color, transparent: opacity < 1, opacity, depthWrite: false,
          });
          const mesh = new THREE.Mesh(geometry, bandMaterial);
          mesh.frustumCulled = false;
          borderGroup.add(mesh);
          return mesh;
        };
        bandMesh(nationBand, 0x0c0f0e, 0.9);
        commanderyBorders = bandMesh(commanderyBand, 0x0c0f0e, 0.55);
        if (countyLine.length > 0) {
          const geometry = new THREE.BufferGeometry();
          geometry.setAttribute('position', new THREE.Float32BufferAttribute(countyLine, 3));
          const lineMaterial = new THREE.LineBasicMaterial({
            color: 0x0c0f0e, transparent: true, opacity: 0.32, depthWrite: false,
          });
          countyBorders = new THREE.LineSegments(geometry, lineMaterial);
          countyBorders.frustumCulled = false;
          borderGroup.add(countyBorders);
        }
      };
      rebuildBorders();

      // ── 城 ───────────────────────────────────────────────────────────
      // 郡治(seat)와 縣을 따로 세운다. 축소 상태에서는 縣 쪽 메시를 통째로 끈다
      // (SEAT_ONLY_TILE_PIXELS 주석 참조). 2D 와 같은 눈금이라 두 판이 같이 움직인다.
      //
      // 자리는 **소수** 타일 좌표다. 정수로 내리면 37 곳이 다른 도시와 같은 타일에 겹쳐
      // 통째로 가려지고, 그러면 눌러서 들어갈 수 없다(placeGameCities 주석 참조).
      // 다만 원좌표(col/row)가 아니라 drawCol/drawRow 를 쓴다 — 밑면이 제 칸을 벗어나지
      // 않도록 눌러 둔 값이고, 같은 칸에 여럿이면 drawScale 로 함께 줄어든다.
      const countyMeshes: THREE.InstancedMesh[] = [];
      const cityMeshes: { mesh: THREE.InstancedMesh; cities: PlacedCity[] }[] = [];
      // 전장은 3D 물체가 아니라 겹판 위 화면 좌표다. drawLabels 가 채우고 집기가 읽는다.
      const fieldHits: { target: IsoBattlefieldMarker; x: number; y: number; radius: number }[] = [];
      // 城 집기 상자도 겹판 화면 좌표다 — 마우스를 얹으면 툴팁이 여기서 뜬다.
      const cityHits: { city: PlacedCity; x0: number; x1: number; y0: number; y1: number }[] = [];
      let seatTotal = 0;
      let countyTotal = 0;
      const inGrid = (city: PlacedCity) => city.col >= 0 && city.col < cols
        && city.row >= 0 && city.row < rows;
      for (const tier of BUILDING_TIERS) {
        const geometry = loaded.building.get(tier.name);
        if (!geometry) continue;
        const tierCities = cities.filter(
          (city) => city.level >= tier.from && city.level <= tier.to && inGrid(city),
        );
        for (const seat of [true, false]) {
          const placed = tierCities.filter((city) => city.seat === seat);
          if (placed.length === 0) continue;
          const mesh = new THREE.InstancedMesh(geometry, material, placed.length);
          mesh.frustumCulled = false;
          for (let n = 0; n < placed.length; n += 1) {
            const city = placed[n];
            const i = city.tileRow * cols + city.tileCol;
            dummy.position.set(city.drawCol - halfCols, y(baseHeight[i]), city.drawRow - halfRows);
            dummy.rotation.set(0, 0, 0);
            dummy.scale.set(city.drawScale, city.drawScale, city.drawScale);
            dummy.updateMatrix();
            mesh.setMatrixAt(n, dummy.matrix);
          }
          mesh.instanceMatrix.needsUpdate = true;
          scene.add(mesh);
          cityMeshes.push({ mesh, cities: placed });
          if (seat) seatTotal += placed.length;
          else {
            countyMeshes.push(mesh);
            countyTotal += placed.length;
          }
        }
      }
      // 城 은 세력색을 곱하지 않는다 — 등급별 실루엣이 색에 먹히면 城 크기가 안 읽힌다.
      // 소속은 아래 라벨 층의 색 점이 말한다.
      setStats({
        tiles: tileTotal, skirts: walls.length, seats: seatTotal, counties: countyTotal,
      });

      // ── 세력색 합성 ───────────────────────────────────────────────────
      // 곱하기이되 **밝기를 보존하는** 색을 곱한다(luminancePreserving). 정규화색을 그대로
      // 곱하면 국가색 밝기만큼 땅이 통째로 어두워진다 — 「세력색이 너무 짙다」(2026-09-09).
      // 2D 판은 같은 일을 캔버스 합성 모드 'color' 로 한다.
      const white: Rgb = { r: 1, g: 1, b: 1 };
      const color = new THREE.Color();
      const applyTint = (strength: number, mode: TintMode) => {
        const paint = paintRef.current;
        // 같은 세기 값이라도 3D 는 2D 보다 색이 약하게 읽힌다. 2D 는 합성 모드 'color' 로
        // 색상을 통째로 바꾸는데 여기는 정점색에 곱하는 것뿐이기 때문이다. 두 판을 나란히
        // 두고 맞춘 보정값이다 — 부르는 쪽은 한 숫자만 준다.
        const tint = Math.min(1, strength * 1.6);
        for (const { mesh, tiles } of tileMeshes) {
          for (let n = 0; n < tiles.length; n += 1) {
            const i = tiles[n];
            let rgb = playable[i] === 0 ? OUT_OF_PLAY : white;
            if (playable[i] === 1 && mode !== 'none' && tint > 0 && !isWater(code[i])) {
              const key = mode === 'commandery' ? parentOwner[i] : owner[i];
              if (key >= 0) {
                const hex = paint?.[key];
                rgb = mixToward(
                  luminancePreserving(hex ? normaliseNationColor(hex) : indexTint(key)),
                  tint,
                );
              }
            }
            color.setRGB(rgb.r, rgb.g, rgb.b);
            mesh.setColorAt(n, color);
          }
          if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
        }
        renderOnce();
      };
      tintRef.current = { apply: applyTint, rebuildBorders };

      // ── 조명 ─────────────────────────────────────────────────────────
      // 해는 카메라 쪽 어깨 너머에 둔다. 흙벽은 남면(+Z)·동면(+X)만 카메라에 보이는데
      // 해를 반대편에 두면 그 두 면이 전부 그늘로 죽어 단차가 검은 띠로 나온다.
      // 두 면의 밝기는 일부러 다르게 둔다(+X 를 더 밝게) — 그래야 모서리가 읽힌다.
      const sun = new THREE.DirectionalLight(0xf3ead6, 1.9);
      sun.position.set(0.8, 1.0, 0.3);
      scene.add(sun);
      scene.add(new THREE.HemisphereLight(0x9fb3c8, 0x3a4038, 1.1));

      // ── 카메라 ───────────────────────────────────────────────────────
      // 고도 30°·방위 45°. 2:1 픽셀 아이소와 같은 각이라 2D 스프라이트판과 눈금이 맞는다.
      const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 4000);
      const dir = new THREE.Vector3(
        Math.sin(CAMERA_AZIMUTH_RAD) * Math.cos(CAMERA_ELEVATION_RAD),
        Math.sin(CAMERA_ELEVATION_RAD),
        Math.cos(CAMERA_AZIMUTH_RAD) * Math.cos(CAMERA_ELEVATION_RAD),
      );
      const target = new THREE.Vector3(0, 0, 0);
      // 화면 세로에 담을 세계 높이. 작을수록 확대.
      let span = Math.max(cols, rows) * 0.9;

      renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false });
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      canvas.style.width = '100%';
      canvas.style.height = '100%';
      canvas.style.display = 'block';
      canvas.style.cursor = 'grab';
      canvas.style.touchAction = 'none';
      host.appendChild(canvas);

      // 城 라벨·소속 점을 얹는 2D 층. WebGL 캔버스 위에 같은 크기로 겹쳐 둔다.
      // 스프라이트 텍스처나 DOM 노드 대신 이걸 쓰는 이유는 두 가지다 — 781 개 DOM 을
      // 프레임마다 옮기지 않아도 되고, 글자 모양이 2D 판과 똑같이 나온다.
      const overlay = document.createElement('canvas');
      overlay.style.position = 'absolute';
      overlay.style.inset = '0';
      overlay.style.width = '100%';
      overlay.style.height = '100%';
      overlay.style.pointerEvents = 'none';
      host.appendChild(overlay);
      const overlayContext = overlay.getContext('2d');
      const projected = new THREE.Vector3();

      const drawLabels = (seatOnly: boolean) => {
        if (!overlayContext) return;
        const dpr = Math.min(window.devicePixelRatio || 1, 2);
        const w = host.clientWidth || 1;
        const h = host.clientHeight || 1;
        if (overlay.width !== Math.round(w * dpr) || overlay.height !== Math.round(h * dpr)) {
          overlay.width = Math.round(w * dpr);
          overlay.height = Math.round(h * dpr);
        }
        overlayContext.setTransform(dpr, 0, 0, dpr, 0, 0);
        overlayContext.clearRect(0, 0, w, h);
        const showNames = !hideCityNames && !seatOnly;

        // 표식은 **화면 크기**로 그린다. 세계 물체로 세우면 전체 보기에서 1px 로 줄어
        // 사라지고 당기면 화면을 덮는다 — 2D 판이 그래서 「깃발이 없다」는 소리를 들었다.
        const tileWidth = (Math.SQRT2 * h) / span;
        const k = markerScale(tileWidth / 256);
        // 깃대 밑동을 건물 꼭대기쯤으로 올린다(건물이 대략 한 세계 단위다).
        const lift = Math.max(12, (h / span) * 1.1);
        const half = Math.max(15 * k, tileWidth * 0.22);
        const below = Math.max(9, tileWidth * 0.22);

        const drawn: { city: PlacedCity; sx: number; sy: number }[] = [];
        for (const city of cities) {
          if (seatOnly && !city.seat) continue;
          const i = city.tileRow * cols + city.tileCol;
          // 깃발·이름표는 건물이 실제로 선 자리에 붙는다.
          projected.set(city.drawCol - halfCols, y(baseHeight[i]), city.drawRow - halfRows);
          projected.project(camera);
          if (projected.z > 1) continue;
          const sx = (projected.x * 0.5 + 0.5) * w;
          const sy = (-projected.y * 0.5 + 0.5) * h;
          if (sx < -60 || sx > w + 60 || sy < -80 || sy > h + 60) continue;
          drawn.push({ city, sx, sy });
        }
        // 뒤쪽 城 부터 그린다 — 화면 아래일수록 앞이다.
        drawn.sort((a, b) => a.sy - b.sy);

        cityHits.length = 0;
        for (const { city, sx, sy } of drawn) {
          const top = drawCityFlag(overlayContext, sx, sy - lift, {
            color: city.nationColor ? rgbCss(normaliseNationColor(city.nationColor)) : null,
            capital: city.isCapital,
            k,
          });
          // 郡國 밖 세력은 게임 城 번호가 없다 — 그림·이름·깃발은 중원과 똑같이 나가되
          // 눌러 들어갈 데가 없으므로 집기 상자에서만 뺀다.
          if (isExternalPlace(city)) continue;
          // 집기 상자는 깃발 꼭대기부터 칸 아래까지 — 깃발을 얹어도 城 을 얹어도 잡힌다.
          cityHits.push({ city, x0: sx - half, x1: sx + half, y0: top - 2, y1: sy + below });
        }

        // 선택·주둔 테는 깃발 위에 얹는다 — 가려지면 어디가 내 城 인지 못 찾는다.
        for (const { city, sx, sy } of drawn) {
          const ring = city.id === currentCityId
            ? '#ffd36d' // --focus
            : city.id === selectedCityId ? '#ece6d8' : null; // --text
          if (ring) drawCityRing(overlayContext, sx, sy, { color: ring, k });
        }

        // 이름표. 겹치면 뒤엣것을 버린다 — 城 이 몰린 곳(한반도 남부 12 곳)에서
        // 글씨가 한 덩어리로 뭉개진다. 郡治가 먼저 자리를 잡는다. 2D 판과 같은 규칙이다.
        if (showNames) {
          const named = [...drawn].sort((a, b) => Number(b.city.seat) - Number(a.city.seat));
          const keepName = dropOverlappingLabels(
            named.map(({ city, sx, sy }) => cityLabelBox(sx, sy + below + 2, city.name, k)),
          );
          named.forEach(({ city, sx, sy }, n) => {
            if (keepName[n]) drawCityName(overlayContext, city.name, sx, sy + below + 2, k);
          });
        }

        // 전장 — 城 위에 마름모. 3D 는 인스턴스 메시가 아니라 이 겹판에 그리고,
        // 집기도 화면 좌표로 한다(레이캐스트 대상이 없다).
        fieldHits.length = 0;
        for (const field of battlefields) {
          const i = Math.min(rows - 1, Math.max(0, Math.floor(field.row))) * cols
            + Math.min(cols - 1, Math.max(0, Math.floor(field.col)));
          projected.set(field.col - halfCols, y(baseHeight[i]), field.row - halfRows);
          projected.project(camera);
          if (projected.z > 1) continue;
          const sx = (projected.x * 0.5 + 0.5) * w;
          const sy = (-projected.y * 0.5 + 0.5) * h;
          if (sx < -40 || sx > w + 40 || sy < -40 || sy > h + 40) continue;
          const radius = drawBattlefieldMark(overlayContext, sx, sy, {
            current: field.current === true,
            k,
          });
          fieldHits.push({ target: field, x: sx, y: sy, radius });
        }
      };

      const layout = () => {
        const w = host.clientWidth || 1;
        const h = host.clientHeight || 1;
        renderer!.setSize(w, h, false);
        const aspect = w / h;
        camera.left = (-span * aspect) / 2;
        camera.right = (span * aspect) / 2;
        camera.top = span / 2;
        camera.bottom = -span / 2;
        camera.near = 0.1;
        camera.far = 4000;
        camera.updateProjectionMatrix();
      };
      const place = () => {
        camera.position.copy(target).addScaledVector(dir, 1000);
        camera.up.set(0, 1, 0);
        camera.lookAt(target);
      };
      // 타일 한 칸이 화면에서 차지하는 가로 폭. 직교 카메라라 세로 span 이 화면 높이를
      // 덮으므로 1 세계 단위 = h/span px 이고, 아이소 각에서 타일 대각이 √2 다.
      const tilePixels = () => (Math.SQRT2 * (host.clientHeight || 1)) / span;
      const renderOnce = () => {
        if (frame) return;
        frame = requestAnimationFrame(() => {
          frame = 0;
          const px = tilePixels();
          const seatOnly = px < SEAT_ONLY_TILE_PIXELS;
          for (const mesh of countyMeshes) mesh.visible = !seatOnly;
          // 축소하면 아래 등급 경계부터 끈다 — 2D 판과 같은 눈금이다.
          if (countyBorders) countyBorders.visible = px >= 26;
          if (commanderyBorders) commanderyBorders.visible = px >= 16;
          canvas.dataset.seatOnly = String(seatOnly);
          layout();
          place();
          renderer!.render(scene, camera);
          drawLabels(seatOnly);
        });
      };

      applyTint(tintPropsRef.current.tintStrength, tintPropsRef.current.tintMode);

      // ── 조작: 끌어서 이동 · 휠로 확대 ────────────────────────────────
      let dragging = false;
      let lastX = 0;
      let lastY = 0;
      let lastPointerType = 'mouse';
      const onDown = (e: PointerEvent) => {
        lastPointerType = e.pointerType || 'mouse';
        dragging = true;
        lastX = e.clientX;
        lastY = e.clientY;
        canvas.style.cursor = 'grabbing';
        canvas.setPointerCapture(e.pointerId);
      };
      // 마우스를 얹기만 해도 城 정보가 나와야 한다 — 눌러야 나오는 건 지도가 아니라 목록이다.
      let hovered: number | null = null;
      const onMove = (e: PointerEvent) => {
        if (!dragging) {
          if (!onHoverCity) return;
          const rect = canvas.getBoundingClientRect();
          const px = e.clientX - rect.left;
          const py = e.clientY - rect.top;
          let found: PlacedCity | null = null;
          // 위에 그려진 쪽을 먼저 집는다.
          for (let n = cityHits.length - 1; n >= 0; n -= 1) {
            const box = cityHits[n];
            if (px >= box.x0 && px <= box.x1 && py >= box.y0 && py <= box.y1) {
              found = box.city;
              break;
            }
          }
          canvas.style.cursor = found ? 'pointer' : 'grab';
          // 같은 城 위에서 움직이는 동안에도 좌표는 계속 준다 — 툴팁이 커서를 따라간다.
          if (found || hovered !== null) onHoverCity(found, { x: px, y: py });
          hovered = found ? found.id : null;
          return;
        }
        const h = host.clientHeight || 1;
        const perPixel = span / h;
        // 화면 x·y 를 카메라 오른쪽·위 축으로 되돌린다.
        const right = new THREE.Vector3().crossVectors(new THREE.Vector3(0, 1, 0), dir).normalize();
        const up = new THREE.Vector3().crossVectors(dir, right).normalize();
        target.addScaledVector(right, -(e.clientX - lastX) * perPixel);
        target.addScaledVector(up, (e.clientY - lastY) * perPixel);
        lastX = e.clientX;
        lastY = e.clientY;
        renderOnce();
      };
      const onUp = (e: PointerEvent) => {
        dragging = false;
        canvas.style.cursor = 'grab';
        if (canvas.hasPointerCapture(e.pointerId)) canvas.releasePointerCapture(e.pointerId);
      };
      const onWheel = (e: WheelEvent) => {
        e.preventDefault();
        span = Math.max(6, Math.min(Math.max(cols, rows) * 1.2, span * (e.deltaY > 0 ? 1.12 : 1 / 1.12)));
        renderOnce();
      };
      // 직교 카메라라 확대는 span 을 줄이는 것이다 — 휠과 같은 눈금·같은 한계를 쓴다.
      const spanBy = (factor: number) => {
        span = Math.max(6, Math.min(Math.max(cols, rows) * 1.2, span * factor));
        renderOnce();
      };
      zoomRef.current = {
        by: (factor: number) => spanBy(1 / factor),
        fit: () => {
          span = Math.max(cols, rows) * 0.9;
          target.set(0, 0, 0);
          renderOnce();
        },
      };

      canvas.addEventListener('pointerdown', onDown);
      canvas.addEventListener('pointermove', onMove);
      canvas.addEventListener('pointerup', onUp);
      canvas.addEventListener('pointercancel', onUp);
      const onLeave = () => {
        if (hovered === null) return;
        hovered = null;
        onHoverCity?.(null, { x: 0, y: 0 });
      };
      canvas.addEventListener('wheel', onWheel, { passive: false });
      canvas.addEventListener('pointerleave', onLeave);

      // ── 집기 ─────────────────────────────────────────────────────────
      // 지형 윗면 자체를 레이캐스트한다. 인스턴스 인덱스가 그대로 타일 인덱스로 풀린다.
      const raycaster = new THREE.Raycaster();
      const pointer = new THREE.Vector2();
      const onClick = (e: MouseEvent) => {
        if (!onPickTile && !onPickCity && !onPickBattlefield) return;
        const rect = canvas.getBoundingClientRect();
        // 전장이 제일 먼저다 — 겹판에 제일 위로 그렸으니 집기도 그 순서다.
        if (onPickBattlefield) {
          const px = e.clientX - rect.left;
          const py = e.clientY - rect.top;
          for (let n = fieldHits.length - 1; n >= 0; n -= 1) {
            const hit = fieldHits[n];
            if (Math.hypot(hit.x - px, hit.y - py) <= hit.radius) {
              onPickBattlefield(hit.target);
              return;
            }
          }
        }
        pointer.x = ((e.clientX - rect.left) / rect.width) * 2 - 1;
        pointer.y = -((e.clientY - rect.top) / rect.height) * 2 + 1;
        raycaster.setFromCamera(pointer, camera);
        // 城 이 그다음이다. 건물 메시를 맞히면 그 도시를 집고 지형은 보지 않는다.
        // 郡國 밖 세력은 게임 城 번호가 없다(음수 id) — 겹판 집기 상자와 같은 규칙으로
        // 여기서도 건너뛴다. 앞에 서 있다고 뒤의 城 까지 못 집게 만들면 안 되므로
        // 제일 가까운 것 하나만 보지 않고 城 이 나올 때까지 훑는다.
        if (onPickCity) {
          const visibleCityMeshes = cityMeshes.filter((entry) => entry.mesh.visible);
          const meshHits = raycaster.intersectObjects(
            visibleCityMeshes.map((entry) => entry.mesh), false,
          );
          const city = firstPickableCity(meshHits.map((meshHit) => {
            if (meshHit.instanceId == null) return undefined;
            const entry = visibleCityMeshes.find((candidate) => candidate.mesh === meshHit.object);
            return entry?.cities[meshHit.instanceId];
          }));
          if (city) {
            onPickCity(city, { pointerType: lastPointerType });
            return;
          }
        }
        if (!onPickTile) return;
        const hits = raycaster.intersectObjects(tileMeshes.map((entry) => entry.mesh), false);
        const hit = hits[0];
        if (!hit || hit.instanceId == null) {
          onPickTile(null);
          return;
        }
        const entry = tileMeshes.find((candidate) => candidate.mesh === hit.object);
        if (!entry) {
          onPickTile(null);
          return;
        }
        const i = entry.tiles[hit.instanceId];
        // 지도 밖은 그리기만 하고 집히지는 않는다. 2D 쪽과 같은 규칙이다.
        onPickTile(playable[i] === 1 ? { col: i % cols, row: (i / cols) | 0 } : null);
      };
      canvas.addEventListener('click', onClick);

      const observer = new ResizeObserver(() => renderOnce());
      observer.observe(host);
      renderOnce();

      disposables.push({
        dispose: () => {
          observer.disconnect();
          canvas.removeEventListener('pointerdown', onDown);
          canvas.removeEventListener('pointermove', onMove);
          canvas.removeEventListener('pointerup', onUp);
          canvas.removeEventListener('pointercancel', onUp);
          canvas.removeEventListener('wheel', onWheel);
          canvas.removeEventListener('pointerleave', onLeave);
          canvas.removeEventListener('click', onClick);
          canvas.remove();
          overlay.remove();
          for (const child of borderGroup.children) {
            const mesh = child as THREE.Mesh;
            (mesh.geometry as THREE.BufferGeometry | undefined)?.dispose();
            (mesh.material as THREE.Material | undefined)?.dispose();
          }
          scene.traverse((node) => {
            const mesh = node as THREE.InstancedMesh;
            if (mesh.isInstancedMesh) mesh.dispose();
          });
          for (const geometry of loaded.terrain.values()) geometry.dispose();
          for (const geometry of loaded.building.values()) geometry.dispose();
          loaded.skirt.dispose();
        },
      });
    })().catch((e: unknown) => {
      if (controller.signal.aborted) return;
      setError(e instanceof Error ? e.message : '알 수 없는 오류');
    });

    return () => {
      controller.abort();
      if (frame) cancelAnimationFrame(frame);
      tintRef.current = null;
      zoomRef.current = null;
      for (const item of disposables) item.dispose();
      renderer?.dispose();
    };
  }, [data, cities, hideCityNames, currentCityId, selectedCityId, onPickTile, onPickCity,
    onHoverCity, battlefields, onPickBattlefield]);

  // 색 세기·모드만 바뀌면 씬을 다시 짓지 않고 instanceColor 만 갈아 끼운다.
  useEffect(() => {
    tintRef.current?.apply(tintStrength, tintMode);
  }, [tintStrength, tintMode, nationColorByOwner]);

  // 국경은 세력색 **표**로만 갈린다. 세기·모드에 묶어 두면 「세력색/지형만」 탭을 누를 때마다
  // 32,064 칸을 다시 훑고 BufferGeometry·Material 을 새로 굽는다 — 표가 그대로면 다시 굽지 않는다.
  useEffect(() => {
    tintRef.current?.rebuildBorders();
  }, [nationColorByOwner]);

  if (error) {
    return (
      <div className={className} role="alert" style={{ padding: 16, color: 'var(--rust-2)' }}>
        3D 지도를 그리지 못했다: {error}
      </div>
    );
  }

  return (
    <div className={className} style={{ position: 'relative', width: '100%', height: '100%' }}>
      <div ref={hostRef} data-testid="iso3d-host" style={{ width: '100%', height: '100%' }} />
      <div className="iso-zoom" role="group" aria-label="지도 배율">
        <button type="button" aria-label="지도 확대" onClick={() => zoomRef.current?.by(1.4)}>+</button>
        <button type="button" aria-label="지도 축소" onClick={() => zoomRef.current?.by(1 / 1.4)}>−</button>
        <button type="button" aria-label="지도 전체 보기" onClick={() => zoomRef.current?.fit()}>전체</button>
      </div>
      {stats && showStats ? (
        <p
          data-testid="iso3d-stats"
          style={{
            position: 'absolute', left: 8, bottom: 36, margin: 0,
            font: '11px var(--font-mono)', color: 'var(--muted)', pointerEvents: 'none',
          }}
        >
          지형 {stats.tiles.toLocaleString()} · 흙벽 {stats.skirts.toLocaleString()}
          {' · '}治所 {stats.seats.toLocaleString()} · 縣 {stats.counties.toLocaleString()}
        </p>
      ) : null}
    </div>
  );
}

export default IsoMap3D;
