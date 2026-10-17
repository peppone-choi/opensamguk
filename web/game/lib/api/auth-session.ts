// /api/auth/me renews sam_access through sam_refresh only when the access token is expired or invalid.
// Public reads such as front-info accept an expired token as anonymous instead of answering 401, so
// fetchGame's 401 retry never fires for them; callers that see an anonymous answer renew here first.

/** renewed: the account is signed in again. signed-out: refresh failed too (401/403). unavailable: 5xx or no connection. */
export type AuthRenewal = 'renewed' | 'signed-out' | 'unavailable';

export async function renewAuthSession(signal?: AbortSignal): Promise<AuthRenewal> {
    let res: Response;
    try {
        res = await fetch('/api/auth/me', { cache: 'no-store', signal });
    } catch (e) {
        // A cancelled renewal belongs to a read nobody waits for; let the caller drop it.
        if (signal?.aborted) throw e;
        return 'unavailable';
    }
    if (res.ok) return 'renewed';
    if (res.status === 401 || res.status === 403) return 'signed-out';
    return 'unavailable';
}
