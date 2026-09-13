# Mobile map controls

Production Chromium 390×844/DPR2 showed the full-map legend overlapping the
city-name and touch-mode buttons. Those controls also cover the canvas. Both
use absolute positioning; increasing touch targets to44px made the collision
more visible. Drag and pinch already work in the deployed map.

At the existing1023px responsive breakpoint, place MapViewer settings after
the canvas in normal flow, with wrapping44px controls. Place the full-map
legend after the viewer in normal flow. The iso wrapper uses a320–560px height bounded by55dvh so settings cannot be clipped by the old aspect-ratio box. Retain the zoom control inside the
canvas and leave desktop layout unchanged. This is a CSS layout correction;
there is no new gesture or navigation behavior.

Verify on the actual map DOM at390px and768px with the candidate CSS injected:
legend/settings rectangles must not intersect the canvas or each other;
settings have44px touch height; drag/pinch still operate. Compare1280px desktop
computed positioning. Independently review, run relevant frontend checks,
create PR, wait CI, merge, web-only promote with the recovered API/engine pins,
and verify the deployed layout. A reset is not required.

Candidate CSS verification completed:390/768px rectangles do not overlap, settings44px, drag/pinch pass;1280px settings remain absolute. Independent review passed. Real-device testing remains outstanding.
