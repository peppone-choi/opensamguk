# Main Map Initial Focus and Self-Location Design

## Status and scope

This design makes the live game's main map start closer to the player's current city and makes the
player's position distinguishable at a glance. It applies only to the main `GameChrome` map. Other
map consumers, including selection, history, and administrative views, retain their existing camera
defaults unless they explicitly opt in later.

The work covers the initial camera contract, a dedicated current-location presentation layer,
reduced-motion behavior, focused frontend tests, and updates to the overlapping GitHub issues. It
does not add geolocation, reveal hidden information, change fog-of-war or map ownership, or keep
forcing the camera back after the player starts navigating.

## Current behavior

`GameChrome` already passes the live player's city ID through `MapViewer` to `HanMapCanvas`.
`HanMapCanvas` centers that marker during its initial focused view, but stops at the county-label
threshold, approximately CSS scale `5.5`. The current location is then rendered as a small white
circle whose opacity alternates every 500 milliseconds.

The data path is therefore present, but the initial information hierarchy is weak:

- the player starts too far out for nearby terrain and cities to be immediately legible;
- the self marker relies on color and opacity alone;
- the marker has no textual or directional identity;
- the fast blink competes with other map activity and ignores reduced-motion intent;
- changing the shared default would also change unrelated map screens.

## Decision: main-only focus profile

`HanMapCanvas` receives an explicit optional initial-focus profile rather than a new global zoom
default. `MapViewer` forwards it, and `GameChrome` opts the live main map into the profile.

The profile sets the main map's initial target CSS scale to `10`, the existing full-size marker
stage. This is approximately 1.82 times the current `5.5` start and keeps the current city centered.
The shared canvas still clamps the result to its normal minimum/maximum bounds and fits the actual
viewport, so small screens never receive an invalid transform.

The profile is initial-state input only:

1. It applies when the first valid current-city marker becomes available.
2. It does not repeatedly recenter on ordinary rerenders, polling, or marker animation.
3. A real current-city identity change may establish a new initial focus only when the existing
   component lifecycle already represents a newly loaded player/game context. It must not fight
   manual pan or zoom in the same mounted session.
4. When no current city is available, the existing whole-map/default view remains unchanged.

This isolates the visible change to the requested main surface and keeps selection and historical
map camera behavior stable.

## Decision: dedicated self-location layer

The current-city marker is rendered as its own semantic overlay above ordinary city markers. It
uses three redundant channels so that it remains recognizable without relying on a single color:

- a dark under-stroke that separates it from light terrain and borders;
- a white-and-gold double halo around the current city marker;
- a compact upward chevron and `내 위치` chip anchored above the marker.

The overlay follows the current city at every map detail level. In overview mode it may simplify
the ordinary city glyph, but the halo, chevron, and label identity remain. The label is screen-space
sized so zooming does not make it unreadably small or excessively large, and it is positioned to
avoid intercepting pointer input intended for the city or canvas.

The existing selected-city, capital, force, and hover layers keep their meanings. Self-location is
not encoded as selection: selecting another city must not remove the `내 위치` overlay, and the
self overlay must not make a foreign city look owned or selectable.

## Motion and accessibility

The self marker may use a slow, low-amplitude halo pulse when motion is permitted. It does not use
the current 500-millisecond full opacity blink. When `prefers-reduced-motion: reduce` is active, the
overlay is completely static while retaining the under-stroke, double halo, chevron, and text.

The label and shape provide non-color identification. Foreground/background combinations must meet
the project's existing map contrast checks where those checks apply. The overlay is visual context,
not an extra focus target; the existing city interaction remains the keyboard and pointer target,
with an accessible name that can include the current-location state if the canvas exposes one.

## Component contract

The implementation keeps the shared surface narrow:

- `GameChrome` selects the main-map focus profile and continues to provide the live current city.
- `MapViewer` forwards the profile without deriving a second city identity.
- `HanMapCanvas` owns transform calculation and rendering of the current-location layer.
- The default prop value preserves all existing call sites.

The prop expresses intent, not a generic bag of pixel constants. A name such as
`initialFocus="current-city-close"` is preferred over passing unrelated scale and marker-style
numbers through multiple layers. The canvas maps that profile to its internal scale target. If the
existing prop conventions make a typed `initialFocusScale` substantially clearer, it must still be
optional and main-only; visual-layer constants remain internal to the canvas.

## Tests and acceptance criteria

### Camera behavior

- With a valid current city and the main focus profile, the initial transform centers that city and
  targets CSS scale `10` within the existing bounds.
- The same fixture without the profile retains the previous shared initial scale.
- No current city preserves the current default/whole-map behavior.
- Rerendering with unchanged current-city data does not overwrite user pan or zoom.
- Selection and history consumers do not receive the main profile accidentally.

### Self-location rendering

- The current city renders a dedicated overlay with a halo, non-color shape cue, and `내 위치`
  label in both overview and detail paths.
- Selecting or hovering another city leaves the self overlay attached to the current city.
- The overlay does not intercept the city's pointer interaction.
- Reduced-motion mode produces a static marker; normal mode may render only the approved slow
  pulse.
- A missing or invalid current-city marker renders no misleading self overlay and does not throw.

### Integration and regression

- `GameChrome.main-map` proves that the live main map opts into the close focus profile and forwards
  the player's current city.
- `MapViewer.props` proves the profile and city identity reach `HanMapCanvas` unchanged.
- `HanMapCanvas` unit and interaction tests cover initial scale, one-shot focus, overlay identity,
  selection independence, and reduced motion.
- The `web/game` typecheck, focused test files, and the repository's frontend verification command
  pass without weakening existing assertions.

## GitHub issue handling

- **#212** receives the main-only initial camera/LOD change and focused transform evidence.
- **#465** receives the independent current-location layer, reduced-motion behavior, and visual
  regression evidence.
- Neither issue is closed unless its full acceptance criteria outside this task are also complete.
- A new issue is unnecessary unless implementation reveals an independent blocker that cannot be
  represented by either issue.

## Documentation and operations

This is a visible player-facing change. Any user guide that describes the main map or marker legend
must be updated in the same implementation commit; otherwise the task report records why no current
user-facing page is affected. The final metarepo report records the result, commit, screenshots or
render evidence, tests, issue mutations, and remaining risks.

No deployment, merge, shared-branch push, or production reset is part of this task without a
separate explicit action.
