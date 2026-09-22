"""Land-convoy passage under an explicit ownership and deployed-army snapshot.

ArmyPresence represents actual deployed unit-card formations, never ordinary
visiting generals. The caller supplies effective war relations (not declarations
still under grace) and explicitly identifies neutral armed forces. No inference
from general nationId or crew is made. Missing ownership is rejected.

This filters the exploratory dry-land Graph only: it does not reproduce capital
connectivity, supplyAllowed edges, water control, capacity or city-only protection
in StrategicSupplyNetwork. It is not runtime supply reachability or capture.
"""
from dataclasses import dataclass


@dataclass(frozen=True)
class ArmyPresence:
    province: int
    nation_id: int
    neutral_force: bool = False


def passage_snapshot(*, provinces: set[int], nation_id: int, owners: dict[int, int],
                     armies: list[ArmyPresence], effective_war_nations: set[int]) -> dict:
    def integer(value, minimum):
        return type(value) is int and value >= minimum

    if not provinces or any(not integer(p, 0) for p in provinces):
        raise ValueError('known province indices required')
    if not integer(nation_id, 1):
        raise ValueError('supply nation must be positive')
    if set(owners) != provinces or any(not integer(n, 0) for n in owners.values()):
        raise ValueError('complete ownership snapshot over exactly the known provinces required')
    if any(not integer(n, 1) or n == nation_id for n in effective_war_nations):
        raise ValueError('effective war relations require other positive nations')
    military = set()
    for army in armies:
        if not isinstance(army, ArmyPresence) or not integer(army.province, 0) or army.province not in provinces:
            raise ValueError('deployed army must reference a known province')
        if (not integer(army.nation_id, 0) or type(army.neutral_force) is not bool
                or (army.nation_id == 0 and not army.neutral_force)
                or (army.nation_id == nation_id and army.neutral_force)):
            raise ValueError('army allegiance must explicitly distinguish neutral armed forces')
        if army.neutral_force or army.nation_id in effective_war_nations:
            military.add(army.province)
    blocked = {}
    for province in sorted(provinces):
        reasons = []
        if owners[province] != nation_id:
            reasons.append('FOREIGN_TERRITORY')
        if province in military:
            reasons.append('HOSTILE_OR_NEUTRAL_ARMED_OCCUPANCY')
        if reasons:
            blocked[province] = reasons
    return {'status': 'EXPLORATORY_LAND_CONVOY_PASSAGE',
            'allowedProvinces': sorted(provinces - blocked.keys()),
            'blockedReasons': {str(p): reasons for p, reasons in blocked.items()},
            'runtimeSupplyImplemented': False}
