"""Controller regressions. All service mutations and downloads are mocked."""
import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import types
import unittest
from unittest.mock import Mock, patch
import zipfile

from scripts import local_runner as runner


class ControllerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='agent runner spaces ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / '.runtime/local'
        self.env = dict(runner.DEFAULTS, DB_NAME='test', DB_USERNAME='root', DB_PASSWORD='', REDIS_PASSWORD='',
                        AI_INTERNAL_KEY='private-test-secret', SEED_ADMIN_USERNAME='admin', SEED_ADMIN_PASSWORD='test-secret')
        for name, value in [('ROOT', self.root), ('STATE', self.state)]:
            item = patch.object(runner, name, value)
            item.start()
            self.addCleanup(item.stop)
        self.addCleanup(patch.stopall)

    def test_opening_readonly_configuration_creates_no_files(self):
        env = runner.config(initialize=False)
        self.assertEqual(env['LOCAL_FRONTEND_PORT'], '5178')
        self.assertFalse((self.root / '.env.local').exists())
        self.assertFalse(self.state.exists())

    def test_start_configuration_creates_relative_state_and_credentials(self):
        env = runner.config()
        self.assertTrue((self.root / '.env.local').is_file())
        self.assertTrue((self.state / 'credentials.json').is_file())
        self.assertTrue(env['AI_INTERNAL_KEY'])

    def test_utf8_bom_and_quoted_paths_in_environment(self):
        path = self.root / '.env.local'
        path.write_text('\ufeffLOCAL_QDRANT_BIN="tools/目录 with spaces/qdrant.exe"\n', encoding='utf-8')
        self.assertEqual(runner.read_env(path)['LOCAL_QDRANT_BIN'], 'tools/目录 with spaces/qdrant.exe')

    def test_cancel_running_and_stopped_menu_never_mutates_services(self):
        for running in [False, True]:
            with self.subTest(running=running), patch.object(runner, 'config', return_value=self.env) as config, \
                    patch.object(runner, 'alive', return_value={'pid': 1} if running else None), \
                    patch.object(runner, 'status'), patch.object(runner, 'perform') as perform, \
                    patch.object(runner, 'read_menu_choice', return_value='4') as menu, contextlib.redirect_stdout(io.StringIO()):
                runner.menu()
                perform.assert_not_called()
                config.assert_called_with(initialize=False)
                self.assertEqual(menu.call_args.args[0][0], '停止项目' if running else '启动项目')

    def test_menu_confirm_dispatches_start_or_stop_only_after_selection(self):
        for running in [False, True]:
            with self.subTest(running=running), patch.object(runner, 'config', return_value=self.env), \
                    patch.object(runner, 'alive', return_value={'pid': 1} if running else None), \
                    patch.object(runner, 'status'), patch.object(runner, 'perform') as perform, \
                    patch.object(runner, 'read_menu_choice', side_effect=['1', '4']), contextlib.redirect_stdout(io.StringIO()):
                runner.menu()
                perform.assert_called_once_with('stop' if running else 'start', demo=False)

    def test_menu_reports_failure_and_remains_available_for_cancel(self):
        output = io.StringIO()
        with patch.object(runner, 'config', return_value=self.env), patch.object(runner, 'alive', return_value=None), \
                patch.object(runner, 'status'), patch.object(runner, 'perform', side_effect=RuntimeError('Vue 前端端口 5178 已占用')), \
                patch.object(runner, 'read_menu_choice', side_effect=['1', '4']), \
                contextlib.redirect_stderr(output), contextlib.redirect_stdout(io.StringIO()):
            runner.menu()
        self.assertIn('5178 已占用', output.getvalue())

    def test_arrow_navigation_numbers_and_enter(self):
        options = ['启动', '刷新', '日志', '取消']
        for keys, expected in [(['RIGHT', 'ENTER'], '2'), (['DOWN', 'DOWN', 'ENTER'], '3'),
                               (['LEFT', 'ENTER'], '4'), (['UP', 'ENTER'], '4'),
                               (['RIGHT', 'LEFT', 'ENTER'], '1'), (['4', 'ENTER'], '4'),
                               (['3', 'ENTER'], '3'), (['ESC'], '4')]:
            with self.subTest(keys=keys), patch.object(runner, 'enable_ansi', return_value=False), contextlib.redirect_stdout(io.StringIO()):
                iterator = iter(keys)
                self.assertEqual(runner.read_menu_choice(options, lambda: next(iterator)), expected)

    def test_eof_in_noninteractive_menu_is_cancel(self):
        with patch('builtins.input', side_effect=EOFError), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(runner.read_menu_choice(['启动', '刷新', '日志', '取消']), '4')

    def test_default_cli_command_is_menu_not_toggle(self):
        with patch.object(sys, 'argv', ['local_runner.py']), patch.object(runner, 'menu') as menu, patch.object(runner, 'perform') as perform:
            runner.main()
        menu.assert_called_once_with(False)
        perform.assert_not_called()

    def test_posix_identity_is_independent_of_terminal_locale_width_and_timezone(self):
        result = subprocess.CompletedProcess([], 0, stdout='Tue Oct 27 11:20:04 2026     python app.py\n')
        with patch.object(runner, 'IS_WINDOWS', False), patch.dict(runner.os.environ, {'LC_ALL': 'zh_CN.UTF-8', 'COLUMNS': '80', 'TZ': 'UTC'}), \
                patch.object(runner.subprocess, 'run', return_value=result) as execute:
            value = runner.identity(1234)
        self.assertEqual(value, result.stdout.strip())
        self.assertIn('-ww', execute.call_args.args[0])
        env = execute.call_args.kwargs['env']
        self.assertEqual(env['LC_ALL'], 'C')
        self.assertNotIn('COLUMNS', env)
        self.assertNotIn('TZ', env)

    def test_identity_padding_is_normalized_but_creation_time_and_command_are_preserved(self):
        self.assertTrue(runner.same_identity('Tue Oct 27 11:20:04 2026 python app.py', 'Tue Oct 27 11:20:04 2026     python app.py'))
        self.assertFalse(runner.same_identity('Tue Oct 27 11:20:05 2026 python app.py', 'Tue Oct 27 11:20:04 2026 python app.py'))
        self.assertFalse(runner.same_identity('Tue Oct 27 11:20:04 2026 python other.py', 'Tue Oct 27 11:20:04 2026 python app.py'))

    def test_lan_addresses_exclude_loopback_and_vpn_benchmark_addresses(self):
        for ip in ['192.168.31.102', '10.0.0.2', '172.16.4.5']:
            self.assertTrue(runner.private_ipv4(ip))
        for ip in ['127.0.0.1', '198.18.0.1', '169.254.1.1', '0.0.0.0', '::1', 'invalid']:
            self.assertFalse(runner.private_ipv4(ip))
        self.assertEqual(runner.lan_ipv4({'LOCAL_LAN_IP': '192.168.1.2'}), '192.168.1.2')
        with self.assertRaisesRegex(RuntimeError, 'LOCAL_LAN_IP'):
            runner.lan_ipv4({'LOCAL_LAN_IP': '127.0.0.1'})

    def test_mac_lan_detection_prefers_active_physical_adapter(self):
        interfaces = 'utun4: flags=UP\n inet 198.18.0.1\nen1: flags=UP\n inet 192.168.7.1\n status: inactive\nen0: flags=UP\n inet 192.168.31.102 netmask 0xffffff00\n status: active\n'
        with patch.object(runner.platform, 'system', return_value='Darwin'), patch.object(runner.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, stdout=interfaces)):
            self.assertEqual(runner.lan_ipv4({}), '192.168.31.102')

    def test_status_probes_in_parallel_once_and_render_does_no_io(self):
        # All twelve socket probes must enter together; serial detection deadlocks.
        barrier = threading.Barrier(12)
        def probe(host, port):
            barrier.wait(timeout=2)
            return True
        with patch.object(runner, 'lan_ipv4', return_value='192.168.31.102'), \
                patch.object(runner, 'tcp', side_effect=probe) as tcp, \
                patch.object(runner, 'alive', return_value={'pid':1234}) as alive:
            snapshot = runner.collect_status(self.env)
        endpoints = [call.args for call in tcp.call_args_list]
        self.assertEqual(len(endpoints),12)
        self.assertEqual(len(set(endpoints)),12)
        self.assertEqual(alive.call_count,4)
        with patch.object(runner, 'tcp') as tcp, patch.object(runner, 'alive') as alive, \
                patch.object(runner, 'lan_ipv4') as lan, contextlib.redirect_stdout(io.StringIO()):
            runner.status(self.env,snapshot)
        tcp.assert_not_called()
        alive.assert_not_called()
        lan.assert_not_called()

    def test_status_is_green_and_uses_lan_ipv4_without_loopback_urls(self):
        output = io.StringIO()
        with patch.object(runner, 'COLOR', True), patch.object(runner, 'lan_ipv4', return_value='192.168.31.102'), \
                patch.object(runner, 'tcp', return_value=True), patch.object(runner, 'alive', return_value={'pid': 1234}), contextlib.redirect_stdout(output):
            runner.status(self.env)
        text = output.getvalue()
        self.assertIn('\033[32m', text)
        self.assertIn('http://192.168.31.102:5178/', text)
        self.assertIn('http://192.168.31.102:18080/api/health', text)
        self.assertNotIn('127.0.0.1', text)
        self.assertNotIn('归属待确认', text)

    def test_unconfirmed_listening_ports_are_not_reported_as_project_stopped(self):
        output = io.StringIO()
        with patch.object(runner, 'config', return_value=self.env), patch.object(runner, 'status'), \
                patch.object(runner, 'alive', return_value=None), patch.object(runner, 'tcp', return_value=True), \
                patch.object(runner, 'read_menu_choice', return_value='4'), contextlib.redirect_stdout(output):
            runner.menu()
        self.assertIn('检测到服务端口已监听', output.getvalue())
        self.assertNotIn('项目未启动', output.getvalue())

    def test_status_does_not_initialize_configuration_or_acquire_mutation_lock(self):
        with patch.object(runner, 'config', return_value=self.env) as config, patch.object(runner, 'status') as status, patch.object(runner, 'controller_lock') as lock:
            runner.perform('status')
        config.assert_called_once_with(initialize=False)
        status.assert_called_once_with(self.env)
        lock.assert_not_called()

    def test_windows_lock_uses_one_byte_without_importing_fcntl(self):
        fake = types.SimpleNamespace(LK_NBLCK=1, LK_UNLCK=2, locking=Mock())
        with patch.object(runner, 'IS_WINDOWS', True), patch.dict(sys.modules, {'msvcrt': fake}):
            with runner.controller_lock():
                self.assertEqual((self.state / 'controller.lock').read_bytes(), b'0')
        self.assertEqual([call.args[1:] for call in fake.locking.call_args_list], [(1, 1), (2, 1)])

    def test_posix_controller_lock_rejects_concurrent_mutation(self):
        if runner.IS_WINDOWS:
            self.skipTest('POSIX only')
        with runner.controller_lock():
            with self.assertRaisesRegex(RuntimeError, '已有启停操作'):
                with runner.controller_lock():
                    self.fail('second controller acquired lock')

    def test_windows_command_quotes_batch_executable_with_spaces(self):
        with patch.object(runner, 'IS_WINDOWS', True), patch.object(runner.shutil, 'which', return_value=r'C:\Program Files\nodejs\npm.cmd'):
            command = runner.command_line(['npm', 'ci'])
        self.assertIn('/d /s /c ""C:\\Program Files\\nodejs\\npm.cmd" ci"', command)

    def test_windows_arrow_key_decoding(self):
        fake = types.SimpleNamespace(getwch=Mock(side_effect=['\xe0', 'M', '\r']))
        with patch.object(runner, 'IS_WINDOWS', True), patch.dict(sys.modules, {'msvcrt': fake}):
            self.assertEqual(runner.read_key(), 'RIGHT')
            self.assertEqual(runner.read_key(), 'ENTER')

    def test_windows_stop_checks_identity_and_terminates_only_owned_tree(self):
        record = {'pid': 4321, 'identity': 'owned-process'}
        with patch.object(runner, 'IS_WINDOWS', True), patch.object(runner, 'require', side_effect=lambda x: x), \
                patch.object(runner, 'identity', return_value='owned-process'), patch.object(runner, 'alive', return_value=record), \
                patch.object(runner.subprocess, 'run', side_effect=[subprocess.CompletedProcess([], 1), subprocess.CompletedProcess([], 0)]) as execute:
            runner.terminate_record('frontend', record)
        self.assertEqual(execute.call_args.args[0], ['taskkill.exe', '/PID', '4321', '/T', '/F'])
        with patch.object(runner, 'identity', return_value='recycled-pid'), patch.object(runner.subprocess, 'run') as execute:
            runner.terminate_record('frontend', record, force=True)
            execute.assert_not_called()

    def test_existing_resident_services_are_not_restarted(self):
        with patch.object(runner, 'tcp', return_value=True), patch.object(runner.subprocess, 'run') as execute:
            runner.resident('MySQL', '127.0.0.1', '3306', 'mysql@8.0', self.env)
            runner.resident('Redis', '127.0.0.1', '6379', 'redis', self.env)
        execute.assert_not_called()

    def test_windows_resident_auto_detection_and_start(self):
        query = subprocess.CompletedProcess([], 0, stdout='"MySQL80"', stderr='')
        started = subprocess.CompletedProcess([], 0, stdout=b'', stderr=b'')
        with patch.object(runner, 'IS_WINDOWS', True), patch.object(runner, 'tcp', side_effect=[False, True]), \
                patch.object(runner, 'require', side_effect=lambda x: x), patch.object(runner.subprocess, 'run', side_effect=[query, started]) as execute, \
                contextlib.redirect_stdout(io.StringIO()):
            runner.resident('MySQL', '127.0.0.1', '3306', 'mysql@8.0', self.env)
        self.assertEqual(execute.call_args.args[0], ['sc.exe', 'start', 'MySQL80'])

    def test_windows_ambiguous_services_require_explicit_name(self):
        query = subprocess.CompletedProcess([], 0, stdout='["MySQL80","MySQL84"]', stderr='')
        with patch.object(runner, 'require', return_value='powershell.exe'), patch.object(runner.subprocess, 'run', return_value=query):
            with self.assertRaisesRegex(RuntimeError, 'LOCAL_MYSQL_SERVICE'):
                runner.windows_service('MySQL', self.env)

    def test_windows_explicit_service_does_not_need_discovery(self):
        with patch.object(runner.subprocess, 'run') as execute:
            self.assertEqual(runner.windows_service('Redis', dict(self.env, LOCAL_REDIS_SERVICE='CustomRedis')), 'CustomRedis')
        execute.assert_not_called()

    def test_windows_service_start_permission_error_is_readable(self):
        error = subprocess.CompletedProcess([], 5, stdout=b'Access is denied.', stderr=b'')
        with patch.object(runner, 'IS_WINDOWS', True), patch.object(runner, 'tcp', return_value=False), \
                patch.object(runner, 'require', side_effect=lambda x: x), patch.object(runner.subprocess, 'run', return_value=error), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, 'Access is denied'):
                runner.resident('Redis', '127.0.0.1', '6379', 'redis', dict(self.env, LOCAL_REDIS_SERVICE='Redis'))

    def test_startup_failure_contains_log_cause_without_secrets(self):
        self.state.mkdir(parents=True)
        (self.state / 'backend.log').write_text('Access denied for user root; password=private-test-secret\n', encoding='utf-8')
        error = runner.startup_error('backend', '进程退出，代码 1', self.env)
        self.assertIn('Java 后端 启动失败', error)
        self.assertIn('Access denied for user root', error)
        self.assertNotIn('private-test-secret', error)

    def test_early_frontend_exit_reports_actual_log(self):
        self.state.mkdir(parents=True)
        (self.state / 'frontend.log').write_text('Error: Port 5178 is already in use\n', encoding='utf-8')
        process = Mock(returncode=1)
        process.poll.return_value = 1
        with patch.object(runner.subprocess, 'Popen', return_value=process), patch.object(runner.time, 'sleep'):
            with self.assertRaisesRegex(RuntimeError, 'Port 5178 is already in use'):
                runner.spawn('frontend', ['node', 'vite.js'], self.env, self.root)

    def test_readiness_timeout_contains_endpoint_and_recent_cause(self):
        self.state.mkdir(parents=True)
        (self.state / 'backend.log').write_text('Failed to configure a DataSource\n', encoding='utf-8')
        with self.assertRaisesRegex(RuntimeError, 'Failed to configure a DataSource'):
            runner.wait_http('backend', 'http://127.0.0.1:18080/api/health', seconds=0, env=self.env)

    def test_external_port_conflict_does_not_install_or_stop_others(self):
        with patch.object(runner, 'alive', return_value=None), patch.object(runner, 'tcp', return_value=True), \
                patch.object(runner, 'prepare') as prepare, patch.object(runner, 'stop') as stop:
            with self.assertRaisesRegex(RuntimeError, '端口.*被其他进程占用'):
                runner.start(self.env)
        prepare.assert_not_called()
        stop.assert_called_once_with([], announce=False)

    def test_backend_failure_rolls_back_only_new_project_services(self):
        self.state.mkdir(parents=True)
        def ready(name, *args, **kwargs):
            if name == 'backend':
                raise RuntimeError('Java 后端连接数据库失败')
        mysql = subprocess.CompletedProcess([], 0, stdout='8.0.40', stderr='')
        redis = subprocess.CompletedProcess([], 0, stdout='PONG', stderr='')
        with patch.object(runner, 'alive', return_value=None), patch.object(runner, 'tcp', return_value=False), \
                patch.object(runner, 'prepare', return_value=(Path('python'), Path('backend.jar'))), \
                patch.object(runner, 'qdrant_bin', return_value=Path('qdrant')), patch.object(runner, 'resident'), \
                patch.object(runner, 'spawn'), patch.object(runner, 'wait_http', side_effect=ready), patch.object(runner, 'run'), \
                patch.object(runner.subprocess, 'run', side_effect=[mysql, redis]), patch.object(runner, 'stop') as stop:
            with self.assertRaisesRegex(RuntimeError, '连接数据库失败'):
                runner.start(self.env)
        stop.assert_called_once_with(['qdrant', 'ai', 'backend'], announce=False)

    def test_next_start_listens_on_lan_for_web_services_only(self):
        self.state.mkdir(parents=True)
        mysql = subprocess.CompletedProcess([], 0, stdout='8.0.40', stderr='')
        redis = subprocess.CompletedProcess([], 0, stdout='PONG', stderr='')
        with patch.object(runner, 'alive', return_value=None), patch.object(runner, 'tcp', return_value=False), \
                patch.object(runner, 'prepare', return_value=(Path('python'), Path('backend.jar'))), \
                patch.object(runner, 'qdrant_bin', return_value=Path('qdrant')), patch.object(runner, 'resident'), \
                patch.object(runner, 'spawn') as spawn, patch.object(runner, 'wait_http'), patch.object(runner, 'run'), \
                patch.object(runner.subprocess, 'run', side_effect=[mysql, redis]), patch.object(runner, 'status'), contextlib.redirect_stdout(io.StringIO()):
            runner.start(self.env)
        calls = {c.args[0]: c for c in spawn.call_args_list}
        self.assertEqual(calls['backend'].args[2]['SERVER_ADDRESS'], '0.0.0.0')
        self.assertEqual(calls['frontend'].args[1][-5:-3], ['--host', '0.0.0.0'])
        ai = calls['ai'].args[1]
        self.assertEqual(ai[ai.index('--host') + 1], '127.0.0.1')

    def test_windows_qdrant_zip_and_relative_override(self):
        binary_dir = self.state / 'bin'
        binary_dir.mkdir(parents=True)
        with zipfile.ZipFile(binary_dir / 'qdrant.zip', 'w') as archive:
            archive.writestr('qdrant.exe', b'test fixture, not executable')
        with patch.object(runner, 'IS_WINDOWS', True), patch.object(runner.platform, 'machine', return_value='AMD64'), \
                patch.object(runner.shutil, 'which', return_value=None), patch.object(runner, 'require', return_value='curl.exe'), \
                patch.object(runner.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as download, contextlib.redirect_stdout(io.StringIO()):
            path = runner.qdrant_bin({})
        self.assertEqual(path.name, 'qdrant.exe')
        self.assertIn('qdrant-x86_64-pc-windows-msvc.zip', download.call_args.args[0][-3])
        self.assertEqual(runner.qdrant_bin({'LOCAL_QDRANT_BIN': str(path.relative_to(self.root))}), path.resolve())

    def test_existing_project_is_not_rebuilt_on_duplicate_start(self):
        with patch.object(runner, 'alive', return_value={'pid': 1}), patch.object(runner, 'tcp', return_value=True), \
                patch.object(runner, 'prepare') as prepare, patch.object(runner, 'status'), contextlib.redirect_stdout(io.StringIO()):
            runner.start(self.env)
        prepare.assert_not_called()

    def test_missing_backend_executable_is_reported_with_service_name(self):
        self.state.mkdir(parents=True)
        with patch.object(runner.subprocess, 'Popen', side_effect=FileNotFoundError('java executable missing')):
            with self.assertRaisesRegex(RuntimeError, 'Java 后端 启动失败：java executable missing'):
                runner.spawn('backend', ['java', '-jar', 'backend.jar'], self.env, self.root)

    def test_corrupt_windows_package_does_not_leave_a_cached_executable(self):
        (self.state / 'bin').mkdir(parents=True)
        archive = self.state / 'bin/qdrant.zip'
        archive.write_bytes(b'corrupt download fixture')
        with patch.object(runner, 'IS_WINDOWS', True), patch.object(runner.platform, 'machine', return_value='AMD64'), \
                patch.object(runner.shutil, 'which', return_value=None), patch.object(runner, 'require', return_value='curl.exe'), \
                patch.object(runner.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, '安装包解压失败'):
                runner.qdrant_bin({})
        self.assertFalse(archive.exists())
        self.assertFalse((self.state / 'bin/qdrant.exe').exists())


if __name__ == '__main__':
    unittest.main()
