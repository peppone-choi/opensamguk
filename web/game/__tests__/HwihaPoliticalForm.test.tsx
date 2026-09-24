import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {vi,test,expect,beforeEach} from 'vitest';
import HwihaPoliticalForm from '../components/command/HwihaPoliticalForm';
import {api} from '../lib/api';
import {submitCommandAndAwaitResult} from '../lib/commandSubmit';

vi.mock('../lib/api',()=>({api:{politicalOptions:vi.fn(),politicalConsentOptions:vi.fn(),courtPoliticalConsent:vi.fn(),command:vi.fn()}}));
vi.mock('../lib/commandSubmit',()=>({submitCommandAndAwaitResult:vi.fn()}));
const props={inputId:'action.independence' as const,generalId:7,turnIdx:0,unavailable:false,
    onToast:vi.fn(),onClose:vi.fn(),onReserved:vi.fn()};
beforeEach(()=>{
    vi.clearAllMocks();
    vi.mocked(api.politicalOptions).mockResolvedValue([{inputId:'action.independence',available:true}]);
    vi.mocked(api.politicalConsentOptions).mockResolvedValue([]);
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit=>{await submit();return {status:'reserved'} as any;});
});

test('target general can accept an oath and issuer then reserves that target',async()=>{
    vi.mocked(api.politicalOptions).mockResolvedValue([{inputId:'action.oath',available:true,
        targets:[{generalId:8,name:'동료',available:true}]}]);
    vi.mocked(api.politicalConsentOptions).mockResolvedValue([{inputId:'action.oath',issuerGeneralId:9,
        issuerName:'청한 장수',available:true,accepted:null}]);
    render(<HwihaPoliticalForm {...props} inputId="action.oath"/>);
    fireEvent.click(await screen.findByRole('button',{name:'수락'}));
    await waitFor(()=>expect(api.courtPoliticalConsent).toHaveBeenCalledWith(7,
        {issuerGeneralId:9,inputId:'action.oath',accepted:true}));
});

test('issuer reserves an explicitly accepted target',async()=>{
    vi.mocked(api.politicalOptions).mockResolvedValue([{inputId:'action.abdicate',available:true,
        targets:[{generalId:8,name:'후계자',available:true}]}]);
    render(<HwihaPoliticalForm {...props} inputId="action.abdicate"/>);
    const button=await screen.findByRole('button',{name:'선양 예약'});
    await waitFor(()=>expect(button).toBeEnabled());
    fireEvent.click(button);
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.abdicate',{targetGeneralId:8},7,0));
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
