#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ "$(uname -s)" = Darwin ]; then
  source scripts/macos_env.sh
  macos_activate_environment
  if [ "${1:-}" = setup ]; then
    macos_install_environment
    exit $?
  fi
  if [ "${1:-}" = check-env ]; then
    missing_environment="$(macos_missing_environment)"
    if [ -n "$missing_environment" ]; then printf '缺少环境：\n%s\n' "$missing_environment"; exit 1; fi
    printf '%s\n' '运行环境已齐全。'
    exit 0
  fi

fi
choose_runner_python() {
  python_command="${PYTHON:-}"
  if [ -z "$python_command" ]; then
    for candidate in python3.12 python3 python; do
      if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 11))' >/dev/null 2>&1; then
        python_command="$candidate"
        break
      fi
    done
  fi
  [ -n "$python_command" ] && "$python_command" -c 'import sys; sys.exit(sys.version_info < (3, 11))' >/dev/null 2>&1
}
if ! choose_runner_python; then
  if [ "$(uname -s)" = Darwin ] && [ -t 0 ]; then
    printf '%s\n' '缺少用于打开服务菜单的 Python 3.11+。'
    read -r -p '现在一键安装运行环境？[Y/n] ' install_answer
    case "$install_answer" in n|N|no|NO) exit 0;; esac
    macos_install_environment
    choose_runner_python
  else
    printf '%s\n' '需要 Python 3.11 或更高版本；Mac 可运行 bash run.sh setup 自动安装。' >&2
    exit 1
  fi
fi
export PYTHONUTF8=1
export PYTHONIOENCODING=utf-8
exec "$python_command" scripts/local_runner.py "$@"
