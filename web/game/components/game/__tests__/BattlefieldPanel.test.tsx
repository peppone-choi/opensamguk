import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import BattlefieldPanel from '../BattlefieldPanel';
import { api } from '@/lib/api';
vi.mock('@/lib/api',()=>({api:{command:vi.fn()},isIntakeQueued:(o:{status:string})=>o.status==='AVAILABLE'}));
const data={generalId:7,catalogHash:'a'.repeat(64),positionRevision:'9007199254740993',currentSiteId:null,canExit:false,sites:[{id:'changban',name:'장판',latitude:30.98367,longitude:112.199583,confidence:'APPROXIMATE' as const,canEnter:true,reason:null}]};
describe('BattlefieldPanel',()=>{
 beforeEach(()=>{document.cookie='sam_server=; Max-Age=0; path=/';vi.clearAllMocks();});
 it('rejects commands after the selected server changes',async()=>{
  render(<BattlefieldPanel data={data} siteId="changban" serverId="pep" onRefresh={()=>{}} onClose={()=>{}}/>);
  document.cookie='sam_server=other; path=/';
  fireEvent.click(screen.getByRole('button',{name:'전장 진입 예약'}));
  expect(await screen.findByText(/서버가 변경/)).toBeTruthy();
  expect(api.command).not.toHaveBeenCalled();
 });
 it('queues exact identity and revision without claiming movement',async()=>{
  vi.mocked(api.command).mockResolvedValue({status:'AVAILABLE',requestId:'r'});
  render(<BattlefieldPanel data={data} siteId="changban" onRefresh={()=>{}} onClose={()=>{}}/>);
  fireEvent.click(screen.getByRole('button',{name:'전장 진입 예약'}));
  await waitFor(()=>expect(api.command).toHaveBeenCalledWith('che_전장이동',{siteId:'changban',catalogHash:data.catalogHash,expectedRevision:data.positionRevision},7,0));
  expect(await screen.findByText(/예약 요청이 접수/)).toBeTruthy();
 });
 it('retains server denial and disables unavailable entry',()=>{
  render(<BattlefieldPanel data={{...data,sites:[{...data.sites[0],canEnter:false,reason:'진입 도시가 아닙니다.'}]}} siteId="changban" onRefresh={()=>{}} onClose={()=>{}}/>);
  expect(screen.getByRole('button',{name:'전장 진입 예약'})).toBeDisabled();
  expect(screen.getByText('진입 도시가 아닙니다.')).toBeTruthy();
 });
 it('current site exit uses empty site id and displays actual denial',async()=>{
  vi.mocked(api.command).mockResolvedValue({status:'BLOCKED',reason:'위치가 변경되었습니다.'});
  render(<BattlefieldPanel data={{...data,currentSiteId:'changban',canExit:true}} siteId="changban" onRefresh={()=>{}} onClose={()=>{}}/>);
  expect(screen.getByText('현재 주둔 중')).toBeTruthy();
  fireEvent.click(screen.getByRole('button',{name:'귀환 예약'}));
  expect(await screen.findByText('위치가 변경되었습니다.')).toBeTruthy();
  expect(api.command).toHaveBeenLastCalledWith('che_전장이동',expect.objectContaining({siteId:''}),7,0);
 });
});
