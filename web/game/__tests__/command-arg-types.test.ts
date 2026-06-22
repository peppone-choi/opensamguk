import { describe, expect, it } from 'vitest';
import { argFieldName, inferArgType } from '@/lib/command-arg-types';

describe('command arg type inference', () => {
    it('routes composite legacy command forms to their dedicated modals', () => {
        expect(inferArgType('che_건국')).toBe('founding');
        expect(inferArgType('che_징병')).toBe('recruit');
        expect(argFieldName('founding')).toBeNull();
        expect(argFieldName('recruit')).toBeNull();
    });

    it('keeps scalar legacy command field names unchanged', () => {
        expect(inferArgType('che_이동')).toBe('city');
        expect(inferArgType('che_선전포고')).toBe('nation');
        expect(inferArgType('che_부대_탈퇴')).toBe('general');
        expect(inferArgType('che_헌납')).toBe('amount');
        expect(argFieldName('city')).toBe('destCityID');
        expect(argFieldName('nation')).toBe('destNationID');
        expect(argFieldName('general')).toBe('destGeneralID');
        expect(argFieldName('amount')).toBe('amount');
    });
});
