#!/usr/bin/env python3
"""Add read-only probe diagnostics to pinned NullMoth NMHDA.

Do not force a successful probe, alter PCI identity, or change the control
flow on a successful call. Fail closed when upstream source layout changes.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch-nmhda-diagnostics.py path/to/nmhda.cpp")

path = Path(sys.argv[1])
src = path.read_text()
old_buffer = "static char gReport[512];"
if src.count(old_buffer) != 1:
    raise SystemExit("Unexpected NMHDA report buffer: upstream source changed")
src = src.replace(old_buffer, "static char gReport[2048];", 1)

start_marker = "static IOService *wrapProbe(IOService *self, IOService *provider, SInt32 *score) {"
end_marker = "static bool wrapStart(IOService *self, IOService *provider) {"
if src.count(start_marker) != 1 or src.count(end_marker) != 1:
    raise SystemExit("Unexpected NMHDA probe/start source: upstream source changed")
start = src.index(start_marker)
end = src.index(end_marker)
replacement = r'''static IOService *wrapProbe(IOService *self, IOService *provider, SInt32 *score) {
    const bool isNv = isNvidiaAudio(provider);
    if (isNv) {
        auto pci = OSDynamicCast(IOPCIDevice, provider);
        // Read the physical PCI configuration registers used by AppleHDAController::probe().
        // IORegistry's OSData device-id alone does not establish the value read by probe.
        if (pci)
            report("diag", "PCI config: vendor=%04X device=%04X (IORegistry=%04X:%04X)",
                   (unsigned)pci->extendedConfigRead16(0),
                   (unsigned)pci->extendedConfigRead16(2),
                   (unsigned)(readU32(provider, "vendor-id") & 0xFFFF),
                   (unsigned)(readU32(provider, "device-id") & 0xFFFF));
        auto before = OSDynamicCast(OSArray, self->getProperty("HwFactoryPrefixTranslation"));
        report("diag", "pre: v=%04X d=%04X rev=%04X sub=%04X layout=%08X gfx=%u trs=%u",
               (unsigned)(readU32(provider, "vendor-id") & 0xFFFF),
               (unsigned)(readU32(provider, "device-id") & 0xFFFF),
               (unsigned)(readU32(provider, "revision-id") & 0xFFFF),
               (unsigned)(readU32(provider, "subsystem-id") & 0xFFFF),
               (unsigned)readU32(provider, "layout-id"),
               provider->getProperty("hda-gfx") ? 1U : 0U,
               before ? before->getCount() : 0U);
        // This is the unmodified upstream native-ID restoration. Never force a match.
        const bool prepared = restoreNvidiaDeviceId(self, provider);
        auto entries = OSDynamicCast(OSArray, self->getProperty("HwFactoryPrefixTranslation"));
        unsigned matching = 0;
        if (entries) {
            for (unsigned i = 0; i < entries->getCount(); ++i) {
                auto entry = OSDynamicCast(OSDictionary, entries->getObject(i));
                if (entry &&
                    numberValue(entry, "LayoutID") == readU32(provider, "layout-id") &&
                    numberValue(entry, "VID") == 0x10DE &&
                    numberValue(entry, "SourceDID") == 0x9D70 &&
                    numberValue(entry, "StandInDID") == (readU32(provider, "device-id") & 0xFFFF))
                    ++matching;
            }
        }
        report("diag", "translation: prepared=%u entries=%u matching=%u",
               prepared ? 1U : 0U, entries ? entries->getCount() : 0U, matching);
        if (!prepared) {
            report("probe", "cannot prepare NVIDIA native device-id translation");
            return nullptr;
        }
    }
    auto r = FunctionCast(wrapProbe, orgProbe)(self, provider, score);
    if (isNv) {
        report("probe", "probe %s -> %s (10DE:%04X score=%d)", provider->getName(),
               r ? "accepted" : "REFUSED",
               (unsigned)(readU32(provider, "device-id") & 0xFFFF),
               score ? (int)*score : -1);
    }
    return r;
}
'''
src = src[:start] + replacement + "\n" + src[end:]
path.write_text(src)
print("NMHDA diagnostic-only probe patch applied (2 KiB report buffer)")
