// 2026-09-17 사용자 결정: 「메인에서 전장이라 되어있는 장판, 관도 표시를 삭제해」.
// 메인 지도는 전장 목록을 받지도, 표식·선택 줄로 그리지도 않는다. 城 강조는 전장과 무관하게 그대로다.
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { beforeEach, it, expect, vi } from 'vitest';
import type { ComponentProps } from 'react';
import type IsoWorldMapType from '@/components/iso/IsoWorldMap';
const state = vi.hoisted(() => ({ props: null as ComponentProps<typeof IsoWorldMapType> | null }));
vi.mock('@/components/iso/IsoWorldMap', () => ({
  default: (props: ComponentProps<typeof IsoWorldMapType>) => {
    state.props = props;
    const field = props.battlefields?.[0];
    return <button onClick={() => field && props.onBattlefieldActivate?.(field)}>select field</button>;
  },
}));
vi.mock('@/lib/api',()=>({api:{mapPreview:vi.fn(),worldMap:vi.fn(),battlefields:vi.fn()},isIntakeQueued:()=>true}));
import { api } from '@/lib/api';
import MapViewer from '../MapViewer';
const fields={generalId:7,catalogHash:'a'.repeat(64),positionRevision:'1',currentSiteId:'changban',canExit:true,sites:[{id:'changban',name:'장판',latitude:30.98367,longitude:112.199583,confidence:'APPROXIMATE' as const,canEnter:false,reason:null}]};
beforeEach(()=>{
 vi.clearAllMocks();state.props=null;document.cookie='sam_server=; Max-Age=0; path=/';
 vi.stubGlobal('localStorage',{getItem:()=>null,setItem(){}});
 vi.stubGlobal('matchMedia',()=>({matches:false,addListener(){},removeListener(){}}));
 vi.mocked(api.mapPreview).mockResolvedValue({serverName:'test',year:208,month:1,mapCode:'han-world-v3',width:700,height:610,cities:[{id:405,name:"당양",level:5,nationId:0,x:335,y:286,state:0,supply:true,isCapital:false}],nations:[]});
 vi.mocked(api.worldMap).mockRejectedValue(new Error('no live overlay'));
 vi.mocked(api.battlefields).mockResolvedValue(fields);
});
it('does not request or draw battlefields on the main map',async()=>{
 render(<MapViewer live currentCityId={405}/>);
 await waitFor(()=>expect(state.props).not.toBeNull());
 expect(api.battlefields).not.toHaveBeenCalled();
 expect(state.props?.battlefields).toBeUndefined();
 expect(state.props?.currentCityId).toBe(405);
});
