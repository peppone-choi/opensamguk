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
  cityPixelVisualBox,
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
export { buildJuLayer, juUrlForTerrain, mapLod, verifiedJuByParent, JU_NAMES,
  type JuIndexResponse, type JuLayer, type MapLod } from './iso/juLod';
export { ARCHITECTURE_BY_JU, architectureForJu, type RegionalArchitecture } from './iso/regionalArchitecture';
export { cityBadgeAssetKey, cityBadgeLabel, citySnapshotBadges, drawCityBadgeLayer, type IsoCityBadge } from './iso/cityBadgeLayer';
export { cityBadgesById, WORK_BADGE_LABELS, type WorkBadgeCode } from './worldCityBadges';
export {
  WORLD_MAP_CODE, worldTerrainUrl, worldProvincesUrl, useWorldMap,
  buildWorldCities, buildMarkerPositions, buildCommanderies, buildProvinceCenters, buildLegend,
  type WorldMapPreview, type WorldMapOptions, type WorldMapState,
  type HwihaCommanderyCell, type HwihaLegendEntry,
} from './useWorldMap';
export { isUprisingNation } from './iso/marker';
export { WATERWAY_SITE_ROLES } from './iso/waterwaySiteRoles';
