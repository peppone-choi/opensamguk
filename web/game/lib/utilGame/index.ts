// utilGame — legacy hwe/ts/utilGame/* 표시 포매터 시드(Tier-0). web/game FE 페이지 공용 소비.
// 보류(별도 배선 필요): formatCityName/postFilterNationCommandGen(GameConstStore), getNewMsgToast(Vue 전용).
export { calcInjury } from './calcInjury';
export type { InjuryGeneral } from './calcInjury';
export { formatRefreshScore } from './formatRefreshScore';
export { formatDefenceTrain } from './formatDefenceTrain';
export { formatDexLevel, DexLevelMap } from './formatDexLevel';
export type { DexInfo } from './formatDexLevel';
export { formatGeneralTypeCall } from './formatGeneralTypeCall';
export type { GeneralTypeConst } from './formatGeneralTypeCall';
export { formatHonor } from './formatHonor';
export { formatInjury } from './formatInjury';
export { formatLog } from './formatLog';
export {
  formatOfficerLevelText,
  OfficerLevelMapDefault,
  OfficerLevelMapByNationLevel,
} from './formatOfficerLevelText';
export { formatTournamentType, formatTournamentStep } from './formatTournament';
export type { TournamentStepType } from './formatTournament';
export { formatVoteColor } from './formatVoteColor';
export { getNPCColor } from './getNPCColor';
export { isValidObjKey } from './isValidObjKey';
export { nextExpLevelRemain } from './nextExpLevelRemain';
export { isTechLimited, convTechLevel, getMaxRelativeTechLevel, TECH_LEVEL_STEP } from './techLevel';
export { calcTournamentTerm } from './tournament';
