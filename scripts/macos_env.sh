#!/usr/bin/env bash
# Sourced by run.sh; only macos_install_environment performs installations.
macos_find_brew() {
  if command -v brew >/dev/null 2>&1; then command -v brew; return; fi
  local candidate
  for candidate in /opt/homebrew/bin/brew /usr/local/bin/brew; do
    if [ -x "$candidate" ]; then printf '%s\n' "$candidate"; return; fi
  done
  return 1
}

macos_activate_environment() {
  local brew_command prefix formula
  brew_command="$(macos_find_brew)" || return 0
  prefix="$("$brew_command" --prefix)" || return 1
  export PATH="$prefix/bin:$prefix/sbin:$PATH"
  for formula in python@3.12 node@24 mysql@8.4 mysql@8.0 redis maven; do
    if [ -d "$prefix/opt/$formula/bin" ]; then
      export PATH="$prefix/opt/$formula/bin:$PATH"
    fi
  done
  if [ -d "$prefix/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="$prefix/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
}

macos_missing_environment() {
  command -v python3.12 >/dev/null 2>&1 || printf '%s\n' python@3.12
  if ! java -version 2>&1 | /usr/bin/grep -Eq 'version "(2[1-9]|[3-9][0-9])'; then printf '%s\n' openjdk@21; fi
  if ! node -e 'process.exit(Number(process.versions.node.split(".")[0])>=22 ? 0:1)' >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then printf '%s\n' node@24; fi
  if ! mysql --version 2>/dev/null | /usr/bin/grep -Eq '(Ver|Distrib) 8\.' || ! command -v mysqld >/dev/null 2>&1; then printf '%s\n' mysql@8.4; fi
  if ! command -v redis-server >/dev/null 2>&1 || ! command -v redis-cli >/dev/null 2>&1; then printf '%s\n' redis; fi
  command -v mvn >/dev/null 2>&1 || printf '%s\n' maven
  return 0
}

macos_brew_mirrors() {
  export HOMEBREW_BREW_GIT_REMOTE=https://mirrors.tuna.tsinghua.edu.cn/git/homebrew/brew.git
  export HOMEBREW_CORE_GIT_REMOTE=https://mirrors.tuna.tsinghua.edu.cn/git/homebrew/homebrew-core.git
  export HOMEBREW_BOTTLE_DOMAIN=https://mirrors.tuna.tsinghua.edu.cn/homebrew-bottles
  export HOMEBREW_API_DOMAIN=https://mirrors.tuna.tsinghua.edu.cn/homebrew-bottles/api
  export HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_ANALYTICS=1 HOMEBREW_NO_INSTALL_CLEANUP=1
}

macos_install_brew() {
  local installer_dir count=0
  if ! /usr/bin/xcode-select -p >/dev/null 2>&1; then
    printf '%s\n' '正在请求安装 Apple 命令行工具，请在系统弹窗中完成安装；完成后会自动继续。'
    /usr/bin/xcode-select --install || true
    until /usr/bin/xcode-select -p >/dev/null 2>&1; do
      count=$((count + 1))
      if [ "$count" -ge 360 ]; then printf '%s\n' '命令行工具尚未安装完成，请完成系统安装后重新打开脚本。' >&2; return 1; fi
      sleep 5
    done
  fi
  installer_dir="$(mktemp -d "${TMPDIR:-/tmp}/zhiying-brew.XXXXXX")" || return 1
  printf '%s\n' '下载 Homebrew 官方安装器（清华镜像优先）…'
  if ! GIT_TERMINAL_PROMPT=0 /usr/bin/git -c http.lowSpeedLimit=1024 -c http.lowSpeedTime=20 clone --depth=1 https://mirrors.tuna.tsinghua.edu.cn/git/homebrew/install.git "$installer_dir/install"; then
    printf '%s\n' '镜像未成功，改用 Homebrew 官方安装器 …'
    if ! /usr/bin/curl -fLsS --connect-timeout 10 --max-time 90 https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh -o "$installer_dir/install.sh"; then
      rm -rf "$installer_dir"; return 1
    fi
  else
    cp "$installer_dir/install/install.sh" "$installer_dir/install.sh" || { rm -rf "$installer_dir"; return 1; }
  fi
  printf '%s\n' '开始安装 Homebrew；系统要求时请输入当前 Mac 登录密码。'
  if ! /bin/bash "$installer_dir/install.sh"; then
    printf '%s\n' 'Homebrew 镜像安装未完成，回退官方源重试 …'
    if ! (unset HOMEBREW_BREW_GIT_REMOTE HOMEBREW_CORE_GIT_REMOTE HOMEBREW_BOTTLE_DOMAIN HOMEBREW_API_DOMAIN; /bin/bash "$installer_dir/install.sh"); then
      rm -rf "$installer_dir"; return 1
    fi
  fi
  rm -rf "$installer_dir"
}

macos_install_environment() {
  local brew_command missing formula remaining
  if [ "$(uname -s)" != Darwin ]; then printf '%s\n' '自动安装环境入口仅用于 macOS。' >&2; return 1; fi
  if [ "$(id -u)" = 0 ]; then printf '%s\n' '请使用普通账户运行，不要使用 sudo bash run.sh。安装器会按需请求系统权限。' >&2; return 1; fi
  macos_brew_mirrors
  if ! macos_find_brew >/dev/null; then macos_install_brew || return 1; fi
  brew_command="$(macos_find_brew)" || return 1
  macos_activate_environment
  missing="$(macos_missing_environment)"
  if [ -z "$missing" ]; then printf '%s\n' '运行环境已齐全，无需安装。'; return 0; fi
  printf '需要安装：\n%s\n' "$missing"
  # A child installer may read stdin; never use the install queue as its stdin.
  for formula in $missing; do
    [ -n "$formula" ] || continue
    printf '\n安装 %s …\n' "$formula"
    if ! "$brew_command" install "$formula" </dev/null; then
      printf '%s\n' '镜像安装未成功，回退 Homebrew 官方源 …'
      if ! (unset HOMEBREW_BOTTLE_DOMAIN HOMEBREW_API_DOMAIN HOMEBREW_BREW_GIT_REMOTE HOMEBREW_CORE_GIT_REMOTE; "$brew_command" install "$formula" </dev/null); then
        printf '安装 %s 失败。修复上方错误后再次运行即可，已成功安装的依赖会复用。\n' "$formula" >&2
        return 1
      fi
    fi
    macos_activate_environment
  done
  remaining="$(macos_missing_environment)"
  if [ -n "$remaining" ]; then printf '以下工具仍不可用：\n%s\n' "$remaining" >&2; return 1; fi
  printf '\n%s\n' '运行环境安装完成。MySQL、Redis 会在选择启动项目时自动启用。' '未修改已有数据库密码；已有数据库账号请在 .env.local 配置。'
}
