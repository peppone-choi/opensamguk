/** Curated han-waterway-network-v1 nodes resolved to han-world-v3 runtime city IDs.
 * The exact join is tested against both ledgers; this is a map display index only.
 */
export const WATERWAY_SITE_ROLES: Readonly<Record<number, readonly ('port' | 'ferry')[]>> = {
  1034: ['ferry'], // 郖津
  1037: ['port'], // 樊口
  1042: ['ferry', 'port'], // 漢津
  397: ['port'], // 江陵
  572: ['port'], // 江州
  934: ['port'], // 建業
  1062: ['ferry'], // 孟津
  1068: ['ferry'], // 蒲坂津
  1072: ['port'], // 濡須口
  1074: ['ferry'], // 陝津
  473: ['port'], // 沙羡
  401: ['port'], // 夷陵
};
