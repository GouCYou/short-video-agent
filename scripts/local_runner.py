#!/usr/bin/env python3
"""Local service controller. MySQL and Redis remain running on stop."""
import argparse
from contextlib import contextmanager, nullcontext
from concurrent.futures import ThreadPoolExecutor
import hashlib
import ipaddress
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
import unicodedata
import zipfile

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / '.runtime' / 'local'
APP_ORDER = ('qdrant', 'ai', 'backend', 'frontend')
IS_WINDOWS = os.name == 'nt'
COLOR = False
LABELS = {'qdrant': 'Qdrant', 'ai': 'Python AI', 'backend': 'Java 后端', 'frontend': 'Vue 前端'}
DEFAULTS = {'LOCAL_DB_HOST': '127.0.0.1', 'LOCAL_DB_PORT': '3306',
            'LOCAL_REDIS_HOST': '127.0.0.1', 'LOCAL_REDIS_PORT': '6379',
            'LOCAL_QDRANT_PORT': '16333', 'LOCAL_QDRANT_GRPC_PORT': '16334',
            'LOCAL_AI_PORT': '18000', 'LOCAL_BACKEND_PORT': '18080', 'LOCAL_FRONTEND_PORT': '5178'}


def read_env(path):
    values = {}
    if not path.exists():
        return values
    for raw in path.read_text(encoding='utf-8-sig').splitlines():
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


def config(initialize=True):
    file = ROOT / '.env.local'
    if initialize:
        STATE.mkdir(parents=True, exist_ok=True)
        if not IS_WINDOWS:
            STATE.chmod(0o700)
    if initialize and not file.exists():
        text = ('# Local services\nDB_NAME=short_video_agent_local\nDB_USERNAME=root\nDB_PASSWORD=\n'
                'REDIS_PASSWORD=\nLOCAL_DB_HOST=127.0.0.1\nLOCAL_DB_PORT=3306\n'
                'LOCAL_REDIS_HOST=127.0.0.1\nLOCAL_REDIS_PORT=6379\n'
                'LOCAL_QDRANT_PORT=16333\nLOCAL_QDRANT_GRPC_PORT=16334\n'
                'LOCAL_AI_PORT=18000\nLOCAL_BACKEND_PORT=18080\nLOCAL_FRONTEND_PORT=5178\n'
                'AI_MODE=real\nREGISTRATION_MAX_ATTEMPTS=100\n')
        fd = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as output:
            output.write(text)
    local_defaults = dict(DEFAULTS, DB_NAME='short_video_agent_local', DB_USERNAME='root', DB_PASSWORD='', REDIS_PASSWORD='')
    env = {**os.environ, **read_env(ROOT / '.env'), **local_defaults, **read_env(file)}
    for key in DEFAULTS:
        if key.endswith('_PORT'):
            port = int(env[key])
            if not 1 <= port <= 65535:
                raise RuntimeError(f'{key} 端口无效')
    if not re.fullmatch(r'[a-zA-Z0-9_]+', env['DB_NAME']):
        raise RuntimeError('DB_NAME 只能包含字母、数字、下划线')
    generated = STATE / 'credentials.json'
    if initialize and not generated.exists():
        generated.write_text(json.dumps({'AI_INTERNAL_KEY': secrets.token_hex(32),
                                        'SEED_ADMIN_USERNAME': 'admin',
                                        'SEED_ADMIN_PASSWORD': '123456'}))
        generated.chmod(0o600)
    credentials = json.loads(generated.read_text()) if generated.exists() else {}
    for key, value in credentials.items():
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


def command_line(argv):
    values = [str(v) for v in argv]
    values[0] = shutil.which(values[0]) or values[0]
    if IS_WINDOWS and Path(values[0]).suffix.lower() in ('.cmd', '.bat'):
        shell = os.environ.get('COMSPEC', 'cmd.exe')
        return subprocess.list2cmdline([shell]) + ' /d /s /c "' + subprocess.list2cmdline(values) + '"'
    return values


