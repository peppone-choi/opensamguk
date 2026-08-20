import { cookies } from 'next/headers';
import { NextResponse, type NextRequest } from 'next/server';
import { ACCESS_COOKIE } from '@/lib/cookies';
import { BOARD_API_URL, GATEWAY_UPSTREAM_TIMEOUT_MS, isGatewayTimeout } from '@/lib/server-api';

type BoardMethod = 'GET' | 'POST' | 'PATCH' | 'DELETE';
type AccessMode = 'optional' | 'required';
type RouteContext = { params: Promise<{ path: string[] }> };

function isPostId(value: string | undefined): boolean {
  return value !== undefined && /^[1-9]\d*$/.test(value);
}

function isPublicReadPath(path: readonly string[]): boolean {
  return (path.length === 1 && path[0] === 'posts') ||
    (path.length === 2 && path[0] === 'posts' && isPostId(path[1]));
}

function isPostCreatePath(path: readonly string[]): boolean {
  return path.length === 1 && path[0] === 'posts';
}

function isCommentCreatePath(path: readonly string[]): boolean {
  return path.length === 3 && path[0] === 'posts' && isPostId(path[1]) && path[2] === 'comments';
}

function isPostMutationPath(path: readonly string[]): boolean {
  return path.length === 2 && path[0] === 'posts' && isPostId(path[1]);
}

function isCommentDeletePath(path: readonly string[]): boolean {
  return path.length === 4 &&
    path[0] === 'posts' &&
    isPostId(path[1]) &&
    path[2] === 'comments' &&
    isPostId(path[3]);
}

function isPinPath(path: readonly string[]): boolean {
  return path.length === 3 && path[0] === 'posts' && isPostId(path[1]) && path[2] === 'pin';
}

function routeNotFound(): NextResponse {
  return NextResponse.json({ message: '게시판 경로를 찾을 수 없습니다.', status: 404 }, { status: 404 });
}

async function forward(
  request: NextRequest,
  path: readonly string[],
  method: BoardMethod,
  accessMode: AccessMode,
): Promise<NextResponse> {
  const access = (await cookies()).get(ACCESS_COOKIE)?.value;
  if (accessMode === 'required' && !access) {
    return NextResponse.json({ message: '로그인이 필요합니다.', status: 401 }, { status: 401 });
  }

  const headers: Record<string, string> = {};
  const contentType = request.headers.get('content-type');
  if (access) headers.Authorization = `Bearer ${access}`;
  if (method !== 'GET' && method !== 'DELETE' && contentType) headers['Content-Type'] = contentType;

  const init: RequestInit = {
    method,
    headers,
    cache: 'no-store',
    signal: AbortSignal.timeout(GATEWAY_UPSTREAM_TIMEOUT_MS),
  };
  if (method !== 'GET' && method !== 'DELETE') init.body = await request.text();

  try {
    const upstream = await fetch(`${BOARD_API_URL}/board/${path.join('/')}${request.nextUrl.search}`, init);
    const text = await upstream.text();
    const responseHeaders: Record<string, string> = {
      'Content-Type': upstream.headers.get('content-type') ?? 'application/json',
    };
    if (method === 'GET') {
      responseHeaders.Vary = 'Authorization, Cookie';
      responseHeaders['Cache-Control'] = 'private, no-store';
    }
    return new NextResponse(text === '' ? null : text, {
      status: upstream.status,
      headers: responseHeaders,
    });
  } catch (error) {
    if (isGatewayTimeout(error)) {
      return NextResponse.json({ message: '게시판 서버 응답 시간이 초과되었습니다.', status: 504 }, { status: 504 });
    }
    if (error instanceof Error) {
      return NextResponse.json({ message: '게시판 서버에 연결할 수 없습니다.', status: 502 }, { status: 502 });
    }
    throw error;
  }
}

export async function GET(request: NextRequest, context: RouteContext): Promise<NextResponse> {
  const { path } = await context.params;
  return isPublicReadPath(path) ? forward(request, path, 'GET', 'optional') : routeNotFound();
}

export async function POST(request: NextRequest, context: RouteContext): Promise<NextResponse> {
  const { path } = await context.params;
  return isPostCreatePath(path) || isCommentCreatePath(path)
    ? forward(request, path, 'POST', 'required')
    : routeNotFound();
}

export async function PATCH(request: NextRequest, context: RouteContext): Promise<NextResponse> {
  const { path } = await context.params;
  return isPostMutationPath(path) || isPinPath(path)
    ? forward(request, path, 'PATCH', 'required')
    : routeNotFound();
}

export async function DELETE(request: NextRequest, context: RouteContext): Promise<NextResponse> {
  const { path } = await context.params;
  return isPostMutationPath(path) || isCommentDeletePath(path)
    ? forward(request, path, 'DELETE', 'required')
    : routeNotFound();
}
