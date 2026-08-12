import { cookies } from 'next/headers';
import { NextResponse, type NextRequest } from 'next/server';
import { ACCESS_COOKIE } from '@/lib/cookies';
import { GATEWAY_API_URL } from '@/lib/server-api';

type BoardMethod = 'GET' | 'POST' | 'PATCH' | 'DELETE';
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

function isPostDeletePath(path: readonly string[]): boolean {
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
  authenticated: boolean,
): Promise<NextResponse> {
  const access = authenticated ? (await cookies()).get(ACCESS_COOKIE)?.value : undefined;
  if (authenticated && !access) {
    return NextResponse.json({ message: '로그인이 필요합니다.', status: 401 }, { status: 401 });
  }

  const headers: Record<string, string> = {};
  const contentType = request.headers.get('content-type');
  if (access) headers.Authorization = `Bearer ${access}`;
  if (method !== 'GET' && method !== 'DELETE' && contentType) headers['Content-Type'] = contentType;

  const init: RequestInit = { method, headers, cache: 'no-store' };
  if (method !== 'GET' && method !== 'DELETE') init.body = await request.text();

  try {
    const upstream = await fetch(`${GATEWAY_API_URL}/board/${path.join('/')}${request.nextUrl.search}`, init);
    const text = await upstream.text();
    return new NextResponse(text === '' ? null : text, {
      status: upstream.status,
      headers: { 'Content-Type': upstream.headers.get('content-type') ?? 'application/json' },
    });
  } catch (error) {
    if (error instanceof Error) {
      return NextResponse.json({ message: '게이트웨이에 연결할 수 없습니다.', status: 502 }, { status: 502 });
    }
    throw error;
  }
}

export async function GET(request: NextRequest, context: RouteContext): Promise<NextResponse> {
  const { path } = await context.params;
  return isPublicReadPath(path) ? forward(request, path, 'GET', false) : routeNotFound();
}

export async function POST(request: NextRequest, context: RouteContext): Promise<NextResponse> {
  const { path } = await context.params;
  return isPostCreatePath(path) || isCommentCreatePath(path)
    ? forward(request, path, 'POST', true)
    : routeNotFound();
}

export async function PATCH(request: NextRequest, context: RouteContext): Promise<NextResponse> {
  const { path } = await context.params;
  return isPinPath(path) ? forward(request, path, 'PATCH', true) : routeNotFound();
}

export async function DELETE(request: NextRequest, context: RouteContext): Promise<NextResponse> {
  const { path } = await context.params;
  return isPostDeletePath(path) || isCommentDeletePath(path)
    ? forward(request, path, 'DELETE', true)
    : routeNotFound();
}
