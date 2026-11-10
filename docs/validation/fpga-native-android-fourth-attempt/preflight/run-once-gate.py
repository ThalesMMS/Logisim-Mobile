import json, os, sys
from pathlib import Path
root = Path.cwd()
stage = root / "outputs/fpga-5c2abc60-stage-004"
print("ARMED: one FPGA run004 on emulator-5558; waiting for START", flush=True)
assert input().strip() == "START", "Run was not released"
args = [sys.executable, "scripts/run-android-fpga.py", "--execute", "--serial", "emulator-5558", "--case", "all", "--artifacts", str(stage), "--build-receipt", str(stage / "build-receipt.json"), "--catalog-root", "/Users/thales/GitHub/logisim-mobile-worktrees/fpga-native-catalog-3a0a01f1", "--output", str(root / "outputs/fpga-native-5c2abc60-004")]
(stage / "released-command.json").write_text(json.dumps({"singleInvocation": True, "serial": "emulator-5558", "argv": args}, indent=2) + "\n")
stream = (stage / "execution.log").open("x")
os.dup2(stream.fileno(), 1)
os.dup2(stream.fileno(), 2)
env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PYTHONUNBUFFERED="1", ANDROID_HOME="/Users/thales/Library/Android/sdk")
os.execvpe(sys.executable, args, env)
