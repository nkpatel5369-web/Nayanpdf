# NayanPDF offline verification

What was done to establish that NayanPDF works without an internet connection and does not contact external servers, what each check found, and which checks are still outstanding.

## Bottom line

- Every processing feature has been executed with **no network available at all** (Linux network namespace, no route to any host): 102 of 102 engine tests passed, covering OCR, Office conversion, PDF/A, digital signing and verification, redaction, compression and every page operation. The full 25-scenario UI test also passed three times in a row under the same conditions.
- Three channels by which a desktop PDF tool can leak traffic without the user noticing have been closed and each is covered by an automated test that fails if the protection is removed: remote images in Office files, remote resources in HTML, and background requests from the user interface.
- **Not yet done:** the same checks on Windows against the installed `NayanPDF_Setup.exe`. They are scripted (`scripts/win-acceptance.ps1`) and run automatically in the Windows build pipeline, which has not been able to run because this session could not push to GitHub.

## 1. How the application is built to stay offline

| Component | What could reach the network | Control in NayanPDF |
|---|---|---|
| User interface (Electron / Chromium) | Page requests, background services, spell-check dictionaries, component updates | Every request is cancelled unless it targets the app's own local schemes (`app:`, `nayan-doc:`) or in-memory data (`data:`, `blob:`) — `src/main/main.ts`. Content-Security-Policy on every page. Navigation and new windows blocked. Chromium background networking, component updates, domain reliability, sync, pings, phishing checks, translation and autofill server calls switched off. Spell-checker disabled. No auto-updater, crash reporter, analytics or telemetry module exists in the code |
| Print and HTML-to-PDF windows | Remote images, fonts, scripts in the HTML | Separate sessions with the same request blocking; JavaScript disabled — `src/main/output.ts` |
| Processing engine (Python) | Library code making HTTP calls | Runs in isolated mode (`-I`) with a minimal environment and no proxy settings. NayanPDF's engine imports no network module (static scan below). `pip` is not shipped |
| Digital-signature validation (pyHanko) | Certificate revocation (CRL/OCSP), certificate download, timestamp servers | `allow_fetching=False`; the result states "Revocation not checked (offline)". Timestamping is not offered |
| OCR (Tesseract) | Language-data download | Language data bundled in the installer; Tesseract only receives local image files |
| PDF/A (Ghostscript) | File access outside the job | Runs with `-dSAFER` on local files only |
| Office conversion (LibreOffice) | Linked images or data referenced by URL inside DOCX/XLSX/PPTX; external-link updates; update check; macros | Each private LibreOffice profile is seeded with: block untrusted referer links, never update links, never recalculate on load (so `WEBSERVICE()` and similar are not evaluated), macros disabled, update check disabled — `engine/nayanpdf_engine/ops/convert.py` |
| Installer (NSIS) | Downloading components during setup | All components are inside the installer; no download plug-in is used; `publish: null` so no update feed is generated — `electron-builder.yml`, `build/installer.nsh` |
| Accounts, licensing | Activation, sign-in | None exists |

## 2. Checks executed and their results

All executed on the Linux development build on 13–14 October 2026. The engine, its Python libraries, Tesseract, LibreOffice and Ghostscript on Linux are the same projects as the bundled Windows builds, but not the same binaries.

### 2.1 Entire engine test suite with no network

```
unshare -n sh -c 'env -u HTTPS_PROXY -u HTTP_PROXY python -m pytest engine/tests'
```

A fresh network namespace has no usable interface at all (even loopback is down); any attempt to reach an address fails immediately with "Network is unreachable" (confirmed before the run).

Result: **102 passed, 0 failed** in 71 s. The suite includes OCR (English, orientation detection), Word/Excel/PowerPoint conversion in both directions (LibreOffice), PDF/A conversion (Ghostscript), certificate creation, PAdES signing and verification, encryption, redaction with verification, compression, comparison and all page operations. No test was skipped. (In this run the linked-content test of §2.2 passes trivially, because nothing can be reached; its real evidence is the networked run in §2.2.)

### 2.2 Office files with remote content (automated test)

