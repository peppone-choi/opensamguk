// WebGL2 도우미: 컨텍스트 · 셰이더 · 정수 텍스처. 지도 렌더러 전용이라 필요한 형식만 둔다.

export class GlError extends Error {}

export function createGl(canvas: HTMLCanvasElement | OffscreenCanvas): WebGL2RenderingContext | null {
  const gl = canvas.getContext('webgl2', {
    alpha: false,
    antialias: false,
    depth: false,
    stencil: false,
    premultipliedAlpha: true,
    preserveDrawingBuffer: false,
    powerPreference: 'high-performance',
  }) as WebGL2RenderingContext | null;
  return gl;
}

function compileShader(gl: WebGL2RenderingContext, type: number, source: string): WebGLShader {
  const shader = gl.createShader(type);
  if (!shader) throw new GlError('createShader failed');
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS) && !gl.isContextLost()) {
    const log = gl.getShaderInfoLog(shader) ?? '';
    gl.deleteShader(shader);
    throw new GlError(`shader compile failed: ${log}`);
  }
  return shader;
}

export function compileProgram(gl: WebGL2RenderingContext, vertexSource: string, fragmentSource: string): WebGLProgram {
  const vertex = compileShader(gl, gl.VERTEX_SHADER, vertexSource);
  const fragment = compileShader(gl, gl.FRAGMENT_SHADER, fragmentSource);
  const program = gl.createProgram();
  if (!program) throw new GlError('createProgram failed');
  gl.attachShader(program, vertex);
  gl.attachShader(program, fragment);
  gl.linkProgram(program);
  gl.deleteShader(vertex);
  gl.deleteShader(fragment);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS) && !gl.isContextLost()) {
    const log = gl.getProgramInfoLog(program) ?? '';
    gl.deleteProgram(program);
    throw new GlError(`program link failed: ${log}`);
  }
  return program;
}

export function uniformLocations<T extends string>(
  gl: WebGL2RenderingContext,
  program: WebGLProgram,
  names: readonly T[],
): Record<T, WebGLUniformLocation | null> {
  const out = {} as Record<T, WebGLUniformLocation | null>;
  for (const name of names) out[name] = gl.getUniformLocation(program, name);
  return out;
}

export type IntegerFormat = 'R8UI' | 'R16UI' | 'RG16UI' | 'RGBA16UI' | 'RGBA8UI';

interface FormatSpec { internal: number; format: number; type: number }

export function integerFormat(gl: WebGL2RenderingContext, name: IntegerFormat): FormatSpec {
  switch (name) {
    case 'R8UI': return { internal: gl.R8UI, format: gl.RED_INTEGER, type: gl.UNSIGNED_BYTE };
    case 'R16UI': return { internal: gl.R16UI, format: gl.RED_INTEGER, type: gl.UNSIGNED_SHORT };
    case 'RG16UI': return { internal: gl.RG16UI, format: gl.RG_INTEGER, type: gl.UNSIGNED_SHORT };
    case 'RGBA16UI': return { internal: gl.RGBA16UI, format: gl.RGBA_INTEGER, type: gl.UNSIGNED_SHORT };
    case 'RGBA8UI': return { internal: gl.RGBA8UI, format: gl.RGBA_INTEGER, type: gl.UNSIGNED_BYTE };
  }
}

function nearestClamp(gl: WebGL2RenderingContext, target: number): void {
  gl.texParameteri(target, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
  gl.texParameteri(target, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
  gl.texParameteri(target, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(target, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
}

/** 정수 텍스처(표본 추출 없이 texelFetch로만 읽는다). */
export function createIntegerTexture(
  gl: WebGL2RenderingContext,
  format: IntegerFormat,
  width: number,
  height: number,
  data: ArrayBufferView | null,
): WebGLTexture {
  const spec = integerFormat(gl, format);
  const texture = gl.createTexture();
  if (!texture) throw new GlError('createTexture failed');
  gl.bindTexture(gl.TEXTURE_2D, texture);
  nearestClamp(gl, gl.TEXTURE_2D);
  gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
  gl.texImage2D(gl.TEXTURE_2D, 0, spec.internal, width, height, 0, spec.format, spec.type, data);
  return texture;
}

export function updateIntegerTexture(
  gl: WebGL2RenderingContext,
  texture: WebGLTexture,
  format: IntegerFormat,
  x: number,
  y: number,
  width: number,
  height: number,
  data: ArrayBufferView,
): void {
  const spec = integerFormat(gl, format);
  gl.bindTexture(gl.TEXTURE_2D, texture);
  gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
  gl.texSubImage2D(gl.TEXTURE_2D, 0, x, y, width, height, spec.format, spec.type, data);
}

export function createIntegerArrayTexture(
  gl: WebGL2RenderingContext,
  format: IntegerFormat,
  width: number,
  height: number,
  layers: number,
): WebGLTexture {
  const spec = integerFormat(gl, format);
  const texture = gl.createTexture();
  if (!texture) throw new GlError('createTexture failed');
  gl.bindTexture(gl.TEXTURE_2D_ARRAY, texture);
  nearestClamp(gl, gl.TEXTURE_2D_ARRAY);
  gl.texStorage3D(gl.TEXTURE_2D_ARRAY, 1, spec.internal, width, height, layers);
  return texture;
}

/** RGBA8 색 텍스처. 픽셀아트라 최근접만 쓴다. 색공간 변환 · 미리 곱하기를 끈다. */
export function createColorTexture(
  gl: WebGL2RenderingContext,
  source: TexImageSource | { width: number; height: number; data: Uint8Array },
): WebGLTexture {
  const texture = gl.createTexture();
  if (!texture) throw new GlError('createTexture failed');
  gl.bindTexture(gl.TEXTURE_2D, texture);
  nearestClamp(gl, gl.TEXTURE_2D);
  gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
  gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL, gl.NONE);
  gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
  if ('data' in source && !(source instanceof ImageData)) {
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, source.width, source.height, 0, gl.RGBA, gl.UNSIGNED_BYTE, source.data);
  } else {
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, source as TexImageSource);
  }
  return texture;
}

export function bindTexture(gl: WebGL2RenderingContext, unit: number, target: number, texture: WebGLTexture | null): void {
  gl.activeTexture(gl.TEXTURE0 + unit);
  gl.bindTexture(target, texture);
}
