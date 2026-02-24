#!/usr/bin/env node

/**
 * OAuth gate preflight checker (frontend-focused).
 * - Verifies required env vars
 * - Optionally probes API endpoints with --probe
 */

const requiredEnv = [
  "NEXT_PUBLIC_API_URL",
  "NEXT_PUBLIC_KAKAO_CLIENT_ID",
];

const optionalEnv = [
  "NEXT_PUBLIC_WS_URL",
  "NEXT_PUBLIC_SERVER_MAP_URL",
];

const missing = requiredEnv.filter((k) => !process.env[k] || !String(process.env[k]).trim());

console.log("[oauth-gate] Required env checks");
for (const key of requiredEnv) {
  const ok = !!(process.env[key] && String(process.env[key]).trim());
  console.log(` - ${key}: ${ok ? "OK" : "MISSING"}`);
}

console.log("[oauth-gate] Optional env checks");
for (const key of optionalEnv) {
  const ok = !!(process.env[key] && String(process.env[key]).trim());
  console.log(` - ${key}: ${ok ? "set" : "unset"}`);
}

if (missing.length > 0) {
  console.error(`\nMissing required env var(s): ${missing.join(", ")}`);
  process.exit(1);
}

if (!process.argv.includes("--probe")) {
  console.log("\nEnv looks good. Skipping endpoint probe (pass --probe to enable).");
  process.exit(0);
}

const apiBase = process.env.NEXT_PUBLIC_API_URL.replace(/\/$/, "");
const checks = [
  { name: "scenarios", url: `${apiBase}/scenarios`, method: "GET" },
  { name: "world list", url: `${apiBase}/worlds`, method: "GET" },
  {
    name: "oauth login endpoint (shape check)",
    url: `${apiBase}/auth/oauth/login`,
    method: "POST",
    body: { provider: "kakao", code: "health-check", redirectUri: "http://localhost:3000/auth/kakao/callback" },
  },
];

let failed = false;
for (const check of checks) {
  try {
    const res = await fetch(check.url, {
      method: check.method,
      headers: { "content-type": "application/json" },
      body: check.body ? JSON.stringify(check.body) : undefined,
    });

    // oauth/login often returns 4xx on fake code; that still proves route is reachable.
    const ok =
      check.name.includes("oauth login endpoint")
        ? res.status !== 404 && res.status !== 405
        : res.ok;

    console.log(` - ${check.name}: ${ok ? "OK" : `FAIL (${res.status})`}`);
    if (!ok) failed = true;
  } catch (err) {
    console.log(` - ${check.name}: FAIL (${err instanceof Error ? err.message : String(err)})`);
    failed = true;
  }
}

if (failed) process.exit(2);
console.log("\nOAuth gate probe checks passed.");
