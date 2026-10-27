#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python_command="${PYTHON:-}"
if [ -z "$python_command" ]; then
  for candidate in python3.12 python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 11))' >/dev/null 2>&1; then
      python_command="$candidate"
      break
    fi
  done
fi
if [ -z "$python_command" ] || ! "$python_command" -c 'import sys; sys.exit(sys.version_info < (3, 11))' >/dev/null 2>&1; then
  printf '%s\n' '需要 Python 3.11 或更高版本（推荐 3.12），也可用 PYTHON 指定解释器路径。' >&2
  exit 1
fi
export PYTHONUTF8=1
export PYTHONIOENCODING=utf-8
exec "$python_command" scripts/local_runner.py "$@"
