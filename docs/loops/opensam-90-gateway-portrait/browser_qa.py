import argparse
import base64
import json
import re
from pathlib import Path

from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
from playwright.sync_api import sync_playwright


BASE_URL = "http://127.0.0.1:3000"
RUN_ID = "opensam-90-20260717"
ARTIFACT_DIR = Path(__file__).resolve().parent
PIXEL = base64.b64decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==")
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
    route.fulfill(
        status=200,
        content_type="application/json",
        body=json.dumps(body, ensure_ascii=False),
    )


def new_context(browser):
    context = browser.new_context()
    context.route(SENTRY_URL, lambda route: route.abort())
    context.add_cookies(
        [
            {
                "name": "sam_access",
                "value": "opensam-90-browser-fixture",
                "url": BASE_URL,
                "httpOnly": True,
                "sameSite": "Lax",
            }
        ]
    )
    return context


def run_live_probe(browser, route_name):
    context = new_context(browser)
    page = context.new_page()
    responses = []
    page.on(
        "response",
        lambda response: responses.append({"url": response.url, "status": response.status})
        if "/api/auth/me" in response.url
        else None,
    )
    try:
        page.goto(f"{BASE_URL}/{route_name}", wait_until="domcontentloaded", timeout=20_000)
        page.wait_for_timeout(2_500)
        screenshot = ARTIFACT_DIR / f"{RUN_ID}-live-{route_name}.png"
        page.screenshot(path=str(screenshot), full_page=True)
        print(
            "LIVE_OBSERVED "
            + json.dumps(
                {
                    "route": route_name,
                    "finalUrl": page.url,
                    "authResponses": responses,
                    "screenshot": screenshot.name,
                },
                ensure_ascii=False,
            ),
            flush=True,
        )
    except PlaywrightTimeoutError as error:
        print(
            "LIVE_PENDING "
            + json.dumps(
                {
                    "route": route_name,
                    "finalUrl": page.url,
                    "authResponses": responses,
                    "reason": str(error).splitlines()[0],
                },
                ensure_ascii=False,
            ),
            flush=True,
        )
    finally:
        context.close()


def run_mocked(browser, route_name, scenario, picture, image_server):
    context = new_context(browser)
    responses = []
    user = {
        "id": 1,
        "username": "browser-tester",
        "email": None,
        "nickname": None,
        "role": "USER",
        "picture": picture,
        "imageServer": image_server,
    }

    context.route("**/api/auth/me", lambda route: fulfill_json(route, {"user": user}))
    context.route(
        "**/api/servers",
        lambda route: fulfill_json(route, {"servers": [{"id": "alpha", "name": "알파"}]}),
    )
    context.route(
        "**/api/server-basic-info/**",
        lambda route: fulfill_json(
            route,
            {
                "game": GAME,
                "me": {
                    "name": "브라우저테스터",
                    "picture": picture,
                    "imageServer": image_server,
                },
            },
        ),
    )

    def fulfill_image(route):
        if route.request.url.endswith("/missing.png"):
            route.fulfill(status=404, content_type="image/gif", body=b"")
            return
        route.fulfill(status=200, content_type="image/gif", body=PIXEL)

    context.route("**/icons/**", fulfill_image)
    page = context.new_page()
    page.on(
        "response",
        lambda response: responses.append({"url": response.url, "status": response.status})
        if "/icons/" in response.url or response.url.startswith(f"{BASE_URL}/api/")
        else None,
    )
    page.goto(f"{BASE_URL}/{route_name}", wait_until="domcontentloaded", timeout=60_000)
    selector = 'img[alt="현재 전콘"]' if route_name == "account" else 'img[alt="브라우저테스터"]'
    portrait = page.locator(selector)
    portrait.wait_for(state="visible", timeout=30_000)
    expected_suffix = "/icons/1001.jpg" if scenario == "normal" else "/icons/default.jpg"
    page.wait_for_function(
        "({ selector, suffix }) => {"
        "const image = document.querySelector(selector);"
        "return image?.getAttribute('src')?.endsWith(suffix)"
        "&& image.complete && image.naturalWidth > 0;}",
        arg={"selector": selector, "suffix": expected_suffix},
        timeout=30_000,
    )
    dom_src = portrait.get_attribute("src")
    if not dom_src or not dom_src.endswith(expected_suffix):
        raise AssertionError(f"{route_name}/{scenario}: unexpected portrait src {dom_src}")
    icon_responses = [response for response in responses if "/icons/" in response["url"]]
    if scenario == "404":
        if not any(response["url"].endswith("/missing.png") and response["status"] == 404 for response in icon_responses):
            raise AssertionError(f"{route_name}/{scenario}: missing 404 request evidence")
        if not any(response["url"].endswith("/default.jpg") and response["status"] == 200 for response in icon_responses):
            raise AssertionError(f"{route_name}/{scenario}: missing default fallback evidence")
    elif not any(response["url"].endswith(expected_suffix) and response["status"] == 200 for response in icon_responses):
        raise AssertionError(f"{route_name}/{scenario}: missing 200 image evidence")
    screenshot = ARTIFACT_DIR / f"{RUN_ID}-{route_name}-{scenario}.png"
    page.screenshot(path=str(screenshot), full_page=True)
    print(
        "OBSERVED "
        + json.dumps(
            {
                "route": route_name,
                "scenario": scenario,
                "domSrc": dom_src,
                "responses": responses,
                "screenshot": screenshot.name,
            },
            ensure_ascii=False,
        ),
        flush=True,
    )
    context.close()


parser = argparse.ArgumentParser()
parser.add_argument("--route", choices=("account", "lobby", "all"), default="all")
parser.add_argument("--skip-live", action="store_true")
parser.add_argument("--live-only", action="store_true")
args = parser.parse_args()

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(
        headless=True,
        executable_path="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    )
    scenarios = [
        ("normal", "1001", 0),
        ("missing", None, 0),
        ("whitespace", "   ", 0),
        ("image-server-1", "uploaded.png", 1),
        ("404", "missing.png", 0),
    ]
    routes = ("account", "lobby") if args.route == "all" else (args.route,)
    if not args.live_only:
        for route_name in routes:
            for scenario, picture, image_server in scenarios:
                run_mocked(browser, route_name, scenario, picture, image_server)
    if not args.skip_live:
        for route_name in routes:
            run_live_probe(browser, route_name)
    browser.close()
