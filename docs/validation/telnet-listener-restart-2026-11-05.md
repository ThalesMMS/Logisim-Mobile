# Telnet listener restart

A Telnet listener could not immediately reopen its concrete port after stopping a connected session. This blocked the native background/resume scenario before the second session could exchange bytes.

The reproduction starts from `50668f45230ada6f49b10c1e05fe983b8ea183c4` on macOS 27.2 with Python 3.15.2. It accepts a real loopback peer, stops the session, observes peer EOF, closes the peer, and starts a new session on the same port.

Before the correction, `test_stopped_connected_listener_reopens_the_same_port_immediately` failed with `TelnetFailure: [Errno 48] Address already in use`. The manager had already reported zero active sessions. The companion now sets `SO_REUSEADDR` before binding the listener.

The restart test also requires a new session identity and receives byte `78` through a new TCP peer. A second test holds a foreign listener with address reuse enabled, verifies that the companion still reports `listen-failed`, and confirms that the foreign listener still delivers byte `70`. The existing owned-listener collision and failed-bind cleanup tests also pass.

| Check | Result |
| --- | --- |
| Two focused tests before the correction | One restart error, foreign-listener control passed, 0.003 seconds |
| Complete Telnet suite after the correction | 26 passed, zero failures/errors/skips, 0.217 seconds |
| Source boundaries and diff formatting | Passed |

The local logs remain in `outputs/telnet-listener-restart/` in the isolated worktree. Their SHA-256 values are:

- `before.log`: `3b5cb6e8af46d77909e528d4b15b9320fd15222d3df6511fb00ebd06d584cbe8`
- `after.log`: `51deb8a51d9473fa6cbe8763419d806a876a3efa6ab89aef211c3685db7a24e3`

Reproduce the complete suite with:

```sh
python3 -m unittest discover -s companion/tests -p 'test_telnet*.py' -v
```

The checks exercise real loopback sockets on this Mac. They do not inspect kernel TCP states or execute Android/iOS UI. Native background/resume qualification remains separate.
