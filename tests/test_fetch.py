#!/usr/bin/env python3
"""Generate and simulate the standalone fetch frontend (Mill and Verilator)."""
import argparse
import os
from pathlib import Path
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build" / "fetch"


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-generate", action="store_true",
                        help="simulate existing build/fetch/rtl/Frontend.sv")
    args = parser.parse_args()
    if not args.skip_generate:
        run(*shlex.split(os.environ.get("MILL", "mill")), "--no-server",
            "chisel.runMain", "pararisc.fetch.GenerateFetch", BUILD / "rtl")
    run(os.environ.get("VERILATOR", "verilator"), "--cc", "--exe", "--build",
        "--assert", "-Wno-fatal", "--top-module", "Frontend", "-j", "4",
        "--Mdir", BUILD / "obj", BUILD / "rtl" / "Frontend.sv",
        ROOT / "scripts" / "ram" / "sram_fakeram.sv", ROOT / "tests" / "fetch_test.cpp")
    run(BUILD / "obj" / "VFrontend")
