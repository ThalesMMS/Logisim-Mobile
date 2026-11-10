"""Local authenticated HTTP fixture used by the real KMP Tcl client checks."""
from pathlib import Path
import os
import shutil
import signal
import sys
import threading

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from companion.api import OperationRegistry
from companion.server import Configuration, make_http_server
from companion.tcl_runtime import register


def main():
    tclsh = shutil.which("tclsh")
    if not tclsh:
        print("SKIP: tclsh is unavailable", flush=True)
        return
    registry = OperationRegistry()
    register(registry)
    server = make_http_server(Configuration(os.environ["LOGISIM_TCL_TEST_TOKEN"],
        {"tcl-run", "tcl-step"}, tool_paths={"tclsh": tclsh}), registry)
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
    print(server.server_port, flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        server.service.close()


if __name__ == "__main__":
    main()