`engine/tests/test_convert.py::test_office_conversion_never_fetches_linked_content` starts a local web server standing in for "the internet", converts a Word file whose picture is linked to that server and an Excel file with a `WEBSERVICE()` formula pointing at it, and asserts that the server receives no request.

| Run | Requests received by the stand-in server | PDF produced |
|---|---|---|
| Stock LibreOffice, default profile (control) | 1 (`/tracker.png`) | Yes |
| NayanPDF conversion (hardened profile) | **0** | Yes |
| NayanPDF with the hardening deliberately removed (sensitivity check) | 1 — test fails as intended | Yes |

The control run is the reason this protection exists: without it, opening a Word file for conversion would silently tell the file's author when and from where it was converted.

### 2.3 HTML to PDF with remote resources (UI end-to-end test)

Scenario "HTML to PDF renders offline and never fetches remote resources" in `scripts/ui-e2e.mjs`: an HTML page referencing a stylesheet and an image on a local stand-in server is converted through the real application UI. Result: **PDF created with the page text; 0 requests** reached the server.

### 2.4 User-interface network activity (UI end-to-end test)

After all 25 UI scenarios (opening, editing, annotating, redacting, signing, converting, OCR, batch, settings), the test reads the browser's own resource log. Result: **0 requests** to anything other than the app's local schemes.

### 2.5 The whole UI test with no network

The complete 25-scenario UI test (§2.4) was also run three times in a row inside a network namespace that has only a loopback interface (needed by the test driver to talk to the application). Every external address is unreachable. Result: **25 / 25 passed in each run**, covering editing, annotation, redaction, signing (including certificate signing), conversion, OCR, compression, merging, splitting, comparison and batch processing.

### 2.6 Static scan of the code

- `engine/nayanpdf_engine`: no import of `urllib`, `http`, `requests`, `socket` or `aiohttp`; the only network-related setting is pyHanko's `allow_fetching=False`.
- `src/main`, `src/preload`: no `net` requests, `fetch` to remote hosts, `autoUpdater` or `crashReporter`.
- `package.json`: no updater, analytics or telemetry package.

Some bundled third-party libraries contain network code they never use in NayanPDF (for example aiohttp inside pyHanko's dependency tree and libcurl linked into Tesseract). They are listed in `docs/DEPENDENCIES.md`; the checks above show they are not exercised.

## 3. Windows acceptance test (scripted, not yet executed)

`scripts/win-acceptance.ps1`, run by `.github/workflows/windows-installer.yml` on a clean `windows-latest` machine, performs the brief's §29 acceptance procedure:

1. Silent per-user install of `NayanPDF_Setup.exe`; checks files, Start Menu shortcut, uninstall registration and version.
2. Adds Windows Firewall rules that **block all outbound traffic** for `NayanPDF.exe`, the bundled Python, Tesseract, LibreOffice and Ghostscript.
3. Runs the built-in self-test and the full engine test suite against the **installed** runtime.
4. Launches the installed application on a test PDF, takes screenshots, and samples every TCP connection owned by NayanPDF's processes for the duration; any connection to a non-loopback address fails the test.
5. Uninstalls (checking that user documents survive and shortcuts are removed), then reinstalls.

Separately, the pipeline runs the 25-scenario UI test on Windows against the development build with the bundled Windows runtime. Results are published as `acceptance/report.md` with the installer. **Until that run exists, Windows offline behaviour is designed and statically verified but not demonstrated.**

## 4. How you can verify it yourself on Windows

1. Disconnect the computer from the internet (or disable Wi-Fi and unplug the network cable).
2. Install from `NayanPDF_Setup.exe` and start NayanPDF from the Start Menu.
3. Open a PDF; edit a line of text; save with Save As; compress it; convert it to Word and Excel; run OCR on a scanned PDF; convert a Word file to PDF.
4. Optional, while connected: open Resource Monitor (`resmon.exe`) → Network → TCP Connections, and watch for `NayanPDF.exe`, `python.exe`, `tesseract.exe`, `soffice.bin` or `gswin64c.exe`. None should appear with a remote address.
