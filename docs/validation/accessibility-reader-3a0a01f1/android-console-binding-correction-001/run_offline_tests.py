"""Run this candidate's tests with external process and network access blocked."""
from pathlib import Path
import argparse
import contextlib
import hashlib
import io
import json
import socket
import subprocess
import sys
import unittest
import unittest.mock  # Load SSL's socket subclass before replacing the constructor.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="New directory for replay log and receipt; default prints only.")
    arguments = parser.parse_args()
    if arguments.output is not None:
        arguments.output.mkdir(parents=True, exist_ok=False)
    directory = Path(__file__).resolve().parent
    attempts = []

    def blocked(*args, **kwargs):
        attempts.append("blocked")
        raise AssertionError("External process/network prohibited in offline replay")

    subprocess.run = blocked
    subprocess.Popen = blocked
    socket.socket = blocked
    stream = io.StringIO()
    with contextlib.redirect_stdout(stream), contextlib.redirect_stderr(stream):
        suite = unittest.defaultTestLoader.discover(str(directory), pattern="test_*.py")
        suite.addTests(unittest.defaultTestLoader.discover(str(directory), pattern="contact_test.py"))
        result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    receipt = {
        "phase": "GREEN" if result.wasSuccessful() and not attempts else "FAIL",
        "tests": result.testsRun,
        "errors": len(result.errors),
        "failures": len(result.failures),
        "external_process_and_socket_boundaries": "BLOCKED",
        "external_attempts": len(attempts),
        "helper_sha256": hashlib.sha256((directory / "check_binding.py").read_bytes()).hexdigest(),
        "wrapper_sha256": hashlib.sha256((directory / "run_contact.py").read_bytes()).hexdigest(),
        "runtime": "NONE",
        "reader_qualification": False,
    }
    if arguments.output is not None:
        (arguments.output / "green.log").write_text(stream.getvalue())
        (arguments.output / "green-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt))
    return 0 if receipt["phase"] == "GREEN" else 1


if __name__ == "__main__":
    sys.exit(main())
