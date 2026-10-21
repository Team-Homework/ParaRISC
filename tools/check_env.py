#!/usr/bin/env python3
"""Check Chisel, Verilator, the AXI simulator, ASAP7, ABC and OpenSTA together."""
import argparse
import errno
import json
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mill", default="mill", help="Mill executable")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = root / "build" / "env-check"
    rtl = output / "rtl"
    output.mkdir(parents=True, exist_ok=True)

    def run(*command):
        print("[env-check] " + " ".join(map(str, command)), flush=True)
        command = list(map(str, command))
        try:
            subprocess.run(command, cwd=root, check=True)
        except OSError as error:
            if error.errno != errno.ENOEXEC:
                raise
            # The official cross-platform Mill launcher has a shell preamble
            # without a shebang. Match the fallback used by Make's shell.
            launcher = shutil.which(command[0]) or command[0]
            subprocess.run(["/bin/sh", launcher, *command[1:]], cwd=root, check=True)

    run(args.mill, "--no-server", "chisel.run", "--smoke", "--out", rtl)
    image = output / "memory.data"
    image.write_text("@00000000\n2a 00 00 00\n")
    common = [f"FILELIST={rtl / 'filelist.f'}", f"BUILD={output / 'simulation'}"]
    run("make", "--no-print-directory", "build", *common)
    run("make", "--no-print-directory", "run", *common,
        f"SIM={output / 'simulation' / 'sim'}", f"PROGRAM={image}", "EXPECTED=42",
        "MAX_CYCLES=1000", f"WAVE={output / 'trace.vcd'}", f"LOG={output / 'simulation.log'}")
    for mode in ("opt", "diagnose"):
        run("make", "--no-print-directory", "synth", *common,
            f"SYNTH_OUT={output / 'synth'}", f"MODE={mode}")
        report = json.loads((output / "synth" / mode / "report.json").read_text())
        if not report["timing"]["timing_analyzed"] or not report["timing"]["critical_paths"]:
            raise RuntimeError(f"{mode}: no analyzed timing paths")
        if len(report["libraries"]) != 5 or report["area"]["area_um2"] <= 0:
            raise RuntimeError(f"{mode}: invalid library or area report")
    print("[env-check] PASS: Chisel -> Verilog -> simulation (42) -> ASAP7/ABC -> OpenSTA.")
    print("[env-check] This checks the environment only; the CPU starter is not implemented.")


if __name__ == "__main__":
    main()
