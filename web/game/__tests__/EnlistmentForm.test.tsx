import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { vi, test, expect, beforeEach } from 'vitest';
import CommandModal from '../components/CommandModal';
import { api } from '../lib/api';
import { submitCommandAndAwaitResult } from '../lib/commandSubmit';
vi.mock('../lib/api', () => ({ api: { enlistmentOptions: vi.fn(), command: vi.fn(), availableCommands: vi.fn(), frontInfo: vi.fn() } }));
vi.mock('../lib/commandSubmit', () => ({ submitCommandAndAwaitResult: vi.fn() }));
const options = [
    {mode: 'RANDOM', label: '무작위', availability: {status: 'AVAILABLE'}},
    {mode: 'NATION', targetId: 2, label: '국가2', availability: {status: 'AVAILABLE'}},
    {mode: 'GENERAL', targetId: 11, label: '장수11', availability: {status: 'AVAILABLE'}},
    {mode: 'GENERAL', targetId: 12, label: '장수12', availability: {status: 'BLOCKED', reason: '명망이 부족합니다.'}},
];
beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.enlistmentOptions).mockResolvedValue({result: true, inputId: 'action.enlist', maxReservedTurns: 12, options} as any);
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit => { await submit(); return {status: 'reserved', reason: '예약'}; });
});
function modal(props = {}) { return render(<CommandModal ruleProfile="HWIHA" generalId={1} onClose={vi.fn()} onToast={vi.fn()} extraArgs={{cost: 999}} {...props}/>); }
test.each([['0', {mode:'RANDOM'}], ['1', {mode:'NATION', targetId:2}], ['2', {mode:'GENERAL', targetId:11}]])('server option %s sends exact single args', async (value, body) => {
    modal({turnIdx:11});
    fireEvent.change(await screen.findByLabelText('출사 대상'), {target:{value}});
    fireEvent.click(screen.getByRole('button', {name:'출사 예약'}));
    await waitFor(() => expect(api.command).toHaveBeenCalledWith('action.enlist', body, 1, 11));
    expect(api.availableCommands).not.toHaveBeenCalled();
});
test('blocked server reason prevents submission', async () => {
    modal(); fireEvent.change(await screen.findByLabelText('출사 대상'), {target:{value:'3'}});
    expect(screen.getByRole('status')).toHaveTextContent('명망이 부족합니다.');
    expect(screen.getByRole('button', {name:'출사 예약'})).toBeDisabled();
    expect(api.command).not.toHaveBeenCalled();
});
test.each([{turnIdx:12}, {pinnedCommand:'휴식'}, {ruleProfile:null}])('unavailable context refuses write %j', async props => {
    modal(props); expect(screen.queryByRole('button', {name:'출사 예약'})).not.toBeInTheDocument();
    expect(api.command).not.toHaveBeenCalled();
});
test('execution rejection stays visible without success', async () => {
    vi.mocked(submitCommandAndAwaitResult).mockResolvedValue({status:'blocked', reason:'대상이 바뀌었습니다.'} as any);
    const onToast = vi.fn(); modal({onToast});
    fireEvent.change(await screen.findByLabelText('출사 대상'), {target:{value:'0'}});
    fireEvent.click(screen.getByRole('button', {name:'출사 예약'}));
    expect(await screen.findByRole('alert')).toHaveTextContent('대상이 바뀌었습니다.'); expect(onToast).not.toHaveBeenCalled();
});
test('missing prop resolves authoritative global profile before exposing enlistment', async () => {
    vi.mocked(api.frontInfo).mockResolvedValue({global:{ruleProfile:'HWIHA'}} as any);
    modal({ruleProfile:undefined});
    expect(await screen.findByLabelText('출사 대상')).toBeInTheDocument();
    expect(api.frontInfo).toHaveBeenCalledOnce();
    expect(api.availableCommands).not.toHaveBeenCalled();
});
test('options read failure shows reason and cannot submit', async () => {
    vi.mocked(api.enlistmentOptions).mockRejectedValue(new Error('network'));
    modal();
    expect(await screen.findByRole('alert')).toHaveTextContent('출사 정보를 불러오지 못했습니다.');
    expect(screen.getByRole('button', {name:'출사 예약'})).toBeDisabled();
});
