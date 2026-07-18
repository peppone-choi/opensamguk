import json
import re
from pathlib import Path

from playwright.sync_api import sync_playwright


BASE_URL = "http://127.0.0.1:3000"
ARTIFACT_DIR = Path(__file__).resolve().parent
SENTRY_URL = re.compile(r"^https://[^/]*\.sentry\.io/")
GAME = {
    "isUnited": 0,
    "npcMode": 0,
    "year": 200,
    "month": 1,
    "scenario": "테스트 시나리오",
    "maxUserCnt": 100,
    "turnTerm": 10,
    "userCnt": 1,
    "npcCnt": 0,
    "nationCnt": 1,
    "fictionMode": "사실",
    "joinMode": None,
    "blockGeneralCreate": 0,
    "defaultStatTotal": 275,
    "otherTextInfo": "",
    "status": "OPEN",
}


def fulfill_json(route, body):
    route.fulfill(status=200, content_type="application/json", body=json.dumps(body, ensure_ascii=False))


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(
        headless=True,
        executable_path="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    )
    context = browser.new_context()
    context.route(SENTRY_URL, lambda route: route.abort())
    events = {"console": [], "pageErrors": [], "requests": [], "responses": [], "routeHits": []}

    def route_json(name, body):
        def handler(route):
            events["routeHits"].append({"name": name, "url": route.request.url})
            fulfill_json(route, body)

        return handler

    user = {
        "id": 1,
        "username": "browser-tester",
        "email": None,
        "nickname": None,
        "role": "USER",
        "picture": "1001",
        "imageServer": 0,
    }
    context.route("**/api/auth/me", route_json("auth", {"user": user}))
    context.route(
        "**/api/servers",
        route_json("servers", {"servers": [{"id": "alpha", "name": "알파"}]}),
    )
    context.route(
        "**/api/server-basic-info/**",
        route_json(
            "basic-info",
            {"game": GAME, "me": {"name": "브라우저테스터", "picture": "1001", "imageServer": 0}},
        ),
    )
    page = context.new_page()
    page.on("console", lambda message: events["console"].append({"type": message.type, "text": message.text}))
    page.on("pageerror", lambda error: events["pageErrors"].append(str(error)))
    page.on(
        "request",
        lambda request: events["requests"].append({"method": request.method, "url": request.url})
        if request.url.startswith(f"{BASE_URL}/api/")
        else None,
    )
    page.on(
        "response",
        lambda response: events["responses"].append({"status": response.status, "url": response.url})
        if response.url.startswith(f"{BASE_URL}/api/")
        else None,
    )
    page.goto(f"{BASE_URL}/lobby", wait_until="domcontentloaded", timeout=60_000)
    try:
        page.locator('img[alt="브라우저테스터"]').wait_for(state="attached", timeout=8_000)
    except Exception as error:
        events["selectorError"] = str(error).splitlines()[0]
    events["finalUrl"] = page.url
    events["selectors"] = {
        "expectedPortrait": page.locator('img[alt="브라우저테스터"]').count(),
        "allImages": page.locator("img").count(),
        "serverRows": page.locator("tbody tr").count(),
        "spinner": page.locator(".spinner").count(),
    }
    events["bodyText"] = page.locator("body").inner_text()[:4000]
    html_path = ARTIFACT_DIR / "opensam-90-lobby-diagnostic.html"
    html_path.write_text(page.content(), encoding="utf-8")
    events["htmlArtifact"] = html_path.name
    print("LOBBY_DIAGNOSTIC " + json.dumps(events, ensure_ascii=False), flush=True)
    context.close()
    browser.close()
