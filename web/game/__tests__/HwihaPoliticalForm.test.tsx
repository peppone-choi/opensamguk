import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {vi,test,expect,beforeEach} from 'vitest';
import HwihaPoliticalForm from '../components/command/HwihaPoliticalForm';
import {api} from '../lib/api';
import {submitCommandAndAwaitResult} from '../lib/commandSubmit';

vi.mock('../lib/api',()=>({api:{politicalOptions:vi.fn(),command:vi.fn()}}));
vi.mock('../lib/commandSubmit',()=>({submitCommandAndAwaitResult:vi.fn()}));
const props={inputId:'action.independence' as const,generalId:7,turnIdx:0,unavailable:false,
    onToast:vi.fn(),onClose:vi.fn(),onReserved:vi.fn()};
beforeEach(()=>{
    vi.clearAllMocks();
    vi.mocked(api.politicalOptions).mockResolvedValue([{inputId:'action.independence',available:true}]);
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit=>{await submit();return {status:'reserved'} as any;});
});

test('independence submits only the server checked actor action',async()=>{
    render(<HwihaPoliticalForm {...props}/>);
    const button=await screen.findByRole('button',{name:'독립 예약'});
    await waitFor(()=>expect(button).toBeEnabled());
    fireEvent.click(button);
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.independence',{},7,0));
});

test('political action remains blocked when current county or renown fails',async()=>{
    vi.mocked(api.politicalOptions).mockResolvedValue([{inputId:'action.independence',available:false,
        code:'INSUFFICIENT_RENOWN',reason:'명망 50이 필요합니다.'}]);
    render(<HwihaPoliticalForm {...props}/>);
    expect(await screen.findByText('명망 50이 필요합니다.')).toBeInTheDocument();
    expect(screen.getByRole('button',{name:'독립 예약'})).toBeDisabled();
});
