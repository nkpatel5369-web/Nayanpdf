# NayanPDF offline verification

What was done to establish that NayanPDF works without an internet connection and does not contact external servers, what each check found, and which checks are still outstanding.

## Bottom line

- Every processing feature has been executed with **no network available at all** (Linux network namespace, no route to any host): 105 of 105 engine tests passed, covering OCR, Office conversion, PDF/A, digital signing and verification, redaction, compression, print preparation and every page operation. The full 29-scenario UI test, including text selection, markup, printing and saving, also passed under the same conditions (and its earlier 25-scenario version passed three times in a row).
- Four channels by which a desktop PDF tool can leak traffic without the user noticing have been closed and each is covered by an automated test that fails if the protection is removed: remote images in Office files, remote resources in HTML, background requests from the user interface, and background downloads by the browser engine itself (a spell-check dictionary download found on Linux on 15 October 2026, §2.4).
- On Windows, the **installed** application was tested with Windows Firewall blocking all outbound traffic for NayanPDF and every bundled engine: its self-test and the full engine suite passed, and while the app ran and displayed a document no NayanPDF process opened a single connection to a non-local address.

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

All executed on the Linux development build on 13–15 October 2026. The engine, its Python libraries, Tesseract, LibreOffice and Ghostscript on Linux are the same projects as the bundled Windows builds, but not the same binaries.

### 2.1 Entire engine test suite with no network

```
unshare -n sh -c 'env -u HTTPS_PROXY -u HTTP_PROXY python -m pytest engine/tests'
```

A fresh network namespace has no usable interface at all (even loopback is down); any attempt to reach an address fails immediately with "Network is unreachable" (confirmed before the run).

Result on 15 October 2026, current code: **105 passed, 0 failed** in 54 s (the earlier 102-test version: 102 passed in 71 s). The suite includes OCR (English, orientation detection), Word/Excel/PowerPoint conversion in both directions (LibreOffice), PDF/A conversion (Ghostscript), certificate creation, PAdES signing and verification, encryption, redaction with verification, compression, print preparation, comparison and all page operations. No test was skipped. (In this run the linked-content test of §2.2 passes trivially, because nothing can be reached; its real evidence is the networked run in §2.2.)

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

After all 29 UI scenarios (opening, editing, selecting and marking up text, redacting, signing, converting, OCR, printing, saving, batch, settings), the test makes two checks:

- The browser's own resource log: **0 requests** to anything other than the app's local schemes.
- Since 15 October 2026, on Linux, every app process (browser engine, GPU and utility processes, the Python engine) is started with its proxy setting pointing at a local recording proxy that refuses and logs every request. Any logged attempt fails the test. Result: **0 attempts**.

The second check was added because the first cannot see requests the browser engine makes on its own. It found one: on Linux, Chromium tried to download spell-check dictionaries from a Google server at start-up, even though NayanPDF never uses the spell-checker. Every browser session is now hardened the moment it is created: spell-checker off, the dictionary address pointed at a closed local port and, on Linux, no spell-check languages (`src/main/main.ts`). With the fix removed, the check reports the attempt; with it, zero. On Windows, Chromium uses the Windows spell-checker and downloads no dictionaries, and the Windows acceptance test (§3) independently observed no remote connection from any NayanPDF process.

### 2.5 The whole UI test with no network

The complete UI test (§2.4) was run inside a network namespace that has only a loopback interface (needed by the test driver to talk to the application). Every external address is unreachable. Result on 15 October 2026: **29 / 29 passed**, covering editing, text selection and markup, saving, annotation, redaction, signing (including certificate signing), conversion, OCR, printing, compression, merging, splitting, comparison and batch processing. The earlier 25-scenario version passed three times in a row under the same conditions.

### 2.6 Static scan of the code

- `engine/nayanpdf_engine`: no import of `urllib`, `http`, `requests`, `socket` or `aiohttp`; the only network-related setting is pyHanko's `allow_fetching=False`.
- `src/main`, `src/preload`: no `net` requests, `fetch` to remote hosts, `autoUpdater` or `crashReporter`.
- `package.json`: no updater, analytics or telemetry package.

Some bundled third-party libraries contain network code they never use in NayanPDF (for example aiohttp inside pyHanko's dependency tree and libcurl linked into Tesseract). They are listed in `docs/DEPENDENCIES.md`; the checks above show they are not exercised.

## 3. Windows acceptance test (executed)

`scripts/win-acceptance.ps1`, run by `.github/workflows/windows-installer.yml` on a clean GitHub Actions machine (Windows Server 2025, x64), performs the brief's §29 acceptance procedure. Result for builds 10 and 11 (15 October 2026): **67 of 67 checks passed** each (build 7: 63 of 63; build 3: 62 of 62, before later checks were added).

| Step | Result |
|---|---|
| Silent per-user install; files, Start Menu shortcut, uninstall registration, version information | Pass |
| Windows Firewall rules blocking all outbound traffic for `NayanPDF.exe`, the bundled Python, Tesseract, LibreOffice and Ghostscript (10 program rules) | Applied |
| Built-in self-test of the installed engines with traffic blocked: PDF engine, page operations, compression, qpdf, Office writers, digital-signature engine, OCR (eng, hin, guj), LibreOffice conversion, Ghostscript | 9 / 9 pass |
| Full engine test suite against the installed runtime, traffic blocked | 105 / 105 pass |
| Installed app launched (home screen, then with a PDF, which it edits and undoes); every TCP connection owned by its processes sampled throughout | Rendered and edited; **0 remote endpoints** and **0 listening ports** observed in both launches |
| Electron fuses read back from the installed `NayanPDF.exe` (after install and after reinstall) | Run-as-Node off, `NODE_OPTIONS` ignored, inspector arguments ignored, ASAR integrity check on, app loaded only from its ASAR archive, no extra `file:` privileges |
| Uninstall (user document and settings must survive), then reinstall and repeat the offline self-test | Pass |

Separately, the 29-scenario UI test passed on Windows against the development build with the bundled Windows runtime (build 11). On Windows that test checks the page-level request log; traffic from the browser engine's own processes is covered by the connection sampling above.

## 4. How you can verify it yourself on Windows

1. Disconnect the computer from the internet (or disable Wi-Fi and unplug the network cable).
2. Install from `NayanPDF_Setup.exe` and start NayanPDF from the Start Menu.
3. Open a PDF; edit a line of text; save with Save As; compress it; convert it to Word and Excel; run OCR on a scanned PDF; convert a Word file to PDF.
4. Optional, while connected: open Resource Monitor (`resmon.exe`) → Network → TCP Connections, and watch for `NayanPDF.exe`, `python.exe`, `tesseract.exe`, `soffice.bin` or `gswin64c.exe`. None should appear with a remote address.
