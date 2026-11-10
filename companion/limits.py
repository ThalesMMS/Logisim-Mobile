"""Apply per-process POSIX limits before exec, without a threaded preexec_fn."""
import argparse
import os
import resource
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cpu-seconds", type=int, required=True)
    parser.add_argument("--memory-bytes", type=int, required=True)
    parser.add_argument("--file-bytes", type=int, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("An executable argument list is required")
    try:
        resource.setrlimit(resource.RLIMIT_CPU, (args.cpu_seconds, args.cpu_seconds + 1))
        resource.setrlimit(resource.RLIMIT_FSIZE, (args.file_bytes, args.file_bytes))
        resource.setrlimit(resource.RLIMIT_NOFILE, (128, 128))
        if sys.platform != "darwin":
            resource.setrlimit(resource.RLIMIT_AS, (args.memory_bytes, args.memory_bytes))
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    except (ValueError, OSError) as error:
        print(f"LOGISIM_RESOURCE_LIMIT_ERROR: {error}", file=sys.stderr)
        return 125
    os.execve(command[0], command, os.environ)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
