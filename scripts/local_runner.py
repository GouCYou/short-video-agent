#!/usr/bin/env python3
"""Local service controller. MySQL and Redis remain running on stop."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / '.runtime' / 'local'
APP_ORDER = ('qdrant', 'ai', 'backend', 'frontend')
DEFAULTS = {'LOCAL_DB_HOST': '127.0.0.1', 'LOCAL_DB_PORT': '3306',
            'LOCAL_REDIS_HOST': '127.0.0.1', 'LOCAL_REDIS_PORT': '6379',
            'LOCAL_QDRANT_PORT': '16333', 'LOCAL_QDRANT_GRPC_PORT': '16334',
            'LOCAL_AI_PORT': '18000', 'LOCAL_BACKEND_PORT': '18080', 'LOCAL_FRONTEND_PORT': '5178'}


def read_env(path):
    values = {}
    if not path.exists():
        return values
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:]
        key, sep, value = line.partition('=')
        if not sep or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key.strip()):
            raise RuntimeError(f'{path.name} 存在无效配置行')
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
            value = value[1:-1]
        values[key.strip()] = value
    return values


def config():
    file = ROOT / '.env.local'
    if not file.exists():
        text = ('# Local services\nDB_NAME=short_video_agent_local\nDB_USERNAME=root\nDB_PASSWORD=\n'
                'REDIS_PASSWORD=\nLOCAL_DB_HOST=127.0.0.1\nLOCAL_DB_PORT=3306\n'
                'LOCAL_REDIS_HOST=127.0.0.1\nLOCAL_REDIS_PORT=6379\n'
                'LOCAL_QDRANT_PORT=16333\nLOCAL_QDRANT_GRPC_PORT=16334\n'
                'LOCAL_AI_PORT=18000\nLOCAL_BACKEND_PORT=18080\nLOCAL_FRONTEND_PORT=5178\n'
                'AI_MODE=real\nREGISTRATION_MAX_ATTEMPTS=100\n')
        fd = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as output:
            output.write(text)
    env = {**os.environ, **read_env(ROOT / '.env'), **DEFAULTS, **read_env(file)}
    for key in DEFAULTS:
        if key.endswith('_PORT'):
            port = int(env[key])
            if not 1 <= port <= 65535:
                raise RuntimeError(f'{key} 端口无效')
    if not re.fullmatch(r'[a-zA-Z0-9_]+', env['DB_NAME']):
        raise RuntimeError('DB_NAME 只能包含字母、数字、下划线')
    generated = STATE / 'credentials.json'
    if not generated.exists():
        generated.write_text(json.dumps({'AI_INTERNAL_KEY': secrets.token_hex(32),
                                        'SEED_ADMIN_USERNAME': 'admin',
                                        'SEED_ADMIN_PASSWORD': '123456'}))
        generated.chmod(0o600)
    for key, value in json.loads(generated.read_text()).items():
        if not env.get(key):
            env[key] = value
    return env


def tcp(host, port):
    try:
        with socket.create_connection((host, int(port)), timeout=0.5):
            return True
    except OSError:
        return False


def require(binary):
    value = shutil.which(binary)
    if not value:
        raise RuntimeError(f'未找到 {binary}，请先安装后再运行')
    return value


def run(argv, cwd=ROOT, env=None, quiet=False):
    result = subprocess.run([str(v) for v in argv], cwd=cwd, env=env,
                            stdout=subprocess.DEVNULL if quiet else None,
                            stderr=subprocess.DEVNULL if quiet else None)
    if result.returncode:
        raise RuntimeError(f'{Path(str(argv[0])).name} 执行失败，请检查以上信息')


def record_path(name):
    return STATE / (name + '.json')


def identity(pid):
    result = subprocess.run(['ps', '-p', str(pid), '-o', 'lstart=,command='], capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else ''


def alive(name):
    path = record_path(name)
    try:
        data = json.loads(path.read_text())
        return data if identity(data['pid']) == data['identity'] and data['identity'] else None
    except (OSError, ValueError, KeyError):
        return None


def spawn(name, argv, env, cwd):
    log = STATE / (name + '.log')
    with log.open('ab') as output:
        process = subprocess.Popen([str(v) for v in argv], cwd=cwd, env=env,
                                   stdin=subprocess.DEVNULL, stdout=output, stderr=output,
                                   start_new_session=True)
    time.sleep(0.1)
    if process.poll() is not None:
        raise RuntimeError(f'{name} 启动失败，日志：{log}')
    record_path(name).write_text(json.dumps({'pid': process.pid, 'identity': identity(process.pid)}))


def wait_http(name, url, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if not alive(name):
            raise RuntimeError(f'{name} 已退出，日志：{STATE / (name + ".log")}')
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.4)
    raise RuntimeError(f'{name} 未就绪，日志：{STATE / (name + ".log")}')


def resident(name, host, port, formula):
    if tcp(host, port):
        return
    if host not in ('127.0.0.1', 'localhost') or platform.system() != 'Darwin':
        raise RuntimeError(f'{name} 未运行：{host}:{port}，请先启动系统服务')
    print(f'启动常驻服务 {name} …', flush=True)
    run([require('brew'), 'services', 'start', formula])
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if tcp(host, port):
            return
        time.sleep(0.5)
    raise RuntimeError(f'{name} 未监听 {host}:{port}，请检查 brew services 与 .env.local')


def qdrant_bin(env):
    supplied = env.get('LOCAL_QDRANT_BIN') or shutil.which('qdrant')
    if supplied:
        path = Path(supplied).expanduser().resolve()
        if not path.is_file():
            raise RuntimeError('LOCAL_QDRANT_BIN 文件不存在')
        return path
    binary = STATE / 'bin' / 'qdrant'
    if binary.exists():
        return binary
    arch = {'arm64': 'aarch64', 'aarch64': 'aarch64', 'x86_64': 'x86_64'}.get(platform.machine())
    os_name = {'Darwin': 'apple-darwin', 'Linux': 'unknown-linux-musl'}.get(platform.system())
    if not arch or not os_name:
        raise RuntimeError('请通过 LOCAL_QDRANT_BIN 指定本机 Qdrant 可执行文件')
    archive = STATE / 'bin' / 'qdrant.tar.gz'
    binary.parent.mkdir(exist_ok=True)
    url = f'https://github.com/qdrant/qdrant/releases/download/v1.15.5/qdrant-{arch}-{os_name}.tar.gz'
    print('下载 Qdrant 1.15.5 …', flush=True)
    for _ in range(8):
        result = subprocess.run([require('curl'), '-fLsS', '-C', '-', '--max-time', '55', url, '-o', str(archive)])
        if result.returncode == 0:
            break
    else:
        raise RuntimeError('Qdrant 下载未完成；重新运行可续传，或设置 LOCAL_QDRANT_BIN')
    with tarfile.open(archive, 'r:gz') as package:
        member = next((m for m in package.getmembers() if Path(m.name).name == 'qdrant' and m.isfile()), None)
        if member is None:
            raise RuntimeError('Qdrant 安装包缺少可执行文件')
        with package.extractfile(member) as source, binary.open('wb') as output:
            shutil.copyfileobj(source, output)
    binary.chmod(0o755)
    archive.unlink()
    return binary


def fingerprint(paths):
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(str(path.relative_to(ROOT)).encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def prepare(env):
    for binary in ('java', 'mvn', 'node', 'npm', 'mysql', 'redis-cli'):
        require(binary)
    java = subprocess.run(['java', '-version'], capture_output=True, text=True)
    match = re.search(r'version "(\d+)', java.stderr)
    if not match or int(match.group(1)) < 21:
        raise RuntimeError('需要 Java 21 或更高版本')
    frontend = ROOT / 'short-video-agent-frontend'
    lock_hash = fingerprint([frontend / 'package-lock.json', frontend / 'package.json'])
    marker = STATE / 'frontend-deps.hash'
    if not (frontend / 'node_modules/.bin/vite').exists() or not marker.exists() or marker.read_text() != lock_hash:
        print('安装前端依赖 …', flush=True)
        run(['npm', 'ci'], cwd=frontend)
        marker.write_text(lock_hash)
    ai = ROOT / 'short-video-agent-ai'
    python = ai / '.venv/bin/python'
    if not python.exists():
        candidate = env.get('LOCAL_AI_PYTHON') or shutil.which('python3.12') or sys.executable
        run([candidate, '-m', 'venv', ai / '.venv'])
    deps_hash = fingerprint([ai / 'requirements.txt', ai / 'constraints.txt'])
    marker = STATE / 'ai-deps.hash'
    if not marker.exists() or marker.read_text() != deps_hash:
        print('安装 AI 服务依赖 …', flush=True)
        run([python, '-m', 'pip', 'install', '-r', 'requirements.txt'], cwd=ai)
        marker.write_text(deps_hash)
    backend = ROOT / 'short-video-agent-backend'
    source_hash = fingerprint([backend / 'pom.xml', *[p for p in (backend / 'src/main').rglob('*') if p.is_file()]])
    marker = STATE / 'backend-build.hash'
    jar = STATE / 'bin' / 'backend.jar'
    if not jar.exists() or not marker.exists() or marker.read_text() != source_hash:
        print('构建 Java 服务 …', flush=True)
        run(['mvn', '-q', '-DskipTests', 'package'], cwd=backend)
        jar.parent.mkdir(exist_ok=True)
        shutil.copy2(backend / 'target/short-video-agent-backend-1.0.0.jar', jar)
        marker.write_text(source_hash)
    return python, jar


def ports(env):
    return {'qdrant': int(env['LOCAL_QDRANT_PORT']), 'ai': int(env['LOCAL_AI_PORT']),
            'backend': int(env['LOCAL_BACKEND_PORT']), 'frontend': int(env['LOCAL_FRONTEND_PORT'])}


def start(env, mode=None):
    newly_started = []
    try:
        for name, port in {**ports(env), 'qdrant-grpc': int(env['LOCAL_QDRANT_GRPC_PORT'])}.items():
            if alive('qdrant' if name == 'qdrant-grpc' else name):
                continue
            if tcp('127.0.0.1', port):
                raise RuntimeError(f'{name} 端口 {port} 被其他进程占用，请调整 .env.local')
        python, jar = prepare(env)
        binary = qdrant_bin(env)
        resident('MySQL', env['LOCAL_DB_HOST'], env['LOCAL_DB_PORT'], 'mysql@8.0')
        resident('Redis', env['LOCAL_REDIS_HOST'], env['LOCAL_REDIS_PORT'], 'redis')
        mysql_env = dict(env, MYSQL_PWD=env.get('DB_PASSWORD', ''))
        mysql = ['mysql', '--no-defaults', '-h', env['LOCAL_DB_HOST'], '-P', env['LOCAL_DB_PORT'], '-u', env['DB_USERNAME']]
        check = subprocess.run(mysql + ['--batch', '--skip-column-names', '-e', 'SELECT VERSION()'], env=mysql_env, capture_output=True, text=True)
        if check.returncode:
            raise RuntimeError('MySQL 连接失败，请在 .env.local 配置 DB_USERNAME、DB_PASSWORD 和本机端口')
        if not check.stdout.strip().startswith('8.'):
            raise RuntimeError('需要 MySQL 8.x')
        run(mysql + ['-e', f'CREATE DATABASE IF NOT EXISTS `{env["DB_NAME"]}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci'], env=mysql_env, quiet=True)
        redis_env = dict(env, REDISCLI_AUTH=env.get('REDIS_PASSWORD', ''))
        check = subprocess.run(['redis-cli', '-h', env['LOCAL_REDIS_HOST'], '-p', env['LOCAL_REDIS_PORT'], 'ping'], env=redis_env, capture_output=True, text=True)
        if check.returncode or check.stdout.strip() != 'PONG':
            raise RuntimeError('Redis 连接失败，请检查 .env.local 的端口与 REDIS_PASSWORD')
        p = ports(env)
        knowledge = STATE / 'knowledge'
        knowledge.mkdir(exist_ok=True)
        mode = mode or env.get('AI_MODE', 'real')
        settings = dict(env, AI_MODE=mode, KNOWLEDGE_DIR=str(knowledge),
                        BACKEND_INTERNAL_URL=f'http://127.0.0.1:{p["backend"]}',
                        QDRANT_URL=f'http://127.0.0.1:{p["qdrant"]}', AI_BASE_URL=f'http://127.0.0.1:{p["ai"]}',
                        DB_URL=f'jdbc:mysql://{env["LOCAL_DB_HOST"]}:{env["LOCAL_DB_PORT"]}/{env["DB_NAME"]}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false',
                        REDIS_HOST=env['LOCAL_REDIS_HOST'], REDIS_PORT=env['LOCAL_REDIS_PORT'],
                        SERVER_ADDRESS='127.0.0.1', SERVER_PORT=str(p['backend']),
                        VITE_PROXY_TARGET=f'http://127.0.0.1:{p["backend"]}')
        converter = env.get('DOCUMENT_CONVERTER') or shutil.which('soffice') or shutil.which('libreoffice')
        if converter:
            settings['DOCUMENT_CONVERTER'] = converter
        q_env = dict(settings, QDRANT__SERVICE__HOST='127.0.0.1', QDRANT__SERVICE__HTTP_PORT=str(p['qdrant']),
                     QDRANT__SERVICE__GRPC_PORT=env['LOCAL_QDRANT_GRPC_PORT'],
                     QDRANT__STORAGE__STORAGE_PATH=str(STATE / 'qdrant'), QDRANT__TELEMETRY_DISABLED='true')
        commands = {
            'qdrant': ([binary], q_env, STATE),
            'ai': ([python, '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', str(p['ai'])], settings, ROOT / 'short-video-agent-ai'),
            'backend': (['java', '-jar', jar], settings, ROOT),
            'frontend': (['node', ROOT / 'short-video-agent-frontend/node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', str(p['frontend']), '--strictPort'], settings, ROOT / 'short-video-agent-frontend')}
        health = {'qdrant': '/healthz', 'ai': '/health', 'backend': '/api/health', 'frontend': '/'}
        for name in APP_ORDER:
            if not alive(name):
                argv, service_env, cwd = commands[name]
                spawn(name, argv, service_env, cwd)
                newly_started.append(name)
                wait_http(name, f'http://127.0.0.1:{p[name]}{health[name]}')
        if 'ai' in newly_started:
            (STATE / 'mode').write_text(mode)
        print('\n项目已启动。', flush=True)
        status(env)
        print(f'管理员：{settings["SEED_ADMIN_USERNAME"]}；密码见 .env 中 SEED_ADMIN_PASSWORD（或 {STATE / "credentials.json"}）。')
        print(f'日志目录：{STATE}\n再次执行 bash run.sh 可关闭项目。')
    except BaseException:
        stop(newly_started, announce=False)
        raise


def stop(names=APP_ORDER, announce=True):
    owned = []
    for name in reversed(tuple(names)):
        record = alive(name)
        if record:
            os.killpg(record['pid'], signal.SIGTERM)
            owned.append((name, record))
    deadline = time.monotonic() + 15
    for name, record in owned:
        while time.monotonic() < deadline and alive(name):
            time.sleep(0.15)
        if alive(name):
            os.killpg(record['pid'], signal.SIGKILL)
        record_path(name).unlink(missing_ok=True)
    if announce:
        print('项目已关闭。MySQL 和 Redis 保持运行，数据保留。')


def status(env):
    print(f'{"服务":<12} {"状态":<10}  地址')
    for name, host_key, port_key in [('MySQL', 'LOCAL_DB_HOST', 'LOCAL_DB_PORT'), ('Redis', 'LOCAL_REDIS_HOST', 'LOCAL_REDIS_PORT')]:
        host, port = env[host_key], env[port_key]
        print(f'{name:<12} {"运行中" if tcp(host, port) else "未运行":<10}  {host}:{port}')
    for name, port in ports(env).items():
        print(f'{name:<12} {"运行中" if alive(name) and tcp("127.0.0.1", port) else "未运行":<10}  127.0.0.1:{port}')
    print(f'前端：http://127.0.0.1:{env["LOCAL_FRONTEND_PORT"]}/')
    if (STATE / 'mode').exists():
        print('AI 模式：' + (STATE / 'mode').read_text())


def main():
    parser = argparse.ArgumentParser(description='本地前后端启停；MySQL、Redis 为常驻服务')
    parser.add_argument('command', nargs='?', choices=['toggle', 'start', 'stop', 'status', 'logs'], default='toggle')
    parser.add_argument('service', nargs='?', choices=APP_ORDER)
    parser.add_argument('--demo', action='store_true', help='显式使用演示模式')
    args = parser.parse_args()
    STATE.mkdir(parents=True, exist_ok=True)
    STATE.chmod(0o700)
    with (STATE / 'controller.lock').open('w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('已有启停操作正在执行')
        env = config()
        command = args.command
        if command == 'toggle':
            command = 'stop' if any(alive(name) for name in APP_ORDER) else 'start'
        if command == 'start':
            start(env, 'demo' if args.demo else None)
        elif command == 'stop':
            stop()
            status(env)
        elif command == 'status':
            status(env)
        else:
            for name in ([args.service] if args.service else APP_ORDER):
                log = STATE / (name + '.log')
                print(f'\n[{name}]')
                print('\n'.join(log.read_text(errors='replace').splitlines()[-50:]) if log.exists() else '暂无日志')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError) as error:
        print('错误：' + str(error), file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print('\n已取消。', file=sys.stderr)
        sys.exit(130)
