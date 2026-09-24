import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {vi,test,expect,beforeEach} from 'vitest';
import HwihaFieldForm from '../components/command/HwihaFieldForm';
import {api} from '../lib/api';
import {submitCommandAndAwaitResult} from '../lib/commandSubmit';

vi.mock('../lib/api',()=>({api:{fieldOptions:vi.fn(),command:vi.fn()}}));
vi.mock('../lib/commandSubmit',()=>({submitCommandAndAwaitResult:vi.fn()}));
const props={inputId:'action.farm' as const,generalId:1,turnIdx:11,unavailable:false,
    onToast:vi.fn(),onClose:vi.fn(),onReserved:vi.fn()};
beforeEach(()=>{
    vi.clearAllMocks();
    vi.mocked(api.fieldOptions).mockResolvedValue({inputId:'action.farm',available:true,countyId:4,countyName:'漢縣'});
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit=>{await submit();return {status:'reserved'} as any;});
});

test('reserves the current county action without caller supplied target or cost',async()=>{
    render(<HwihaFieldForm {...props}/>);
    expect(await screen.findByText('대상 縣: 漢縣')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button',{name:'농지개간 예약'}));
    await waitFor(()=>expect(api.command).toHaveBeenCalledWith('action.farm',{},1,11));
    expect(props.onToast).toHaveBeenCalledWith('농지개간 명령이 예약되었습니다.','success');
});

test('invalid slot and failed precheck cannot reserve',async()=>{
    const view=render(<HwihaFieldForm {...props} turnIdx={12}/>);
    expect(api.fieldOptions).not.toHaveBeenCalled();
    vi.mocked(api.fieldOptions).mockResolvedValue({inputId:'action.farm',available:false,
        code:'FOREIGN_COUNTY',reason:'본인 세력의 縣이 아닙니다.'});
    view.rerender(<HwihaFieldForm {...props}/>);
    expect(await screen.findByText('본인 세력의 縣이 아닙니다.')).toBeInTheDocument();
    expect(screen.getByRole('button',{name:'농지개간 예약'})).toBeDisabled();
});
