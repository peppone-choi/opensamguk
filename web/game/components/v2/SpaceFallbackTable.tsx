'use client';

// OPENSAM-41 [G0-C] G0C-f / T1-G11 — WebGL 불가 정사영 fallback.
//
// 완료 기준(T1-G11): 별도 simulation·다른 이동 규칙 금지. 그래서 이 컴포넌트는
// 3D scene과 **완전히 같은 SpaceReadModel**만 읽고, 자체 계산을 하나도 하지 않는다.
// 좌표는 3D와 같은 project()를 통과한 값을 표시해 두 표현이 같은 세계임을 보인다.

import type { CommandCamera, SpaceReadModel, Viewport } from '@/lib/v2/terrain/contracts';
import { project } from '@/lib/v2/terrain/projection';

export interface SpaceFallbackTableProps {
  model: SpaceReadModel;
  camera: CommandCamera;
  viewport: Viewport;
  reason: string;
  onPick?: (placeId: string) => void;
}

export function SpaceFallbackTable({ model, camera, viewport, reason, onPick }: SpaceFallbackTableProps) {
  return (
    <div data-testid="v2-space-fallback">
      <p role="status">WebGL을 쓸 수 없어 정보 표현으로 대체했다 ({reason}). 같은 read model·같은 좌표다.</p>
      <table>
        <caption>거점 {model.places.length}개 · projectionVersion {model.versions.projectionVersion}</caption>
        <thead>
          <tr>
            <th scope="col">거점</th>
            <th scope="col">분류</th>
            <th scope="col">화면 좌표</th>
            <th scope="col">위치 확실성</th>
          </tr>
        </thead>
        <tbody>
          {model.places.map((place) => {
            const s = project(place.position, camera, viewport);
            return (
              <tr key={place.placeId} data-place-id={place.placeId}>
                <th scope="row">
                  <button type="button" onClick={() => onPick?.(place.placeId)}>
                    {place.name}
                  </button>
                </th>
                <td>{place.budgetClass}</td>
                <td>
                  {Math.round(s.sx)}, {Math.round(s.sy)}
                </td>
                {/* T1-G12: 개연 위치를 확정 사료처럼 정밀하게 보이면 안 된다. */}
                <td data-status={place.reconstructionStatus}>
                  {place.reconstructionStatus === 'OBSERVED'
                    ? '사료 확정'
                    : `${place.reconstructionStatus === 'PLAUSIBLE' ? '개연' : '재구성'} · 반경 약 ${place.uncertaintyRadius}`}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export default SpaceFallbackTable;
