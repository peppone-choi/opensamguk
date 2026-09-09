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
  indexTint,
  isWater,
  mixToward,
  normaliseNationColor,
  type IsoMapData,
  type IsoBattlefieldMarker,
  type PlacedCity,
  type Rgb,
  type TintMode,
} from '@opensamguk/ui';

const MODEL_BASE = '/models/iso3d';

/** 城 등급 → 건물 애셋. manifest.buildingTiers 그대로. */
const BUILDING_TIERS: { name: string; from: number; to: number }[] = [
  { name: 'hamlet', from: 4, to: 4 },
  { name: 'county', from: 5, to: 6 },
  { name: 'commandery', from: 7, to: 8 },
  { name: 'capital', from: 9, to: 11 },
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
  const tintRef = useRef<{ apply: (strength: number, mode: TintMode) => void } | null>(null);

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

      const material = tileMaterial();
      disposables.push(material);
      const dummy = new THREE.Object3D();
      const tileMeshes: { mesh: THREE.InstancedMesh; tiles: number[] }[] = [];
      let tileTotal = 0;

      for (const [terrainCode, tiles] of byCode) {
        const geometry = loaded.terrain.get(terrainCode);
        if (!geometry) continue;
        const mesh = new THREE.InstancedMesh(geometry, material, tiles.length);
        mesh.frustumCulled = false;
        for (let n = 0; n < tiles.length; n += 1) {
          const i = tiles[n];
          const c = i % cols;
          const r = (i / cols) | 0;
          dummy.position.set(c - halfCols, y(baseHeight[i]), r - halfRows);
          dummy.rotation.set(0, 0, 0);
          dummy.scale.set(1, 1, 1);
          dummy.updateMatrix();
          mesh.setMatrixAt(n, dummy.matrix);
        }
        mesh.instanceMatrix.needsUpdate = true;
        scene.add(mesh);
        tileMeshes.push({ mesh, tiles });
        tileTotal += tiles.length;
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

      // ── 城 ───────────────────────────────────────────────────────────
      // 郡治(seat)와 縣을 따로 세운다. 축소 상태에서는 縣 쪽 메시를 통째로 끈다
      // (SEAT_ONLY_TILE_PIXELS 주석 참조). 2D 와 같은 눈금이라 두 판이 같이 움직인다.
      //
      // 자리는 **소수** 타일 좌표다. 정수로 내리면 37 곳이 다른 도시와 같은 타일에 겹쳐
      // 통째로 가려지고, 그러면 눌러서 들어갈 수 없다(placeGameCities 주석 참조).
      const countyMeshes: THREE.InstancedMesh[] = [];
      const cityMeshes: { mesh: THREE.InstancedMesh; cities: PlacedCity[] }[] = [];
      // 전장은 3D 물체가 아니라 겹판 위 화면 좌표다. drawLabels 가 채우고 집기가 읽는다.
      const fieldHits: { target: IsoBattlefieldMarker; x: number; y: number; radius: number }[] = [];
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
            dummy.position.set(city.col - halfCols, y(baseHeight[i]), city.row - halfRows);
            dummy.rotation.set(0, 0, 0);
            dummy.scale.set(1, 1, 1);
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
      // 덮어쓰기가 아니라 곱하기다. 지형 정점색이 그대로 살아 있고 그 위에 국가 색조가 얹힌다.
      const white: Rgb = { r: 1, g: 1, b: 1 };
      const color = new THREE.Color();
      const applyTint = (strength: number, mode: TintMode) => {
        for (const { mesh, tiles } of tileMeshes) {
          for (let n = 0; n < tiles.length; n += 1) {
            const i = tiles[n];
            let rgb = playable[i] === 0 ? OUT_OF_PLAY : white;
            if (playable[i] === 1 && mode !== 'none' && strength > 0 && !isWater(code[i])) {
              const key = mode === 'commandery' ? parentOwner[i] : owner[i];
              if (key >= 0) {
                const hex = nationColorByOwner?.[key];
                rgb = mixToward(hex ? normaliseNationColor(hex) : indexTint(key), strength);
              }
            }
            color.setRGB(rgb.r, rgb.g, rgb.b);
            mesh.setColorAt(n, color);
          }
          if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
        }
        renderOnce();
      };
      tintRef.current = { apply: applyTint };

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
        overlayContext.font = '600 12px "Pretendard Variable", Pretendard, sans-serif';
        overlayContext.textAlign = 'center';
        overlayContext.textBaseline = 'top';
        overlayContext.lineJoin = 'round';
        for (const city of cities) {
          if (seatOnly && !city.seat) continue;
          const i = city.tileRow * cols + city.tileCol;
          projected.set(city.col - halfCols, y(baseHeight[i]), city.row - halfRows);
          projected.project(camera);
          if (projected.z > 1) continue;
          const sx = (projected.x * 0.5 + 0.5) * w;
          const sy = (-projected.y * 0.5 + 0.5) * h;
          if (sx < -40 || sx > w + 40 || sy < -40 || sy > h + 40) continue;
          const ring = city.id === currentCityId
            ? '#ffd36d' // --focus
            : city.id === selectedCityId ? '#ece6d8' : null; // --text
          if (city.nationColor) {
            overlayContext.fillStyle = rgbCss(normaliseNationColor(city.nationColor));
            overlayContext.strokeStyle = 'rgba(12, 15, 14, 0.85)';
            overlayContext.lineWidth = 1.5;
            overlayContext.beginPath();
            overlayContext.arc(sx, sy - 14, city.isCapital ? 6 : 4, 0, Math.PI * 2);
            overlayContext.fill();
            overlayContext.stroke();
            if (city.isCapital) {
              overlayContext.fillStyle = '#ece6d8';
              overlayContext.beginPath();
              overlayContext.arc(sx, sy - 14, 2, 0, Math.PI * 2);
              overlayContext.fill();
            }
          }
          if (ring) {
            overlayContext.strokeStyle = ring;
            overlayContext.lineWidth = 2;
            overlayContext.beginPath();
            overlayContext.arc(sx, sy - 14, 10, 0, Math.PI * 2);
            overlayContext.stroke();
          }
          if (!showNames) continue;
          overlayContext.strokeStyle = 'rgba(12, 15, 14, 0.92)';
          overlayContext.lineWidth = 3;
          overlayContext.strokeText(city.name, sx, sy + 2);
          overlayContext.fillStyle = '#ece6d8';
          overlayContext.fillText(city.name, sx, sy + 2);
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
          const radius = 9;
          overlayContext.fillStyle = '#1b201d'; // --panel
          overlayContext.strokeStyle = field.current ? '#ffd36d' : '#d3b064'; // --focus / --bronze
          overlayContext.lineWidth = 2.5;
          overlayContext.beginPath();
          overlayContext.moveTo(sx, sy - radius);
          overlayContext.lineTo(sx + radius, sy);
          overlayContext.lineTo(sx, sy + radius);
          overlayContext.lineTo(sx - radius, sy);
          overlayContext.closePath();
          overlayContext.fill();
          overlayContext.stroke();
          fieldHits.push({ target: field, x: sx, y: sy, radius: radius + 6 });
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
          const seatOnly = tilePixels() < SEAT_ONLY_TILE_PIXELS;
          for (const mesh of countyMeshes) mesh.visible = !seatOnly;
          canvas.dataset.seatOnly = String(seatOnly);
          layout();
          place();
          renderer!.render(scene, camera);
          drawLabels(seatOnly);
        });
      };

      applyTint(tintStrength, tintMode);

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
      const onMove = (e: PointerEvent) => {
        if (!dragging) return;
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
      canvas.addEventListener('pointerdown', onDown);
      canvas.addEventListener('pointermove', onMove);
      canvas.addEventListener('pointerup', onUp);
      canvas.addEventListener('pointercancel', onUp);
      canvas.addEventListener('wheel', onWheel, { passive: false });

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
        if (onPickCity) {
          const visibleCityMeshes = cityMeshes.filter((entry) => entry.mesh.visible);
          const cityHits = raycaster.intersectObjects(
            visibleCityMeshes.map((entry) => entry.mesh), false,
          );
          const cityHit = cityHits[0];
          if (cityHit && cityHit.instanceId != null) {
            const entry = visibleCityMeshes.find((candidate) => candidate.mesh === cityHit.object);
            const city = entry?.cities[cityHit.instanceId];
            if (city) {
              onPickCity(city, { pointerType: lastPointerType });
              return;
            }
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
          canvas.removeEventListener('click', onClick);
          canvas.remove();
          overlay.remove();
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
      for (const item of disposables) item.dispose();
      renderer?.dispose();
    };
  }, [data, cities, hideCityNames, currentCityId, selectedCityId, onPickTile, onPickCity,
    battlefields, onPickBattlefield]);

  // 색 세기·모드만 바뀌면 씬을 다시 짓지 않고 instanceColor 만 갈아 끼운다.
  useEffect(() => {
    tintRef.current?.apply(tintStrength, tintMode);
  }, [tintStrength, tintMode, nationColorByOwner]);

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
      {stats && showStats ? (
        <p
          data-testid="iso3d-stats"
          style={{
            position: 'absolute', left: 8, bottom: 8, margin: 0,
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
