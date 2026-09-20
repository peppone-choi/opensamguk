"""Advance a fixed land convoy path with per-boundary passage revalidation.

Dispatch at boundary1, movement begins at2. Blocked cargo waits in transit; no
loss, teleport, reroute or saved-up unused movement. Edge progress is retained.
The caller must use these arrivals with a funded convoy reservation.
"""
import math


def move_convoy(*, graph, path: list[int], speed: float, rough_factor: float,
                allowed_by_turn: dict[int, set[int]], horizon: int) -> dict:
    if type(horizon) is not int or horizon < 1:
        raise ValueError('positive horizon required')
    if not math.isfinite(speed) or speed <= 0 or not math.isfinite(rough_factor) or rough_factor < 1:
        raise ValueError('finite positive speed and rough factor >=1 required')
    if len(path)<2 or len(set(path))!=len(path) or any(p not in graph.center for p in path):
        raise ValueError('known simple path with distinct endpoints required')
    if any(v not in graph.adj[u] for u,v in zip(path,path[1:])):
        raise ValueError('path contains a non-adjacent edge')
    if set(allowed_by_turn)!=set(range(1,horizon+1)):
        raise ValueError('complete per-boundary passage snapshots required')
    if any(not set(allowed)<=set(graph.center) for allowed in allowed_by_turn.values()):
        raise ValueError('passage snapshot contains unknown provinces')
    costs=[graph.km(u,v)*(1+(rough_factor-1)*(graph.rough_share[u]+graph.rough_share[v])/2)
           for u,v in zip(path,path[1:])]
    if any(not math.isfinite(c) or c<=0 for c in costs):
        raise ValueError('positive finite edge costs required')
    if any(p not in allowed_by_turn[1] for p in path):
        return {'dispatched':False,'arrivalTurn':None,'ledger':[]}
    index=0; progress=0.0; arrival=None; ledger=[]
    for turn in range(1,horizon+1):
        moved=0.0; state='IN_TRANSIT'
        if arrival is not None:
            state='ARRIVED'
        elif turn>1:
            budget=speed
            while index<len(costs) and budget>0:
                if path[index] not in allowed_by_turn[turn] or path[index+1] not in allowed_by_turn[turn]:
                    state='BLOCKED';break
                step=min(budget,costs[index]-progress)
                progress+=step;budget-=step;moved+=step
                if progress>=costs[index]:
                    index+=1;progress=0.0
            if index==len(costs):
                arrival=turn;state='ARRIVED'
        ledger.append({'turn':turn,'state':state,'completedEdges':index,'edgeProgressKm':progress,'movedCostKm':moved})
    return {'dispatched':True,'arrivalTurn':arrival,'ledger':ledger}
