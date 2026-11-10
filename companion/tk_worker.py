"""Trusted entry point. Run only inside the private Bubblewrap profile."""
import base64
import ctypes
import errno
import json
import os
from pathlib import Path
import re
import secrets
import stat
import struct
import subprocess
import sys
import tempfile
import time
import zlib


WIDTH, HEIGHT = 640, 480
PREVIEW_LIMIT = 1_000_000
FRAMEBUFFER_LIMIT = 256 + 256 * 12 + WIDTH * HEIGHT * 4
INPUT_FILE = Path("/logisim-tk-input")
INPUT_LIMIT = 128


def write_authority(path):
    # Xauthority's FamilyLocal entry binds the cookie to our private host and display.
    fields = (b"logisim-tk", b"0", b"MIT-MAGIC-COOKIE-1", secrets.token_bytes(16))
    data = struct.pack("!H", 256) + b"".join(struct.pack("!H", len(field)) + field for field in fields)
    with open(path, "xb", opener=lambda name, flags: os.open(name, flags, 0o600)) as authority:
        authority.write(data)


def restrict_syscalls():
    # X11 and Tcl's notifier are initialized before synchronizing this filter to all threads.
    lib = ctypes.CDLL("libseccomp.so.2")
    lib.seccomp_init.argtypes = [ctypes.c_uint32]
    lib.seccomp_init.restype = ctypes.c_void_p
    lib.seccomp_release.argtypes = [ctypes.c_void_p]
    lib.seccomp_release.restype = None
    lib.seccomp_attr_set.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_uint32]
    lib.seccomp_syscall_resolve_name.argtypes = [ctypes.c_char_p]
    lib.seccomp_rule_add.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int, ctypes.c_uint]
    lib.seccomp_load.argtypes = [ctypes.c_void_p]

    scmp_act_allow = 0x7fff0000
    scmp_act_errno = 0x00050000 | errno.EPERM
    scmp_fltatr_ctl_tsync = 4
    context = lib.seccomp_init(scmp_act_allow)
    if not context:
        raise RuntimeError("Could not initialize the Tk syscall filter.")
    try:
        if lib.seccomp_attr_set(context, scmp_fltatr_ctl_tsync, 1) != 0:
            raise RuntimeError("Could not synchronize the Tk syscall filter.")
        for name in (b"execve", b"execveat", b"clone", b"clone3", b"fork", b"vfork", b"socket",
                     b"unshare", b"setns", b"ptrace", b"io_uring_setup"):
            number = lib.seccomp_syscall_resolve_name(name)
            if number < 0 and name in (b"fork", b"vfork") and os.uname().machine == "aarch64":
                continue  # Linux arm64 implements these through clone.
            if number < 0 or lib.seccomp_rule_add(context, scmp_act_errno, number, 0) != 0:
                raise RuntimeError("Could not build the Tk syscall filter.")
        if lib.seccomp_load(context) != 0:
            raise RuntimeError("Could not activate the Tk syscall filter.")
    finally:
        lib.seccomp_release(context)


def framebuffer_png(data):
    """Encode only the fixed, bounded TrueColor Xvfb framebuffer."""
    if not 100 <= len(data) <= FRAMEBUFFER_LIMIT:
        raise ValueError("Invalid Tk framebuffer length.")
    fields = struct.unpack_from("!25I", data)
    header_size, version, format_, depth, width, height, xoffset, byte_order = fields[:8]
    bits_per_pixel, pitch, visual, red, green, blue, _, _, colors = fields[11:20]
    offset = header_size + colors * 12
    if (not 100 <= header_size <= 256 or version != 7 or format_ != 2 or depth != 24 or
            (width, height) != (WIDTH, HEIGHT) or xoffset != 0 or byte_order not in (0, 1) or
            (bits_per_pixel, pitch, visual) != (32, WIDTH * 4, 4) or
            (red, green, blue) != (0xff0000, 0xff00, 0xff) or colors > 256 or offset + pitch * HEIGHT != len(data)):
        raise ValueError("Unsupported Tk framebuffer format.")
    stride = 1 + WIDTH * 3
    rows = bytearray(stride * HEIGHT)
    channels = (2, 1, 0) if byte_order == 0 else (1, 2, 3)
    for y in range(HEIGHT):
        row = data[offset + y * pitch:offset + (y + 1) * pitch]
        for channel, source in enumerate(channels):
            rows[y * stride + 1 + channel:(y + 1) * stride:3] = row[source::4]

    def chunk(kind, payload):
        return struct.pack("!I", len(payload)) + kind + payload + struct.pack("!I", zlib.crc32(kind + payload))

    header = struct.pack("!2I5B", WIDTH, HEIGHT, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows, level=1)) + chunk(b"IEND", b"")


