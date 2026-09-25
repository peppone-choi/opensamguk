import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { vi, test, expect, beforeEach } from 'vitest';
import CommandModal from '../components/CommandModal';
import CourtForm from '../components/command/CourtForm';
import { api } from '../lib/api';
import { submitCommandAndAwaitResult } from '../lib/commandSubmit';
vi.mock('../lib/api', () => ({api:{dispatchOptions:vi.fn(),dispatchPending:vi.fn(),courtDispatch:vi.fn(),courtDispatchReply:vi.fn(),commandResult:vi.fn(),legacyCourtOptions:vi.fn(),legacyStratagemOptions:vi.fn()}}));
vi.mock('../lib/commandSubmit', () => ({submitCommandAndAwaitResult:vi.fn()}));
const options = {result:true,targets:[{generalId:2,label:'조운'}],counties:[{countyId:7,label:'허현',available:true}],queued:null};
const order = {dispatchId:'private-key',issuerId:1,targetId:2,countyId:7,issuerLabel:'유비',targetLabel:'조운',countyLabel:'허현',issuedAt:{year:200,month:1,phase:1},dueAt:{year:200,month:5,phase:1},status:'PENDING' as const};
beforeEach(() => {
 vi.clearAllMocks();
 vi.mocked(api.dispatchOptions).mockResolvedValue(options);
 vi.mocked(api.dispatchPending).mockResolvedValue({result:true,dispatches:[]});
 vi.mocked(api.courtDispatch).mockResolvedValue({status:'AVAILABLE',requestId:'ticket'} as any);
 vi.mocked(api.courtDispatchReply).mockResolvedValue({status:'AVAILABLE',requestId:'reply'} as any);
 vi.mocked(api.legacyCourtOptions).mockResolvedValue({inputId:'court.releaseCorps',available:false,reason:'선택지가 없습니다.',choices:[]});
 vi.mocked(api.legacyStratagemOptions).mockResolvedValue({inputId:'stratagem.play',available:false,reason:'선택지가 없습니다.',choices:[]});
 vi.mocked(submitCommandAndAwaitResult).mockImplementation(async send => {await send();return {status:'reserved',reason:'접수'};});
});
async function selectDestination(){fireEvent.change(await screen.findByLabelText('직속 장수'),{target:{value:'2'}});fireEvent.change(await screen.findByLabelText('발령할 현'),{target:{value:'7'}});}
test('dispatch is separate single command and reservation never claims execution',async()=>{
 render(<CourtForm generalId={1}/>);await selectDestination();fireEvent.click(screen.getByRole('button',{name:'발령 접수'}));
 await waitFor(()=>expect(api.courtDispatch).toHaveBeenCalledWith(1,{targetGeneralId:2,countyId:7}));
 expect(await screen.findByText('접수되었습니다. 주공의 다음 개인 턴에 발령합니다.')).toBeInTheDocument();
 expect(screen.queryByText('처리가 완료되었습니다.')).not.toBeInTheDocument();expect(document.body).not.toHaveTextContent('ticket');
});
test('recipient NOT_LORD can reply and terminal status outranks failure code',async()=>{
 vi.mocked(api.dispatchOptions).mockResolvedValue({...options,result:false,code:'NOT_LORD',reason:'주공만 발령할 수 있습니다.'});
 vi.mocked(api.dispatchPending).mockResolvedValue({result:true,dispatches:[order]});
 render(<CourtForm generalId={2}/>);fireEvent.click(await screen.findByRole('button',{name:'수락'}));
 await waitFor(()=>expect(api.courtDispatchReply).toHaveBeenCalledWith(2,{dispatchId:'private-key',accept:true}));
 expect(document.body).not.toHaveTextContent('private-key');
});
test('blocked county uses server reason and cannot submit',async()=>{
 vi.mocked(api.dispatchOptions).mockResolvedValue({...options,counties:[{countyId:7,label:'허현',available:false,reason:'이미 배치된 장수가 있습니다.'}]});
 render(<CourtForm generalId={1}/>);await selectDestination();
 expect(screen.getByText('이미 배치된 장수가 있습니다.')).toBeInTheDocument();expect(screen.getByRole('button',{name:'발령 접수'})).toBeDisabled();
});
test('stale read response cannot replace a newer refresh',async()=>{
 let resolveOld!: (value:any)=>void;
 vi.mocked(api.dispatchOptions).mockImplementationOnce(()=>new Promise(resolve=>{resolveOld=resolve;}));
 const view=render(<CourtForm generalId={1} refreshKey={0}/>);
 view.rerender(<CourtForm generalId={1} refreshKey={1}/>);
 expect(await screen.findByLabelText('직속 장수')).toBeInTheDocument();
 await act(async()=>resolveOld({...options,targets:[{generalId:99,label:'오래된 대상'}]}));
 expect(screen.queryByText('오래된 대상')).not.toBeInTheDocument();
});
test('read failure remains explicit with retry and no optimistic empty success',async()=>{
 vi.mocked(api.dispatchPending).mockRejectedValue(new Error('offline'));
 render(<CourtForm generalId={1}/>);
 expect(await screen.findByRole('alert')).toHaveTextContent('발령 상태를 불러오지 못했습니다.');
 expect(screen.queryByText('표시할 발령이 없습니다.')).not.toBeInTheDocument();
});
test('queued issuer is disabled and terminal read shows accepted without response controls',async()=>{
 vi.mocked(api.dispatchPending).mockResolvedValue({result:true,dispatches:[{...order,status:'ACCEPTED',currentFailure:'ALREADY_RESOLVED'}],queued:{requestId:'secret',targetGeneralId:2,countyId:7}});
 render(<CourtForm generalId={2}/>);
 expect(await screen.findByText(/유비 · 수락/)).toBeInTheDocument();expect(screen.queryByRole('button',{name:'수락'})).not.toBeInTheDocument();
 expect(screen.getByText(/발령 접수됨/)).toBeInTheDocument();expect(document.body).not.toHaveTextContent('secret');
});
test('refusal passes an opaque dispatch ID and execution rejection preserves the form',async()=>{
 vi.mocked(api.dispatchPending).mockResolvedValue({result:true,dispatches:[order]});
 vi.mocked(api.dispatchOptions).mockResolvedValue({...options,result:false,code:'NOT_LORD'});
 vi.mocked(submitCommandAndAwaitResult).mockImplementation(async send=>{await send();return {status:'rejected',reason:'관계가 변경되었습니다.'};});
 render(<CourtForm generalId={2}/>);fireEvent.click(await screen.findByRole('button',{name:'거절'}));
 await waitFor(()=>expect(api.courtDispatchReply).toHaveBeenCalledWith(2,{dispatchId:'private-key',accept:false}));
 expect(await screen.findByRole('alert')).toHaveTextContent('관계가 변경되었습니다.');
});

