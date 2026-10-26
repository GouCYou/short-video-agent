#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  echo '.env 已存在，保留原文件。请直接编辑其中配置。'
  exit 0
fi
mode=real
if [ "${1:-}" = '--demo' ]; then mode=demo; fi
umask 077
cp .env.example .env
python_cmd="${PYTHON:-python3}"
"$python_cmd" - "$mode" <<'PY'
from pathlib import Path
import secrets,sys
p=Path('.env')
s=p.read_text()
for key in ['MYSQL_ROOT_PASSWORD','DB_PASSWORD','REDIS_PASSWORD','AI_INTERNAL_KEY']:
    s=s.replace(key+'=\n',key+'='+secrets.token_hex(24)+'\n')
s=s.replace('SEED_ADMIN_PASSWORD=\n','SEED_ADMIN_PASSWORD='+secrets.token_urlsafe(24)+'\n')
s=s.replace('AI_MODE=real','AI_MODE='+sys.argv[1])
p.write_text(s)
p.chmod(0o600)
print('已生成 .env 和随机服务凭据。管理员用户名为 admin，随机密码请在 .env 的 SEED_ADMIN_PASSWORD 中查看。')
print('当前 AI 模式：'+sys.argv[1]+'。真实模式需补全两组模型配置；演示模式生成的是合成示例。')
PY
