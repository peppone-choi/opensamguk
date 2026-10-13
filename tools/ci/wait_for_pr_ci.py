#!/usr/bin/env python3
"""One-shot, fail-closed candidate eligibility check, executed from trusted main."""
import json
import os
import re
import subprocess
import urllib.parse
import urllib.request


def api(path):
    req = urllib.request.Request('https://api.github.com/repos/' + os.environ['GITHUB_REPOSITORY'] + '/' + path,
        headers={'Accept': 'application/vnd.github+json', 'Authorization': 'Bearer ' + os.environ['GITHUB_TOKEN'],
                 'X-GitHub-Api-Version': '2022-11-28'})
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)


def eligible(pr, runs, repo, sha):
    if not re.fullmatch('[a-f0-9]{40}', sha):
        raise ValueError('Full lowercase source SHA required')
    if (pr.get('state') != 'open' or pr.get('base', {}).get('ref') != 'main'
            or pr.get('base', {}).get('repo', {}).get('full_name') != repo
            or pr.get('head', {}).get('repo', {}).get('full_name') != repo
            or pr.get('head', {}).get('sha') != sha):
        raise ValueError('Open same-repository PR with exact head and main base required')
    matching = [r for r in runs if r.get('head_sha') == sha and r.get('event') == 'pull_request'
                and r.get('head_repository', {}).get('full_name') == repo
                and any(p.get('number') == pr['number'] for p in r.get('pull_requests', []))]
    if not matching:
        raise ValueError('Exact PR CI run missing')
    latest = max(matching, key=lambda r: r['id'])
    if latest.get('status') != 'completed' or latest.get('conclusion') != 'success':
        raise ValueError('Latest exact PR CI is not successful')
    return latest['id']


def protected(path):
    return (path.startswith(('docker/', '.github/', 'tools/', 'gradle/'))
            or path.startswith('docker-compose')
            or path in ('gradlew', 'gradlew.bat', '.dockerignore', '.npmrc', '.yarnrc', '.yarnrc.yml')
            or path.endswith(('Dockerfile', '.gradle.kts', '.gradle', 'gradle.properties',
                              'package.json', 'package-lock.json', 'pnpm-lock.yaml', 'yarn.lock')))


def verify_paths(paths):
    if any(protected(p) for p in paths):
        raise ValueError('Candidate changes trusted build/QA supply chain')


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def main():
    repo = os.environ['GITHUB_REPOSITORY']
    number = os.environ['PR_NUMBER']
    sha = os.environ['SOURCE_SHA']
    if not re.fullmatch('[1-9][0-9]*', number):
        raise ValueError('Positive PR number required')
    pr = api('pulls/' + number)
    query = urllib.parse.urlencode({'event': 'pull_request', 'head_sha': sha, 'per_page': 100})
    ci = eligible(pr, api('actions/workflows/ci.yml/runs?' + query)['workflow_runs'], repo, sha)
    base = git('rev-parse', 'origin/main')
    # NUL separation handles whitespace/newlines in changed filenames; triple-dot is PR delta.
    paths = subprocess.check_output(['git', 'diff', '--name-only', '--no-renames', '-z', base + '...' + sha]).decode().split('\0')
    verify_paths(paths)
    # Re-read after fetching/diff to catch head changes; do not silently follow a moved PR.
    eligible(api('pulls/' + number), api('actions/workflows/ci.yml/runs?' + query)['workflow_runs'], repo, sha)
    receipt = {'pr': int(number), 'sourceSha': sha, 'baseSha': base, 'ciRunId': ci,
               'runId': os.environ['GITHUB_RUN_ID'], 'channel': 'pr-candidate'}
    with open(os.environ.get('CANDIDATE_METADATA', 'candidate-metadata.json'), 'w') as out:
        json.dump(receipt, out)
    with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
        out.write('base_sha=' + base + '\n')


if __name__ == '__main__':
    main()
