import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {vi,test,expect,beforeEach} from 'vitest';
import HwihaPersonalForm from '../components/command/HwihaPersonalForm';
import {api} from '../lib/api';
import {submitCommandAndAwaitResult} from '../lib/commandSubmit';

vi.mock('../lib/api',()=>({api:{personalOptions:vi.fn(),command:vi.fn()}}));
vi.mock('../lib/commandSubmit',()=>({submitCommandAndAwaitResult:vi.fn()}));
const props={inputId:'action.selfTrain' as const,generalId:7,turnIdx:0,unavailable:false,
    onToast:vi.fn(),onClose:vi.fn(),onReserved:vi.fn()};
beforeEach(()=>{
    vi.clearAllMocks();
    vi.mocked(api.personalOptions).mockResolvedValue({inputId:'action.selfTrain',available:true,
        stats:[{stat:'strength',available:true},{stat:'charm',available:false,reason:'최대치'}]});
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit=>{await submit();return {status:'reserved'} as any;});
});

test('self training submits only an available server stat',async()=>{
    render(<HwihaPersonalForm {...props}/>);
    expect(await screen.findByRole('option',{name:'무력'})).toBeInTheDocument();
    expect(screen.getByRole('option',{name:'매력 — 최대치'})).toBeDisabled();
    fireEvent.click(screen.getByRole('button',{name:'단련 예약'}));
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.selfTrain',{stat:'strength'},7,0));
});

test('travel sends no client selected actor or location',async()=>{
    vi.mocked(api.personalOptions).mockResolvedValue({inputId:'action.travel',available:true,stats:[]});
    render(<HwihaPersonalForm {...props} inputId="action.travel"/>);
    await screen.findByRole('button',{name:'견문 예약'});
    fireEvent.click(screen.getByRole('button',{name:'견문 예약'}));
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.travel',{},7,0));
});

test('healthy actor cannot reserve recuperation',async()=>{
    vi.mocked(api.personalOptions).mockResolvedValue({inputId:'action.recuperate',available:false,
        code:'ALREADY_HEALTHY',reason:'요양할 필요가 없습니다.',stats:[]});
    render(<HwihaPersonalForm {...props} inputId="action.recuperate"/>);
    expect(await screen.findByText('요양할 필요가 없습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button',{name:'요양 예약'})).toBeDisabled();
});

test('retirement submits the chosen successor from server options',async()=>{
    vi.mocked(api.personalOptions).mockResolvedValue({inputId:'action.retire',available:true,
        successors:[{generalId:8,name:'후계',available:true},{generalId:9,name:'타인',available:false,reason:'불가'}]});
    render(<HwihaPersonalForm {...props} inputId="action.retire"/>);
    expect(await screen.findByRole('option',{name:'후계'})).toBeInTheDocument();
    expect(screen.getByRole('option',{name:'타인 — 불가'})).toBeDisabled();
    fireEvent.click(screen.getByRole('button',{name:'은퇴 예약'}));
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.retire',{successorGeneralId:8},7,0));
});
