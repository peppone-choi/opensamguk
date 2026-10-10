#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
python3 "$script_dir/../bin/work-env-preflight" \
  --manifest "$script_dir/development-environment.json" "$@"
