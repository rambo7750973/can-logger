@echo off
rem 用法: tools\parse_canlog.py D:\can_20261030_101233.log
rem 需要 Python 3.8+
python "%~dp0parse_canlog.py" %*
