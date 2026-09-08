import { expect, it } from 'vitest';
import { projectBattlefieldTarget } from '@opensamguk/ui';
const projection={cell:0.04690971,k:0.866025,x0:80.540363,y1:45,pad:0.69282};
it('projects source coordinates from persisted terrain projection',()=>{
 const point=projectBattlefieldTarget(30.98367,112.199583,projection,768,669);
 expect(point?.col).toBeCloseTo(369.222083606,7);expect(point?.row).toBeCloseTo(313.563012860,7);
});
it('withholds missing invalid and out of extent projections',()=>{
 expect(projectBattlefieldTarget(31,112,undefined,768,669)).toBeNull();
 expect(projectBattlefieldTarget(NaN,112,projection,768,669)).toBeNull();
 expect(projectBattlefieldTarget(31,112,{...projection,cell:0},768,669)).toBeNull();
 expect(projectBattlefieldTarget(89,112,projection,768,669)).toBeNull();
});
