#!/usr/bin/env python3
"""Exercise the local issuer and API; never prints passwords or bearer tokens."""
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("API_URL", "http://localhost:8080")
ISSUER = os.environ.get("OIDC_ISSUER", "http://localhost:8081/realms/saas")


def request(method, url, body=None, token=None, expected=200, form=False):
    headers = {}
    if body is not None:
        body = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            status, payload = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, payload = error.code, error.read()
    if status != expected:
        raise RuntimeError(f"{method} {url}: expected {expected}, got {status}")
    return json.loads(payload) if payload else None


def wait_ready(url):
    for _ in range(90):
        try:
            request("GET", url)
            return
        except (urllib.error.URLError, ConnectionError, TimeoutError, RuntimeError):
            time.sleep(2)
    raise RuntimeError(f"Service did not become ready: {url}")


def token_for(username, password):
    response = request("POST", ISSUER + "/protocol/openid-connect/token",
        {"grant_type": "password", "scope": "openid", "client_id": "saas-cli", "username": username, "password": password}, form=True)
    return response["access_token"]


def main():
    env = Path(__file__).resolve().parent.parent / ".env"
    values = dict(line.split("=", 1) for line in env.read_text().splitlines() if line and not line.startswith("#"))
    wait_ready(BASE + "/actuator/health/readiness")
    wait_ready(ISSUER + "/.well-known/openid-configuration")
    alice = token_for("alice", values["DEMO_PASSWORD"])
    bob = token_for("bob", values["DEMO_PASSWORD"])
    bob_claims = request("GET", ISSUER + "/protocol/openid-connect/userinfo", token=bob)
    tenants = BASE + "/api/v1/tenants"
    tenant = request("POST", tenants, {"name": "Example workspace"}, alice, 201)
    tenant_url = tenants + "/" + tenant["id"]
    projects = tenant_url + "/projects"
    project = request("POST", projects, {"name": "First project", "description": "Generic example"}, alice, 201)
    request("GET", projects, token=bob, expected=404)
    request("PUT", tenant_url + "/members", {"subject": bob_claims["sub"], "role": "MEMBER"}, alice, 204)
    visible = request("GET", projects, token=bob)
    assert [entry["id"] for entry in visible] == [project["id"]]
    request("POST", projects, {"name": "Forbidden", "description": ""}, bob, 403)
    project_url = projects + "/" + project["id"]
    updated = request("PUT", project_url, {"name": "Updated", "description": "", "version": 0}, alice)
    assert updated["version"] == 1
    request("DELETE", project_url + "?version=0", token=alice, expected=409)
    request("DELETE", project_url + "?version=1", token=alice, expected=204)
    events = request("GET", tenant_url + "/audit-events", token=alice)
    assert {event["action"] for event in events} >= {"TENANT_CREATED", "PROJECT_CREATED", "PROJECT_UPDATED", "PROJECT_DELETED"}
    request("GET", projects, expected=401)
    print("PASS: real OIDC login, tenant isolation, membership, RBAC, CRUD, conflicts, and audit")


if __name__ == "__main__":
    main()
