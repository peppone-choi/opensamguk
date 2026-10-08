#!/usr/bin/env python3
"""CI-only real nginx/Docker DNS regression; HTTP fixtures are not QA authentication."""
import ipaddress
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
import uuid

STACK = runpy.run_path(str(Path(__file__).with_name("local-stack.py")))
IMAGE = "nginx:1.27-alpine"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise RuntimeError("Unexpected redirect from the routing fixture")


class QaRoutingDockerTest(unittest.TestCase):
    def docker(self, *args):
        try:
            return subprocess.run(["docker", *args], check=True, capture_output=True,
                                  text=True, timeout=120).stdout.strip()
        except subprocess.CalledProcessError as exc:
            exc.add_note(f"Isolated routing fixture Docker error: {exc.stderr[-4000:]}")
            raise

    def setUp(self):
        if os.environ.get("GITHUB_ACTIONS") != "true":
            raise RuntimeError("Docker routing regression must run in CI, never against local QA")
        self.project = "qa-routing-" + uuid.uuid4().hex[:12]
        self.network = self.project + "-net"
        tmp = tempfile.TemporaryDirectory(prefix=self.project + "-")
        self.addCleanup(tmp.cleanup)
        self.directory = Path(tmp.name)
        self.directory.chmod(0o700)
        self.docker("pull", IMAGE)
        self.network_id = self.docker("network", "create", self.network)
        self.addCleanup(self.remove_network)
        model = json.loads(self.docker("network", "inspect", self.network_id))[0]
        subnet = ipaddress.ip_network(model["IPAM"]["Config"][0]["Subnet"])
        # Let Docker allocate an unused pool, then declare that exact pool explicitly:
        # fixed replacement IPs require a user-configured subnet, not an automatic pool.
        self.docker("network", "rm", self.network_id)
        self.network_id = None
        self.network_id = self.docker("network", "create", "--subnet", str(subnet), self.network)
        self.address = lambda offset: str(subnet.network_address + offset)
        (self.directory / "qa.env").write_text("")
        (self.directory / "qa.env").chmod(0o600)
        STACK["private_json"](self.directory / "stack.json",
                              {"project": self.project, "worldId": 160})
        self.config = STACK["nginx"]()
        (self.directory / "nginx.conf").write_text(self.config)
        (self.directory / "web.conf").write_text(
            "events {}\nhttp { server { listen 3000; listen 3001; return 200 'web-fixture'; } }\n")
        self.model = {
            "services": {
                "gateway-api": self.service("gateway.conf", 2, 8080),
                "web-fixture": self.service("web.conf", 6, 3000),
                "nginx": self.service("nginx.conf", 7, 80),
            },
            "networks": {"routing": {"external": True, "name": self.network}},
        }
        self.model["services"]["web-fixture"]["networks"]["routing"]["aliases"] = [
            "web-gateway", "web-game"]
        self.model["services"]["nginx"]["ports"] = ["127.0.0.1::80"]
        self.model["services"]["nginx"]["depends_on"] = {
            name: {"condition": "service_healthy"} for name in ("gateway-api", "web-fixture")}
        for name, service in self.model["services"].items():
            service["container_name"] = self.project + "-" + name
        self.write_gateway("original", 2)
        self.gateway_offset = 2
        # Only the test project/root change; custody, owner guard, compose and refresh run unmocked.
        scope = patch.dict(STACK["refresh_routes"].__globals__,
                           PROJECT=self.project, ROOT=self.directory)
        scope.start()
        self.addCleanup(scope.stop)
        self.addCleanup(self.remove_containers)
        self.compose("up", "-d", "--no-build", "--wait", "--wait-timeout", "60")
        port = self.docker("port", self.project + "-nginx", "80/tcp")
        self.base = "http://" + port
        self.wait_phase("original")

    def service(self, config, offset, port):
        return {
            "image": IMAGE,
            "entrypoint": ["nginx", "-g", "daemon off;"],
            "volumes": [f"{self.directory / config}:/etc/nginx/nginx.conf:ro"],
            "networks": {"routing": {"ipv4_address": self.address(offset)}},
            "healthcheck": {
                "test": ["CMD", "wget", "-q", "-O", "/dev/null", f"http://127.0.0.1:{port}/health"],
                "interval": "1s", "timeout": "1s", "retries": 30,
            },
        }

    def write_gateway(self, phase, offset):
        # Echo method/path and a deployment marker, with no accounts, tokens or database.
        (self.directory / "gateway.conf").write_text(
            "events {}\nhttp { server { listen 8080; default_type application/json; "
            "return 200 '{\"phase\":\"" + phase
            + "\",\"method\":\"$request_method\",\"uri\":\"$request_uri\"}'; } }\n")
        self.model["services"]["gateway-api"]["networks"]["routing"]["ipv4_address"] = self.address(offset)
        (self.directory / "docker-compose.qa.yml").write_text(json.dumps(self.model))

    def compose(self, *args):
        try:
            return STACK["compose"](self.directory, *args, capture_output=True, timeout=120)
        except subprocess.CalledProcessError as exc:
            # Only this generated, secret-free fixture uses this diagnostic wrapper.
            exc.add_note(f"Isolated routing fixture compose error: {exc.stderr.decode()[-4000:]}")
            raise

    def remove_containers(self):
        self.compose("down", "--timeout", "5")

    def remove_network(self):
        if self.network_id is not None:
            self.docker("network", "rm", self.network_id)

    def replace_gateway(self, phase, offset):
        retired_offset = self.gateway_offset
        self.write_gateway(phase, offset)
        self.compose("up", "-d", "--no-build", "--no-deps", "--force-recreate",
                     "--wait", "--wait-timeout", "60", "gateway-api")
        # Reproduce QA's ECONNREFUSED at the old IP. An unallocated address instead
        # waits for ARP/connect timeout (nginx defaults to 60s), not the observed 502.
        # This real container answers on 3000/3001 but has no listener on 8080.
        retired_name = f"retired-ip-{retired_offset}"
        retired = self.service("web.conf", retired_offset, 3000)
        retired["container_name"] = self.project + "-" + retired_name
        self.model["services"][retired_name] = retired
        (self.directory / "docker-compose.qa.yml").write_text(json.dumps(self.model))
        self.compose("up", "-d", "--no-build", "--no-deps", "--wait",
                     "--wait-timeout", "60", retired_name)
        old_network = json.loads(self.docker("inspect", retired["container_name"]))[0][
            "NetworkSettings"]["Networks"][self.network]
        self.assertEqual(old_network["IPAddress"], self.address(retired_offset))
        network = json.loads(self.docker("inspect", self.project + "-gateway-api"))[0][
            "NetworkSettings"]["Networks"][self.network]
        self.assertEqual(network["IPAddress"], self.address(offset))
        self.gateway_offset = offset

    def request(self, method, path):
        req = urllib.request.Request(self.base + path,
                                     data=b"{}" if method == "POST" else None, method=method)
        # Do not inherit a runner proxy or follow an unexpected redirect out of loopback.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open(req, timeout=3) as response:
            return json.load(response)

    def wait_phase(self, phase):
        deadline = time.monotonic() + 15
        while True:
            try:
                for method, path in (("POST", "/api/gateway/auth/login?probe=routing"),
                                     ("GET", "/api/gateway/auth/me")):
                    self.assertEqual(self.request(method, path), {
                        "phase": phase, "method": method,
                        "uri": path.removeprefix("/api/gateway"),
                    })
                return
            except (AssertionError, OSError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.1)  # nginx reload hands requests to new workers asynchronously.

    def assert_stale_routes(self):
        for method, path in (("POST", "/api/gateway/auth/login"), ("GET", "/api/gateway/auth/me")):
            with self.assertRaises(urllib.error.HTTPError) as error:
                self.request(method, path)
            self.assertEqual(error.exception.code, 502)

    def test_install_and_failed_install_rollback_reconnect_to_replaced_gateway(self):
        self.replace_gateway("installed", 3)
        self.assert_stale_routes()  # Real static proxy resolution retains the removed IP.
        STACK["refresh_routes"](self.directory)
        self.wait_phase("installed")

        self.replace_gateway("failed-install", 4)
        self.assert_stale_routes()
        (self.directory / "nginx.conf").write_text(self.config + "invalid_routing_directive;\n")
        with self.assertRaises(subprocess.CalledProcessError):
            STACK["refresh_routes"](self.directory)  # Real nginx -t failure must propagate.

        # Existing rollback restores read surfaces/config; the same helper reconnects them.
        self.replace_gateway("original", 5)
        (self.directory / "nginx.conf").write_text(self.config)
        self.assert_stale_routes()
        STACK["refresh_routes"](self.directory)
        self.wait_phase("original")
        self.assertEqual((self.directory / "nginx.conf").read_text(), self.config)


if __name__ == "__main__":
    unittest.main(verbosity=2)
