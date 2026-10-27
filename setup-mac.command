#!/bin/bash
cd "$(dirname "$0")" || exit 1
bash run.sh setup
setup_exit_code=$?
if [ "$setup_exit_code" -eq 0 ]; then
  read -r -p '环境已就绪，按回车打开服务菜单（Ctrl+C 退出）…' _
  bash run.sh
else
  printf '\n安装未完成，请查看上方原因。\n'
  read -r -p '按回车关闭…' _
fi
exit "$setup_exit_code"
