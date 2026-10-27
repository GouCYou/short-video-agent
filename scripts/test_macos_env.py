"""Exercise shell bootstrap decisions with mocked tools, without installing software."""
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class MacEnvironmentTests(unittest.TestCase):
    def shell(self, code):
        return subprocess.run(['bash','-eu','-c','source scripts/macos_env.sh\n'+code],cwd=ROOT,capture_output=True,text=True,timeout=10)

    def test_compatible_tools_are_reused(self):
        result=self.shell('''
python3.12(){ :; }; java(){ echo 'openjdk version "21.0.8"'; }
node(){ :; }; npm(){ :; }; mysql(){ echo 'mysql Ver 8.0.40'; }
mysqld(){ :; }; redis-server(){ :; }; redis-cli(){ :; }; mvn(){ :; }
macos_missing_environment
''')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout,'')

    def test_incompatible_versions_request_pinned_formulae(self):
        result=self.shell('''
java(){ echo 'openjdk version "17.0.1"'; }; node(){ return 1; }
mysql(){ echo 'mysql Ver 9.0.0'; }
macos_missing_environment
''')
        self.assertEqual(result.returncode,0,result.stderr)
        for formula in ['openjdk@21','node@24','mysql@8.4']:
            self.assertIn(formula,result.stdout)

    def test_non_mac_setup_stops_before_install(self):
        result=self.shell('''
uname(){ echo Linux; }
macos_install_brew(){ echo UNEXPECTED_INSTALL; }
macos_install_environment
''')
        self.assertNotEqual(result.returncode,0)
        self.assertNotIn('UNEXPECTED_INSTALL',result.stdout)

    def test_install_retries_official_and_reuses_existing_brew(self):
        result=self.shell('''
uname(){ echo Darwin; }; id(){ echo 501; }
macos_find_brew(){ echo fakebrew; }; macos_activate_environment(){ :; }
macos_missing_environment(){ if [ "${installed:-0}" = 0 ]; then echo redis; fi; }
macos_install_brew(){ echo UNEXPECTED_INSTALL; return 1; }
fakebrew(){
 if [ -n "${HOMEBREW_BOTTLE_DOMAIN:-}" ]; then echo MIRROR; return 1; fi
 echo OFFICIAL; installed=1
}
# Official fallback runs in a subshell, so simulate final environment on the second check.
checks=0
macos_missing_environment(){ if [ ! -e "$fixture_dir/done" ]; then echo redis; fi; }
fakebrew(){
 if [ -n "${HOMEBREW_BOTTLE_DOMAIN:-}" ]; then echo MIRROR; return 1; fi
 echo OFFICIAL; touch "$fixture_dir/done"
}
fixture_dir=$(mktemp -d)
trap 'rm -rf "$fixture_dir"' EXIT
macos_install_environment
''')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('MIRROR',result.stdout)
        self.assertIn('OFFICIAL',result.stdout)
        self.assertNotIn('UNEXPECTED_INSTALL',result.stdout)

    def test_missing_homebrew_is_installed_before_dependencies(self):
        result=self.shell('''
uname(){ echo Darwin; }; id(){ echo 501; }
fixture_dir=$(mktemp -d)
trap 'rm -rf "$fixture_dir"' EXIT
macos_find_brew(){ [ -f "$fixture_dir/brew" ] || return 1; echo fakebrew; }
macos_install_brew(){ echo INSTALL_BREW; touch "$fixture_dir/brew"; }
macos_activate_environment(){ :; }
macos_missing_environment(){ [ -e "$fixture_dir/redis" ] || echo redis; return 0; }
fakebrew(){ echo INSTALL_REDIS; touch "$fixture_dir/redis"; }
macos_install_environment
''')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertLess(result.stdout.index('INSTALL_BREW'),result.stdout.index('INSTALL_REDIS'))

    def test_failed_install_does_not_report_ready(self):
        result=self.shell('''
uname(){ echo Darwin; }; id(){ echo 501; }
macos_find_brew(){ echo fakebrew; }; macos_activate_environment(){ :; }
macos_missing_environment(){ echo redis; }; fakebrew(){ return 1; }
macos_install_environment
''')
        self.assertNotEqual(result.returncode,0)
        self.assertIn('安装 redis 失败',result.stderr)
        self.assertNotIn('运行环境安装完成',result.stdout)

if __name__=='__main__': unittest.main()
