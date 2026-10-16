// Fixed orthographic camera math shared by the Node side (anchors, tests) and the
// browser page (three.js camera/light placement). World: +Y up, model foot at the origin,
// one world unit = one tile edge. Screen/pixel coordinates: x right, y down.

export const DIRECTIONS = ['s', 'sw', 'w', 'nw', 'n', 'ne', 'e', 'se'];

const RAD = Math.PI / 180;

function normalize(v) {
  const n = Math.hypot(v[0], v[1], v[2]);
  if (!(n > 0)) throw new Error('zero-length vector');
  return [v[0] / n, v[1] / n, v[2] / n];
}

function cross(a, b) {
  return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
}

function dot(a, b) {
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

// A 1x1 ground tile projects to a diamond whose width is sqrt(2) * pixelsPerUnit.
export function pixelsPerUnit(camera) {
  return camera.tileDiamondWidthPx / Math.SQRT2;
}

// Unit vectors of the fixed camera in world space. `toCamera` points from the origin to the
// camera; `right`/`up` match three.js Object3D.lookAt with up = +Y.
export function cameraBasis(camera) {
  const el = camera.elevationDeg * RAD;
  const yaw = camera.yawDeg * RAD;
  const toCamera = [Math.sin(yaw) * Math.cos(el), Math.sin(el), Math.cos(yaw) * Math.cos(el)];
  const forward = toCamera.map((c) => -c);
  const right = normalize(cross(forward, [0, 1, 0]));
  const up = normalize(cross(right, forward));
  return { toCamera, right, up };
}

// Orthographic frustum (camera units) that puts the world origin exactly on the anchor pixel
// corner (anchor.x, anchor.y) of a cell.width x cell.height image.
export function frustum(config) {
  const ppu = pixelsPerUnit(config.camera);
  const { width, height } = config.cell;
  const { x, y } = config.anchor;
  return { left: -x / ppu, right: (width - x) / ppu, top: y / ppu, bottom: -(height - y) / ppu };
}

// Continuous pixel coordinate of a world point.
export function projectToPixel(config, point) {
  const { right, up } = cameraBasis(config.camera);
  const ppu = pixelsPerUnit(config.camera);
  return [config.anchor.x + ppu * dot(point, right), config.anchor.y - ppu * dot(point, up)];
}

// Screen-space direction (x right, y down) of a world vector, without the anchor offset.
export function screenVector(config, v) {
  const { right, up } = cameraBasis(config.camera);
  return [dot(v, right), -dot(v, up)];
}

// Model rotation about +Y (degrees, three.js rotation.y) that turns the model's local +Z
// (its front) toward the named screen direction. 's' faces the viewer; each step is 45
// degrees clockwise on screen.
export function directionYawDeg(camera, direction) {
  const k = DIRECTIONS.indexOf(direction);
  if (k < 0) throw new Error(`unknown direction: ${direction}`);
  let yaw = camera.yawDeg - 45 * k;
  yaw = ((((yaw + 180) % 360) + 360) % 360) - 180;
  return yaw === -180 ? 180 : yaw;
}

export function facingWorld(camera, direction) {
  const yaw = directionYawDeg(camera, direction) * RAD;
  return [Math.sin(yaw), 0, Math.cos(yaw)];
}

// Camera-space light vector (x right, y up, z toward viewer; points toward the light) to world.
export function lightWorld(config) {
  const [x, y, z] = normalize(config.light.directionCamera);
  const { toCamera, right, up } = cameraBasis(config.camera);
  return normalize([0, 1, 2].map((i) => x * right[i] + y * up[i] + z * toCamera[i]));
}
