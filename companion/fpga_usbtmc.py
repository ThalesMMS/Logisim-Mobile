"""Send a GECKO bitstream to an explicitly selected Linux USBTMC interface."""

import os
from pathlib import Path
import re
import stat
import sys


def program(device, serial, bitstream):
    if not re.fullmatch(r"/dev/usbtmc[0-9]{1,4}", device):
        raise ValueError("Expected a Linux USBTMC device node.")
    fd = os.open(device, os.O_WRONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "wb", buffering=0) as destination:
        info = os.fstat(destination.fileno())
        if not stat.S_ISCHR(info.st_mode):
            raise ValueError("The selected USBTMC node is not a character device.")
        path = Path(f"/sys/dev/char/{os.major(info.st_rdev)}:{os.minor(info.st_rdev)}").resolve(strict=True)
        serial_file = next((parent / "serial" for parent in (path, *path.parents)
                            if (parent / "idVendor").is_file() and (parent / "serial").is_file()), None)
        if serial_file is None or serial_file.read_text().strip() != serial:
            raise ValueError("The attached USBTMC device does not match the reviewed USB serial.")
        with open(bitstream, "rb") as source:
            # Match the Evolution sender's 16 KiB reads through an 8 KiB BufferedOutputStream.
            prefix = b"FPGA "
            data = source.read(16 * 1024)
            if len(data) + len(prefix) > 8 * 1024:
                write_all(destination, prefix)
            else:
                data = prefix + data
            while data:
                write_all(destination, data)
                data = source.read(16 * 1024)


def write_all(destination, data):
    while data:
        written = destination.write(data)
        if not written:
            raise OSError("The USBTMC device stopped accepting bytes.")
        data = data[written:]


if __name__ == "__main__":
    try:
        program(*sys.argv[1:])
    except (OSError, ValueError) as failure:
        sys.exit(f"USBTMC programming failed: {failure}")
