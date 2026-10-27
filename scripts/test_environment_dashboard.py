"""Environment checks and live installation output, without package installation."""
import contextlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from scripts import local_runner as runner

class DashboardTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name)
        self.env={'DB_PASSWORD':'test-secret-password'}
        self.environment=[{'id':'python','label':'Python','state':'已安装','version':'Python 3.12','path':'/tools/python','requirement':'3.11+'}]
        self.dependencies=[{'label':'前端依赖','state':'待安装','path':'node_modules'}]
        scope=patch.object(runner,'STATE',self.root/'state');scope.start();self.addCleanup(scope.stop)

    def versions(self,old=False):
        data={'java':'openjdk version "17.0.1"' if old else 'openjdk version "21.0.8"',
              'node':'v24.2.0','npm':'11.1.0','mvn':'Apache Maven 3.9.9','mysql':'mysql Ver 8.4.0',
              'redis-cli':'redis-cli 7.2.0','curl':'curl 8.10.0'}
        def command(argv,**kwargs):
            return subprocess.CompletedProcess(argv,0,stdout=data.get(Path(argv[0]).name,'Python 3.12.1'),stderr='')
        return command

    def test_reports_usable_and_incompatible_versions(self):
        for old in (False,True):
            with patch.object(runner.platform,'system',return_value='Linux'),patch.object(runner,'IS_WINDOWS',False), \
                    patch.object(runner.shutil,'which',side_effect=lambda name:str(name) if str(name).startswith('/') else '/tools/'+str(name)), \
                    patch.object(runner.subprocess,'run',side_effect=self.versions(old)):
                rows=runner.inspect_environment({})
            self.assertEqual(len(rows),8)
            self.assertEqual(next(r for r in rows if r['id']=='java')['state'],'版本不兼容' if old else '已安装')
            self.assertTrue(all(r['state']=='已安装' for r in rows if r['id']!='java'))

    def test_reports_missing_tools_and_timeout_without_crashing_menu(self):
        def probe(argv,**kwargs):raise subprocess.TimeoutExpired(argv,8)
        with patch.object(runner.platform,'system',return_value='Linux'),patch.object(runner,'IS_WINDOWS',False), \
                patch.object(runner.shutil,'which',side_effect=lambda name:None if str(name)=='redis-cli' else str(name)), \
                patch.object(runner.subprocess,'run',side_effect=probe):
            rows=runner.inspect_environment({})
        self.assertEqual(next(r for r in rows if r['id']=='redis')['state'],'缺失')
        self.assertEqual(next(r for r in rows if r['id']=='node')['state'],'检测失败')

    def test_child_output_is_streamed_saved_redacted_and_failure_is_reported(self):
        with contextlib.redirect_stdout(io.StringIO()) as output,patch.object(runner,'enable_ansi',return_value=False):
            with runner.installation_display(self.env,self.environment,self.dependencies) as display:
                runner.run([sys.executable,'-c','print("download completed")'],cwd=self.root)
                print('password=test-secret-password')
                with self.assertRaisesRegex(RuntimeError,'退出码 3'):
                    runner.run([sys.executable,'-c','print("download failed");raise SystemExit(3)'],cwd=self.root)
        text=display.log_path.read_text()
        self.assertIn('download completed',output.getvalue())
        self.assertIn('download failed',text)
        self.assertNotIn('test-secret-password',text)
        self.assertIsNone(runner.INSTALL_OUTPUT)

    def test_tty_dashboard_keeps_header_and_limits_visible_logs(self):
        with contextlib.redirect_stdout(io.StringIO()) as output,patch.object(runner,'enable_ansi',return_value=True), \
                patch.object(runner.shutil,'get_terminal_size',return_value=(80,18)):
            with runner.installation_display(self.env,self.environment,self.dependencies) as display:
                print('\n'.join('log '+str(n) for n in range(100)))
                display.draw(force=True)
        frame=output.getvalue().split('\033[H\033[2J')[-1]
        self.assertIn('运行环境',frame)
        self.assertIn('log 99',frame)
        self.assertNotIn('log 0\n',frame)
        self.assertLessEqual(len(frame.splitlines()),18)

    def test_dependency_details_menu_is_reachable_without_mutating_services(self):
        env=dict(runner.DEFAULTS)
        snapshot={'owners':{name:None for name in runner.APP_ORDER},'ports':runner.ports(env),'endpoints':{('127.0.0.1',p):False for p in runner.ports(env).values()}}
        with patch.object(runner,'config',return_value=env),patch.object(runner,'collect_status',return_value=snapshot), \
                patch.object(runner,'inspect_environment',return_value=self.environment),patch.object(runner,'inspect_project_dependencies',return_value=self.dependencies), \
                patch.object(runner,'status'),patch.object(runner,'clear_menu_screen'),patch.object(runner,'wait_for_menu_return'), \
                patch.object(runner,'read_menu_choice',side_effect=['5','4']),patch.object(runner,'show_environment_details') as details, \
                patch.object(runner,'perform') as mutate,contextlib.redirect_stdout(io.StringIO()):
            runner.menu()
        details.assert_called_once_with(self.environment,self.dependencies)
        mutate.assert_not_called()

    def test_start_blocks_before_install_when_software_is_missing(self):
        missing=[dict(self.environment[0],label='Java',id='java',state='缺失')]
        with patch.object(runner,'inspect_environment',return_value=missing),patch.object(runner,'run') as install:
            with self.assertRaisesRegex(RuntimeError,'Java：缺失'):
                runner.prepare({})
        install.assert_not_called()

if __name__=='__main__': unittest.main()
