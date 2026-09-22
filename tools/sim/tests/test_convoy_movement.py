import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from march_tempo import Graph
from convoy_movement import move_convoy
from convoy_schedule import schedule_convoys

class ConvoyMovementTest(unittest.TestCase):
    def graph(self):
        g=Graph.__new__(Graph);g.center={0:(0,0),1:(1,0),2:(2,0)}
        g.adj={0:{1},1:{0,2},2:{1}};g.rough_share={i:0 for i in g.center};g.km=lambda a,b:15
        return g

    def movement(self,blocked=(),horizon=6):
        allowed={t:({0,1} if t in blocked else {0,1,2}) for t in range(1,horizon+1)}
        return move_convoy(graph=self.graph(),path=[0,1,2],speed=20,rough_factor=1,allowed_by_turn=allowed,horizon=horizon)

    def test_route_and_progress_carry_across_province_boundary(self):
        g=self.graph();self.assertEqual(g.shortest_path(0,2,1),(30,30,2,[0,1,2]))
        self.assertEqual(g.shortest(0,2,1),(30,30,2))
        out=self.movement();self.assertEqual(out['arrivalTurn'],3)
        self.assertEqual(out['ledger'][1]['edgeProgressKm'],5)

    def test_blocking_destination_preserves_edge_progress_then_resumes(self):
        out=self.movement(blocked={3,4})
        self.assertEqual(out['arrivalTurn'],5)
        self.assertEqual(out['ledger'][2]['edgeProgressKm'],5)
        self.assertEqual(out['ledger'][3]['edgeProgressKm'],5)
        self.assertEqual(out['ledger'][4]['movedCostKm'],10)

    def test_blocked_forever_cargo_remains_reserved_in_transit(self):
        move=self.movement(blocked={3,4,5,6})
        self.assertIsNone(move['arrivalTurn'])
        out=self.funded(move['arrivalTurn'])
        self.assertEqual(out['stocksAfterDispatch']['a'],0)
        self.assertEqual(out['arrivals']['b'],{})
        self.assertEqual(out['ledger'][-1]['inTransit'],10)

    def funded(self,at):
        return schedule_convoys(stocks={'a':10,'b':0},
            orders=[dict(id='x',source='a',destination='b',grain=10,travelTurns=2)],
            horizon=6,actual_arrivals={'x':at})

    def test_delayed_receipt_happens_once_and_conserves_grain(self):
        out=self.funded(self.movement(blocked={3,4})['arrivalTurn'])
        self.assertEqual(out['arrivals']['b'],{5:10})
        for row in out['ledger']:
            self.assertEqual(sum(out['stocksAfterDispatch'].values())+row['arrived']+row['inTransit'],10)

    def test_invalid_or_early_arrival_is_rejected(self):
        for actual in ({},{'wrong':3},{'x':2}):
            with self.assertRaises(ValueError):
                schedule_convoys(stocks={'a':10,'b':0},orders=[dict(id='x',source='a',destination='b',grain=10,travelTurns=2)],horizon=6,actual_arrivals=actual)

    def test_initial_rejection_is_not_dispatched_unarrived_cargo(self):
        movement=self.movement(blocked={1})
        out=schedule_convoys(stocks={'a':10,'b':0},orders=[dict(id='x',source='a',destination='b',grain=10,
            travelTurns=2 if movement['dispatched'] else None)],horizon=6,actual_arrivals={'x':movement['arrivalTurn']})
        self.assertEqual(out['decisions'][0]['status'],'NO_ROUTE')
        self.assertEqual(out['stocksAfterDispatch']['a'],10)
        self.assertEqual(out['ledger'][-1]['inTransit'],0)

    def test_initial_blockade_prevents_dispatch_and_missing_snapshot_rejected(self):
        self.assertFalse(self.movement(blocked={1})['dispatched'])
        with self.assertRaises(ValueError):
            move_convoy(graph=self.graph(),path=[0,2],speed=20,rough_factor=1,allowed_by_turn={1:{0,1,2}},horizon=1)
        with self.assertRaises(ValueError):
            move_convoy(graph=self.graph(),path=[0,1,2],speed=20,rough_factor=1,allowed_by_turn={1:{0,1,2}},horizon=2)

if __name__=='__main__':unittest.main()
