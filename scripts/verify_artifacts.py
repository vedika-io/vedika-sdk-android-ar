#!/usr/bin/env python3
"""Verify actual AAR public exports and every native library in a consumer APK.

ELF LOAD and RELRO checks follow https://developer.android.com/guide/practices/page-sizes.
32-bit libraries are inventoried; the 16 KB ELF gate applies to 64-bit ABIs.
ZIP offsets are checked for every uncompressed .so, including 32-bit libraries.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import zipfile

PUBLIC_API = {
    "LocalOverlayRelocalizer": {"add", "evaluate", "clear"},
    "CaptureOutbox": {"enqueue", "flush", "records", "retry", "removeSynced"},
    "AndroidCaptureStore": {"open"},
    "CaptureUploadAdapter": {"upload"},
    "VastuArCoreUploader": {"upload", "saveCapture"},
    "VastuArCoreCaptureView": {"showRemedies", "clearRemedies"},
    "FloorRemedyScene": set(),
    "FloorRemedyMarker": set(),
    "FloorRemedyGeometry": {"planToFloor", "interiorPoint"},
}


def public_members(data):
    """Read access flags and method names from a JVM class, without loading it."""
    assert data[:4] == b"\xca\xfe\xba\xbe", "Invalid class file"
    pos = 8

    def take(fmt):
        nonlocal pos
        value = struct.unpack_from(">" + fmt, data, pos)
        pos += struct.calcsize(">" + fmt)
        return value

    count, = take("H")
    strings = {}
    index = 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag, = take("B")
        if tag == 1:
            length, = take("H")
            strings[index] = data[pos:pos + length].decode("utf-8", errors="replace")
            pos += length
        else:
            pos += sizes[tag]
            if tag in (5, 6):
                index += 1
        index += 1
    flags, _, _ = take("HHH")
    interfaces, = take("H")
    pos += interfaces * 2

    def members():
        nonlocal pos
        total, = take("H")
        result = set()
        for _ in range(total):
            access, name, _, attributes = take("HHHH")
            if access & 1:
                result.add(strings[name])
            for _ in range(attributes):
                _, length = take("HI")
                pos += length
        return result

    members()  # fields
    return bool(flags & 1), members()


def verify_api(aar):
    with zipfile.ZipFile(aar) as archive:
        with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
            for name, expected in PUBLIC_API.items():
                member = "io/vedika/sdk/ar/" + name + ".class"
                assert member in classes.namelist(), "Missing public export: " + name
                public, methods = public_members(classes.read(member))
                assert public and expected <= methods, "Missing public members: " + name
    return sorted(PUBLIC_API)


def elf_report(data):
    assert data[:4] == b"\x7fELF" and len(data) >= 52, "Invalid/truncated ELF"
    bits = data[4]
    assert bits in (1, 2) and data[5] in (1, 2), "Unsupported ELF format"
    endian = "<" if data[5] == 1 else ">"
    offset = struct.unpack_from(endian + ("Q" if bits == 2 else "I"), data, 32 if bits == 2 else 28)[0]
    size, count = struct.unpack_from(endian + "HH", data, 54 if bits == 2 else 42)
    fmt = endian + ("IIQQQQQQ" if bits == 2 else "IIIIIIII")
    assert size >= struct.calcsize(fmt) and count > 0, "Invalid program headers"
    assert offset + count * size <= len(data), "Truncated program headers"
    loads, relro = [], []
    for i in range(count):
        p = struct.unpack_from(fmt, data, offset + i * size)
        kind = p[0]
        file_offset, address, file_size, memory, align = (p[2], p[3], p[5], p[6], p[7]) if bits == 2 else (p[1], p[2], p[4], p[5], p[7])
        if kind == 1:
            assert file_offset + file_size <= len(data), "Truncated LOAD segment"
            loads.append({"alignment": align, "offset": file_offset, "address": address})
        elif kind == 0x6474E552:
            relro.append({"end": address + memory, "remainder16k": (address + memory) % 16384})
    assert loads, "ELF has no LOAD segment"
    aligned = all(p["alignment"] >= 16384 and p["alignment"] & (p["alignment"] - 1) == 0 and
                  p["offset"] % 16384 == p["address"] % 16384 for p in loads)
    relro_aligned = all(p["remainder16k"] == 0 for p in relro)
    return {"bits": 64 if bits == 2 else 32, "loadSegments": loads, "relroSegments": relro,
            "load16k": aligned, "relro16k": relro_aligned,
            "passes16k": bits != 2 or (aligned and relro_aligned)}


def native_report(path):
    rows = []
    with zipfile.ZipFile(path) as archive, path.open("rb") as raw:
        for info in archive.infolist():
            if not info.filename.endswith(".so"):
                continue
            data = archive.read(info)
            row = {"library": info.filename, "sha256": hashlib.sha256(data).hexdigest(), **elf_report(data)}
            raw.seek(info.header_offset)
            header = raw.read(30)
            assert header[:4] == b"PK\x03\x04", "Invalid local ZIP header"
            names, extras = struct.unpack_from("<HH", header, 26)
            row["zipOffset"] = info.header_offset + 30 + names + extras
            row["compressed"] = info.compress_type != zipfile.ZIP_STORED
            row["zip16k"] = row["compressed"] or row["zipOffset"] % 16384 == 0
            if path.suffix == ".apk":
                row["passes16k"] &= row["zip16k"]
            rows.append(row)
    assert rows and any(row["bits"] == 64 for row in rows), "No 64-bit native library inventory"
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=Path)
    parser.add_argument("--native", type=Path)
    parser.add_argument("--zipalign", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    assert args.aar or args.native, "Specify an AAR or native archive"
    report = {}
    if args.aar:
        report["publicExports"] = verify_api(args.aar)
        report["aarSha256"] = hashlib.sha256(args.aar.read_bytes()).hexdigest()
    if args.native:
        report["nativeLibraries"] = native_report(args.native)
        report["nativeArchiveSha256"] = hashlib.sha256(args.native.read_bytes()).hexdigest()
    if args.zipalign:
        assert args.native and args.native.suffix == ".apk"
        result = subprocess.run([str(args.zipalign), "-c", "-P", "16", "4", str(args.native)], capture_output=True, text=True)
        report["zipalign"] = {"exit": result.returncode, "output": result.stdout + result.stderr}
    report["passed"] = all(row["passes16k"] for row in report.get("nativeLibraries", [])) and report.get("zipalign", {}).get("exit", 0) == 0
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print("Public exports:", len(report.get("publicExports", [])))
    for row in report.get("nativeLibraries", []):
        print(row["library"], "bits=" + str(row["bits"]), "LOAD=" + str(row["load16k"]),
              "RELRO=" + str(row["relro16k"]), "ZIP=" + str(row["zip16k"]),
              "PASS" if row["passes16k"] else "FAIL")
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
