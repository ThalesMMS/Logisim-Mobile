#!/usr/bin/env python3
"""JSON-lines wrapper around companion.telnet.TelnetManager for TelnetOracle.java.

Usage: python3 -I companion_bridge.py REPO_ROOT. Each stdin line is one request; each stdout line one reply.
"""
import json
import sys

sys.path.insert(0, sys.argv[1])
from companion.telnet import TelnetFailure, TelnetManager  # noqa: E402

manager = TelnetManager()
poll_id = 0
for line in sys.stdin:
    request = json.loads(line)
    try:
        op = request["op"]
        if op == "start":
            reply = manager.start(port=request["port"], telnet_mode=request["telnetMode"],
                                  buffer_size=request["bufferSize"], request_id=request.get("requestId"))
        elif op == "poll":
            poll_id += 1
            reply = manager.poll(request["id"], maximum_bytes=request["max"], poll_id=poll_id)
        elif op == "send":
            reply = manager.send(request["id"], request["packetId"], request["data"])
        elif op == "stop":
            reply = manager.stop(request["id"])
        elif op == "quit":
            manager.close()
            print(json.dumps({"ok": True}), flush=True)
            break
        else:
            raise TelnetFailure("unknown-op", op)
    except TelnetFailure as failure:
        reply = {"error": failure.code, "message": str(failure)}
    print(json.dumps(reply), flush=True)
