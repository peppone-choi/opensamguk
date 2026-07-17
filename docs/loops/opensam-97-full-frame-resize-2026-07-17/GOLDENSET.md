# OPENSAM-97 full-frame portrait golden set

- Locked: 2026-07-17
- Authority: direct user decision to resize the complete general image instead of locating and cropping a face
- Scope: RTK portrait generation and every game/gateway general-portrait surface

## Acceptance contract

| Dimension | Locked expectation |
|---|---|
| Source geometry | Preserve the complete decoded frame and source aspect ratio |
| Crop logic | No face detector, chosen box, square crop, or content-aware crop |
| Output bounds | Fit inside 156×210 without upscaling |
| Reference case | 633×900 becomes 148×210 with the complete top and bottom retained |
| Live regression | An asymmetric OpenCV fixture retains distinct markers at all four output corners |
| Failure behavior | Record FAIL; never substitute another officer image |
| Browser rendering | Use `object-fit: contain`; no portrait `cover` rule |
| Visual tolerance | Side or top/bottom whitespace is allowed; face, headgear, and torso clipping is not |
| Activation gate | The prior 743-image crop/composite batch is non-adopted and must be regenerated from original cached frames before CDN activation |

## Observable reference

- Element capture: `/Users/apple/.codex/visualizations/2026/07/17/019f7041-cfc5-7082-9dcd-d5fcc685c7bd/portrait-contain-element.png`
- Lobby capture: `/Users/apple/.codex/visualizations/2026/07/17/019f7041-cfc5-7082-9dcd-d5fcc685c7bd/portrait-contain-lobby.png`
- Browser observation: natural 633×900, CSS frame 64×64, painted content 45.013333×64, computed `object-fit: contain`
