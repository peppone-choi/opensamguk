import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {vi,test,expect,beforeEach} from 'vitest';
import TransferForm from '../components/command/TransferForm';
import {api} from '../lib/api';
import {submitCommandAndAwaitResult} from '../lib/commandSubmit';

vi.mock('../lib/api',()=>({api:{transferOptions:vi.fn(),command:vi.fn()}}));
vi.mock('../lib/commandSubmit',()=>({submitCommandAndAwaitResult:vi.fn()}));
const props={inputId:'action.gift' as const,generalId:7,turnIdx:0,unavailable:false,
    onToast:vi.fn(),onClose:vi.fn(),onReserved:vi.fn()};
beforeEach(()=>{
    vi.clearAllMocks();
    vi.mocked(api.transferOptions).mockResolvedValue({inputId:'action.gift',available:true,
        resources:[{resource:'MONEY',available:true,maxAmount:100},
            {resource:'IRON',available:false,maxAmount:0,reason:'보유한 자원이 부족합니다.'}],
        targets:[{generalId:8,name:'받는 이',available:true}]});
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit=>{await submit();return {status:'reserved'} as any;});
});

test('gift submits a positive amount and selected co-located recipient',async()=>{
    render(<TransferForm {...props}/>);
    expect(await screen.findByRole('option',{name:'받는 이'})).toBeInTheDocument();
    fireEvent.change(screen.getByRole('spinbutton',{name:'수량'}),{target:{value:'25'}});
    fireEvent.click(screen.getByRole('button',{name:'증여 예약'}));
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.gift',
        {resource:'MONEY',amount:25,targetGeneralId:8},7,0));
});

test('gift rejects an amount beyond personal stock before submission',async()=>{
    render(<TransferForm {...props}/>);
    await screen.findByRole('option',{name:'받는 이'});
    fireEvent.change(screen.getByRole('spinbutton',{name:'수량'}),{target:{value:'101'}});
    expect(screen.getByRole('button',{name:'증여 예약'})).toBeDisabled();
    expect(api.command).not.toHaveBeenCalled();
});
