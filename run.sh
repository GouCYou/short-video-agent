#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ "$#" -gt 0 ]; then
  exec "${PYTHON:-python3}" scripts/local_runner.py "$@"
else
  exec "${PYTHON:-python3}" scripts/local_runner.py
fi
