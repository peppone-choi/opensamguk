import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {vi,test,expect,beforeEach} from 'vitest';
import TravelForm from '../components/command/TravelForm';
import {api} from '../lib/api';
import {submitCommandAndAwaitResult} from '../lib/commandSubmit';

vi.mock('../lib/api',()=>({api:{travelOptions:vi.fn(),command:vi.fn()}}));
vi.mock('../lib/commandSubmit',()=>({submitCommandAndAwaitResult:vi.fn()}));
const options={inputId:'action.move' as const,available:true,destinations:[
    {provinceId:'A',name:'현재 省',available:false,code:'ALREADY_THERE',reason:'이미 목적지에 있습니다.'},
    {provinceId:'B',name:'다음 省',available:true},
]};
const props={inputId:'action.move' as const,generalId:1,turnIdx:11,unavailable:false,
    onToast:vi.fn(),onClose:vi.fn(),onReserved:vi.fn()};
beforeEach(()=>{
    vi.clearAllMocks();vi.mocked(api.travelOptions).mockResolvedValue(options);
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit=>{await submit();return {status:'reserved'} as any;});
});

test('only a server-approved destination can be reserved',async()=>{
    render(<TravelForm {...props}/>);
    expect(screen.getByRole('button',{name:'이동 예약'})).toBeDisabled();
    expect(await screen.findByRole('option',{name:'현재 省 — 이미 목적지에 있습니다.'})).toBeDisabled();
    fireEvent.change(screen.getByLabelText('목적 省'),{target:{value:'B'}});
    fireEvent.click(screen.getByRole('button',{name:'이동 예약'}));
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.move',{destinationProvinceId:'B'},1,11));
    expect(props.onToast).toHaveBeenCalledWith('이동 명령이 예약되었습니다.','success');
});

test('return submits no caller-supplied destination',async()=>{
    vi.mocked(api.travelOptions).mockResolvedValue({inputId:'action.return',available:true,
        destinations:[{provinceId:'B',name:'근무 城',available:true}]});
    render(<TravelForm {...props} inputId="action.return"/>);
    expect(await screen.findByText('귀환지: 근무 城')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button',{name:'귀환 예약'}));
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.return',{},1,11));
});

test('invalid slot and unavailable server state cannot submit',async()=>{
    const view=render(<TravelForm {...props} turnIdx={12}/>);
    expect(api.travelOptions).not.toHaveBeenCalled();
    vi.mocked(api.travelOptions).mockResolvedValue({inputId:'action.move',available:false,reason:'조우 중',destinations:[]});
    view.rerender(<TravelForm {...props}/>);
    await screen.findByLabelText('목적 省');
    expect(screen.getByRole('button',{name:'이동 예약'})).toBeDisabled();
});
