#!/usr/bin/env python3
"""Read-only AppleHDAController 600.2 gControllerDescriptions verification.

Checks whether the 57 x 48-byte controller table used by the real probe
contains 10DE:22BD after the existing Lilu patch. Only inspect the symbol
when its entire expected range fits in the reported loaded image.
Never force probe success or write to the table.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch-nmhda-table-check.py path/to/nmhda.cpp")
path = Path(sys.argv[1])
src = path.read_text()
needle = "    if (patched) rematchNvidiaAudio();"
if src.count(needle) != 1:
    raise SystemExit("Unexpected NMHDA patcher layout; refuse to modify")

inject = r'''    if (patched) {
        // The symbol and 57-entry, 48-byte layout were observed in the 600.2
        // AppleHDAController provided with MyKextInstaller v1.9. This test is
        // read-only. Do not dereference memory outside the loader's image range.
        const auto sym = patcher.solveSymbol(index, "__ZL23gControllerDescriptions", address, size);
        patcher.clearError();
        constexpr size_t RowBytes = 48;
        constexpr size_t Rows = 57;
        constexpr size_t Bytes = Rows * RowBytes;
        const bool bounded = sym && address && size >= Bytes &&
                             sym >= address && (sym - address) <= (size - Bytes);
        unsigned matched22bd = 0;
        unsigned oldKepler = 0;
        if (bounded) {
            const auto table = reinterpret_cast<const uint8_t *>(sym);
            for (size_t i = 0; i < Rows; ++i) {
                uint16_t ven = 0, did = 0;
                memcpy(&ven, table + i * RowBytes, sizeof(ven));
                memcpy(&did, table + i * RowBytes + 2, sizeof(did));
                if (ven == 0x10DE && did == 0x22BD) ++matched22bd;
                if (ven == 0x10DE && did == 0x0E0A) ++oldKepler;
            }
        }
        report("diag", "controller table: symbol=%u bounded=%u matches22BD=%u oldKepler0E0A=%u",
               sym ? 1U : 0U, bounded ? 1U : 0U, matched22bd, oldKepler);
    }
    if (patched) rematchNvidiaAudio();'''
src = src.replace(needle, inject, 1)
path.write_text(src)
print("Read-only AppleHDAController lookup-table diagnostics applied")
