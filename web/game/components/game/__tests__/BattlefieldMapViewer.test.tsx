// 전장은 아이소 지도(정본) 위에 얹힌다. 여기서 지키는 것은 세 가지다.
//   1) 진행 중 전장이 지도까지 내려간다(주둔 중인 곳은 표시가 다르다).
//   2) 전장에 있으면 城 강조를 끈다 — 두 군데가 동시에 빛나면 어디 있는지 못 읽는다.
//   3) 앞 서버의 응답이 뒤늦게 도착해도 지도에 올리지 않는다.
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
it('shows current field separately and suppresses compatibility city highlight',async()=>{
 render(<MapViewer live currentCityId={405}/>);
 await waitFor(()=>expect(state.props?.battlefields?.[0].id).toBe('changban'));
 expect(state.props?.currentCityId).toBeNull();
 expect(state.props?.battlefields?.[0]).toMatchObject({current:true,latitude:30.98367,longitude:112.199583});
 fireEvent.click(screen.getByText('select field'));
 expect(screen.getByText('현재 주둔 중')).toBeTruthy();
});
it('does not publish a battlefield response from a previous server',async()=>{
 let resolve!:(value:typeof fields)=>void;
 vi.mocked(api.battlefields).mockReturnValue(new Promise(done=>{resolve=done;}));
 render(<MapViewer live/>);
 await waitFor(()=>expect(api.battlefields).toHaveBeenCalled());
 document.cookie='sam_server=changed; path=/';await act(async()=>{resolve(fields);});
 await waitFor(()=>expect(state.props?.battlefields).toBeUndefined());
});
