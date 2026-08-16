'use client';

// OPENSAM-41 [G0-C] Three.js 정사영 지휘 카메라 scene (T1-G10, G0C-a/f/i/k).
//
// 설계 결정 — 왜 three.Camera가 아니라 project()로 좌표를 미리 계산하는가:
//   picking(G0C-b~e)과 렌더가 **같은 수식**을 타야 "화면에서 집은 것 = read model이
//   돌려준 것"이 보장된다. three의 카메라 행렬과 lib/v2/terrain/projection.ts를 각각
//   유지하면 두 수학이 조용히 어긋난다. 정사영은 원근 나눗셈이 없으므로 월드→화면을
//   미리 계산한 뒤 픽셀 1:1 OrthographicCamera로 그리는 것이 수학적으로 동일하다.
//   three는 GPU instancing/드로우콜만 담당한다.
//
// 판정은 전부 서버 몫이다(T1-G10). 카메라·LOD는 어떤 게임 판정에도 입력되지 않는다.

import { useCallback, useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import type { CommandCamera, RuntimeLod, SpaceReadModel, Viewport } from '@/lib/v2/terrain/contracts';
import { pickPlace, project } from '@/lib/v2/terrain/projection';
import { planRender } from '@/lib/v2/terrain/lod';
import { FrameCollector } from '@/lib/v2/terrain/telemetry';
import type { SpaceProbe } from '@/lib/v2/terrain/telemetry';
import { SpaceFallbackTable } from './SpaceFallbackTable';

declare global {
  // eslint-disable-next-line no-var
  var __v2Space: SpaceProbe | undefined;
}

export interface SpaceProof3DProps {
  model: SpaceReadModel;
  camera: CommandCamera;
  viewport: Viewport;
  lod: RuntimeLod;
  /** 카메라를 계속 돌려 프레임 부하를 만든다. FPS 게이트(G0C-k)에서 켠다. */
  animate?: boolean;
  /** 테스트에서 WebGL 없음을 강제. */
  forceFallback?: boolean;
}

function detectWebGL(canvas: HTMLCanvasElement): { ok: boolean; reason: string } {
  try {
    const gl = canvas.getContext('webgl2') ?? canvas.getContext('webgl');
    return gl ? { ok: true, reason: '' } : { ok: false, reason: '컨텍스트 생성 실패' };
  } catch (e) {
    return { ok: false, reason: e instanceof Error ? e.message : '알 수 없는 오류' };
  }
}

/** LOD별 마커 반경(픽셀). 표현만 바꾼다. */
const MARKER_RADIUS: Record<RuntimeLod, number> = {
  CLUSTER: 7,
  SYMBOL: 3,
  KIT: 5,
  FULL_SCENE: 7,
};

export function SpaceProof3D({
  model,
  camera,
  viewport,
  lod,
  animate = false,
  forceFallback = false,
}: SpaceProof3DProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [fallback, setFallback] = useState<string | null>(forceFallback ? '강제 fallback' : null);
  const [picked, setPicked] = useState<string | null>(null);
  const liveCamera = useRef<CommandCamera>(camera);

  // 프로브는 프레임 루프에서 매번 갱신되므로 여기서 무거운 계산을 하면 안 된다
  // (planRender를 프레임마다 돌리면 FPS 측정 자체가 오염된다).
  const probeRef = useRef<SpaceProbe>({
    mode: 'WEBGL',
    lod,
    placeCount: model.places.length,
    drawnMarkerCount: 0,
    clusterCount: 0,
    projectionVersion: model.versions.projectionVersion,
    telemetry: null,
    lastPickedPlaceId: null,
  });

  const publish = useCallback((probe: Partial<SpaceProbe>) => {
    probeRef.current = { ...probeRef.current, ...probe };
    globalThis.__v2Space = probeRef.current;
  }, []);

  const handlePick = useCallback(
    (sx: number, sy: number) => {
      const hit = pickPlace({ sx, sy }, model.places, liveCamera.current, viewport);
      const id = hit?.placeId ?? null;
      setPicked(id);
      publish({ lastPickedPlaceId: id });
    },
    [model, publish, viewport],
  );

  useEffect(() => {
    if (forceFallback) {
      setFallback('강제 fallback');
      publish({ mode: 'FALLBACK_TABLE' });
      return;
    }
    const canvas = canvasRef.current;
    if (!canvas) return;

    const support = detectWebGL(canvas);
    if (!support.ok) {
      setFallback(support.reason);
      publish({ mode: 'FALLBACK_TABLE' });
      return;
    }

    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ canvas, antialias: false, powerPreference: 'high-performance' });
    } catch (e) {
      setFallback(e instanceof Error ? e.message : 'WebGLRenderer 생성 실패');
      publish({ mode: 'FALLBACK_TABLE' });
      return;
    }

    const { width, height } = viewport;
    renderer.setPixelRatio(1); // FPS 측정을 기기 DPR에 흔들리지 않게 고정한다.
    renderer.setSize(width, height, false);
    renderer.setClearColor(0x0d1117, 1);

    const scene = new THREE.Scene();
    // 화면 픽셀 1:1 정사영. project()가 낸 (sx, sy)를 (x, -y)로 그대로 쓴다.
    const cam3 = new THREE.OrthographicCamera(0, width, 0, -height, -1e5, 1e5);
    cam3.position.z = 1e4;

    const plan = planRender(model.places, lod);
    const drawn = plan.lod === 'CLUSTER' ? plan.clusters : plan.markers;
    publish({
      mode: 'WEBGL',
      lod,
      placeCount: model.places.length,
      drawnMarkerCount: plan.markers.length,
      clusterCount: plan.clusters.length,
      projectionVersion: model.versions.projectionVersion,
    });

    const radius = MARKER_RADIUS[lod];
    const markerGeom = new THREE.CircleGeometry(radius, lod === 'SYMBOL' ? 6 : 16);
    const observedMat = new THREE.MeshBasicMaterial({ color: 0x9ecbff });
    // T1-G12: 개연 위치는 반투명 + 불확실 반경 링. 확정 사료와 시각적으로 구분한다.
    const plausibleMat = new THREE.MeshBasicMaterial({ color: 0xffb454, transparent: true, opacity: 0.45 });
    const ringGeom = new THREE.RingGeometry(0.92, 1, 24);
    const ringMat = new THREE.MeshBasicMaterial({ color: 0xffb454, transparent: true, opacity: 0.25 });

    const isPlausible = (i: number) =>
      plan.lod === 'CLUSTER' ? false : plan.markers[i].reconstructionStatus !== 'OBSERVED';
    const observedIdx = drawn.map((_, i) => i).filter((i) => !isPlausible(i));
    const plausibleIdx = drawn.map((_, i) => i).filter((i) => isPlausible(i));

    const observed = new THREE.InstancedMesh(markerGeom, observedMat, Math.max(1, observedIdx.length));
    const plausible = new THREE.InstancedMesh(markerGeom, plausibleMat, Math.max(1, plausibleIdx.length));
    const rings = new THREE.InstancedMesh(ringGeom, ringMat, Math.max(1, plausibleIdx.length));
    observed.count = observedIdx.length;
    plausible.count = plausibleIdx.length;
    rings.count = plausibleIdx.length;
    observed.frustumCulled = false;
    plausible.frustumCulled = false;
    rings.frustumCulled = false;
    scene.add(observed, plausible, rings);

    // 지형 영역(T1-G03) — 재구성 상태라 채우지 않고 윤곽선만 그린다.
    const regionGeom = new THREE.BufferGeometry();
    const regionLine = new THREE.LineLoop(
      regionGeom,
      new THREE.LineBasicMaterial({ color: 0x4b6a4b, transparent: true, opacity: 0.7 }),
    );
    regionLine.frustumCulled = false;
    scene.add(regionLine);

    // RouteCorridor(T1-G01) — geometryConfidence가 낮으면 흐리게(T1-G12).
    const corridorLines = model.corridors.map((corridor) => {
      const geom = new THREE.BufferGeometry();
      const line = new THREE.Line(
        geom,
        new THREE.LineBasicMaterial({
          color: corridor.type === 'RIVER' || corridor.type === 'CANAL' ? 0x5aa9e6 : 0xd0a05a,
          transparent: true,
          opacity: 0.35 + 0.65 * corridor.geometryConfidence,
        }),
      );
      line.frustumCulled = false;
      scene.add(line);
      return { corridor, geom };
    });

    const dummy = new THREE.Object3D();
    const collector = new FrameCollector();
    let raf = 0;
    let frame = 0;
    let disposed = false;

    const layout = (cam: CommandCamera) => {
      liveCamera.current = cam;

      for (let n = 0; n < observedIdx.length; n += 1) {
        const item = drawn[observedIdx[n]];
        const pos = 'position' in item ? item.position : item.center;
        const s = project(pos, cam, viewport);
        dummy.position.set(s.sx, -s.sy, -s.depth);
        dummy.scale.set(1, 1, 1);
        dummy.updateMatrix();
        observed.setMatrixAt(n, dummy.matrix);
      }
      observed.instanceMatrix.needsUpdate = true;

      for (let n = 0; n < plausibleIdx.length; n += 1) {
        const place = plan.markers[plausibleIdx[n]];
        const s = project(place.position, cam, viewport);
        dummy.position.set(s.sx, -s.sy, -s.depth);
        dummy.scale.set(1, 1, 1);
        dummy.updateMatrix();
        plausible.setMatrixAt(n, dummy.matrix);
        // 불확실 반경은 월드 단위 → 화면 픽셀로 zoom 배율만 곱한다(정사영이라 성립).
        const r = Math.max(radius, place.uncertaintyRadius * cam.zoom);
        dummy.scale.set(r, r, 1);
        dummy.updateMatrix();
        rings.setMatrixAt(n, dummy.matrix);
      }
      plausible.instanceMatrix.needsUpdate = true;
      rings.instanceMatrix.needsUpdate = true;

      const region = model.regions[0];
      if (region) {
        const pts = region.outline.map((p) => {
          const s = project(p, cam, viewport);
          return new THREE.Vector3(s.sx, -s.sy, -s.depth);
        });
        regionGeom.setFromPoints(pts);
      }
      for (const { corridor, geom } of corridorLines) {
        geom.setFromPoints(
          corridor.geometry.map((p) => {
            const s = project(p, cam, viewport);
            return new THREE.Vector3(s.sx, -s.sy, -s.depth);
          }),
        );
      }
    };

    layout(camera);

    const loop = (now: number) => {
      if (disposed) return;
      if (animate) {
        layout({ ...liveCamera.current, azimuth: liveCamera.current.azimuth + 0.01 });
      }
      renderer.render(scene, cam3);
      collector.tick(now);
      frame += 1;
      // 통계 요약은 정렬을 포함하므로 매 프레임 돌리지 않는다.
      if (frame % 15 === 0) publish({ telemetry: collector.summary() });
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);

    return () => {
      disposed = true;
      cancelAnimationFrame(raf);
      for (const { geom } of corridorLines) geom.dispose();
      regionGeom.dispose();
      markerGeom.dispose();
      ringGeom.dispose();
      observedMat.dispose();
      plausibleMat.dispose();
      ringMat.dispose();
      observed.dispose();
      plausible.dispose();
      rings.dispose();
      renderer.dispose();
    };
  }, [animate, camera, forceFallback, lod, model, publish, viewport]);

  if (fallback !== null) {
    return (
      <SpaceFallbackTable
        model={model}
        camera={camera}
        viewport={viewport}
        reason={fallback}
        onPick={(id) => {
          setPicked(id);
          publish({ mode: 'FALLBACK_TABLE', lastPickedPlaceId: id });
        }}
      />
    );
  }

  return (
    <div>
      <canvas
        ref={canvasRef}
        data-testid="v2-space-canvas"
        width={viewport.width}
        height={viewport.height}
        style={{ width: viewport.width, height: viewport.height, display: 'block' }}
        onPointerDown={(e) => {
          const rect = e.currentTarget.getBoundingClientRect();
          handlePick(e.clientX - rect.left, e.clientY - rect.top);
        }}
      />
      <p data-testid="v2-space-picked">{picked ?? '(선택 없음)'}</p>
    </div>
  );
}

export default SpaceProof3D;
