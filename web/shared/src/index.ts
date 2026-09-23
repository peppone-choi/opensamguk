export { Brand, type BrandProps, type BrandSize } from './Brand';
export * from './strategicMap';
export { Button, type ButtonProps, type ButtonSize, type ButtonVariant } from './Button';
export { Card, type CardProps } from './Card';
export { ConfirmDialog, type ConfirmDialogProps } from './ConfirmDialog';
export { Modal, type ModalProps } from './Modal';
export { Table, type TableProps } from './Table';
export { Chip, type ChipProps, type ChipTone } from './Chip';
export { EMPTY_ILLUSTRATION_FILE, EMPTY_ILLUSTRATION_PATH, EmptyState, type EmptyIllustration, type EmptyStateProps } from './EmptyState';
export { Feed, FeedItem, type FeedItemProps, type FeedProps } from './Feed';
export { Flag, type FlagProps } from './Flag';
export { Gauge, type GaugeProps, type GaugeTone } from './Gauge';
export { Icon, type IconProps, type IconSize } from './Icon';
export { ICON_NAMES, ICON_SPRITE_PATH, type IconName } from './icons';
export { KV, type KVItem, type KVProps } from './KV';
export { LogText, type LogTextProps } from './LogText';
export { logPlainText, parseLogTokens, type LogSegment, type LogTone } from './logTokens';
export { NavItem, type NavItemProps } from './NavItem';
export { Divider, Inset, Panel, type InsetProps, type PanelProps } from './Panel';
export { PillTabs, type PillTab, type PillTabsProps } from './PillTabs';
export {
  PORTRAIT_SIZES,
  Portrait,
  PortraitResolverProvider,
  PortraitStack,
  portraitVariantForSize,
  usePortraitResolver,
  type PortraitProps,
  type PortraitRingReason,
  type PortraitSize,
} from './Portrait';
export {
  DEFAULT_IMAGE_CDN_BASE,
  DEFAULT_PORTRAIT_PATH,
  createPortraitResolver,
  defaultPortraitResolver,
  type PortraitResolver,
  type PortraitVariant,
} from './portraitResolver';
export { ReasonTooltip, type ReasonTooltipProps } from './ReasonTooltip';
export { SectionHeader, type SectionHeaderProps, type SectionTone } from './SectionHeader';
export { Slot, type SlotProps, type SlotState } from './Slot';
export { StatRow, type StatRowProps } from './StatRow';
export { Tile, type TileProps, type TileState } from './Tile';

export {
  CITY_MARKER_SPECS,
  HanMapCanvas,
  projectBattlefieldTarget,
  type BattlefieldMapTarget,
  type BattlefieldMapProjection,
  cityFallbackHitBox,
  cityLabelMetrics,
  cityMarkerDrawBox,
  cityMarkerAssetScale,
  cityMarkerHitBox,
  cityMarkerRadius,
  cityMarkerZoomStep,
  parseTerrainEtagHash,
  buildIsoScene,
  completeJurisdictionOverlays,
  expandOwner,
  flagClothPoints,
  initialView,
  initialFocusedView,
  labelledRegions,
  labelZoomFor,
  mapCityToTile,
  provinceLayerRuntimeCities,
  overviewCityVisualBox,
  provinceAtScreenPoint,
  sceneGolden,
  screenBoxInsideProvince,
  screenBoxInsideVisualClearance,
  seatLabel,
  terrainColorFor,
  tierZoom,
  TIER2_LABEL_ZOOM,
  TIER2_MARKER_ZOOM,
  type AdjEdge,
  type HanMapCanvasProps,
  type CommanderyVisibility,
  type InitialFocusProfile,
  type HanTiles,
  type IsoCityOverlay,
  type CityMarkerZoom,
  type IsoCountyHover,
  type IsoActivation,
  type IsoHoverPoint,
  type IsoScene,
  type IsoSceneCity,
  type IsoSceneOptions,
  type IsoSourceSize,
  type Jun,
} from './HanMapCanvas';
export {
  MAX_CSS_SCALE,
  MAX_SCALE,
  cellToScreen,
  centeredView,
  clampView,
  effectiveDpr,
  fitScale,
  junSpanCells,
  maxScaleForDpr,
  pinchGesture,
  scaleForSpan,
  screenToCell,
  viewAt,
  visibleCells,
  zoomAt,
  type GridSize,
  type IsoView,
  type PointerPosition,
} from './isoMap';
export {
  bindProvinceOwnership,
  bindAdministrativeOwnership,
  bindCompleteProvinceOwnership,
  buildCountyAdministrativeIndex,
  buildProvinceAdministrativeIndex,
  buildProvinceVisualAnchors,
  composeProvincePixels,
  decodeProvincePixels,
  loadProvinceIdentityMap,
  formatProvinceTooltip,
  type ProvinceColor,
  type ProvinceEdge,
  type ProvinceIdentityMap,
  type ProvinceOwnershipBinding,
  type ProvincePlacement,
  type ProvinceVisualAnchor,
  type CountyAdministrativeIndex,
  type AdministrativeLayer,
  type AdministrativeOwnershipData,
  type CommanderyRecordDto,
  type ParentRegionRecordDto,
  type JurisdictionRecordDto,
  type ProvinceRecordDto,
  resolveProvincePlacement,
} from './provinceMap';
export {
  formatCompactMapTooltipMeta,
  isOwnedNationVisual,
  type CompactMapTooltipMetaInput,
} from './nationVisual';
export {
  TERRAIN,
  TERRAIN_ASSET_NAME,
  RASTER_GROUP,
  MAX_LEVEL,
  TILE_SCREEN_WIDTH,
  TILE_SCREEN_HEIGHT,
  STEP_SCREEN_PIXELS,
  SEAT_ONLY_TILE_PIXELS,
  isWater,
  pickTileAtScreen,
  downsampleTerrain,
  levelsFromImageData,
  fillSeaEnclosedGaps,
  terrainFromElevation,
  relaxCornerLattice,
  buildCornerLattice,
  buildTileHeights,
  buildIsoTileGrid,
  landUnderSeats,
  type SeatCells,
  tileToScreen,
  expandRunLength,
  downsampleOwner,
  stampSeatOwners,
  sourceCellToTile,
  type TerrainCode,
  type TerrainTiles,
  type TileHeights,
  type IsoTileGrid,
} from './isoTileGrid';

