import { expect, test } from "@playwright/test";

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api";

function base64UrlEncode(input: string): string {
  return Buffer.from(input)
    .toString("base64")
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

function createTestJwt(payload: Record<string, unknown>): string {
  const header = { alg: "HS256", typ: "JWT" };
  return [
    base64UrlEncode(JSON.stringify(header)),
    base64UrlEncode(JSON.stringify(payload)),
    "sig",
  ].join(".");
}

test.describe("OAuth gate: login -> lobby -> world entry", () => {
  test("OAuth callback success lands in lobby and allows world entry", async ({ page }) => {
    const token = createTestJwt({
      userId: 999,
      sub: "oauth_e2e",
      displayName: "OAuthE2E",
      role: "USER",
      exp: Math.floor(Date.now() / 1000) + 3600,
    });

    await page.route(`${API_BASE}/auth/oauth/login`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ token }),
      });
    });

    await page.route(`${API_BASE}/worlds`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([
          {
            id: 1,
            name: "E2E 월드",
            scenarioCode: "std_hero",
            year: 200,
            month: 1,
            realtimeMode: false,
            locked: false,
            config: { generalCntLimit: 200, joinMode: "normal" },
            meta: { playerCount: 10, npcCount: 5 },
          },
        ]),
      });
    });

    await page.route(`${API_BASE}/scenarios`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([
          { code: "std_hero", title: "기본 시나리오", description: "e2e" },
        ]),
      });
    });

    await page.route(`${API_BASE}/worlds/1/generals/me`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          id: 101,
          name: "테스터장수",
          level: 10,
          nationId: 1,
          leadership: 70,
          strength: 70,
          intellect: 70,
          politics: 70,
          charm: 70,
          gold: 1000,
          rice: 1000,
          cityId: 1,
        }),
      });
    });

    // Game page data endpoints can fail in this stub test; keep it deterministic.
    await page.route(`${API_BASE}/worlds/1/front-info**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          global: { year: 200, month: 1 },
          world: {},
        }),
      });
    });

    await page.goto("/auth/kakao/callback?code=fake-code");

    await page.waitForURL("**/lobby", { timeout: 15_000 });
    await expect(page.getByRole("heading", { name: "서버 목록" })).toBeVisible();

    // Select world then enter game
    await page.getByText("E2E 월드").first().click();

    await expect(page.getByRole("button", { name: "입장" })).toBeVisible();
    await page.getByRole("button", { name: "입장" }).click();

    await page.waitForURL("**/", { timeout: 15_000 });

    const storedToken = await page.evaluate(() => localStorage.getItem("token"));
    expect(storedToken).toBeTruthy();
  });

  test("OAuth callback failure redirects back to login", async ({ page }) => {
    await page.route(`${API_BASE}/auth/oauth/login`, async (route) => {
      await route.fulfill({
        status: 401,
        contentType: "application/json",
        body: JSON.stringify({ message: "invalid oauth code" }),
      });
    });

    await page.goto("/auth/kakao/callback?code=bad-code");
    await page.waitForURL("**/login", { timeout: 15_000 });
    await expect(page.getByRole("button", { name: "카카오 로그인" })).toBeVisible();
  });
});
