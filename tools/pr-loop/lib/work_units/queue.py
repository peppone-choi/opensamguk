"""Read-only issue eligibility and deterministic issue-first ordering."""
from datetime import datetime, timezone
from .acceptance import parse_ac


def dependency_depth(reader, repo, ac, seen=None):
    seen = set() if seen is None else seen
    depths = [0]
    for number in ac["depends"]:
        if number in seen or len(seen) >= 100:
            raise ValueError("DEPENDENCY_CYCLE_OR_TOO_WIDE")
        issue = reader.get(f"repos/{repo}/issues/{number}")
        try:
            child = parse_ac(issue.get("body", ""))
        except ValueError:
            child = {"depends": []}
        depths.append(1 + dependency_depth(reader, repo, child, seen | {number}))
    return max(depths)


def eligibility(issue, leases, dependencies, *, legacy=False, repo=None):
    if issue.get("state") != "open":
        return {"status": "CLOSED"}
    if "pull_request" in issue:
        return {"status": "NOT_ISSUE"}
    try:
        ac = parse_ac(issue.get("body", ""), legacy=legacy)
    except ValueError as exc:
        return {"status": str(exc)}
    if any(dependencies.get(n, {}).get("state") != "closed" for n in ac["depends"]):
        return {"status": "DEPENDENCY_PENDING", "acceptance": ac}
    if any((repo and f"{repo}#{issue.get('number')}" in lease.get("issues", [])) or
           ((repo is None or lease.get("repo") == repo) and
            issue.get("number") in lease.get("issueNumbers", [])) for lease in leases):
        return {"status": "LEASED", "acceptance": ac}
    return {"status": "ELIGIBLE", "acceptance": ac}


def rank(issue, ac, *, now=None, regression=False, dependency_depth=0):
    now = now or datetime.now(timezone.utc)
    created = datetime.fromisoformat(issue["created_at"].replace("Z", "+00:00"))
    wait_days = max(0, (now - created).days)
    return (0 if regression else 1, max(0, ac["priority"] - wait_days // 7),
            dependency_depth, issue["created_at"], issue["number"])


def next_units(reader, repo, leases, gaps, *, now=None):
    rows = []
    try:
        issues = reader.pages(f"repos/{repo}/issues?state=open&per_page=100")
        for issue in issues:
            if "pull_request" in issue:
                continue
            try:
                ac = parse_ac(issue.get("body", ""))
                dependencies = {n: reader.get(f"repos/{repo}/issues/{n}") for n in ac["depends"]}
            except ValueError:
                dependencies = {}
            eligible = eligibility(issue, leases, dependencies, repo=repo)
            row = dict(eligible, ref=f"{repo}#{issue['number']}", title=issue["title"])
            if eligible["status"] == "ELIGIBLE":
                ac = eligible["acceptance"]
                regression = any(g["kind"] == "REGRESSION" and row["ref"] in g.get("trackedBy", [])
                                 for g in gaps.get("entries", []))
                row["rank"] = rank(issue, ac, now=now, regression=regression,
                                   dependency_depth=dependency_depth(reader, repo, ac, {issue["number"]}))
            rows.append(row)
    except (ValueError, OSError):
        return [{"status": "UNKNOWN_AUTH", "repo": repo}]
    for gap in gaps.get("entries", []):
        if not gap.get("trackedBy"):
            rows.append({"status": "GAP_PENDING_ISSUE", "gapId": gap["gapId"]})
    return sorted(rows, key=lambda r: (r["status"] != "ELIGIBLE", r.get("rank", ()), r.get("ref", r.get("gapId", ""))))
