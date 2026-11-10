"""Default-deny macOS worker policy for trusted companion adapters."""
import functools
import os
from pathlib import Path
import struct
import sys
import threading
import time


SANDBOX_EXEC = Path("/usr/bin/sandbox-exec")
PROFILE_NAME = "macos-seatbelt-v1"
TK_PROFILE = "linux-private-xvfb-v1"
LEASE_SECONDS = 5

_PROFILE = """
(version 1)
(deny default)
(allow process-exec (literal (param "EXECUTABLE")))
(allow file-read* file-map-executable (subpath "/System/Library/Frameworks") (subpath "/usr/lib"))
(allow file-read-metadata (literal "/System/Library") (literal "/System/Library/CoreServices"))
(allow file-read* (literal "/System/Library/CoreServices/SystemVersion.plist"))
(allow file-read* (literal (param "EXECUTABLE")) (literal "/dev/null")
                  (literal "/dev/random") (literal "/dev/urandom") (literal "/"))
(allow sysctl-read (sysctl-name "hw.ncpu" "hw.memsize" "hw.pagesize_compat" "hw.cachelinesize"
                              "kern.ostype" "kern.osrelease" "kern.osproductversion"))
"""


def available():
    return sys.platform == "darwin" and SANDBOX_EXEC.is_file() and os.access(SANDBOX_EXEC, os.X_OK)


def available_for(profile):
    if profile == PROFILE_NAME:
        return available()
    if profile == TK_PROFILE:
        from companion import tk_linux
        return tk_linux.available()
    return False


def command_for(profile, executable, args, working_directory, inputs, java_home, tk_input=None):
    if profile == PROFILE_NAME and tk_input is None:
        return command(executable, args, working_directory, inputs, java_home)
    if profile == TK_PROFILE:
        from companion import tk_linux
        if java_home is not None:
            raise ValueError("The private Tk worker does not accept a Java runtime.")
        if tk_input is not None:
            if (tk_input.is_symlink() or tk_input.resolve(strict=True).parent != working_directory or
                    not tk_input.is_file() or tk_input.stat().st_nlink != 1):
                raise ValueError("The Tk input mailbox must be a dedicated job file.")
        return tk_linux.command(executable, args, tk_input)
    raise ValueError("The requested sandbox profile is unavailable.")


class Lease:
    def __init__(self, seconds=LEASE_SECONDS):
        self._seconds = seconds
        self._expires = time.monotonic() + seconds
        self._lock = threading.Lock()

    def expired(self):
        with self._lock:
            return time.monotonic() >= self._expires

    def renew(self):
        with self._lock:
            now = time.monotonic()
            if now < self._expires:
                self._expires = now + self._seconds


_SYSTEM_LIBRARIES = (Path("/usr/lib"), Path("/System"))
# LC_LOAD_DYLIB, LC_LOAD_WEAK_DYLIB, LC_REEXPORT_DYLIB, LC_LAZY_LOAD_DYLIB, LC_LOAD_UPWARD_DYLIB
_DYLIB_COMMANDS = {0xC, 0x80000018, 0x8000001F, 0x20, 0x80000023}
_LC_RPATH = 0x8000001C


