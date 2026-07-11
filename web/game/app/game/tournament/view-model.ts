import { calcTournamentTerm } from '@/lib/utilGame';
import type {
    TournamentBracketMatch,
    TournamentEntrant,
    TournamentGroupStage,
} from '@/types/game';

const GROUP_NUM = ['一', '二', '三', '四', '五', '六', '七', '八'] as const;
const STAGES = [
    { stage: 'MAIN', title: '조별 본선 순위' },
    { stage: 'PRELIMINARY', title: '조별 예선 순위' },
] as const satisfies readonly { readonly stage: TournamentGroupStage; readonly title: string }[];
const BRACKET_ROUNDS = [
    { round: 16, label: '16강' },
    { round: 8, label: '8강' },
    { round: 4, label: '4강' },
    { round: 2, label: '결승' },
] as const;
const STATE_TEXT = [
    '경기 없음',
    '참가 모집중',
    '예선 진행중',
    '본선 추첨중',
    '본선 진행중',
    '16강 배정중',
    '베팅 진행중',
    '16강 진행중',
    '8강 진행중',
    '4강 진행중',
    '결승 진행중',
] as const;
const ABILITY_LABEL = {
    전력전: '종합',
    통솔전: '통솔',
    일기토: '무력',
    설전: '지력',
} as const;

export function buildStandingSections(entrants: readonly TournamentEntrant[]) {
    return STAGES.map(({ stage, title }) => ({
        stage,
        title,
        groups: GROUP_NUM.map((number, groupNo) => ({
            groupNo,
            label: `${number}조`,
            rows: entrants.filter((entrant) => entrant.stage === stage && entrant.groupNo === groupNo),
        })),
    }));
}

export function buildBracketRounds(bracket: readonly TournamentBracketMatch[]) {
    return BRACKET_ROUNDS.map(({ round, label }) => ({
        round,
        label,
        matches: bracket
            .filter((match) => match.round === round)
            .slice()
            .sort((left, right) => left.matchIdx - right.matchIdx),
    }));
}

export function tournamentStateText(state: number): string {
    return STATE_TEXT[state] ?? `TOURNAMENT_TYPE_ERR_${state}`;
}

export function tournamentAbilityLabel(type: keyof typeof ABILITY_LABEL): string {
    return ABILITY_LABEL[type];
}

export function tournamentTermText(turnTerm: number): string {
    const seconds = calcTournamentTerm(turnTerm);
    if (seconds % 60 === 0) return `경기당 ${seconds / 60}분`;
    if (seconds > 60) return `경기당 ${Math.floor(seconds / 60)}분 ${seconds % 60}초`;
    return `경기당 ${seconds}초`;
}
