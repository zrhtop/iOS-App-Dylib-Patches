# AltStore Classic 2.3 / PR #1835 - iPhone + LiveContainer test

This is an **experimental** build of `altstoreio/AltStore` PR #1835. It is not a release from AltStore or LiveContainer.

## Build artifacts

- `AltStore-Classic-2.3-PR1835-unsigned.ipa` is the standard upstream app.
- `AltStore-Classic-2.3-PR1835-LiveContainer-test-unsigned.ipa` is the same executable with unsupported `PlugIns/`, `Watch/`, and `WatchKitSupport/` removed from the unsigned IPA.

This difference does **not** guarantee LiveContainer compatibility, entitlement support, or the ability to refresh the LiveContainer host.

## iPhone-only test procedure

1. **Do not delete or overwrite** a working AltStore, LiveContainer, or any existing app.
2. Download the LiveContainer-test build from the successful GitHub Actions run. GitHub artifact downloads are ZIP archives; extract the ZIP to find the IPA.
3. Open LiveContainer Settings and run **JIT-Less Mode Diagnose**. It must pass before testing a new guest app.
4. From LiveContainer Apps, tap `+` and import the test IPA. Prefer a fresh app data container.
5. Attempt to launch the guest app. If it crashes, record the error or crash log before changing anything.
6. Only if the UI launches reliably, test Settings and Remote AltServer connectivity. For any Apple ID test, prefer an account with no valuable data due to LiveContainer's known incomplete guest sandbox isolation.
7. Test Refresh only on a disposable test app. **Do not use LiveContainer itself as the first refresh target.**

## Known limitations

- An unsigned build relies on LiveContainer correctly signing it; an IPA alone is not a valid installed/signed app.
- LiveContainer cannot register guest app extensions or assign guest entitlements to the host.
- iOS app refresh/install actions may require URL scheme handling or pairing and certificate access unavailable to a guest app.
- If the LiveContainer host's 7-day certificate has expired, an AltStore guest **cannot launch** to refresh it, even if guest signing works.
- The app may access sensitive Apple ID or provisioning information. Guest apps in LiveContainer are not fully isolated; do not use untrusted tweaks or other guest apps in the same host.
- `-80045` is under investigation and PR #1835 is still experimental.

## Report test results

Record iOS version, LiveContainer version, JIT-Less diagnosis, IPA variant, launch error, and whether login, signing, and refresh complete. Remove Apple ID, pairing secrets, cookies, certificates, and tokens from logs before sharing.
