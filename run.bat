:; exec bash "$(dirname "$0")/run.sh" "$@"; exit $?
@echo off
setlocal
chcp 65001 >nul
pushd "%~dp0" || exit /b 1
set "PYTHONUTF8=1"
set "PYTHONIOENCODING=utf-8"
set "RUNNER_PYTHON="
set "RUNNER_PYTHON_ARGS="
if defined PYTHON goto custom_python

py -3.12 -c "import sys; sys.exit(sys.version_info < (3, 11))" >nul 2>&1
if not errorlevel 1 (
  set "RUNNER_PYTHON=py"
  set "RUNNER_PYTHON_ARGS=-3.12"
  goto run_controller
)
py -3 -c "import sys; sys.exit(sys.version_info < (3, 11))" >nul 2>&1
if not errorlevel 1 (
  set "RUNNER_PYTHON=py"
  set "RUNNER_PYTHON_ARGS=-3"
  goto run_controller
)
python -c "import sys; sys.exit(sys.version_info < (3, 11))" >nul 2>&1
if not errorlevel 1 (
  set "RUNNER_PYTHON=python"
  goto run_controller
)
goto missing_python

:custom_python
"%PYTHON%" -c "import sys; sys.exit(sys.version_info < (3, 11))" >nul 2>&1
if errorlevel 1 goto missing_python
set "RUNNER_PYTHON=%PYTHON%"

:run_controller
"%RUNNER_PYTHON%" %RUNNER_PYTHON_ARGS% "scripts\local_runner.py" %*
set "RUNNER_EXIT_CODE=%ERRORLEVEL%"
if not "%RUNNER_EXIT_CODE%"=="0" if "%~1"=="" pause
popd
exit /b %RUNNER_EXIT_CODE%

:missing_python
echo 需要 Python 3.11 或更高版本，推荐安装 Python 3.12 并勾选添加到 PATH。
echo 也可以通过 PYTHON 指定已安装的解释器完整路径。
if "%~1"=="" pause
popd
exit /b 1
