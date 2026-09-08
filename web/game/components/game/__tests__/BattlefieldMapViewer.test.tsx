import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { beforeEach, it, expect, vi } from 'vitest';
import type { ComponentProps } from 'react';
import type { HanMapCanvas as Canvas } from '@opensamguk/ui';
const state=vi.hoisted(()=>({props:null as ComponentProps<typeof Canvas>|null}));
vi.mock('@opensamguk/ui',async()=>({...await vi.importActual('@opensamguk/ui'),HanMapCanvas:(props:ComponentProps<typeof Canvas>)=>{
 state.props=props;return <button onClick={()=>props.battlefieldTargets?.[0] && props.onBattlefieldActivate?.(props.battlefieldTargets[0])}>select field</button>;
}}));
vi.mock('@/lib/api',()=>({api:{mapPreview:vi.fn(),worldMap:vi.fn(),battlefields:vi.fn()},isIntakeQueued:()=>true}));
import { api } from '@/lib/api';
import MapViewer from '../MapViewer';
const fields={generalId:7,catalogHash:'a'.repeat(64),positionRevision:'1',currentSiteId:'changban',canExit:true,sites:[{id:'changban',name:'장판',latitude:30.98367,longitude:112.199583,confidence:'APPROXIMATE' as const,canEnter:false,reason:null}]};
beforeEach(()=>{
 vi.clearAllMocks();document.cookie='sam_server=; Max-Age=0; path=/';
 vi.stubGlobal('localStorage',{getItem:()=>null,setItem(){}});
 vi.stubGlobal('matchMedia',()=>({matches:false,addListener(){},removeListener(){}}));
 vi.mocked(api.mapPreview).mockResolvedValue({serverName:'test',year:208,month:1,mapCode:'han-world-v3',width:700,height:610,cities:[{id:405,name:"당양",level:5,nationId:0,x:335,y:286,state:0,supply:true,isCapital:false}],nations:[]});
 vi.mocked(api.worldMap).mockRejectedValue(new Error('no live overlay'));
 vi.mocked(api.battlefields).mockResolvedValue(fields);
});
it('shows current field separately and suppresses compatibility city highlight',async()=>{
 render(<MapViewer live currentCityId={405}/>);
 await waitFor(()=>expect(state.props?.battlefieldTargets?.[0].id).toBe('changban'));
 expect(state.props?.currentCityId).toBeNull();
 expect(state.props?.battlefieldTargets?.[0].current).toBe(true);
 fireEvent.click(screen.getByText('select field'));
 expect(screen.getByText('현재 주둔 중')).toBeTruthy();
});
it('does not publish a battlefield response from a previous server',async()=>{
 let resolve!:(value:typeof fields)=>void;
 vi.mocked(api.battlefields).mockReturnValue(new Promise(done=>{resolve=done;}));
 render(<MapViewer live/>);
 await waitFor(()=>expect(api.battlefields).toHaveBeenCalled());
 document.cookie='sam_server=changed; path=/';await act(async()=>{resolve(fields);});
 await waitFor(()=>expect(state.props?.battlefieldTargets).toBeUndefined());
});