def run(argv, cwd=ROOT, env=None, quiet=False):
    result = subprocess.run(command_line(argv), cwd=cwd, env=env,
                            stdout=subprocess.DEVNULL if quiet else None,
                            stderr=subprocess.DEVNULL if quiet else None)
    if result.returncode:
        raise RuntimeError(f'{Path(str(argv[0])).name} 执行失败，请检查以上信息')


def record_path(name):
    return STATE / (name + '.json')


def identity(pid):
    if IS_WINDOWS:
        return windows_identity(pid)
    env = dict(os.environ, LC_ALL='C', LANG='C')
    env.pop('TZ', None)
    env.pop('COLUMNS', None)
    result = subprocess.run(['ps', '-ww', '-p', str(pid), '-o', 'lstart=,command='], capture_output=True, text=True, env=env)
    return result.stdout.strip() if result.returncode == 0 else ''


def same_identity(actual, recorded):
    def canonical(value):
        if re.match(r'^[A-Z][a-z]{2} [A-Z][a-z]{2}\s+\d', value):
            pieces = value.split(None, 5)
            return ' '.join(pieces[:5]) + ' ' + pieces[5] if len(pieces) == 6 else value
        return value.strip()
    return bool(actual and recorded and canonical(actual) == canonical(recorded))


def windows_identity(pid):
    """Match PID plus native creation time and executable, so recycled PIDs are not stopped."""
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.OpenProcess(0x1000, False, int(pid))
    if not handle:
        return ''
    try:
        code = wintypes.DWORD()
        if not kernel.GetExitCodeProcess(handle, ctypes.byref(code)) or code.value != 259:
            return ''
        created, exited, system, user = (wintypes.FILETIME() for _ in range(4))
        if not kernel.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited), ctypes.byref(system), ctypes.byref(user)):
            return ''
        size = wintypes.DWORD(32768)
        image = ctypes.create_unicode_buffer(size.value)
        if not kernel.QueryFullProcessImageNameW(handle, 0, image, ctypes.byref(size)):
            return ''
        return f'{created.dwHighDateTime:08x}{created.dwLowDateTime:08x}:{image.value}'
    finally:
        kernel.CloseHandle(handle)


def alive(name):
    path = record_path(name)
    try:
        data = json.loads(path.read_text())
        pid = data['pid']
        return data if isinstance(pid, int) and not isinstance(pid, bool) and pid > 0 and same_identity(identity(pid), data['identity']) else None
    except (OSError, ValueError, KeyError, TypeError):
        return None


def redact(text, env=None):
    for key, value in sorted((env or {}).items(), key=lambda item: len(str(item[1])), reverse=True):
        if re.search(r'PASSWORD|SECRET|TOKEN|KEY', key, re.I) and len(str(value)) >= 4:
            text = text.replace(str(value), '[已隐藏]')
    return re.sub(r'(?i)(Bearer\s+)[A-Za-z0-9._-]+', r'\1[已隐藏]', text)


def console_text(value):
    if isinstance(value, str):
        return value
    try:
        return value.decode('utf-8')
    except UnicodeDecodeError:
        try:
            return value.decode('mbcs' if IS_WINDOWS else 'utf-8', errors='replace')
        except LookupError:
            return value.decode('utf-8', errors='replace')


def log_tail(name, env=None):
    log = STATE / (name + '.log')
    if not log.exists():
        return '暂无日志'
    # Bound the read even after a long-running local session.
    with log.open('rb') as source:
        source.seek(max(0, log.stat().st_size - 32000))
        text = source.read().decode('utf-8', errors='replace')
    return redact('\n'.join(text.splitlines()[-30:])[-6000:], env)


def startup_error(name, reason, env=None):
    return (f'{LABELS.get(name, name)} 启动失败：{redact(reason, env)}\n'
            f'日志：{STATE / (name + ".log")}\n最近日志：\n{log_tail(name, env)}')


