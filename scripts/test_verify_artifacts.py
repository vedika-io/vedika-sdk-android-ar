import struct
import unittest
from verify_artifacts import elf_report


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


if __name__ == "__main__":
    unittest.main()
