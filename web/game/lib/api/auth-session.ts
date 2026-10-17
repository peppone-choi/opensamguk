// /api/auth/me renews sam_access through sam_refresh only when the access token is expired or invalid.
// Public reads such as front-info accept an expired token as anonymous instead of answering 401, so
// fetchGame's 401 retry never fires for them; callers that see an anonymous answer renew here first.
export async function refreshAuthSession(signal?: AbortSignal): Promise<boolean> {
    try {
        const res = await fetch('/api/auth/me', { cache: 'no-store', signal });
        return res.ok;
    } catch {
        return false;
    }
}
