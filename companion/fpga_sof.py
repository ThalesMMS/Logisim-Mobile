"""Limits and metadata for Quartus SOF files carried outside the JSON body."""
import hashlib
import hmac
import re
import struct
from io import BytesIO

from companion.api import JobFailure


MAX_SOF_BYTES = 8 * 1024 * 1024
INLINE_PROGRAM_BYTES = 4 * 1024 * 1024
INLINE_ARTIFACT_BYTES = 5 * 1024 * 1024
SOF_PART = re.compile(r"[A-Z0-9][A-Z0-9-]{0,63}")
SOF_ARTIFACT = "design.sof"


def verify_part(stream, size, expected):
    """Require one complete, length-delimited device record matching the reviewed part."""
    header = stream.read(12)
    count = int.from_bytes(header[8:12], "little")
    if len(header) != 12 or header[:4] != b"SOF\0" or not 1 <= count <= 4096:
        raise JobFailure("invalid-bitstream", "Provide a supported Quartus SOF header.")
    part = None
    for _ in range(count):
        record = stream.read(6)
        if len(record) != 6:
            raise JobFailure("invalid-bitstream", "The Quartus SOF record header is incomplete.")
        kind, length = struct.unpack("<HI", record)
        if length > size - stream.tell():
            raise JobFailure("invalid-bitstream", "The Quartus SOF record exceeds the file size.")
        if kind != 2:
            stream.seek(length, 1)
            continue
        if part is not None or not 2 <= length <= 65:
            raise JobFailure("invalid-bitstream", "The Quartus SOF device record is invalid.")
        record = stream.read(length)
        try:
            part = record[:-1].decode("ascii").upper()
        except UnicodeDecodeError:
            raise JobFailure("invalid-bitstream", "The Quartus SOF device record is invalid.") from None
        if len(record) != length or not record.endswith(b"\0") or not SOF_PART.fullmatch(part):
            raise JobFailure("invalid-bitstream", "The Quartus SOF device record is invalid.")
    if stream.tell() != size or part is None:
        raise JobFailure("invalid-bitstream", "The Quartus SOF device record or length is invalid.")
    if part != expected.upper():
        raise JobFailure("fpga-part-mismatch", "The SOF part does not match the reviewed Quartus device.")


def descriptor(path, expected_part):
    size = path.stat().st_size
    if not 0 < size <= MAX_SOF_BYTES:
        raise JobFailure("output-limit", "The Quartus SOF exceeds the 8 MiB binary limit.")
    with path.open("rb") as stream:
        data = stream.read(size + 1)
    if len(data) != size:
        raise JobFailure("invalid-artifact", "The Quartus SOF changed while it was reviewed.")
    verify_part(BytesIO(data), size, expected_part)
    return {"name": SOF_ARTIFACT, "size": size, "sha256": hashlib.sha256(data).hexdigest(), "transport": "binary"}


def verify_upload(data, descriptor, expected_part):
    if len(data) != descriptor["size"]:
        raise JobFailure("invalid-length", "The SOF upload does not match the reviewed size.")
    if not hmac.compare_digest(hashlib.sha256(data).hexdigest(), descriptor["sha256"]):
        raise JobFailure("hash-mismatch", "The SOF upload does not match the reviewed SHA-256.")
    verify_part(BytesIO(data), len(data), expected_part)
