"""Engine-external exploratory county grain/population settlement.

All quantities and schedules are caller assumptions, not approved HWIHA rules.
People are integer persons (no implicit household conversion). Grain is an integer
abstract unit. equipGrain is the TOTAL one-time grain cost of an order.
Transport arrivals precede recruitment (ID order), then soldiers eat; monthly
net tax arrives afterwards. The ration-before-tax ordering follows campaign
spec section 5.2 (supply step 1, monthly income step 4). Recruitment placement
and quantities remain experiment assumptions. Monthly salary/upkeep is absent.
Shortfalls never infer death, capture, morale or desertion.
No production, movement, storage capacity, civilian consumption or population
recovery is inferred. Callers schedule net tax receipts and actual deliveries.
"""


def settle_county(*, initial_grain: int, people: int, deliveries: dict[int, int],
                  tax_grain: dict[int, int], recruitment_orders: list[dict],
                  ration_per_soldier: int, horizon: int) -> dict:
    """Return a per-turn ledger and per-delivery recruitment decisions.

    Re-delivering an ID with the same troops/equipment replays its first outcome
    without spending again, even if that outcome was rejection. A different
    payload for an ID rejects the entire input before settlement with ValueError.
    A genuinely new attempt needs a new ID; delivery turn is not order identity.
    """
    def integer(value, minimum, name):
        if type(value) is not int or value < minimum:
            raise ValueError(f'{name} must be an integer >= {minimum}')

    integer(initial_grain, 0, 'initial_grain')
    integer(people, 0, 'people')
    integer(ration_per_soldier, 1, 'ration_per_soldier')
    integer(horizon, 1, 'horizon')
    for schedule in (deliveries, tax_grain):
        if not isinstance(schedule, dict):
            raise ValueError('receipt schedule must be a turn-to-quantity dictionary')
        for turn, amount in schedule.items():
            integer(turn, 1, 'receipt turn')
            integer(amount, 0, 'receipt amount')
            if turn > horizon:
                raise ValueError('receipt turn exceeds horizon')
    payloads = {}
    scheduled = {}
    for order in recruitment_orders:
        if not isinstance(order, dict) or set(order) != {'id', 'turn', 'troops', 'equipGrain'}:
            raise ValueError('order requires exactly id, turn, troops, equipGrain')
        order_id = order['id']
        if not isinstance(order_id, str) or not order_id.strip():
            raise ValueError('order id must be a nonempty string')
        integer(order['turn'], 1, 'order turn')
        integer(order['troops'], 1, 'order troops')
        integer(order['equipGrain'], 0, 'order equipment grain')
        if order['turn'] > horizon:
            raise ValueError('order turn exceeds horizon')
        payload = (order['troops'], order['equipGrain'])
        if order_id in payloads and payloads[order_id] != payload:
            raise ValueError(f'conflicting payload for order {order_id}')
        payloads[order_id] = payload
        scheduled.setdefault(order['turn'], []).append(dict(order))

    grain, civilians, troops = initial_grain, people, 0
    ledger, decisions, outcomes = [], [], {}
    for turn in range(1, horizon + 1):
        opening = grain
        delivered, tax = deliveries.get(turn, 0), tax_grain.get(turn, 0)
        grain += delivered
        equipment, recruited = 0, 0
        for order in sorted(scheduled.get(turn, []), key=lambda x: x['id']):
            order_id = order['id']
            decision = {'id': order_id, 'turn': turn}
            if order_id in outcomes:
                decision.update(status='DUPLICATE', originalStatus=outcomes[order_id])
            else:
                if order['troops'] > civilians:
                    status = 'REJECTED_PEOPLE'
                elif order['equipGrain'] > grain:
                    status = 'REJECTED_GRAIN'
                else:
                    status = 'APPLIED'
                    civilians -= order['troops']
                    troops += order['troops']
                    recruited += order['troops']
                    grain -= order['equipGrain']
                    equipment += order['equipGrain']
                outcomes[order_id] = status
                decision['status'] = status
            decisions.append(decision)
        demand = troops * ration_per_soldier
        consumed = min(grain, demand)
        grain -= consumed
        grain += tax
        assert opening + delivered + tax == equipment + consumed + grain
        assert civilians + troops == people and min(civilians, troops, grain) >= 0
        ledger.append({'turn': turn, 'openingGrain': opening, 'delivered': delivered,
                       'taxGrain': tax, 'equipmentConsumed': equipment,
                       'recruited': recruited, 'people': civilians, 'troops': troops,
                       'rationDemand': demand, 'rationConsumed': consumed,
                       'unmetRation': demand - consumed, 'closingGrain': grain})
    return {'status': 'EXPLORATORY',
            'assumptions': 'integer persons and abstract grain; transport arrivals -> recruitment by ID -> military ration -> monthly net tax; no death/capture inference',
            'ledger': ledger, 'recruitDecisions': decisions}
