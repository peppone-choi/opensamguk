"""Exploratory, pre-funded grain convoys for engine-external simulations.

All accepted convoys dispatch at boundary 1 from initial stock. Arrival is at
1 + caller-provided travelTurns. ID order allocates contested initial stock.
These are explicit experiment assumptions, not an automatic supply policy.
Optional actual_arrivals comes from explicit path/state revalidation: omitted
uses planned timing; an explicit None retains dispatched cargo in transit.
Routes, permission, speed and capacity must be evaluated by the caller. None
travelTurns means no route. No interception, loss, rerouting or capture is inferred.
Compose stocksAfterDispatch and arrivals with county_economy.settle_county;
include inTransit when checking the combined grain balance. Ledger arrived is
cumulative through that turn; arrivals[county][turn] is the per-turn receipt.
"""


def schedule_convoys(*, stocks: dict[str, int], orders: list[dict], horizon: int, actual_arrivals: dict[str, int | None] | None = None) -> dict:
    def integer(value, minimum, label):
        if type(value) is not int or value < minimum:
            raise ValueError(f'{label} must be an integer >= {minimum}')

    integer(horizon, 1, 'horizon')
    if not isinstance(stocks, dict) or not stocks:
        raise ValueError('stocks must be a nonempty county-to-grain dictionary')
    for county, amount in stocks.items():
        if not isinstance(county, str) or not county:
            raise ValueError('county must be a nonempty string')
        integer(amount, 0, 'stock')
    payloads = {}
    for order in orders:
        if not isinstance(order, dict) or set(order) != {'id', 'source', 'destination', 'grain', 'travelTurns'}:
            raise ValueError('convoy requires id, source, destination, grain, travelTurns')
        if not isinstance(order['id'], str) or not order['id'].strip():
            raise ValueError('convoy id must be a nonempty string')
        if order['source'] not in stocks or order['destination'] not in stocks or order['source'] == order['destination']:
            raise ValueError('convoy requires distinct known endpoints')
        integer(order['grain'], 1, 'grain')
        if order['travelTurns'] is not None:
            integer(order['travelTurns'], 1, 'travelTurns')
        payload = (order['source'], order['destination'], order['grain'], order['travelTurns'])
        if order['id'] in payloads and payloads[order['id']] != payload:
            raise ValueError(f'conflicting payload for convoy {order["id"]}')
        payloads[order['id']] = payload

    if actual_arrivals is not None:
        if set(actual_arrivals) != set(payloads):
            raise ValueError('actual arrival schedule must cover exactly the convoy IDs')
        for oid, at in actual_arrivals.items():
            planned = payloads[oid][3]
            if at is not None:
                integer(at, 2, 'actual arrival')
                if planned is None or at < 1 + planned:
                    raise ValueError('actual arrival cannot precede planned travel or invent a route')

    remaining = dict(sorted(stocks.items()))
    arrivals = {county: {} for county in remaining}
    decisions, dispatched, outcomes = [], [], {}
    for order in sorted(orders, key=lambda row: row['id']):
        oid, src, dst, amount, turns = (order[k] for k in ('id', 'source', 'destination', 'grain', 'travelTurns'))
        decision = {'id': oid}
        if oid in outcomes:
            decision.update(status='DUPLICATE', originalStatus=outcomes[oid])
        else:
            if turns is None:
                status = 'NO_ROUTE'
            elif amount > remaining[src]:
                status = 'REJECTED_STOCK'
            else:
                status = 'DISPATCHED'
                remaining[src] -= amount
                arrival_turn = 1 + turns if actual_arrivals is None else actual_arrivals[oid]
                dispatched.append((arrival_turn, amount))
                decision['arrivalTurn'] = arrival_turn
                if arrival_turn is not None and arrival_turn <= horizon:
                    arrivals[dst][arrival_turn] = arrivals[dst].get(arrival_turn, 0) + amount
            outcomes[oid] = status
            decision['status'] = status
        decisions.append(decision)
    ledger = []
    for turn in range(1, horizon + 1):
        arrived = sum(amount for at, amount in dispatched if at is not None and at <= turn)
        in_transit = sum(amount for at, amount in dispatched if at is None or at > turn)
        assert sum(remaining.values()) + arrived + in_transit == sum(stocks.values())
        ledger.append({'turn': turn, 'arrived': arrived, 'inTransit': in_transit})
    return {'status': 'EXPLORATORY', 'dispatchTurn': 1, 'stocksAfterDispatch': remaining,
            'arrivals': arrivals, 'decisions': decisions, 'ledger': ledger}
