import { redirect } from 'next/navigation';

export default async function RegisterAliasPage({
    searchParams,
}: {
    searchParams?: Promise<Record<string, string | string[] | undefined>>;
}) {
    const params = await searchParams;
    const server = typeof params?.server === 'string' ? params.server.trim() : '';
    redirect(server ? `/game/join?server=${encodeURIComponent(server)}` : '/game/join');
}
