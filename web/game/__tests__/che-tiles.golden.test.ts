import { describe, expect, it } from 'vitest';
import { buildIsoScene, mapCityToTile, sceneGolden, type IsoSourceSize } from '@opensamguk/ui';
import { CHE_OVERLAYS_FIXTURE, CHE_TILES_FIXTURE } from './fixtures/che-tiles';

const SOURCE: IsoSourceSize = { width: 200, height: 120 };

describe('che-tiles palette render golden', () => {
  it('converts x and y with their own axis scale', () => {
    expect(mapCityToTile({ x: 50, y: 90 }, { cols: 8, rows: 3 }, SOURCE)).toEqual({ col: 2, row: 2.25 });
  });

  it('keeps every terrain and city overlay layer', () => {
    const scene = buildIsoScene(CHE_TILES_FIXTURE, CHE_OVERLAYS_FIXTURE, SOURCE, {
      currentCityId: 11,
      selectedCityId: 22,
    });
    expect(sceneGolden(scene)).toBe(
      'terrain:0110/1231/0110\n' +
      'city:11@1.000,1.000 territory=#ff0000 icon=#8b8172[castle:8,flag,capital,event:6,supply:on,current,name:낙양]\n' +
      'city:22@3.000,2.000 territory=#0000ff icon=#8b8172[castle:6,flag,supply:off,selected,name:허창]',
    );
    expect(scene.roads).toEqual([]);
    expect(scene.cities[0].color).toBe(scene.cities[0].territoryColor);
    expect(scene.cities[0].iconColor).toBe('#8b8172');
    expect(scene.cities[0].layers).not.toContain('aura');
  });

  it('does not invent nation visuals for neutral cities', () => {
    const neutral = { ...CHE_OVERLAYS_FIXTURE[0], nationId: 0, nationName: undefined, nationColor: undefined };
    const scene = buildIsoScene(CHE_TILES_FIXTURE, [neutral], SOURCE, {});
    expect(scene.cities[0].layers).not.toContain('aura');
    expect(scene.cities[0].layers).not.toContain('flag');
  });
});