def spawn(name, argv, env, cwd):
    log = STATE / (name + '.log')
    with log.open('ab') as output:
        options = ({'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW}
                   if IS_WINDOWS else {'start_new_session': True})
        try:
            process = subprocess.Popen(command_line(argv), cwd=cwd, env=env,
                                       stdin=subprocess.DEVNULL, stdout=output, stderr=output,
                                       **options)
        except OSError as error:
            raise RuntimeError(startup_error(name, str(error), env)) from error
    time.sleep(0.1)
    if process.poll() is not None:
        raise RuntimeError(startup_error(name, f'进程退出，代码 {process.returncode}', env))
    process_identity = identity(process.pid)
    if not process_identity:
        process.terminate()
        process.wait(timeout=10)
        raise RuntimeError(f'{name} 无法记录进程标识，已取消本次启动')
    record_path(name).write_text(json.dumps({'pid': process.pid, 'identity': process_identity}))


def wait_http(name, url, seconds=90, env=None):
    deadline = time.monotonic() + seconds
    last_error = '健康检查未返回 HTTP 200'
    while time.monotonic() < deadline:
        if not alive(name):
            raise RuntimeError(startup_error(name, '进程在健康检查前退出', env))
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
                last_error = f'健康检查返回 HTTP {response.status}'
        except (OSError, urllib.error.URLError) as error:
            last_error = str(error)
        time.sleep(0.4)
    raise RuntimeError(startup_error(name, f'{seconds} 秒内未就绪；{url}；{last_error}', env))


def windows_service(name, env):
    key = 'LOCAL_MYSQL_SERVICE' if name == 'MySQL' else 'LOCAL_REDIS_SERVICE'
    if env.get(key):
        return env[key]
    pattern = 'mysql' if name == 'MySQL' else '(redis|memurai)'
    script = ("$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[Text.Encoding]::UTF8; "
              f"@(Get-Service | Where-Object {{ $_.Name -match '{pattern}' -or $_.DisplayName -match '{pattern}' }} | "
              "ForEach-Object { $_.Name }) | ConvertTo-Json -Compress")
    result = subprocess.run([require('powershell.exe'), '-NoProfile', '-NonInteractive', '-Command', script],
                            capture_output=True, encoding='utf-8', errors='replace', timeout=20)
    if result.returncode:
        raise RuntimeError(f'无法检测 {name} 系统服务，请在 .env.local 设置 {key}')
    try:
        matches = json.loads(result.stdout.lstrip('\ufeff').strip() or '[]')
    except ValueError as error:
        raise RuntimeError(f'{name} 系统服务检测返回无效结果，请通过 {key} 指定服务名') from error
    matches = [matches] if isinstance(matches, str) else matches
    if not matches:
        raise RuntimeError(f'未找到 {name} 系统服务；请先安装并注册服务，或在 .env.local 配置 {key} 和端口')
    if len(matches) != 1:
        raise RuntimeError(f'找到多个 {name} 服务：{", ".join(matches)}；请在 .env.local 用 {key} 指定')
    return matches[0]


def resident(name, host, port, formula, env=None):
    if tcp(host, port):
        return
    env = env or {}
    if host not in ('127.0.0.1', 'localhost', '::1'):
        raise RuntimeError(f'{name} 未运行：{host}:{port}，请先启动系统服务')
    print(f'启动常驻服务 {name} …', flush=True)
    if IS_WINDOWS:
        command = [require('sc.exe'), 'start', windows_service(name, env)]
    elif platform.system() == 'Darwin':
        command = [require('brew'), 'services', 'start', formula]
    else:
        key = 'LOCAL_MYSQL_SERVICE' if name == 'MySQL' else 'LOCAL_REDIS_SERVICE'
        command = [require('systemctl'), 'start', env.get(key) or ('mysql' if name == 'MySQL' else 'redis-server')]
    result = subprocess.run(command_line(command), capture_output=True)
    if result.returncode:
        reason = console_text(result.stdout + result.stderr).strip()
        raise RuntimeError(f'{name} 系统服务启动失败：{redact(reason, env)}\n请检查服务名称、配置与当前账户的服务管理权限。')
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if tcp(host, port):
            return
        time.sleep(0.5)
    raise RuntimeError(f'{name} 服务启动后仍未监听 {host}:{port}，请检查服务日志与 .env.local 中的端口')


def qdrant_bin(env):
    supplied = env.get('LOCAL_QDRANT_BIN') or shutil.which('qdrant')
    if supplied:
        path = Path(supplied).expanduser()
        path = (ROOT / path).resolve() if not path.is_absolute() else path.resolve()
        if not path.is_file():
            raise RuntimeError('LOCAL_QDRANT_BIN 文件不存在')
        return path
    binary = STATE / 'bin' / ('qdrant.exe' if IS_WINDOWS else 'qdrant')
    if binary.exists():
        return binary
    arch = {'arm64': 'aarch64', 'aarch64': 'aarch64', 'x86_64': 'x86_64', 'amd64': 'x86_64'}.get(platform.machine().lower())
    os_name = 'pc-windows-msvc' if IS_WINDOWS else {'Darwin': 'apple-darwin', 'Linux': 'unknown-linux-musl'}.get(platform.system())
    if IS_WINDOWS and arch != 'x86_64':
        raise RuntimeError('当前 Qdrant Windows 下载仅支持 x64，请在 .env.local 设置 LOCAL_QDRANT_BIN')
    if not arch or not os_name:
        raise RuntimeError('请通过 LOCAL_QDRANT_BIN 指定本机 Qdrant 可执行文件')
    extension = 'zip' if IS_WINDOWS else 'tar.gz'
    archive = STATE / 'bin' / ('qdrant.' + extension)
    binary.parent.mkdir(exist_ok=True)
    url = f'https://github.com/qdrant/qdrant/releases/download/v1.15.5/qdrant-{arch}-{os_name}.{extension}'
    print('下载 Qdrant 1.15.5 …', flush=True)
    for _ in range(8):
        result = subprocess.run([require('curl'), '-fLsS', '-C', '-', '--max-time', '55', url, '-o', str(archive)])
        if result.returncode == 0:
            break
    else:
        raise RuntimeError('Qdrant 下载未完成；重新运行可续传，或设置 LOCAL_QDRANT_BIN')
    temporary = binary.with_name(binary.name + '.extracting')
    try:
        if IS_WINDOWS:
            with zipfile.ZipFile(archive) as package:
                member = next((m for m in package.namelist() if Path(m).name == 'qdrant.exe'), None)
                if member is None:
                    raise RuntimeError('Qdrant 安装包缺少 qdrant.exe')
                with package.open(member) as source, temporary.open('wb') as output:
                    shutil.copyfileobj(source, output)
        else:
            with tarfile.open(archive, 'r:gz') as package:
                member = next((m for m in package.getmembers() if Path(m.name).name == 'qdrant' and m.isfile()), None)
                if member is None:
                    raise RuntimeError('Qdrant 安装包缺少可执行文件')
                with package.extractfile(member) as source, temporary.open('wb') as output:
                    shutil.copyfileobj(source, output)
            temporary.chmod(0o755)
        temporary.replace(binary)
    except (zipfile.BadZipFile, tarfile.TarError, OSError, RuntimeError) as error:
        temporary.unlink(missing_ok=True)
        archive.unlink(missing_ok=True)
        raise RuntimeError(f'Qdrant 安装包解压失败：{error}；已清理下载缓存，可重新选择启动。') from error
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
    host = platform.system() + '-' + platform.machine() + ':'
    lock_hash = host + fingerprint([frontend / 'package-lock.json', frontend / 'package.json'])
    marker = STATE / 'frontend-deps.hash'
    if not (frontend / ('node_modules/.bin/vite.cmd' if IS_WINDOWS else 'node_modules/.bin/vite')).exists() or not marker.exists() or marker.read_text() != lock_hash:
        print('安装前端依赖 …', flush=True)
        run(['npm', 'ci'], cwd=frontend)
        marker.write_text(lock_hash)
    ai = ROOT / 'short-video-agent-ai'
    python = ai / ('.venv/Scripts/python.exe' if IS_WINDOWS else '.venv/bin/python')
    created_venv = not python.exists()
    if created_venv:
        candidate = env.get('LOCAL_AI_PYTHON') or shutil.which('python3.12') or sys.executable
        run([candidate, '-m', 'venv', ai / '.venv'])
    deps_hash = host + fingerprint([ai / 'requirements.txt', ai / 'constraints.txt'])
    marker = STATE / 'ai-deps.hash'
    if created_venv or not marker.exists() or marker.read_text() != deps_hash:
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
        if all(alive(name) and tcp('127.0.0.1', port) for name, port in ports(env).items()):
            resident('MySQL', env['LOCAL_DB_HOST'], env['LOCAL_DB_PORT'], 'mysql@8.0', env)
            resident('Redis', env['LOCAL_REDIS_HOST'], env['LOCAL_REDIS_PORT'], 'redis', env)
            print('项目已在运行，无需重复启动。')
            status(env)
            return
        for name, port in {**ports(env), 'qdrant-grpc': int(env['LOCAL_QDRANT_GRPC_PORT'])}.items():
            if alive('qdrant' if name == 'qdrant-grpc' else name):
                continue
            if tcp('127.0.0.1', port):
                raise RuntimeError(f'{LABELS.get(name, name)} 端口 {port} 被其他进程占用，请调整 .env.local')
        python, jar = prepare(env)
        binary = qdrant_bin(env)
        resident('MySQL', env['LOCAL_DB_HOST'], env['LOCAL_DB_PORT'], 'mysql@8.0', env)
        resident('Redis', env['LOCAL_REDIS_HOST'], env['LOCAL_REDIS_PORT'], 'redis', env)
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
                        SERVER_ADDRESS='0.0.0.0', SERVER_PORT=str(p['backend']),
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
            'frontend': (['node', ROOT / 'short-video-agent-frontend/node_modules/vite/bin/vite.js', '--host', '0.0.0.0', '--port', str(p['frontend']), '--strictPort'], settings, ROOT / 'short-video-agent-frontend')}
        health = {'qdrant': '/healthz', 'ai': '/health', 'backend': '/api/health', 'frontend': '/'}
        for name in APP_ORDER:
            if not alive(name):
                argv, service_env, cwd = commands[name]
                spawn(name, argv, service_env, cwd)
                newly_started.append(name)
                wait_http(name, f'http://127.0.0.1:{p[name]}{health[name]}', env=settings)
        if 'ai' in newly_started:
            (STATE / 'mode').write_text(mode)
        print('\n项目已启动。', flush=True)
        status(env)
        print(f'管理员：{settings["SEED_ADMIN_USERNAME"]}；密码见 .env 中 SEED_ADMIN_PASSWORD（或 {STATE / "credentials.json"}）。')
        print(f'日志目录：{STATE}\n下次打开根目录 run.sh / run.bat 后，可在菜单选择停止。')
    except BaseException:
        stop(newly_started, announce=False)
        raise