// ── 지도 격자와 2D 자산 유틸리티 ──────────────────────────────────────────
export {
  useIsoTileGrid,
  buildProvinceSeatCells,
  LEVEL_PNG_URL,
  ELEVATION_MANIFEST_URL,
  type IsoMapData,
  type IsoCity,
  type ProvinceSeatCells,
  type ElevationManifest,
} from './iso/useIsoTileGrid';
export {
  placeGameCities,
  isExternalPlace,
  firstPickableCity,
  fitCityFootprints,
  fitFootprintsInTile,
  placeBattlefields,
  gameXyToSourceCell,
  type GameCityInput,
  type PlacedCity,
  type PlaceGameCitiesOptions,
  type IsoBattlefieldMarker,
} from './iso/placeGameCities';
export {
  normaliseNationColor,
  bannerColor,
  isAchromaticNationColor,
  indexTint,
  ownerTint,
  mixToward,
  luminancePreserving,
  rgbCss,
  hslToRgb,
  parseHex,
  type Rgb,
  type TintMode,
} from './iso/tint';
export {
  isHanCounty,
  cityDisplayName,
  type CityNameInput,
} from './iso/cityName';
export {
  cityFootprintBlock,
  resolveCityFootprints,
  type FootprintCity,
  cityFootprintSpan,
  type CellBlock,
} from './iso/cityFootprint';
export { drawCorpsOverlay, type MapCorpsOverlay } from './iso/corpsOverlay';
export {
  countyGlossForJurisdiction,
  splitCountyGloss,
  PlaceNameWithGloss,
  type PlaceNameWithGlossProps,
} from './iso/countyNameGloss';
export {
  externalPlaceLevel,
  type ExternalPlaceInput,
} from './iso/externalPlaceTier';

export {
  cityIconLevel,
  type CityIconInput,
} from './iso/cityIconLevel';
export {
  CITY_SEED_RESEATS,
  applyCitySeedReseats,
  type CitySeedReseat,
} from './iso/citySeedReseat';
export {
  drawSeaRoute,
  type IsoSeaRoute,
  drawBattlefieldMark,
  drawCityFlag,
  drawCityName,
  drawCityRing,
  nationGlyph,
  cityLabelBox,
  dropOverlappingLabels,
  markerScale,
  type CityFlagOptions,
  type LabelBox,
} from './iso/marker';
export {
  SPRITE_ROOF_TOP_PX,
  SPRITE_GROUND_CENTER_Y,
  spriteRoofLift,
  cityFlagBase,
} from './iso/buildingRoof';
export {
  SPRITE_SILHOUETTE_PX,
  cellFootprintInTiles,
  spriteFootprintFit,
  type TileFootprint,
  type SpriteFit,
} from './iso/buildingFit';
export { IsoMap2D, type IsoMap2DProps } from './iso/IsoMap2D';
export { ARCHITECTURE_BY_JU, architectureForJu, type RegionalArchitecture } from './iso/regionalArchitecture';
export { cityBadgeAssetKey, cityBadgeLabel, citySnapshotBadges, drawCityBadgeLayer, type IsoCityBadge } from './iso/cityBadgeLayer';
export { isUprisingNation } from './iso/marker';
export { WATERWAY_SITE_ROLES } from './iso/waterwaySiteRoles';
