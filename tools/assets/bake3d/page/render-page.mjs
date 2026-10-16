// Runs inside headless Chromium. Builds the primitive model with three.js, renders a lit colour
// pass and a flat material-id pass from the fixed camera, and returns raw RGBA (top row first).
import * as THREE from 'three';
import { cameraBasis, directionYawDeg, frustum, lightWorld } from '/lib/camera.mjs';

const RAD = Math.PI / 180;
const DISTANCE = 100;

function geometry(part) {
  switch (part.shape) {
    case 'box':
      return new THREE.BoxGeometry(part.size[0], part.size[1], part.size[2]);
    case 'cylinder':
      return new THREE.CylinderGeometry(part.radius, part.radius, part.height, part.segments);
    case 'cone':
      return new THREE.ConeGeometry(part.radius, part.height, part.segments);
    case 'sphere':
      return new THREE.SphereGeometry(part.radius, part.segments, Math.max(2, part.segments >> 1));
    default:
      throw new Error(`unknown shape ${part.shape}`);
  }
}

const ID_VERTEX = 'void main() { gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }';
const ID_FRAGMENT = 'uniform float uId; void main() { gl_FragColor = vec4(uId / 255.0, 0.0, 0.0, 1.0); }';

function readPixels(gl, width, height) {
  const buf = new Uint8Array(width * height * 4);
  gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, buf);
  const out = new Uint8Array(buf.length);
  const stride = width * 4;
  for (let y = 0; y < height; y++) out.set(buf.subarray((height - 1 - y) * stride, (height - y) * stride), y * stride);
  return out;
}

function toBase64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

const canvas = document.createElement('canvas');
const renderer = new THREE.WebGLRenderer({
  canvas,
  antialias: false,
  alpha: true,
  premultipliedAlpha: false,
  preserveDrawingBuffer: true,
});
renderer.setPixelRatio(1);
renderer.setClearColor(0x000000, 0);

window.bake3dEnv = () => {
  const gl = renderer.getContext();
  const info = gl.getExtension('WEBGL_debug_renderer_info');
  return {
    three: THREE.REVISION,
    glVersion: gl.getParameter(gl.VERSION),
    glRenderer: info ? gl.getParameter(info.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER),
  };
};

// job: { config, model, materials: [{name, id, color}], direction, debug?: { lightFollowsModel } }
window.bake3dRender = (job) => {
  const { config, model, materials, direction } = job;
  const { width, height } = config.cell;
  renderer.setSize(width, height, false);

  const f = frustum(config);
  const camera = new THREE.OrthographicCamera(f.left, f.right, f.top, f.bottom, 0.1, DISTANCE * 2);
  const { toCamera } = cameraBasis(config.camera);
  camera.position.set(toCamera[0] * DISTANCE, toCamera[1] * DISTANCE, toCamera[2] * DISTANCE);
  camera.up.set(0, 1, 0);
  camera.lookAt(0, 0, 0);
  camera.updateMatrixWorld();

  const yaw = directionYawDeg(config.camera, direction) * RAD;
  const group = new THREE.Group();
  group.rotation.y = yaw;
  const byName = new Map(materials.map((m) => [m.name, m]));
  const lit = new Map();
  const ids = new Map();
  for (const m of materials) {
    lit.set(m.name, new THREE.MeshLambertMaterial({ color: new THREE.Color(m.color), flatShading: true }));
    ids.set(m.name, new THREE.ShaderMaterial({ uniforms: { uId: { value: m.id } }, vertexShader: ID_VERTEX, fragmentShader: ID_FRAGMENT }));
  }
  const meshes = [];
  for (const part of model.parts) {
    const mesh = new THREE.Mesh(geometry(part), lit.get(part.material));
    mesh.userData.material = byName.get(part.material).name;
    mesh.position.set(part.position[0], part.position[1], part.position[2]);
    const r = part.rotationDeg ?? [0, 0, 0];
    mesh.rotation.set(r[0] * RAD, r[1] * RAD, r[2] * RAD);
    group.add(mesh);
    meshes.push(mesh);
  }

  const scene = new THREE.Scene();
  scene.add(group);
  scene.add(new THREE.AmbientLight(0xffffff, config.light.ambientIntensity));
  const sun = new THREE.DirectionalLight(0xffffff, config.light.directionalIntensity);
  const L = lightWorld(config);
  sun.position.set(L[0] * DISTANCE, L[1] * DISTANCE, L[2] * DISTANCE);
  // Test-only negative control: rotating the light with the model must break the fixed-light check.
  if (job.debug?.lightFollowsModel) sun.position.applyAxisAngle(new THREE.Vector3(0, 1, 0), yaw);
  scene.add(sun);
  scene.add(sun.target);

  const gl = renderer.getContext();
  renderer.render(scene, camera);
  const color = readPixels(gl, width, height);
  for (const mesh of meshes) mesh.material = ids.get(mesh.userData.material);
  renderer.render(scene, camera);
  const matid = readPixels(gl, width, height);

  for (const mesh of meshes) mesh.geometry.dispose();
  for (const m of [...lit.values(), ...ids.values()]) m.dispose();
  return { width, height, color: toBase64(color), matid: toBase64(matid) };
};

window.bake3dReady = true;
