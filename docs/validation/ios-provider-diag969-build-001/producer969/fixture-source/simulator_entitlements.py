#!/usr/bin/env python3
"""Read Simulator entitlement sections without executing or changing a Mach-O image."""
import hashlib
import json
from pathlib import Path
import plistlib
import struct


MAXIMUM_IMAGE_BYTES = 64 * 1024 * 1024
MAXIMUM_ENTITLEMENT_BYTES = 1024 * 1024
GROUP_KEY = "com.apple.security.application-groups"
SECTION_NAMES = ("__entitlements", "__ents_der")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def macho_sections(data):
    require(32 <= len(data) <= MAXIMUM_IMAGE_BYTES, "Unsupported Mach-O image size")
    magic, cpu, _, kind, count, command_bytes = struct.unpack_from("<6I", data)
    require(magic == 0xFEEDFACF and cpu == 0x0100000C and kind in (2, 6),
            "Expected one thin arm64 Mach-O executable or dylib")
    end = 32 + command_bytes
    require(count <= 1024 and end <= len(data), "Invalid Mach-O load-command table")
    cursor = 32
    found = {}
    for _ in range(count):
        require(cursor + 8 <= end, "Truncated Mach-O load command")
        command, size = struct.unpack_from("<2I", data, cursor)
        require(size >= 8 and size % 8 == 0 and cursor + size <= end, "Invalid Mach-O load-command size")
        if command == 0x19:
            require(size >= 72, "Truncated LC_SEGMENT_64")
            segment = data[cursor + 8:cursor + 24].rstrip(b"\0").decode("ascii")
            sections = struct.unpack_from("<I", data, cursor + 64)[0]
            require(72 + 80 * sections <= size, "Truncated Mach-O section table")
            for index in range(sections):
                position = cursor + 72 + 80 * index
                name, owner, _, length, offset, _, _, _, flags = struct.unpack_from("<16s16sQQ5I", data, position)
                name = name.rstrip(b"\0").decode("ascii")
                owner = owner.rstrip(b"\0").decode("ascii")
                require(owner == segment, "Mach-O section/segment disagreement")
                if segment != "__TEXT" or name not in SECTION_NAMES:
                    continue
                require(name not in found, "Duplicate Simulator entitlement section")
                require(flags & 0xFF not in (1, 12, 18), "Entitlements cannot occupy a zero-fill section")
                require(0 < length <= MAXIMUM_ENTITLEMENT_BYTES and offset + length <= len(data),
                        "Invalid Simulator entitlement section range")
                found[name] = {"fileOffset": offset, "bytes": length, "data": data[offset:offset + length]}
        cursor += size
    require(cursor == end, "Mach-O load commands do not fill their declared table")
    return found


def der_entitlements(data):
    """Decode the version-1 DER entitlement dictionary emitted by the current toolchain."""
    require(0 < len(data) <= MAXIMUM_ENTITLEMENT_BYTES, "Invalid DER entitlement size")
    nodes = 0

    def read(cursor, limit, depth):
        nonlocal nodes
        nodes += 1
        require(nodes <= 4096 and depth < 16 and cursor + 2 <= limit, "DER structure exceeds bounds")
        tag = data[cursor]
        cursor += 1
        require(tag & 31 != 31, "Unsupported DER high-tag encoding")
        length = data[cursor]
        cursor += 1
        if length & 128:
            width = length & 127
            require(0 < width <= 4 and cursor + width <= limit, "Invalid DER length")
            raw = data[cursor:cursor + width]
            require(raw[0] != 0, "Noncanonical DER length")
            length = int.from_bytes(raw, "big")
            require(length >= 128, "Noncanonical long DER length")
            cursor += width
        end = cursor + length
        require(end <= limit, "Truncated DER value")
        if tag & 32:
            value = []
            while cursor < end:
                child, cursor = read(cursor, end, depth + 1)
                value.append(child)
        else:
            value = data[cursor:end]
        return (tag, value), end

    def integer(raw):
        require(raw and (len(raw) == 1 or not (raw[0] == 0 and raw[1] < 128)) and
                (len(raw) == 1 or not (raw[0] == 255 and raw[1] >= 128)), "Invalid DER integer")
        return int.from_bytes(raw, "big", signed=True)

    def dictionary(entries):
        result = {}
        for tag, entry in entries:
            require(tag == 0x30 and len(entry) == 2 and entry[0][0] == 0x0C,
                    "Invalid DER dictionary entry")
            key = entry[0][1].decode("utf-8", "strict")
            require(key not in result, "Duplicate DER entitlement key")
            result[key] = value(entry[1])
        return result

    def value(node):
        tag, payload = node
        if tag == 0x0C:
            return payload.decode("utf-8", "strict")
        if tag == 0x01:
            require(payload in (b"\0", b"\xff"), "Invalid DER boolean")
            return payload == b"\xff"
        if tag == 0x02:
            return integer(payload)
        if tag == 0x30:
            return [value(child) for child in payload]
        if tag == 0xB0:
            return dictionary(payload)
        raise ValueError("Unsupported DER entitlement value tag: " + hex(tag))

    root, end = read(0, len(data), 0)
    require(end == len(data), "Trailing DER entitlement bytes")
    require(root[0] == 0x70 and len(root[1]) == 2 and root[1][0][0] == 0x02 and
            integer(root[1][0][1]) == 1 and root[1][1][0] == 0xB0,
            "Expected the version-1 DER entitlement dictionary")
    return dictionary(root[1][1][1])


