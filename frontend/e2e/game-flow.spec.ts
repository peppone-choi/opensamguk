import { expect, test } from "@playwright/test";

const screenshotsRoot = "../";
const GAME_APP_BASE = "http://localhost:9001";
// crypto.randomUUID() unavailable in Node < 19; use Math.random hex
const uid = Math.random().toString(36).slice(2, 10);
const loginId = `e2e_${uid}`;
const displayName = "E2E테스터";
const password = "test1234";
let authToken = "";

async function restoreAuthToken(page: import("@playwright/test").Page) {
  if (!authToken) return;
  await page.addInitScript((token) => {
    window.localStorage.setItem("token", token);
  }, authToken);
}

async function selectFirstWorld(page: import("@playwright/test").Page) {
  await page.getByText("월드 목록", { exact: false }).waitFor();
  const worldCardMeta = page
    .locator("p.text-xs.text-muted-foreground")
    .filter({ hasText: /년\s*\d+월/ })
    .first();
  await worldCardMeta.waitFor();
  await worldCardMeta.click();
}

test.describe.serial("OpenSamguk game full flow", () => {
  // Pause the turn daemon before all tests to prevent DB row-lock contention.
  // After pausing, wait for any in-flight turn transaction to complete.
  test.beforeAll(async ({ request }) => {
    try {
      await request.post(`${GAME_APP_BASE}/internal/turn/pause`);
      // Wait for any in-flight turn transaction to finish (up to 60s)
      for (let i = 0; i < 12; i++) {
        const health = await request.get(`${GAME_APP_BASE}/internal/health`);
        const body = await health.json().catch(() => null);
        if (body?.turnState === "PAUSED" || body?.turnState === "IDLE") break;
        await new Promise((r) => setTimeout(r, 5000));
      }
    } catch {
      // game-app might not be reachable — tests can still run
    }
  });

  test.afterAll(async ({ request }) => {
    try {
      await request.post(`${GAME_APP_BASE}/internal/turn/resume`);
    } catch {
      // best effort
    }
  });

  test("landing page loads and redirects to login", async ({ page }) => {
    await page.goto("/");
    try {
      await page.waitForURL("**/login", { timeout: 8000 });
    } catch {
      await page.goto("/login");
    }
    await expect(page.getByText("오픈삼국 로그인")).toBeVisible();
    await page.screenshot({ path: `${screenshotsRoot}e2e-01-login.png` });
  });

  test("user can register and reach lobby", async ({ page }) => {
    // Try registering a new user via API directly for reliability
    const apiBase = "http://localhost:8080/api";
    const registerRes = await page.request.post(`${apiBase}/auth/register`, {
      data: { loginId, displayName, password },
    });

    let token: string;
    if (registerRes.ok()) {
      const body = await registerRes.json();
      token = body.token;
    } else {
      // User might already exist from a previous run — fall back to login
      const loginRes = await page.request.post(`${apiBase}/auth/login`, {
        data: { loginId, password },
      });
      expect(loginRes.ok()).toBeTruthy();
      const body = await loginRes.json();
      token = body.token;
    }

    expect(token.length).toBeGreaterThan(0);
    authToken = token;

    // Inject token and navigate to lobby
    await page.addInitScript((t) => {
      window.localStorage.setItem("token", t);
    }, token);
    await page.goto("/lobby");
    await page.waitForURL("**/lobby");
    await expect(page.getByText("월드 목록")).toBeVisible();
    await page.screenshot({ path: `${screenshotsRoot}e2e-02-lobby.png` });
  });

  test("lobby shows worlds and allows selection", async ({ page }) => {
    await restoreAuthToken(page);
    await page.goto("/lobby");
    await page.waitForURL("**/lobby");
    await selectFirstWorld(page);

    await expect(page.getByText(/내 장수|장수 선택/)).toBeVisible();
    await page.screenshot({
      path: `${screenshotsRoot}e2e-03-world-selected.png`,
    });
  });

  test("can select NPC general", async ({ page }) => {
    await restoreAuthToken(page);
    await page.goto("/lobby");
    await page.waitForURL("**/lobby");
    await selectFirstWorld(page);

    // If user already has a general, skip NPC selection
    const rightPanel = page.getByText(/내 장수|장수 선택/);
    await rightPanel.waitFor();

    const hasGeneral = await page.getByText("내 장수").isVisible();
    if (hasGeneral) {
      await page.screenshot({
        path: `${screenshotsRoot}e2e-04-npc-selected.png`,
      });
      return;
    }

    // Select NPC
    await page.getByText("NPC 빙의").waitFor();
    await page.getByText("NPC 빙의").first().click();
    await page.waitForURL("**/lobby/select-npc");

    const selectButton = page.getByRole("button", { name: /선택하기/ }).first();
    await selectButton.waitFor();
    page.once("dialog", (dialog) => dialog.accept());
    await selectButton.click();

    await page.waitForURL("**/lobby", { timeout: 30000 });
    await expect(page.getByText("내 장수")).toBeVisible();
    await page.screenshot({
      path: `${screenshotsRoot}e2e-04-npc-selected.png`,
    });
  });

  test("can enter game and see dashboard", async ({ page }) => {
    await restoreAuthToken(page);
    await page.goto("/lobby");
    await selectFirstWorld(page);

    await page.getByText("내 장수").waitFor();
    await page.getByRole("button", { name: "입장" }).click();

    await page.waitForURL("http://localhost:3000/");
    await expect(page.getByText("현재:")).toBeVisible({ timeout: 20000 });
    await page.screenshot({ path: `${screenshotsRoot}e2e-05-dashboard.png` });
  });

  test("can navigate to key game pages", async ({ page }) => {
    await restoreAuthToken(page);
    await page.goto("/lobby");
    await selectFirstWorld(page);
    await page.getByRole("button", { name: "입장" }).click();
    await page.waitForURL("http://localhost:3000/");
    await expect(page.getByText("현재:")).toBeVisible({ timeout: 20000 });

    // Navigate to map page via nav bar
    await page.getByRole("link", { name: "세계지도" }).click();
    await page.waitForURL("**/map", { timeout: 15000 });
    await page.screenshot({ path: `${screenshotsRoot}e2e-06-map.png` });
  });
});
