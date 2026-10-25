#!/usr/bin/env python3
"""Only for the isolated RTX 4060 Ti HDAU-rename experiment.

Uses Lilu's WIOKit::renameDevice in AppleHDAController::probe. It fixes the
IORegistry entry name and compatible list, just as AppleALC does. It does
NOT force probe to succeed or change NVIDIA's PCI device ID.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch-nmhda-hdau.py path/to/nmhda.cpp")
path = Path(sys.argv[1])
src = path.read_text()
header = "#include <Headers/kern_util.hpp>\n"
if src.count(header) != 1:
    raise SystemExit("Unexpected upstream NMHDA include layout")
src = src.replace(header, header + "#include <Headers/kern_iokit.hpp>\n", 1)

needle = "    const bool isNv = isNvidiaAudio(provider);\n"
if src.count(needle) != 1:
    raise SystemExit("Expected single diagnostic wrapProbe implementation")
inject = """    const bool isNv = isNvidiaAudio(provider);
    // AppleALC normally names NVIDIA digital audio functions HDAU.
    // Restrict this adjustment to NVIDIA HDA devices whose original name differs.
    if (isNv && (!provider->getName() || strcmp(provider->getName(), "HDAU") != 0)) {
        bool renamed = WIOKit::renameDevice(provider, "HDAU");
        report("diag", "HDAU name/compatible repair=%u now=%s", renamed ? 1U : 0U,
               provider->getName() ? provider->getName() : "(none)");
    }
"""
src = src.replace(needle, inject, 1)
path.write_text(src)
print("Experimental NVIDIA HDAU IORegistry rename patch applied (no forced probe)")