def stop(names=APP_ORDER, announce=True):
    owned = []
    for name in reversed(tuple(names)):
        record = alive(name)
        if record:
            terminate_record(name, record)
            owned.append((name, record))
    deadline = time.monotonic() + 15
    for name, record in owned:
        while time.monotonic() < deadline and alive(name):
            time.sleep(0.15)
        if alive(name):
            terminate_record(name, record, force=True)
        record_path(name).unlink(missing_ok=True)
    if announce:
        print('项目已关闭。MySQL 和 Redis 保持运行，数据保留。')


def terminate_record(name, record, force=False):
    if not same_identity(identity(record['pid']), record['identity']):
        return
    if IS_WINDOWS:
        command = [require('taskkill.exe'), '/PID', str(record['pid']), '/T']
        result = subprocess.run(command + (['/F'] if force else []), capture_output=True)
        if result.returncode and alive(name):
            if not force:
                return terminate_record(name, record, force=True)
            raise RuntimeError(f'{LABELS.get(name, name)} 无法停止，请检查进程权限。')
    else:
        try:
            os.killpg(record['pid'], signal.SIGKILL if force else signal.SIGTERM)
        except ProcessLookupError:
            pass


def paint(text, color='32'):
    return f'\033[{color}m{text}\033[0m' if COLOR else text


