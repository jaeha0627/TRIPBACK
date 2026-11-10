"""Check APK-local superclass visibility without signatures, keys, or third-party tools.

Exit 1 for a cross-package subclass of a non-public APK-local superclass.
This is a structural regression check, not an Android runtime/verifier replacement.
Usage: python tools/check_dex_superclass_access.py path/to/app.apk
"""
import argparse
import json
import re
import struct
import sys
import zipfile


def class_definitions(apk):
    classes = {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            data = archive.read(name)
            if not data.startswith(b"dex\n"):
                raise ValueError("Unsupported DEX container")
            size, offset = struct.unpack_from("<II", data, 0x38)
            strings = []
            for i in range(size):
                start = struct.unpack_from("<I", data, offset + 4 * i)[0]
                while data[start] & 0x80:
                    start += 1
                start += 1
                strings.append(data[start:data.index(0, start)].decode("utf-8", "replace"))
            size, offset = struct.unpack_from("<II", data, 0x40)
            types = [strings[struct.unpack_from("<I", data, offset + 4 * i)[0]] for i in range(size)]
            size, offset = struct.unpack_from("<II", data, 0x60)
            for i in range(size):
                index, flags, parent = struct.unpack_from("<III", data, offset + 32 * i)
                descriptor = types[index]
                if descriptor in classes:
                    raise ValueError("Duplicate class definition: " + descriptor)
                classes[descriptor] = (flags, types[parent] if parent != 0xFFFFFFFF else "")
    if not classes:
        raise ValueError("No DEX class definitions found")
    return classes


def violations(classes):
    found = []
    for child, (_, parent) in classes.items():
        if parent not in classes:
            continue  # Platform/library classes outside this APK are out of scope.
        parent_flags = classes[parent][0]
        if not parent_flags & 1 and child.rpartition("/")[0] != parent.rpartition("/")[0]:
            found.append({"child": child, "parent": parent, "parent_access_flags": hex(parent_flags)})
    return sorted(found, key=lambda item: item["child"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    args = parser.parse_args()
    classes = class_definitions(args.apk)
    found = violations(classes)
    print(json.dumps({"apk": args.apk, "class_count": len(classes), "illegal_superclass_edges": found}, indent=2))
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
