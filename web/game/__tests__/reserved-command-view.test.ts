import { describe, expect, it } from 'vitest';
import { reservedCommandText, reservedInputId, type ReservedCommandNames } from '../lib/command-flow/reserved-command-view';

const names: ReservedCommandNames = { cities: { '9': '진류현', '10': '낙양현' }, units: { '1100': '창병', '1200': '기병' }, nations: { '3': '조조' } };
const sentence = (action: string, arg: Record<string, unknown> = {}) => reservedCommandText({ action, brief: '', arg }, names);

describe('저장된 예턴 인자 → 자연어', () => {
    it.each(['che_징병', 'action.conscript'])('%s: 실제 병종 registry 이름과 저장 인원', action => {
        expect(sentence(action, { crewType: 1100, amount: 1500 })).toBe('창병 1,500명 징병');
        expect(sentence(action, { crewType: '1200', amount: '500' })).toBe('기병 500명 징병');
    });
    it('모병도 요청된 인원만 표시하고 실행 상한을 추정하지 않는다', () => {
        expect(sentence('che_모병', { crewType: 1100, amount: 0 })).toBe('창병 0명 모병');
        expect(sentence('che_모병', { crewType: 1100, amount: 99999 })).toBe('창병 99,999명 모병');
    });
    it('미등록/누락 병종 숫자·유효하지 않은 수량을 이름이나 수량으로 꾸미지 않는다', () => {
        expect(sentence('che_징병', { crewType: 9999, amount: -7 })).toBe('병종 이름 확인 불가 인원 확인 불가 징병');
        expect(sentence('action.conscript')).toBe('징병 (병종·인원 미기록)');
        expect(sentence('action.conscript', { amount: 500 })).toBe('병종 미기록 500명 징병');
        expect(sentence('action.conscript', { crewType: 1100, amount: Infinity })).not.toContain('Infinity');
    });
    it('이동/강행/출병과 강공은 해당 예약의 현 ID를 풀고 한글 조사를 맞춘다', () => {
        expect(sentence('che_이동', { destCityID: 9 })).toBe('진류현으로 이동');
        expect(sentence('action.forcedMarch', { destCityID: '10' })).toBe('낙양현으로 강행');
        expect(sentence('che_출병', { destCityID: 10 })).toBe('낙양현으로 출병');
        expect(sentence('action.deploy', { destCityID: 9, bugokIds: [3, 4] })).toBe('부곡 #3·부곡 #4 — 진류현으로 출병');
        expect(sentence('action.assault', { targetCountyId: 10 })).toBe('낙양현 공격');
    });
    it('구역 ID를 현 ID로 대입하지 않고 귀환/공격의 암묵 대상을 추측하지 않는다', () => {
        expect(sentence('action.move', { destinationProvinceId: '9' })).toBe('이동 (목적지 현 이름 확인 불가)');
        expect(sentence('action.move', { destCityID: 99 })).toBe('현 #99 (이름 확인 불가)로 이동');
        expect(sentence('action.return')).toBe('귀환 (목적지 미기록)');
        expect(sentence('action.assault')).toBe('강공 (대상 현 확인 불가)');
    });
    it('병종 전환 후보/예약에서 동일 registry 이름을 쓰며 계열 번호와 병종을 혼동하지 않는다', () => {
        expect(sentence('action.convertProficiency', { bugokId: 3, crewTypeId: 1100 })).toBe('부곡 #3 — 창병으로 병종 바꿔 익히기');
        expect(sentence('che_숙련전환', { srcArmType: 1, destArmType: 2 })).toBe('병종 계열 전환 (출발·도착 계열 이름 확인 불가)');
    });
    it('증여/헌납/수송/매매/단련도 저장 자원·수량·대상을 풀어 쓴다', () => {
        expect(sentence('action.gift', { targetGeneralId: 7, resource: 'GRAIN', amount: 1500 })).toBe('쌀 1,500 — 장수 #7 (이름 확인 불가)에게 증여');
        expect(sentence('che_헌납', { isGold: true, amount: 200 })).toBe('금 200 헌납');
        expect(sentence('action.transport', { cargo: 'TIMBER', amount: 300, targetCountyId: 9 })).toBe('목재 300 — 진류현으로 물자조달');
        expect(sentence('che_군량매매', { buyRice: false, amount: 500 })).toBe('쌀 매각 — 500');
        expect(sentence('action.selfTrain', { stat: 'strength' })).toBe('무력 단련');
    });
    it('저장 슬롯을 JSON 왕복해도 같은 문장이며 모르는 명령은 서버 글자로만 표시한다', () => {
        const slot = { action: 'che_징병', brief: '징병', arg: { crewType: 1100, amount: 1500 } };
        expect(reservedCommandText(JSON.parse(JSON.stringify(slot)), names)).toBe(reservedCommandText(slot, names));
        expect(reservedCommandText({ action: 'unknown', brief: '<C>특수 명령</>', arg: {} })).toBe('특수 명령');
        expect(reservedInputId('che_이동')).toBe('action.move');
        expect(reservedInputId('unknown')).toBeNull();
    });
    it('출사 targetId는 mode의 실제 세력/장수 식별자를 구별한다', () => {
        expect(sentence('action.enlist', { mode: 'NATION', targetId: 3 })).toBe('조조에 출사');
        expect(sentence('action.enlist', { mode: 'GENERAL', targetId: 3 })).toBe('장수 #3 (이름 확인 불가)에게 출사');
        expect(sentence('action.enlist', { targetId: 3 })).toBe('출사 (방식·대상 미기록)');
    });
});
