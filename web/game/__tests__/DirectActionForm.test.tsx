import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {vi,test,expect,beforeEach} from 'vitest';
import HwihaLegacyDirectForm from '../components/command/HwihaLegacyDirectForm';
import {api} from '../lib/api';
import {submitCommandAndAwaitResult} from '../lib/commandSubmit';

vi.mock('../lib/api',()=>({api:{legacyDirectOptions:vi.fn(),command:vi.fn()}}));
vi.mock('../lib/commandSubmit',()=>({submitCommandAndAwaitResult:vi.fn()}));
const props={inputId:'action.transport' as const,generalId:7,turnIdx:0,unavailable:false,
    onToast:vi.fn(),onClose:vi.fn(),onReserved:vi.fn()};
beforeEach(()=>{
    vi.clearAllMocks();
    vi.mocked(api.legacyDirectOptions).mockResolvedValue({inputId:'action.transport',available:true,
        choices:[{label:'옆 縣 · GRAIN',arguments:{targetCountyId:8,cargo:'GRAIN',amount:1},available:true,maxAmount:500}]});
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit=>{await submit();return {status:'reserved'} as any;});
});

test('transport submits server checked target and chosen amount',async()=>{
    render(<HwihaLegacyDirectForm {...props}/>);
    const amount=await screen.findByRole('spinbutton',{name:'운반량'});
    fireEvent.change(amount,{target:{value:'400'}});
    fireEvent.click(screen.getByRole('button',{name:'물자조달 예약'}));
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.transport',
        {targetCountyId:8,cargo:'GRAIN',amount:400},7,0));
});

test('transport over available stock cannot be reserved',async()=>{
    render(<HwihaLegacyDirectForm {...props}/>);
    const amount=await screen.findByRole('spinbutton',{name:'운반량'});
    fireEvent.change(amount,{target:{value:'501'}});
    expect(screen.getByRole('button',{name:'물자조달 예약'})).toBeDisabled();
});
