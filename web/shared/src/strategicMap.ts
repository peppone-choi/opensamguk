// 미리보기가 싣는 전략 지형 묶음(지형 판 지문). 지도 훅(useWorldMap)이 구역 이름용 지형을 받을 때 이 지문으로 판을 고른다.
// 옛 지도판의 전략 장면 · 뱃길 · 경로 그리기는 옛 지도와 함께 지웠다(M2-9).

export interface StrategicTopologyBinding {
  worldId: number;
  mapCode: 'han-world-v3';
  topologyRevision: string;
  topologyHash: string;
  baseTilesSha256: string;
  cols: number;
  rows: number;
}

const SHA = /^[a-f0-9]{64}$/;

export function validStrategicBinding(binding: StrategicTopologyBinding): boolean {
  return binding.mapCode === 'han-world-v3' && Number.isSafeInteger(binding.worldId) && binding.worldId > 0
    && typeof binding.topologyRevision === 'string' && binding.topologyRevision.trim().length > 0
    && SHA.test(binding.topologyHash) && SHA.test(binding.baseTilesSha256)
    && Number.isInteger(binding.cols) && binding.cols > 0 && binding.cols <= 4096
    && Number.isInteger(binding.rows) && binding.rows > 0 && binding.rows <= 4096;
}