def verify_bundle(bundle, bundle_id, group, output, label):
    """Retain each XML/DER pair and its owner. These file checks do not qualify discovery."""
    bundle, output = Path(bundle), Path(output)
    info = plistlib.loads((bundle / "Info.plist").read_bytes())
    require(info["CFBundleIdentifier"] == bundle_id, "Unexpected fixture bundle identifier")
    main = bundle / info["CFBundleExecutable"]
    require(main.parent == bundle and main.is_file() and not main.is_symlink(), "Invalid fixture main executable")
    images = [("main", main)]
    debug = main.with_name(main.name + ".debug.dylib")
    if debug.exists():
        require(debug.is_file() and not debug.is_symlink(), "Invalid fixture debug dylib")
        images.append(("debug-dylib", debug))
    report = {"bundleIdentifier": bundle_id, "images": [], "sectionOwners": [],
              "nativeExecution": "NOT_RUN", "generatedEntitlementsReview": "REQUIRED_BEFORE_NATIVE"}
    observed = None
    for kind, path in images:
        data = path.read_bytes()
        sections = macho_sections(data)
        image = {"image": kind, "path": str(path), "bytes": len(data), "sha256": digest(data), "sections": {}}
        for name, section in sections.items():
            destination = output / (label + "-" + kind + "-" + name + ".bin")
            destination.write_bytes(section["data"])
            image["sections"][name] = {"fileOffset": section["fileOffset"], "bytes": section["bytes"],
                                       "sha256": digest(section["data"]), "path": destination.name}
        require(not sections or set(sections) == set(SECTION_NAMES), "Simulator XML and DER must occur in the same image")
        if sections:
            xml = plistlib.loads(sections["__entitlements"]["data"])
            der = der_entitlements(sections["__ents_der"]["data"])
            require(isinstance(xml, dict) and json.dumps(xml, sort_keys=True) == json.dumps(der, sort_keys=True),
                    "Simulator XML and DER entitlements disagree")
            require(xml.get(GROUP_KEY) == [group], "Simulator section does not contain the exact fixture App Group")
            require(not xml.get("com.apple.developer.team-identifier"), "Simulator fixture must not acquire a Team identifier")
            if observed is not None:
                require(json.dumps(xml, sort_keys=True) == json.dumps(observed, sort_keys=True),
                        "Simulator entitlements differ between image owners")
            observed = xml
            report["sectionOwners"].append(kind)
        report["images"].append(image)
    require(observed is not None, "Fixture bundle has no Simulator XML/DER entitlement sections")
    report["simulatedEntitlements"] = observed
    report["generatedAdditionalKeys"] = {key: value for key, value in observed.items() if key != GROUP_KEY}
    report["mainHasSectionPair"] = "main" in report["sectionOwners"]
    return report


def verify_packaging_inputs(report, bundle, derived_data, output, label):
    """Match retained sections to the unique files generated by Xcode packaging."""
    inputs = list(Path(derived_data).rglob(Path(bundle).name + "-Simulated.xcent"))
    require(len(inputs) == 1, "Expected one Xcode Simulator entitlement packaging input per bundle")
    xml_path = inputs[0]
    der_path = xml_path.with_name(xml_path.name + ".der")
    report["packagingInputs"] = {}
    for name, path in [("__entitlements", xml_path), ("__ents_der", der_path)]:
        require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= MAXIMUM_ENTITLEMENT_BYTES,
                "Invalid Xcode Simulator entitlement packaging input")
        data = path.read_bytes()
        retained = Path(output) / (label + "-generated-" + path.name)
        retained.write_bytes(data)
        report["packagingInputs"][name] = {"sourcePath": str(path), "path": retained.name,
                                          "bytes": len(data), "sha256": digest(data)}
        for image in report["images"]:
            if name in image["sections"]:
                require(Path(output, image["sections"][name]["path"]).read_bytes() == data,
                        "Simulator section differs from its Xcode packaging input")
