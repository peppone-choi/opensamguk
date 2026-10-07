// 예약 readback의 인자만 문장으로 푼다. 현재 장수·선택 초안·예상 실행 결과는 쓰지 않는다.
import { FLOW_COMMANDS, flowCommand } from './catalog';
import { withParticle } from '@opensamguk/ui';
import { helpText } from '../help-labels';
import type { ReservedSlot } from '../types';

export interface ReservedCommandNames {
    readonly cities: Readonly<Record<string, string>>;
    readonly provinces?: Readonly<Record<string, string>>;
    readonly units: Readonly<Record<string, string>>;
    readonly nations?: Readonly<Record<string, string>>;
}

export const EMPTY_COMMAND_NAMES: ReservedCommandNames = { cities: {}, units: {} };

/** 저장 코드의 접두사를 추측하지 않는다. 정본 코드 또는 서버가 제공한 명령명으로 읽는다. */
export function reservedInputId(action: string, brief = ''): string | null {
    const direct = flowCommand(action);
    if (direct) return direct.inputId;
    const name = helpText(brief.replace(/<[^>]*>/g, '').trim());
    return FLOW_COMMANDS.find(c => c.name === name)?.inputId ?? null;
}

const text = (v: unknown): string | null => typeof v === 'string' && v.trim() ? v.trim() : null;
const integer = (v: unknown): number | null => {
    if (typeof v !== 'number' && (typeof v !== 'string' || !/^\d+$/.test(v))) return null;
    const n = Number(v);
    return Number.isSafeInteger(n) && n >= 0 ? n : null;
};
const count = (v: unknown): string | null => {
    const n = integer(v);
    return n == null ? null : n.toLocaleString('ko-KR');
};
const unitName = (id: unknown, names: ReservedCommandNames): string => {
    const n = integer(id);
    return n == null ? '병종 미기록' : text(names.units[String(n)]) ?? '병종 이름 확인 불가';
};

function destination(arg: Readonly<Record<string, unknown>>, names: ReservedCommandNames): string {
    const id = integer(arg.destCityID ?? arg.targetCityID ?? arg.targetCountyId);
    if (id != null) return names.cities[String(id)] ?? `현 #${id} (이름 확인 불가)`;
    // 구역 ID는 현 ID와 다른 식별자다. 임의로 같은 숫자의 현에 연결하지 않는다.
    const province = typeof arg.destinationProvinceId === 'string' ? arg.destinationProvinceId : null;
    if (province) return text(names.provinces?.[province]) ?? '목적지 현 이름 확인 불가';
    return '목적지 미기록';
}

function travel(arg: Readonly<Record<string, unknown>>, names: ReservedCommandNames, action: string): string {
    const place = destination(arg, names);
    return place === '목적지 미기록' || place === '목적지 현 이름 확인 불가'
        ? `${action} (${place})` : `${withParticle(place, '로/으로')} ${action}`;
}

const resources: Readonly<Record<string, string>> = {
    MONEY: '금', GRAIN: '쌀', IRON: '철', TIMBER: '목재', HORSES: '말', gold: '금', rice: '쌀',
};
const stats: Readonly<Record<string, string>> = {
    leadership: '통솔', strength: '무력', intelligence: '지력', politics: '정치', charm: '매력',
};
const person = (id: unknown): string => integer(id) == null ? '대상 장수 미기록' : `장수 #${integer(id)} (이름 확인 불가)`;
const bugok = (id: unknown): string => integer(id) == null ? '부곡 미기록' : `부곡 #${integer(id)}`;