def read_preview(path):
    # The path is fixed by this job inside its private tmpfs. Follow neither links nor special files.
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as framebuffer:
        if not stat.S_ISREG(os.fstat(framebuffer.fileno()).st_mode):
            raise ValueError("Invalid private Tk framebuffer.")
        return framebuffer_png(framebuffer.read(FRAMEBUFFER_LIMIT + 1))


class PrivateDisplayInput:
    def __init__(self):
        self.x11 = ctypes.CDLL("libX11.so.6")
        self.xtst = ctypes.CDLL("libXtst.so.6")
        display_type = ctypes.c_void_p
        integer = ctypes.c_int
        self.x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
        self.x11.XOpenDisplay.restype = display_type
        self.x11.XCloseDisplay.argtypes = [display_type]
        self.x11.XSync.argtypes = [display_type, integer]
        self.x11.XStringToKeysym.argtypes = [ctypes.c_char_p]
        self.x11.XStringToKeysym.restype = ctypes.c_ulong
        self.x11.XKeysymToKeycode.argtypes = [display_type, ctypes.c_ulong]
        self.x11.XKeysymToKeycode.restype = ctypes.c_ubyte
        self.xtst.XTestFakeMotionEvent.argtypes = [display_type, integer, integer, integer, ctypes.c_ulong]
        self.xtst.XTestFakeButtonEvent.argtypes = [display_type, ctypes.c_uint, integer, ctypes.c_ulong]
        self.xtst.XTestFakeKeyEvent.argtypes = [display_type, ctypes.c_uint, integer, ctypes.c_ulong]
        self.xtst.XTestQueryExtension.argtypes = [display_type, *([ctypes.POINTER(integer)] * 4)]
        self.display = self.x11.XOpenDisplay(b":0")
        extension = (integer(), integer(), integer(), integer())
        if not self.display or not self.xtst.XTestQueryExtension(self.display, *(ctypes.byref(part) for part in extension)):
            self.close()
            raise RuntimeError("The private display does not provide XTEST.")

    def close(self):
        if self.display:
            self.x11.XCloseDisplay(self.display)
            self.display = None

    def apply(self, event):
        if event["type"] == "tap":
            sent = (self.xtst.XTestFakeMotionEvent(self.display, 0, event["x"], event["y"], 0),
                    self.xtst.XTestFakeButtonEvent(self.display, 1, 1, 0),
                    self.xtst.XTestFakeButtonEvent(self.display, 1, 0, 0))
        else:
            symbol = self.x11.XStringToKeysym(event["key"].encode("ascii"))
            key = self.x11.XKeysymToKeycode(self.display, symbol)
            if not key:
                raise RuntimeError("The private display does not map this key.")
            sent = (self.xtst.XTestFakeKeyEvent(self.display, key, 1, 0),
                    self.xtst.XTestFakeKeyEvent(self.display, key, 0, 0))
        if not all(sent):
            raise RuntimeError("The private display refused the input event.")
        self.x11.XSync(self.display, 0)


def read_input(stream, partial, sequence):
    partial += stream.readline(INPUT_LIMIT - len(partial))
    if not partial.endswith(b"\n"):
        if len(partial) >= INPUT_LIMIT:
            raise ValueError("The Tk input record exceeds its limit.")
        return None, partial
    event = json.loads(partial)
    if not isinstance(event, dict) or type(event.get("sequence")) is not int or event["sequence"] != sequence:
        raise ValueError("Invalid Tk input sequence.")
    if event.get("type") == "tap" and set(event) == {"sequence", "type", "x", "y"}:
        if not (type(event["x"]) is int and 0 <= event["x"] < WIDTH and type(event["y"]) is int and 0 <= event["y"] < HEIGHT):
            raise ValueError("Tk tap outside private display bounds.")
    elif event.get("type") == "key" and set(event) == {"sequence", "type", "key"}:
        if not isinstance(event["key"], str) or not re.fullmatch(r"[a-z0-9]|space|Return|BackSpace|Escape|Tab|Left|Right|Up|Down", event["key"]):
            raise ValueError("Invalid private display key.")
    else:
        raise ValueError("Invalid private display event.")
    return event, b""


