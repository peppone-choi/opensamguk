import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {vi,test,expect,beforeEach} from 'vitest';
import CommandModal from '../components/CommandModal';
import HwihaDeployForm from '../components/command/HwihaDeployForm';
import {api} from '../lib/api';
import {submitCommandAndAwaitResult} from '../lib/commandSubmit';
vi.mock('../lib/api',()=>({api:{deployOptions:vi.fn(),command:vi.fn()}}));
vi.mock('../lib/commandSubmit',()=>({submitCommandAndAwaitResult:vi.fn()}));
const options={available:true,maxReservedTurns:12 as const,bugoks:[{id:7,name:'부곡',troops:100,available:true},{id:8,name:'잠긴 부곡',troops:20,available:false,reason:'출전 중'}],destinations:[{provinceId:'p1',name:'목적지'}]};
const props={generalId:1,turnIdx:11,unavailable:false,onToast:vi.fn(),onClose:vi.fn(),onReserved:vi.fn()};
beforeEach(()=>{vi.clearAllMocks();vi.mocked(api.deployOptions).mockResolvedValue(options);vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit=>{await submit();return {status:'reserved'} as any;});});
async function choose(){fireEvent.click(await screen.findByLabelText('부곡 · 100명'));fireEvent.change(screen.getByLabelText('출병 목적지'),{target:{value:'p1'}});}
test('requires both choices sends only canonical personal args and distinguishes reservation',async()=>{
    render(<HwihaDeployForm {...props}/>);expect(screen.getByRole('button',{name:'출병 예약'})).toBeDisabled();await choose();
    expect(screen.getByLabelText('잠긴 부곡 · 20명 — 출전 중')).toBeDisabled();
    fireEvent.click(screen.getByRole('button',{name:'출병 예약'}));
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.deploy',{bugokIds:[7],destinationProvinceId:'p1'},1,11));
    expect(props.onToast).toHaveBeenCalledWith('출병 명령이 예약되었습니다.','success');
});
test.each([{available:false,reason:'소유권 없음'},{order:{orderId:'order',destinationProvinceId:'p1',stop:'ARRIVED'}}])('server denial or existing order cannot submit %j',async change=>{
    vi.mocked(api.deployOptions).mockResolvedValue({...options,...change});render(<HwihaDeployForm {...props}/>);
    await screen.findByLabelText('출병 목적지');expect(screen.getByRole('button',{name:'출병 예약'})).toBeDisabled();expect(api.command).not.toHaveBeenCalled();
});
test('late actor response is discarded',async()=>{
    let resolve!:(v:typeof options)=>void;vi.mocked(api.deployOptions).mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));
    const view=render(<HwihaDeployForm {...props}/>);view.rerender(<HwihaDeployForm {...props} generalId={2}/>);
    await choose();resolve({...options,available:false});await waitFor(()=>expect(screen.getByRole('button',{name:'출병 예약'})).toBeEnabled());
});
test('refresh resets selections and ignores stale submission completion',async()=>{
    let finish!:(v:any)=>void;vi.mocked(submitCommandAndAwaitResult).mockImplementation(()=>new Promise(r=>{finish=r;}));
    const view=render(<HwihaDeployForm {...props}/>);await choose();fireEvent.click(screen.getByRole('button',{name:'출병 예약'}));
    expect(submitCommandAndAwaitResult).toHaveBeenCalledOnce();view.rerender(<HwihaDeployForm {...props} refreshKey={1}/>);
    await waitFor(()=>expect(screen.getByRole('button',{name:'출병 예약'})).toBeDisabled());finish({status:'applied'});
    await waitFor(()=>expect(props.onToast).not.toHaveBeenCalled());expect(props.onClose).not.toHaveBeenCalled();
});
test.each([12,-1])('invalid slot %i does not fetch',turnIdx=>{render(<HwihaDeployForm {...props} turnIdx={turnIdx}/>);expect(api.deployOptions).not.toHaveBeenCalled();});
test('read failure stays blocked',async()=>{vi.mocked(api.deployOptions).mockRejectedValue(new Error('offline'));render(<HwihaDeployForm {...props}/>);expect(await screen.findByRole('alert')).toHaveTextContent('불러오지 못했습니다');expect(screen.getByRole('button')).toBeDisabled();});

test('existing modal personal action chooser opens deployment without legacy catalog',async()=>{
    render(<CommandModal ruleProfile="HWIHA" pinnedCommand="action.deploy" generalId={1} turnIdx={0} onClose={vi.fn()} onToast={vi.fn()}/>);
    expect(await screen.findByLabelText('출병 목적지')).toBeInTheDocument();
});
test('multiple units sort and double clicks submit once while rejection stays visible',async()=>{
    vi.mocked(api.deployOptions).mockResolvedValue({...options,bugoks:[{id:9,name:'아홉',troops:10,available:true},options.bugoks[0]]});
    let finish!:(v:any)=>void;vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit=>{await submit();return new Promise(r=>{finish=r;});});
    render(<HwihaDeployForm {...props}/>);fireEvent.click(await screen.findByLabelText('아홉 · 10명'));await choose();
    const button=screen.getByRole('button',{name:'출병 예약'});fireEvent.click(button);fireEvent.click(button);
    expect(api.command).toHaveBeenCalledWith('action.deploy',{bugokIds:[7,9],destinationProvinceId:'p1'},1,11);
    await waitFor(()=>expect(finish).toBeDefined());finish({status:'blocked',reason:'부대 소유권 변경'});
    expect(await screen.findByRole('alert')).toHaveTextContent('부대 소유권 변경');expect(submitCommandAndAwaitResult).toHaveBeenCalledOnce();expect(props.onToast).not.toHaveBeenCalled();
});
