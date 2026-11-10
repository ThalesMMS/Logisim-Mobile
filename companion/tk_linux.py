"""Launch the Tk worker in a private Linux display and filesystem."""
import os
from pathlib import Path
import platform
import stat
import sys


BWRAP = Path("/usr/bin/bwrap")
PYTHON = Path("/usr/bin/python3")
XVFB = Path("/usr/bin/Xvfb")
XKBCOMP = Path("/usr/bin/xkbcomp")
XKB_SHELL = Path("/bin/sh")
RUNTIME_DIRS = tuple(Path(path) for path in (
    "/usr/lib", "/usr/share/tcltk", "/usr/share/X11", "/usr/share/fonts", "/etc/fonts",
))
WORKER = Path(__file__).with_name("tk_worker.py").resolve()
WORKER_DESTINATION = "/logisim-tk-worker.py"


def _trusted(path, *, executable=False):
    try:
        info = path.resolve(strict=True).stat()
    except OSError:
        return False
    return (info.st_uid == 0 and not info.st_mode & (stat.S_IWGRP | stat.S_IWOTH) and
            (not executable or (stat.S_ISREG(info.st_mode) and os.access(path, os.X_OK))))


def available():
    if sys.platform != "linux" or os.geteuid() == 0 or platform.machine() not in ("x86_64", "aarch64"):
        return False
    # This first profile uses merged-/usr Linux runtime layouts.
    if Path("/lib").resolve() != Path("/usr/lib"):
        return False
    return (all(_trusted(path, executable=True) for path in (BWRAP, PYTHON, XVFB, XKBCOMP, XKB_SHELL)) and
            all(path.is_dir() and _trusted(path) for path in RUNTIME_DIRS) and WORKER.is_file() and
            any(Path("/usr/lib").glob("*/libseccomp.so.2")) and
            any(Path("/usr/lib").glob("python3.*/lib-dynload/_tkinter*.so")))


def command(executable, args, tk_input=None):
    if not available() or Path(executable) != BWRAP.resolve(strict=True) or args:
        raise ValueError("The private Tk worker requires the system Bubblewrap and the fixed worker.")
    command = [executable, "--unshare-user", "--unshare-pid", "--unshare-net", "--unshare-ipc", "--unshare-uts",
               "--disable-userns", "--die-with-parent", "--cap-drop", "ALL", "--uid", "0", "--gid", "0",
               "--hostname", "logisim-tk", "--clearenv", "--setenv", "LANG", "C", "--setenv", "PATH", "/usr/bin",
               "--setenv", "HOME", "/tmp", "--setenv", "XDG_CACHE_HOME", "/tmp", "--dev", "/dev", "--tmpfs", "/tmp",
               "--chdir", "/tmp", "--symlink", "usr/lib", "/lib"]
    if Path("/usr/lib64").is_dir():
        if not _trusted(Path("/usr/lib64")):
            raise ValueError("The private Tk worker requires a trusted system library directory.")
        command += ["--ro-bind", "/usr/lib64", "/usr/lib64", "--symlink", "usr/lib64", "/lib64"]
    # Xvfb launches its fixed xkbcomp command through /bin/sh during initialization.
    for path in (*RUNTIME_DIRS, PYTHON, XVFB, XKBCOMP, XKB_SHELL):
        command += ["--ro-bind", str(path.resolve(strict=True)), str(path)]
    if tk_input is not None:
        command += ["--ro-bind", str(tk_input), "/logisim-tk-input"]
    return [*command, "--ro-bind", str(WORKER), WORKER_DESTINATION,
            "--", str(PYTHON), "-I", "-S", WORKER_DESTINATION]
