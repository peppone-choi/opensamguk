# Production security baseline - 2026-07-14

## Verdict

No active miner process, persistence hook, vulnerable production dependency, or invalid TLS certificate was observed in the pre-deploy inspection. This is a bounded runtime check, not a forensic guarantee that the host was never compromised.

## Evidence

- Public `https://sam.peppone.dev/health`: HTTP 200, certificate verification result 0.
- Cloudflare edge certificate: `CN=peppone.dev`, Google Trust Services WE1, valid through 2026-09-17.
- Origin certificate on `3.37.232.176:443`: `CN=sam.peppone.dev`, Let's Encrypt YE1, valid through 2026-09-09.
- Both pre-deploy live Next.js containers report `next@15.5.19`; the repository is upgraded to the current Next 15-line patch `15.5.20`. The prior `15.5.19` graphs reported zero `pnpm audit` vulnerabilities, while the final re-audit endpoint returned HTTP 410 and therefore produced no new audit verdict.
- Process, user/root cron, recent executable temp-file, and recent web-container log scans found no `xmrig`, `stratum`, `kdevtmpfsi`, `kinsing`, or similar indicator.
- Host ports 3101 and 8101 are Docker-published for the game server, but direct external probes time out at the network boundary. Public HTTP traffic remains on 80/443 through nginx/Cloudflare.
- The tracked weak admin password example and local Compose fallback were removed. The production Compose contract still requires `ADMIN_PASSWORD`; the real value stays only in the server environment.

## Deployment recheck

After promotion, repeat the certificate, image tag, Next.js version, process, health, daemon, and direct-port probes. Any unexpected process, new persistence entry, or dependency advisory is a no-go.
