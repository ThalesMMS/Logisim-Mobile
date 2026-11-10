"""Loopback-only companion fixture, started explicitly by the JVM integration tests."""
import os
import sys
import threading
import importlib.util

from companion.api import OperationRegistry
from companion import hdl_import
from companion.server import Configuration, make_http_server


def main():
    registry = OperationRegistry()
    hdl_import.register(registry)
    if importlib.util.find_spec("companion.hdl_sim") is not None:
        from companion import hdl_sim
        hdl_sim.register(registry)
    configuration = Configuration(token=os.environ.pop("LOGISIM_HDL_TEST_TOKEN"),
                                  allowed_operations=set(registry.operations), host="127.0.0.1", port=0)
    server = make_http_server(configuration, registry)
    thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.05), daemon=True)
    thread.start()
    try:
        print(server.server_address[1], flush=True)
        sys.stdin.read()
    finally:
        server.shutdown()
        server.server_close()
        server.service.close()
        thread.join(timeout=5)


if __name__ == "__main__":
    main()
