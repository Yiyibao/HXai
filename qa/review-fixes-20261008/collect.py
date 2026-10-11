"""Compatibility entry point: an explicit method/UUID/APK run context is mandatory.
Historical fixed filenames and manifests never count as a fresh execution.
The original collector is preserved in the independent fix QA directory.
"""
import runpy
from pathlib import Path

if __name__ == '__main__':
    runpy.run_path(str(Path(__file__).resolve().parents[2] / 'scripts/collect_run_evidence.py'), run_name='__main__')