def cell(text, width):
    visible = sum(2 if unicodedata.east_asian_width(char) in ('W', 'F') else 1 for char in text)
    return text + ' ' * max(1, width - visible)


def private_ipv4(value):
    try:
        address = ipaddress.IPv4Address(value)
        return any(address in ipaddress.IPv4Network(network) for network in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
    except ipaddress.AddressValueError:
        return False


def lan_ipv4(env):
    if env.get('LOCAL_LAN_IP'):
        value = env['LOCAL_LAN_IP'].strip()
        if not private_ipv4(value):
            raise RuntimeError('LOCAL_LAN_IP 需要填写有效的内网 IPv4 地址')
        return value
    # Prefer the active physical adapter over VPN and virtual machine interfaces.
    if platform.system() == 'Darwin':
        try:
            result = subprocess.run(['ifconfig', '-a'], capture_output=True, text=True, timeout=3)
            for block in re.split(r'(?=^[\w]+:)', result.stdout, flags=re.M):
                if not re.match(r'en\d+:', block) or 'status: active' not in block:
                    continue
                for address in re.findall(r'\binet (\d+\.\d+\.\d+\.\d+)', block):
                    if private_ipv4(address):
                        return address
        except (OSError, subprocess.TimeoutExpired):
            pass
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            # UDP connect selects an interface without sending any traffic.
            probe.connect(('192.0.2.1', 9))
            address = probe.getsockname()[0]
            if private_ipv4(address):
                return address
    except OSError:
        pass
    try:
        for entry in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            if private_ipv4(entry[4][0]):
                return entry[4][0]
    except OSError:
        pass
    return None


def collect_status(env):
    """Probe each endpoint once, concurrently, before drawing the status table."""
    lan = lan_ipv4(env)
    app_ports = ports(env)
    endpoints = {(env['LOCAL_DB_HOST'], env['LOCAL_DB_PORT']),
                 (env['LOCAL_REDIS_HOST'], env['LOCAL_REDIS_PORT'])}
    endpoints.update(('127.0.0.1', port) for port in app_ports.values())
    if lan:
        endpoints.update((lan, port) for _, port in list(endpoints))
    with ThreadPoolExecutor(max_workers=16) as pool:
        probes = {endpoint: pool.submit(tcp, *endpoint) for endpoint in endpoints}
        owners = {name: pool.submit(alive, name) for name in APP_ORDER}
        return {'lan': lan, 'ports': app_ports,
                'endpoints': {key: future.result() for key, future in probes.items()},
                'owners': {key: future.result() for key, future in owners.items()}}


def status(env, snapshot=None):
    snapshot = snapshot if snapshot is not None else collect_status(env)
    lan, checks = snapshot['lan'], snapshot['endpoints']
    lines = [paint('内网 IPv4：' + (lan or '未检测到，请检查网络或设置 LOCAL_LAN_IP'), '1;32'),
             paint(cell('服务', 14) + cell('状态', 26) + '端口 / 访问地址', '1;32')]
    def endpoint(host, port, active):
        if host not in ('127.0.0.1', 'localhost', '::1', '0.0.0.0'):
            return f'{host}:{port}'
        if active and lan and checks.get((lan, port), False):
            return f'{lan}:{port}'
        return f'{port}（仅本机）' if active else str(port)
    for name, host_key, port_key in [('MySQL', 'LOCAL_DB_HOST', 'LOCAL_DB_PORT'), ('Redis', 'LOCAL_REDIS_HOST', 'LOCAL_REDIS_PORT')]:
        host, port = env[host_key], env[port_key]
        active = checks[(host, port)]
        lines.append(paint(cell(name, 14) + cell('运行中' if active else '未运行', 26) + endpoint(host, port, active), '32' if active else '33'))
    for name, port in snapshot['ports'].items():
        owned, listening = snapshot['owners'][name], checks[('127.0.0.1', port)]
        state = ('运行中' if listening else '进程在，端口未就绪') if owned else ('端口已监听，归属待确认' if listening else '未运行')
        lines.append(paint(cell(LABELS[name], 14) + cell(state, 26) + endpoint('127.0.0.1', port, listening), '32' if owned and listening else '33'))
    for label, key, suffix in [('前端', 'LOCAL_FRONTEND_PORT', '/'), ('后端', 'LOCAL_BACKEND_PORT', '/api/health')]:
        port = env[key]
        if lan:
            note = '' if checks.get((lan, port), False) else '（下次启动启用内网访问）'
            lines.append(paint(f'{label}：http://{lan}:{port}{suffix} {note}', '1;32'))
        else:
            lines.append(paint(f'{label}：未检测到内网 IPv4，端口 {port}', '33'))
    lines.append(paint(f'AI 内部接口：端口 {env["LOCAL_AI_PORT"]} /health'))
    if (STATE / 'mode').exists():
        lines.append(paint('AI 模式：' + (STATE / 'mode').read_text()))
    print('\n'.join(lines), flush=True)
    return snapshot


@contextmanager
def controller_lock():
    STATE.mkdir(parents=True, exist_ok=True)
    with (STATE / 'controller.lock').open('a+b') as lock:
        if IS_WINDOWS:
            import msvcrt
            if lock.seek(0, 2) == 0:
                lock.write(b'0')
                lock.flush()
            lock.seek(0)
            try:
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError as error:
                raise RuntimeError('已有启停操作正在执行') from error
        else:
            import fcntl
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise RuntimeError('已有启停操作正在执行') from error
        try:
            yield
        finally:
            if IS_WINDOWS:
                lock.seek(0)
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock, fcntl.LOCK_UN)