test('accepted request remains tracked after result polling throws',async()=>{
 vi.mocked(submitCommandAndAwaitResult).mockImplementation(async send=>{await send();throw new Error('poll offline');});
 render(<CourtForm generalId={1}/>);await selectDestination();fireEvent.click(screen.getByRole('button',{name:'발령 접수'}));
 expect(await screen.findByText('접수된 입력의 결과를 다시 확인하고 있습니다. 다시 제출하지 않아도 됩니다.')).toBeInTheDocument();
 await waitFor(()=>expect(screen.getByRole('button',{name:'발령 접수'})).toBeDisabled());
 fireEvent.click(screen.getByRole('button',{name:'발령 접수'}));expect(api.courtDispatch).toHaveBeenCalledTimes(1);
});
test('modal remount clears old actor selections and ignores their in-flight outcome',async()=>{
 let finish!: (value:any)=>void;
 vi.mocked(submitCommandAndAwaitResult).mockImplementation(async send=>{await send();return await new Promise(resolve=>{finish=resolve;});});
 const props={courtMode:true,ruleProfile:'HWIHA',onClose:vi.fn(),onToast:vi.fn()};
 const view=render(<CommandModal {...props} generalId={1}/>);await selectDestination();fireEvent.click(screen.getByRole('button',{name:'발령 접수'}));
 await waitFor(()=>expect(api.courtDispatch).toHaveBeenCalledOnce());
 view.rerender(<CommandModal {...props} generalId={3}/>);
 expect(await screen.findByLabelText('직속 장수')).toHaveValue('');
 await act(async()=>finish({status:'reserved',reason:'접수'}));
 expect(screen.queryByText('접수되었습니다. 주공의 다음 개인 턴에 발령합니다.')).not.toBeInTheDocument();
 expect(screen.queryByLabelText('발령할 현')).not.toBeInTheDocument();
});
test('parent callback identity changes cannot starve queued result polling',async()=>{
 vi.useFakeTimers();
 vi.mocked(api.dispatchPending).mockResolvedValue({result:true,dispatches:[],queued:{requestId:'waiting-ticket',targetGeneralId:2,countyId:7}});
 vi.mocked(api.commandResult).mockResolvedValue({status:'PENDING',requestId:'waiting-ticket'});
 try {
  const view=render(<CourtForm generalId={1} onReserved={()=>{}}/>);
  await act(async()=>{});
  for(let i=0;i<3;i++){
   await act(async()=>{await vi.advanceTimersByTimeAsync(1000);});
   view.rerender(<CourtForm generalId={1} onReserved={()=>{}}/>);
  }
  expect(api.commandResult).toHaveBeenCalledWith('waiting-ticket');
  view.unmount();
 } finally {vi.useRealTimers();}
});
