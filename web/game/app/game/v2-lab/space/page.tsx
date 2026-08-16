// OPENSAM-41 [G0-C] 3D 공간 증명 페이지.
//
// 서버 컴포넌트다. v2-lab layout(OPENSAM-35)의 V2_ENABLED 게이트와 middleware의
// HTTP 404 게이트 뒤에 있으며, 여기서는 게이트를 다시 구현하지 않는다.
// 실제 scene은 클라이언트 컴포넌트(components/v2/SpaceProof3D)이고, V2_ENABLED가
// 아닌 빌드에서는 next.config.mjs가 그 import를 빈 스텁으로 치환한다.

import { SpaceProof3D } from '@/components/v2/SpaceProof3D';
import { RUNTIME_LODS } from '@/lib/v2/terrain/contracts';
import type { RuntimeLod } from '@/lib/v2/terrain/contracts';
import { demoCamera, demoScene, syntheticCamera, syntheticScene } from '@/lib/v2/terrain/fixtures';

export const dynamic = 'force-dynamic';

type Search = { scene?: string; lod?: string; animate?: string; fallback?: string };

function parseLod(raw: string | undefined): RuntimeLod {
  return (RUNTIME_LODS as readonly string[]).includes(raw ?? '') ? (raw as RuntimeLod) : 'FULL_SCENE';
}

export default async function V2SpaceProofPage({ searchParams }: { searchParams: Promise<Search> }) {
  const params = await searchParams;
  const synthetic = params.scene === 'synthetic';

  // fixture는 프론트 자체 read model이다(백엔드 계약 착지 전). 서버에서 만들어
  // 클라이언트로 내려보내므로, 3D scene과 fallback 표가 같은 모델을 본다(T1-G11).
  const model = synthetic ? syntheticScene() : demoScene();
  const camera = synthetic ? syntheticCamera() : demoCamera();

  return (
    <main>
      <h1>v2-lab · 3D 공간 증명</h1>
      <p data-testid="v2-space-summary">
        {synthetic ? 'synthetic 2,000' : 'demo 3'} 거점 · projectionVersion {model.versions.projectionVersion}
      </p>
      <SpaceProof3D
        model={model}
        camera={camera}
        viewport={{ width: 1024, height: 768 }}
        lod={parseLod(params.lod)}
        animate={params.animate === '1'}
        forceFallback={params.fallback === '1'}
      />
    </main>
  );
}
