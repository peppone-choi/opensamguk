import { defineConfig } from "@playwright/test";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

for (const fileName of [".env.local", ".env"]) {
  const filePath = resolve(process.cwd(), fileName);
  if (!existsSync(filePath)) {
    continue;
  }
  const content = readFileSync(filePath, "utf8");
  for (const line of content.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#") || !trimmed.includes("=")) {
      continue;
    }
    const eqIndex = trimmed.indexOf("=");
    const key = trimmed.slice(0, eqIndex).trim();
    if (!key || process.env[key]) {
      continue;
    }
    let value = trimmed.slice(eqIndex + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    process.env[key] = value;
  }
}

const baseURL = process.env.PLAYWRIGHT_BASE_URL ?? "http://localhost:3000";
const useWebServer = process.env.PLAYWRIGHT_USE_WEBSERVER === "1";

export default defineConfig({
  testDir: "./e2e",
  timeout: 90_000,
  expect: { timeout: 15_000 },
  retries: 1,
  use: {
    baseURL,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    navigationTimeout: 30_000,
    actionTimeout: 15_000,
  },
  webServer: useWebServer
    ? {
        command: "pnpm dev",
        url: baseURL,
        reuseExistingServer: true,
        timeout: 120_000,
      }
    : undefined,
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
});
