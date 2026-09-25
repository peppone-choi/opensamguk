import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {vi,test,expect,beforeEach} from 'vitest';
import MilitaryForm from '../components/command/MilitaryForm';
import {api} from '../lib/api';
import {submitCommandAndAwaitResult} from '../lib/commandSubmit';

vi.mock('../lib/api',()=>({api:{militaryOptions:vi.fn(),command:vi.fn()}}));
vi.mock('../lib/commandSubmit',()=>({submitCommandAndAwaitResult:vi.fn()}));
const props={inputId:'action.muster' as const,generalId:1,turnIdx:0,unavailable:false,
    onToast:vi.fn(),onClose:vi.fn(),onReserved:vi.fn()};
beforeEach(()=>{
    vi.clearAllMocks();
    vi.mocked(api.militaryOptions).mockResolvedValue({inputId:'action.muster',available:true,gatheringCorps:2});
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit=>{await submit();return {status:'reserved'} as any;});
});

test('muster reserves a command for the owned corps without caller supplied destination',async()=>{
    render(<MilitaryForm {...props}/>);
    expect(await screen.findByText('집결 대상 부곡: 2')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button',{name:'집합 예약'}));
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.muster',{},1,0));
});

test('failed military precheck cannot reserve',async()=>{
    vi.mocked(api.militaryOptions).mockResolvedValue({inputId:'action.muster',available:false,
        code:'NO_COMMANDED_CORPS',reason:'집결시킬 부곡이 없습니다.'});
    render(<MilitaryForm {...props}/>);
    expect(await screen.findByText('집결시킬 부곡이 없습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button',{name:'집합 예약'})).toBeDisabled();
});

test('conscript shows server calculated troops and cost before reservation',async()=>{
    vi.mocked(api.militaryOptions).mockResolvedValue({inputId:'action.conscript',available:true,
        countyName:'宛縣',troops:100,troopsAfter:150,populationAfter:950,training:50,trainingAfter:50,
        morale:50,moraleAfter:50,grainCost:15000,moneyCost:0});
    render(<MilitaryForm {...props} inputId="action.conscript"/>);
    expect(await screen.findByText(/도시 병력 100 → 150/)).toHaveTextContent('곡물 15000 소모');
    fireEvent.click(screen.getByRole('button',{name:'징병 예약'}));
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.conscript',{},1,0));
});
