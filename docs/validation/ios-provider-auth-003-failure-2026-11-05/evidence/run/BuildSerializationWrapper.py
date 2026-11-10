import fcntl, subprocess, sys
with open('/tmp/logisim-gradle.lock','w') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    raise SystemExit(subprocess.call(sys.argv[1:]))
