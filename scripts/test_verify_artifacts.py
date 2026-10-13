import struct
import unittest
from verify_artifacts import elf_report, apply_ratchet


def library(load_align=16384, relro_end=16384):
    data = bytearray(256)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 2)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 256, 256, load_align)
    struct.pack_into("<IIQQQQQQ", data, 120, 0x6474E552, 4, 0, 0, 0, 0, relro_end, 1)
    return data


class NativePageGateTest(unittest.TestCase):
    def test_good_segments_pass(self):
        self.assertTrue(elf_report(library())["passes16k"])

    def test_load_only_green_does_not_hide_current_arcore_relro_failure(self):
        report = elf_report(library(relro_end=20480))
        self.assertTrue(report["load16k"])
        self.assertFalse(report["relro16k"])
        self.assertFalse(report["passes16k"])

    def test_bad_load_alignment_fails_even_with_aligned_relro(self):
        self.assertFalse(elf_report(library(load_align=4096))["passes16k"])

    def test_truncated_and_non_elf_fail_closed(self):
        for data in (b"", b"not an ELF", library()[:100]):
            with self.assertRaises((AssertionError, struct.error)):
                elf_report(data)

    def test_missing_load_cannot_pass(self):
        data = library()
        struct.pack_into("<I", data, 64, 0)
        with self.assertRaises(AssertionError):
            elf_report(data)

class RatchetTest(unittest.TestCase):
    def setUp(self):
        self.row = dict(library="lib/arm64-v8a/libarcore_sdk_c.so", sha256="exact",
                        zip16k=True, **elf_report(library(relro_end=20480)))
        self.known = {"libraries": [dict(abi="arm64-v8a", library="libarcore_sdk_c.so",
                       sha256="exact", check="RELRO_END", remainder=4096)]}

    def test_exact_upstream_failure_is_disclosed(self):
        self.assertTrue(apply_ratchet([self.row], self.known))
        self.assertEqual(self.row["status"], "UPSTREAM-KNOWN")
        self.assertFalse(self.row["passes16k"])

    def test_unlisted_failure_rejected(self):
        self.assertFalse(apply_ratchet([self.row], {"libraries": []}))

    def test_changed_checksum_rejected(self):
        self.row["sha256"] = "changed"
        self.assertFalse(apply_ratchet([self.row], self.known))

    def test_load_failure_rejected(self):
        self.row["load16k"] = False
        self.assertFalse(apply_ratchet([self.row], self.known))

    def test_zip_failure_rejected(self):
        self.row["zip16k"] = False
        self.assertFalse(apply_ratchet([self.row], self.known))

    def test_different_remainder_rejected(self):
        self.row["relroSegments"][0]["remainder16k"] = 8192
        self.assertFalse(apply_ratchet([self.row], self.known))

    def test_different_check_rejected(self):
        self.known["libraries"][0]["check"] = "LOAD"
        self.assertFalse(apply_ratchet([self.row], self.known))

    def test_fixed_library_requests_removal(self):
        self.row.update(elf_report(library()))
        self.row["sha256"] = "fixed"
        self.assertTrue(apply_ratchet([self.row], self.known))
        self.assertIn("remove from known list", self.row["status"])

    def test_unknown_zip_failure_rejected(self):
        self.row.update(elf_report(library()))
        self.row.update(passes16k=False, zip16k=False, library="lib/x86_64/other.so")
        self.assertFalse(apply_ratchet([self.row], self.known))


if __name__ == "__main__":
    unittest.main()
