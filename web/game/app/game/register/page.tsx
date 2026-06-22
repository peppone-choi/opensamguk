import { redirect } from 'next/navigation';

export default async function RegisterAliasPage({
    searchParams,
}: {
    searchParams?: Promise<Record<string, string | string[] | undefined>>;
}) {
    const params = await searchParams;
    const server = typeof params?.server === 'string' ? params.server.trim() : '';
    const safeServer = /^[A-Za-z0-9_-]+$/.test(server) ? server : '';
    redirect(safeServer ? `/game/${encodeURIComponent(safeServer)}/join` : '/game/join');
}