def emit_preview(interpreter, framebuffer, nonce, sequence=None):
    # The pointer query makes the X server process queued drawing before the readback.
    interpreter.eval("flush stdout; update idletasks; winfo pointerxy .")
    preview = read_preview(framebuffer)
    if len(preview) > PREVIEW_LIMIT:
        raise RuntimeError("The Tk preview exceeds its limit.")
    tag = "preview" if sequence is None else f"frame|{sequence}"
    print(f"{nonce}|{tag}|{base64.b64encode(preview).decode('ascii')}", flush=True)


def interact(interpreter, display, framebuffer, nonce):
    emit_preview(interpreter, framebuffer, nonce, 0)
    with INPUT_FILE.open("rb") as commands:
        partial = b""
        for sequence in range(1, 9):
            event = None
            while event is None:
                event, partial = read_input(commands, partial, sequence)
                if event is None:
                    interpreter.eval("update")
                    time.sleep(0.02)
            display.apply(event)
            interpreter.eval("update")
            emit_preview(interpreter, framebuffer, nonce, sequence)


def run():
    # Only the namespace launcher sets this hostname, and /tmp and the display socket are private.
    if sys.platform != "linux" or os.uname().nodename != "logisim-tk" or os.geteuid() != 0:
        raise RuntimeError("Tk requires the private Linux display profile.")
    from tkinter import Tcl

    with tempfile.TemporaryDirectory(dir="/tmp") as directory:
        authority = Path(directory) / "authority"
        write_authority(authority)
        os.environ["XAUTHORITY"] = str(authority)
        os.environ["DISPLAY"] = ":0"
        server = subprocess.Popen(["/usr/bin/Xvfb", ":0", "-screen", "0", f"{WIDTH}x{HEIGHT}x24", "-nolisten", "tcp",
                                   "-auth", str(authority), "-fbdir", directory,
                                   "-extension", "GLX", "-extension", "MIT-SHM", "-noreset"],
                                  stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
        interpreter = None
        display = None
        try:
            deadline = time.monotonic() + 2
            while not Path("/tmp/.X11-unix/X0").is_socket():
                if server.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError("The private Xvfb display did not start.")
                time.sleep(0.02)
            interpreter = Tcl()
            interpreter.eval("package require Tk; wm withdraw .")
            interpreter.eval("set ::child [::safe::interpCreate]; ::safe::loadTk $::child")
            # Tk is already loaded. Deny X11 command dispatch and package autoload from the child.
            interpreter.eval("catch {rename send {}}; interp eval $::child {catch {rename send {}}; package unknown {}}")
            interpreter.eval("after 1 {set ::logisimTkReady 1}; vwait ::logisimTkReady")
            if INPUT_FILE.is_file():
                display = PrivateDisplayInput()
            restrict_syscalls()
            nonce = sys.stdin.readline(33)
            if not re.fullmatch(r"[a-f0-9]{32}\n", nonce):
                raise RuntimeError("Invalid Tk worker protocol.")
            driver = sys.stdin.read(3 * 1024 * 1024 + 1)
            if len(driver) > 3 * 1024 * 1024:
                raise RuntimeError("The Tk driver exceeds its limit.")
            interpreter.eval(driver)
            if interpreter.eval("info exists ::logisimPreviewReady") == "1":
                framebuffer = Path(directory) / "Xvfb_screen0"
                if display is None:
                    emit_preview(interpreter, framebuffer, nonce.strip())
                else:
                    interact(interpreter, display, framebuffer, nonce.strip())
        finally:
            try:
                if display is not None:
                    display.close()
                if interpreter is not None:
                    interpreter.eval("catch {interp delete $::child}; catch {destroy .}")
            finally:
                # Releasing the Tcl interpreter can still send X requests.
                del interpreter
                if server.poll() is None:
                    server.terminate()
                    try:
                        server.wait(timeout=0.25)
                    except subprocess.TimeoutExpired:
                        server.kill()
                        server.wait()


if __name__ == "__main__":
    run()