/** 표시용 자연어. 병종 ID를 이름 대신 내보내지 않으며 누락된 수량·대상을 만들어 넣지 않는다. */
export function reservedCommandText(slot: Pick<ReservedSlot, 'action' | 'brief' | 'arg'>, names = EMPTY_COMMAND_NAMES): string {
    const inputId = reservedInputId(slot.action, slot.brief);
    const cmd = flowCommand(inputId);
    const label = cmd?.name ?? text(slot.brief)?.replace(/<[^>]*>/g, '') ?? slot.action;
    const arg = slot.arg ?? {};
    switch (inputId) {
        case 'action.conscript': case 'action.raiseVolunteers': {
            if (Object.keys(arg).length === 0) return `${label} (병종·인원 미기록)`;
            const amount = count(arg.amount);
            return `${unitName(arg.crewType ?? arg.crewTypeId, names)} ${amount == null ? '인원 확인 불가' : `${amount}명`} ${label}`;
        }
        case 'action.move': return travel(arg, names, '이동');
        case 'action.forcedMarch': return travel(arg, names, '강행');
        case 'action.return': return travel(arg, names, '귀환');
        case 'action.deploy': {
            const units = Array.isArray(arg.bugokIds) ? arg.bugokIds.map(bugok).join('·') : null;
            return `${units ? `${units} — ` : ''}${travel(arg, names, '출병')}`;
        }
        case 'action.assault': case 'action.demandSurrender': {
            const target = destination(arg, names);
            if (target === '목적지 미기록' || target === '목적지 현 이름 확인 불가') return `${label} (대상 현 확인 불가)`;
            return inputId === 'action.assault' ? `${target} 공격` : `${target}에 항복 권고`;
        }
        case 'action.siegeRoadFort': return `${text(arg.fortId) ? '지정 보루 (이름 확인 불가)' : '대상 보루 미기록'} 포위`;
        case 'action.convertProficiency': return arg.srcArmType != null || arg.destArmType != null
            ? '병종 계열 전환 (출발·도착 계열 이름 확인 불가)'
            : `${bugok(arg.bugokId)} — ${withParticle(unitName(arg.crewTypeId ?? arg.crewType, names), '로/으로')} 병종 바꿔 익히기`;
        case 'action.gift': case 'action.donate': {
            const resource = resources[String(arg.resource)] ?? (typeof arg.isGold === 'boolean' ? arg.isGold ? '금' : '쌀' : '자원 확인 불가');
            return `${resource} ${count(arg.amount) ?? '수량 미기록'}${inputId === 'action.gift' ? ` — ${person(arg.targetGeneralId ?? arg.destGeneralID)}에게` : ''} ${label}`;
        }
        case 'action.transport': return `${resources[String(arg.cargo)] ?? '물자 확인 불가'} ${count(arg.amount) ?? '수량 미기록'} — ${travel(arg, names, '물자조달')}`;
        case 'action.tradeGrain': return `쌀 ${arg.side === 'BUY' || arg.buyRice === true ? '매입' : arg.side === 'SELL' || arg.buyRice === false ? '매각' : '매매 방향 미기록'} — ${count(arg.amount) ?? '수량 미기록'}`;
        case 'action.tradeEquipment': return `장비${integer(arg.treasureId) == null ? ' 미기록' : ` #${integer(arg.treasureId)} (이름 확인 불가)`} ${arg.side === 'BUY' ? '매입' : arg.side === 'SELL' ? '매각' : '매매 방향 미기록'}`;
        case 'action.selfTrain': return `${stats[String(arg.stat)] ?? '능력 미기록'} 단련`;
        case 'action.employ': case 'action.persuadeCaptive': case 'action.abdicate': case 'action.oath':
            return `${person(arg.targetGeneralId)} ${label}`;
        case 'action.retire': return `${person(arg.successorGeneralId)}에게 승계하고 은퇴`;
        case 'action.scout': return `${text(arg.commanderyId) ? '지정 군 (이름 확인 불가)' : '대상 군 미기록'} 첩보`;
        case 'action.enlist': {
            if (arg.mode === 'RANDOM') return '주공을 무작위로 정해 출사';
            if (arg.mode === 'GENERAL') return `${person(arg.targetId)}에게 출사`;
            if (arg.mode === 'NATION') {
                const id = integer(arg.targetId);
                return `${id == null ? '대상 세력 미기록' : names.nations?.[String(id)] ?? `세력 #${id} (이름 확인 불가)`}에 출사`;
            }
            return '출사 (방식·대상 미기록)';
        }
        default: return Object.keys(arg).length ? `${label} (인자 설명 확인 불가)` : label;
    }
}
