import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {vi,test,expect,beforeEach} from 'vitest';
import HwihaPeopleForm from '../components/command/HwihaPeopleForm';
import {api} from '../lib/api';
import {submitCommandAndAwaitResult} from '../lib/commandSubmit';

vi.mock('../lib/api',()=>({api:{peopleOptions:vi.fn(),command:vi.fn()}}));
vi.mock('../lib/commandSubmit',()=>({submitCommandAndAwaitResult:vi.fn()}));
const props={inputId:'action.employ' as const,generalId:7,turnIdx:0,unavailable:false,
    onToast:vi.fn(),onClose:vi.fn(),onReserved:vi.fn()};
beforeEach(()=>{
    vi.clearAllMocks();
    vi.mocked(api.peopleOptions).mockResolvedValue({inputId:'action.employ',available:true,
        targets:[{generalId:8,name:'재야',available:true}]});
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit=>{await submit();return {status:'reserved'} as any;});
});

test('employ reserves only a server discovered target',async()=>{
    render(<HwihaPeopleForm {...props}/>);
    expect(await screen.findByRole('option',{name:'재야'})).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button',{name:'등용 예약'}));
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.employ',{targetGeneralId:8},7,0));
});

test('search sends no target supplied by the caller',async()=>{
    vi.mocked(api.peopleOptions).mockResolvedValue({inputId:'action.search',available:true,
        undiscoveredCount:2,targets:[]});
    render(<HwihaPeopleForm {...props} inputId="action.search"/>);
    expect(await screen.findByText('찾을 수 있는 인물 2명')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button',{name:'인재탐색 예약'}));
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.search',{},7,0));
});

test('blocked captive target cannot be submitted',async()=>{
    vi.mocked(api.peopleOptions).mockResolvedValue({inputId:'action.persuadeCaptive',available:false,
        code:'TARGET_UNAVAILABLE',reason:'현재 포로가 없습니다.',targets:[]});
    render(<HwihaPeopleForm {...props} inputId="action.persuadeCaptive"/>);
    expect(await screen.findByText('현재 포로가 없습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button',{name:'포로 설득 예약'})).toBeDisabled();
});
