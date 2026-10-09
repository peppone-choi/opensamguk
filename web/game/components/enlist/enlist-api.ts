// Compatibility bridge: the E04 client now lives in lib/api/enlist-slots (screen → hook → api).
export {
  EnlistHttpError, EnlistScopeChanged, readEnlistOptions, readEnlistSlots, sendEnlist,
  type EnlistOption, type EnlistSlotsRead, type EnlistCalendar,
} from '@/lib/api/enlist-slots';