def show_logs(env, service=None):
    for name in ([service] if service else APP_ORDER):
        print(f'\n[{LABELS[name]}]\n{log_tail(name, env)}')


def perform(command, demo=False, service=None):
    if command in ('status', 'logs'):
        env = config(initialize=False)
        return status(env) if command == 'status' else show_logs(env, service)
    with controller_lock():
        if command == 'toggle':
            command = 'stop' if any(alive(name) for name in APP_ORDER) else 'start'
        env = config(initialize=command == 'start')
        if command == 'start':
            start(env, 'demo' if demo else None)
        else:
            stop()
            status(env)


def enable_ansi():
    if not sys.stdout.isatty():
        return False
    if not IS_WINDOWS:
        return True
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.GetStdHandle.argtypes = [wintypes.DWORD]
    kernel.GetStdHandle.restype = wintypes.HANDLE
    kernel.GetConsoleMode.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel.SetConsoleMode.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    handle, mode = kernel.GetStdHandle(-11), wintypes.DWORD()
    return bool(kernel.GetConsoleMode(handle, ctypes.byref(mode)) and kernel.SetConsoleMode(handle, mode.value | 0x0004))


@contextmanager
def keyboard_mode():
    if IS_WINDOWS:
        yield
        return
    import termios
    import tty
    fd = sys.stdin.fileno()
    previous = termios.tcgetattr(fd)
    try:
        tty.setcbreak(fd)
        yield
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, previous)