def _dylib_references(path):
    """Dependencies named by every Mach-O slice of ``path``, resolving @loader_path and @rpath."""
    try:
        data = path.read_bytes()
    except OSError:
        return set()
    slices = [0]
    if data[:4] in (b"\xca\xfe\xba\xbe", b"\xca\xfe\xba\xbf"):
        wide = data[3] == 0xBF
        count = struct.unpack_from(">I", data, 4)[0]
        entry = 32 if wide else 20
        slices = [struct.unpack_from(">Q" if wide else ">I", data, 8 + index * entry + 8)[0] for index in range(min(count, 16))]
    names, rpaths, pending_rpath = set(), [], []
    for start in slices:
        if data[start:start + 4] not in (b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe"):
            continue
        count = struct.unpack_from("<I", data, start + 16)[0]
        offset = start + (32 if data[start] == 0xCF else 28)
        for _ in range(min(count, 4096)):
            if offset + 12 > len(data):
                break
            command, size = struct.unpack_from("<II", data, offset)
            if command in _DYLIB_COMMANDS or command == _LC_RPATH:
                name_offset = struct.unpack_from("<I", data, offset + 8)[0]
                raw = data[offset + name_offset:offset + size].split(b"\0", 1)[0].decode("utf-8", "replace")
                if raw.startswith(("@loader_path/", "@executable_path/")) or raw == "@loader_path":
                    raw = str(path.parent / raw.partition("/")[2])
                if command == _LC_RPATH:
                    rpaths.append(raw)
                elif raw.startswith("@rpath/"):
                    pending_rpath.append(raw.removeprefix("@rpath/"))
                elif raw.startswith("/"):
                    names.add(Path(raw))
            if size < 8:
                break
            offset += size
    for name in pending_rpath:
        names.update(Path(os.path.normpath(Path(root) / name)) for root in rpaths
                     if root.startswith("/") and (Path(root) / name).is_file())
    return names


def _spellings(path):
    """Every path the kernel visits while resolving ``path``, including intermediate symlinks."""
    found, pending = [], [Path(os.path.normpath(path))]
    while pending and len(found) < 64:
        current = pending.pop()
        if current in found:
            continue
        found.append(current)
        prefix = Path(current.anchor)
        for index, part in enumerate(current.parts[1:], 1):
            prefix /= part
            if prefix.is_symlink():
                target = Path(os.path.normpath(prefix.parent / os.readlink(prefix)))
                pending.append(target.joinpath(*current.parts[index + 1:]))
                break
    return found


@functools.lru_cache(maxsize=8)
def _runtime_libraries(runtime):
    """Libraries outside the JDK and the OS that its native libraries link, transitively.

    A Homebrew JDK links libfontmanager to Homebrew's harfbuzz and freetype, so the Java grant
    must cover those exact files for AWT, fonts and icons to load under the default-deny profile.
    """
    pending = [path for path in (runtime / "lib").rglob("*.dylib") if path.is_file()]
    seen, found = set(pending), set()
    while pending:
        # dyld expands @loader_path from the path it loaded, which may be a symlink such as
        # /opt/homebrew/opt/<formula>/lib, so both spellings of a library are followed.
        for reference in _dylib_references(pending.pop()):
            if reference.is_relative_to(runtime) or any(reference.is_relative_to(root) for root in _SYSTEM_LIBRARIES):
                continue
            try:
                resolved = reference.resolve(strict=True)
            except OSError:
                continue
            if not resolved.is_file() or resolved.is_relative_to(runtime):
                continue
            found.update(_spellings(reference))
            for path in (reference, resolved):
                if path not in seen and len(seen) < 512:
                    seen.add(path)
                    pending.append(path)
    return tuple(sorted(found))


def command(executable, args, working_directory, inputs, java_home):
    if not available():
        raise ValueError("The macOS sandbox is unavailable.")
    runtime = Path(java_home).resolve(strict=True) if java_home is not None else None
    executable_path = Path(executable)
    if runtime is not None and (
        executable_path != runtime / "bin/java" or not (runtime / "lib/modules").is_file()
        or working_directory.is_relative_to(runtime) or runtime == Path.home().resolve()
    ):
        raise ValueError("The Java grant requires a dedicated JDK containing the selected executable.")
    profile = [_PROFILE]
    parameters = ["-D", f"EXECUTABLE={executable}"]

    def grant(operation, scope, path):
        key = f"PATH_{len(parameters)}"
        profile.append(f'(allow {operation} ({scope} (param "{key}")))')
        parameters.extend(("-D", f"{key}={path}"))

    metadata = set(executable_path.parents)
    if runtime is not None:
        grant("file-read* file-map-executable", "subpath", runtime)
        for path in _runtime_libraries(runtime):
            grant("file-read* file-map-executable", "literal", path)
            metadata.update(path.parents)
    for source in inputs:
        path = source.resolve(strict=True)
        if (source.is_symlink() or not path.is_relative_to(working_directory)
                or not path.is_file() or path.stat().st_nlink != 1):
            raise ValueError("A sandbox input must be a staged regular file unique to this job.")
        grant("file-read*", "literal", path)
        metadata.update(path.parents)
    # JVM and dyld probe the executable and input ancestors during canonicalization.
    metadata.update(map(Path, ("/System/Volumes/Data", "/var", "/tmp")))
    for path in sorted(metadata):
        grant("file-read-metadata", "literal", path)
    return [str(SANDBOX_EXEC), "-p", "\n".join(profile), *parameters, executable, *args]
