#!/usr/bin/env python3
"""Catch the preloader (or reuse a live DA session) and shut the DA down with bootmode=2 (FASTBOOT),
so LK boots straight into fastboot. No partition is read or written."""
import sys, os, runpy
MTK = "~/Desktop/Bigme/work/ref/mtkclient"
sys.path.insert(0, MTK); os.chdir(MTK)
from mtkclient.Library.DA import mtk_daloader
_orig = mtk_daloader.DAloader.shutdown
def _shutdown_fastboot(self, bootmode=0):
    print("[wrapper] DA shutdown with bootmode=2 (FASTBOOT)")
    return _orig(self, bootmode=2)
mtk_daloader.DAloader.shutdown = _shutdown_fastboot
sys.argv = ["mtk.py", "reset"]
runpy.run_path(os.path.join(MTK, "mtk.py"), run_name="__main__")