def read_key():
    if IS_WINDOWS:
        import msvcrt
        key = msvcrt.getwch()
        if key in ('\x00', '\xe0'):
            return {'H': 'UP', 'P': 'DOWN', 'K': 'LEFT', 'M': 'RIGHT'}.get(msvcrt.getwch(), '')
    else:
        import select
        fd = sys.stdin.fileno()
        key = os.read(fd, 1).decode('ascii', errors='ignore')
        if key == '\x1b':
            suffix = ''
            for _ in range(2):
                if not select.select([fd], [], [], 0.08)[0]:
                    break
                suffix += os.read(fd, 1).decode('ascii', errors='ignore')
            return {'[A': 'UP', '[B': 'DOWN', '[D': 'LEFT', '[C': 'RIGHT', 'OA': 'UP', 'OB': 'DOWN', 'OD': 'LEFT', 'OC': 'RIGHT'}.get(suffix, 'ESC')
    if key == '\x03':
        raise KeyboardInterrupt()
    if key in ('\r', '\n'):
        return 'ENTER'
    return 'ESC' if key in ('', '\x1b') else key


def read_menu_choice(options, key_reader=None):
    if key_reader is None and (not sys.stdin.isatty() or not sys.stdout.isatty()):
        for index, label in enumerate(options, 1):
            print(f'  {index}. {label}')
        try:
            return input('输入编号并回车（直接回车取消）：').strip() or '4'
        except EOFError:
            return '4'
    selected, rendered, ansi = 0, False, enable_ansi()
    with (keyboard_mode() if key_reader is None else nullcontext()):
        while True:
            frame = [f'\033[{len(options) + 1}A'] if rendered and ansi else []
            for index, label in enumerate(options):
                clear = '\r\033[2K' if ansi else ''
                marker = '›' if index == selected else ' '
                text = f'{marker} {index + 1}. {label}'
                frame.append(clear + (('\033[1;30;102m' if index == selected else '\033[32m') + text + '\033[0m' if ansi else text) + '\n')
            frame.append(('\r\033[2K' if ansi else '') + paint('↑↓ / ←→ 选择，数字定位，Enter 确认，Esc 取消') + '\n')
            sys.stdout.write(''.join(frame))
            sys.stdout.flush()
            rendered = True
            key = (key_reader or read_key)()
            if key == 'ENTER':
                return str(selected + 1)
            if key in ('ESC', 'q', 'Q'):
                return '4'
            if key in ('UP', 'LEFT'):
                selected = (selected - 1) % len(options)
            elif key in ('DOWN', 'RIGHT'):
                selected = (selected + 1) % len(options)
            elif key in ('1', '2', '3', '4'):
                selected = int(key) - 1


def menu(demo=False):
    attempted_mutation = False
    while True:
        env = config(initialize=False)
        print(paint('\n知映 · 本地服务\n', '1;32'))
        snapshot = collect_status(env)
        status(env, snapshot)
        running = sum(bool(owner) for owner in snapshot['owners'].values())
        listening = any(snapshot['endpoints'][('127.0.0.1', port)] for port in snapshot['ports'].values())
        summary = ('项目已启动。' if running == len(APP_ORDER) else '项目部分运行。' if running else
                   '检测到服务端口已监听，进程归属待确认。' if listening else '项目未启动。')
        print(paint('\n' + summary, '1;32' if running else '33'))
        choice = read_menu_choice(['停止项目' if running else '启动项目', '刷新状态', '查看日志', '取消 / 退出'])
        if choice in ('4', 'q', 'Q'):
            print(paint('已退出菜单。' if attempted_mutation else '已退出菜单，服务状态未更改。'))
            return
        try:
            if choice == '1':
                attempted_mutation = True
                perform('stop' if running else 'start', demo=demo)
            elif choice == '2':
                continue
            elif choice == '3':
                show_logs(env)
            else:
                print('请输入 1、2、3 或 4。')
        except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired) as error:
            print('\n操作失败：' + redact(str(error), env), file=sys.stderr, flush=True)


def main():
    global COLOR
    parser = argparse.ArgumentParser(description='本地前后端启停；MySQL、Redis 为常驻服务')
    parser.add_argument('command', nargs='?', choices=['menu', 'toggle', 'start', 'stop', 'status', 'logs'], default='menu')
    parser.add_argument('service', nargs='?', choices=APP_ORDER)
    parser.add_argument('--demo', action='store_true', help='显式使用演示模式')
    args = parser.parse_args()
    COLOR = enable_ansi()
    if args.command == 'menu':
        menu(args.demo)
    else:
        perform(args.command, args.demo, args.service)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired) as error:
        print('错误：' + str(error), file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print('\n已取消。', file=sys.stderr)
        sys.exit(130)
