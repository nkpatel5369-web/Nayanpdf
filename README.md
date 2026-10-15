# NayanPDF

A fully offline PDF editor and document toolkit for Windows 10 and 11 (64-bit). Open, edit existing text and images, organise pages, compress, convert to and from Word, Excel and PowerPoint, OCR scanned documents, annotate, sign (image signatures and certificate-based digital signatures), protect, permanently redact, compare and batch-process PDFs. All processing happens on your computer; NayanPDF has no account, activation, subscription, telemetry or update check, and never downloads anything.

| Document | Purpose |
|---|---|
| `docs/FEATURES.md` | Feature register: status of every feature, with test evidence |
| `docs/TEST_REPORT.md` | Tests executed, results, failures and tests not yet run |
| `docs/OFFLINE_VERIFICATION.md` | How offline operation was established |
| `docs/DEPENDENCIES.md` | Every bundled component, its licence and distribution conditions |

## Current delivery status

- **Windows installer:** built, installed and tested on a clean Windows machine by `.github/workflows/windows-installer.yml`. Every successful build is published as a GitHub pre-release (`v1.0.0-build.N`) with `NayanPDF_Setup.exe`, its SHA-256 and the acceptance report. Use the newest pre-release.
- **Evidence on Windows (build 10):** the 105 engine tests pass against the bundled runtime and again against the installed copy with outbound traffic blocked; the 67-point acceptance test covering install, the security switches compiled into `NayanPDF.exe`, offline self-test, app launch with zero network connections and zero listening ports, uninstall and reinstall passes; and the 28-scenario UI test, including text selection, markup and printing, passes.
- **Not yet verified:** installation under a standard (non-administrator) Windows account, and Windows 10 or Windows 11 desktop editions specifically. The CI machine is Windows Server 2025 (Windows 11 kernel 10.0.26100).

## 1. Installing

1. Download `NayanPDF_Setup.exe` (and `NayanPDF_Setup.exe.sha256`) from the repository's Releases page.
2. Optional: check the file hash in PowerShell: `Get-FileHash .\NayanPDF_Setup.exe` and compare with the `.sha256` file.
3. Double-click `NayanPDF_Setup.exe`. Accept the licence, choose the folder (default: `%LOCALAPPDATA%\Programs\NayanPDF`) and, on the last page, tick "Create a desktop shortcut" if you want one.
4. Start NayanPDF from the Start Menu.

No administrator rights are needed and nothing is downloaded during or after installation. The installer is not code-signed, so Windows SmartScreen may show "Windows protected your PC": choose **More info → Run anyway** (signing requires a paid certificate; see Known limitations).

During installation NayanPDF registers itself as an available program for `.pdf` files. It does not take over your default PDF viewer; set that in Windows Settings → Apps → Default apps if you want to.

## 2. Using NayanPDF

### Opening documents

Open with **Open** (Ctrl+O), by dropping files on the window, from the recent list on Home, or with "Open with → NayanPDF" in Explorer. Each document opens in its own tab. Password-protected files ask for the password.

### The document workspace

The toolbar switches between modes:

| Mode | What it does |
|---|---|
| View | Read, search (Ctrl+F), zoom, rotate the view, print (Ctrl+P). Select text by dragging (or double-click a word, Ctrl+A for the page): a small toolbar offers Copy, Highlight, Underline, Strikethrough, Squiggly, Note and Redact; right-click gives the same actions plus zoom, rotate and print |
| Edit | Click a line of existing text to change it in place (original font where possible); drag to move it; select images to move, resize, replace or delete; add text and images; find and replace |
| Comment | Highlight, underline, strike out or squiggly: choose the tool and drag across the text, as in Acrobat (marks follow the text line by line). Notes, text boxes, callouts, lines, arrows, shapes, freehand, stamps, initials; flatten |
| Pages | Thumbnails: drag to reorder, rotate, delete, duplicate, insert blank pages or pages from another PDF, extract |
| Forms | Fill text fields, check boxes, radio buttons, lists; flatten |
| Redact | Mark text, areas or patterns (PAN, GSTIN, Aadhaar, IFSC, account and card numbers, e-mail, phone, dates); review; apply permanently with verification |
| Sign | Draw, type or import a signature image and place it; or apply a certificate-based digital signature (.pfx file or USB token) and verify signatures |

### Printing

Ctrl+P (or the printer button, or right-click → Print) opens NayanPDF's print dialog, laid out like Acrobat's:

- **Printer** (your Windows default or any installed printer; NayanPDF remembers your choice), **copies** and collate, **black and white**.
- **Pages to print:** all, current page or a range such as `1-3, 5, 8-`, with odd/even pages only and reverse order.
- **Page sizing & handling:** Fit, Actual size, Shrink oversized pages, Custom scale (%), or Multiple pages per sheet (2, 4, 6, 9 or 16, horizontal or vertical order, optional page border).
- **Orientation** (auto/portrait/landscape), **paper size** (A4, A3, A5, Letter, Legal), **print on both sides** (long or short edge), **comments & forms** (document and markups, document only, form fields only) and **quality** (150/300/600 dpi).
- A **preview** shows each sheet exactly as it will print. **Print** sends it straight to the chosen printer. For printer-specific options (paper tray, stapling, colour profiles) use **Printer settings (Windows dialog)…**, which opens the Windows dialog with the same layout.

Every change creates a revision you can undo (Ctrl+Z) and redo (Ctrl+Y). **Nothing touches your original file until you save.** Save As (Ctrl+Shift+S) writes a new file; Save (Ctrl+S) on the original asks for confirmation first.

**More tools** in the toolbar adds watermarks, headers/footers, page numbers, Bates numbers, backgrounds and borders (with a live preview), passwords and permissions, compression, OCR and properties.

### Tools, Convert and Batch

- **Tools** (left rail) has single-purpose tools: merge, split, compress, OCR, protect/unlock, compare, extract images, watermark and numbering, properties/metadata and utilities (repair, PDF/A, linearise, attachments, bookmarks, page labels, blank-page removal, page-size standardisation and cropping).
- **Convert** turns PDFs into Word, Excel/CSV, PowerPoint, images (PNG, JPEG, TIFF, BMP, WebP), text, HTML and Markdown, and turns Office files, images, text and HTML into PDF.
- **Batch** applies one operation to many files or whole folders with a naming rule. One file failing does not stop the rest; failed files can be retried.

Results go next to the source file unless you choose another folder (or set a default in Settings). NayanPDF never overwrites an existing file: it adds a number, for example `report_compressed (1).pdf`.

### Reading conversion results professionally

- **PDF to Excel** converts a cell to a number only when the printed text is an unambiguous number (Indian or Western grouping, negatives in brackets, Dr/Cr kept as a format). Account numbers, GSTIN/PAN, invoice references and anything with leading zeros stay as text. The **Extraction Report** sheet shows, page by page, how many printed digits reached the cells and flags pages to check. Treat the output as a working copy that must be reviewed, not as a verified accounting record.
- **OCR** reports a confidence figure per page and flags weak pages. OCR text is never guaranteed to be correct.
- **PDF/A** output passes basic checks but is not formally validated; do not file it where certified compliance is required without validating it in a dedicated tool.
- **Signature images** show intent only. Only the certificate-based **digital signature** protects the document against later changes; NayanPDF says which is which wherever signatures appear.

### Keyboard shortcuts

Ctrl+O open, Ctrl+S save, Ctrl+Shift+S save as, Ctrl+P print, Ctrl+F search, Ctrl+A select the page's text, Ctrl+C copy, Ctrl+Z undo, Ctrl+Y or Ctrl+Shift+Z redo, Ctrl+Plus / Ctrl+Minus zoom, Ctrl+0 fit width, Ctrl+W close tab. Shortcuts are ignored while you are typing in a text field.

### Where NayanPDF keeps its data

| Data | Location |
|---|---|
| Settings, recent list, saved signatures (encrypted with your Windows account) | `%APPDATA%\NayanPDF` |
| Log file (no document content, passwords or file contents) | `%APPDATA%\NayanPDF\logs` |
| Undo revisions and job working files | `%TEMP%\NayanPDF` (removed when documents close and at the next start; Settings shows the size and can clean it up) |

## 3. Uninstalling

Use Windows Settings → Apps → Installed apps → NayanPDF → Uninstall (Windows 10: Settings → Apps → Apps & features). This removes the program, its shortcuts and its temporary folder. **Your documents are never touched.** Settings and saved signatures in `%APPDATA%\NayanPDF` are kept so a reinstall picks them up; delete that folder manually to remove them as well.

## 4. Known limitations

- **Windows coverage:** tested on Windows Server 2025 in CI; not yet on a Windows 10 or 11 desktop or under a standard user account.
- **Editing existing text** depends on how the PDF was made. Text is edited in its original font when the embedded font contains the needed characters; otherwise a similar font is substituted and NayanPDF tells you. Rotated or vertical text, text drawn as outlines and scanned pages cannot be edited in place (OCR or Convert to Word are offered instead). Long replacements are not reflowed into following lines; use paragraph mode or convert to Word for heavy rewording. Resizing an existing paragraph box is not yet available in the editor.
- **Bold/italic** on edited text uses a substitute font family, because PDFs rarely embed the bold variant.
- **PDF to Word/PowerPoint** quality depends on the source layout; complex multi-column pages may need tidying.
- **PDF to Excel** does not merge cells in the output; spanning headings go into the first cell of the span.
- **Office to PDF** uses the bundled LibreOffice. Fonts not installed on your computer are substituted, which can change line breaks. Linked images and web data in Office files are deliberately not fetched.
- **HTML to PDF** renders saved HTML without running scripts or loading internet resources.
- **Digital signatures:** revocation (CRL/OCSP) is not checked and trusted timestamps are not added, because both require the internet. USB tokens / smart cards (PKCS#11) are supported in code but were not tested with real hardware; the token vendor's driver must be installed.
- **Form scripts** (JavaScript in PDFs) are never executed; calculated fields are not recalculated.
- **PDF/A** is not formally validated (veraPDF needs Java and is not bundled).
- **Printing** sends pages as images at the chosen resolution (150/300/600 dpi), which is sharp on paper but means the printer does not receive the PDF's text as text. Printer-specific options are set through the optional Windows dialog. Printing several documents in one action is not available.
- **OCR languages bundled:** English, Hindi, Gujarati (plus orientation detection). Other Tesseract languages can be added offline by copying `.traineddata` files into `<install folder>\resources\tesseract\tessdata`.
- **Code signing:** the installer and executable are not signed (no paid certificate), so SmartScreen warns on first run.
- **Licence:** NayanPDF is AGPL-3.0 because it bundles MuPDF and Ghostscript under their AGPL terms. Free for personal use; if you distribute it, you must also offer its source (see `docs/DEPENDENCIES.md`).

## 5. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| "Windows protected your PC" when running the installer | Unsigned installer. More info → Run anyway |
| "Password required" or "Permission restricted" | The PDF is encrypted or restricts that action. Enter the open password, or the owner password to lift restrictions. NayanPDF does not bypass protection |
| "No editable text was found" when editing | The page is a scan or the text is outlines. Run OCR, then use Add Text, or Convert to Word |
| A replacement character looks different | The original embedded font lacks that character; a substitute font was used (the warning says so) |
| Office to PDF fails or hangs | Close other LibreOffice windows; delete `%APPDATA%\NayanPDF\office-profiles` and retry. Password-protected Office files are not supported |
| OCR result is poor | Rescan at 300 dpi, choose the correct language in Settings, or try 400 dpi in the OCR tool |
| "OCR language not installed" | Copy the `.traineddata` file into the tessdata folder (see Known limitations) |
| A job seems stuck | Open Activity (top-right) and cancel it; cancelling stops the engine process and removes partial output |
| Output name has "(1)" | A file with that name already existed; NayanPDF never overwrites |
| Temp folder is large | Close documents you no longer need, or Settings → Temporary files → "Clean up leftovers now" |
| Anything else | Check `%APPDATA%\NayanPDF\logs\nayanpdf.log` (contains operation names and error codes, never document contents or passwords) |

## 6. Development

### Architecture

```
NayanPDF.exe (Electron 43)
├── main process (TypeScript, src/main)
│   ├── security: sandbox, context isolation, request blocking, IPC validation, path access registry
│   ├── document sessions: revisions for undo/redo, atomic save, nayan-doc:// streaming to the viewer
│   ├── job manager: queue, concurrency, cancellation (process-tree kill), partial-output cleanup
│   └── engine pool: persistent query/edit workers + one process per long job
├── preload (src/preload): allowlisted channels only, file-drop path registration
├── renderer (React 19 + PDF.js 5, src/renderer): workspace, tools, convert, batch, settings
└── engine (Python 3.13, engine/nayanpdf_engine), JSON lines over stdin/stdout, no ports
    ├── PyMuPDF/MuPDF: rendering, editing, annotations, forms, redaction, OCR layer, compression
    ├── pikepdf/qpdf: structure, repair, linearisation, image content-stream edits, XMP
    ├── pdf2docx, openpyxl, python-docx, python-pptx: Office output
    ├── pyHanko: PAdES signatures and validation (offline)
    ├── Tesseract 5 (bundled): OCR
    ├── LibreOffice (bundled): Office → PDF
    └── Ghostscript (bundled): PDF/A
```

Every engine operation writes to a temporary file, validates the result (reopens it, checks page count and expected content), and only then moves it to the destination.

### Prerequisites

- Node.js 22 and npm
- Python 3.13 with the packages in `engine/requirements-win.txt` (on Linux/macOS install the same versions with pip), plus `pytest` and `reportlab` for tests
- For OCR, Office conversion and PDF/A during development: Tesseract, LibreOffice and Ghostscript on `PATH`, or point to them with `NAYANPDF_TESSERACT`, `NAYANPDF_TESSDATA`, `NAYANPDF_SOFFICE`, `NAYANPDF_GS`, `NAYANPDF_GS_ICC`

### Build and run from source

```
npm ci
npm run build          # copies PDF.js assets, builds renderer (Vite) and main/preload (esbuild)
npm run typecheck
npm start              # runs the app; set NAYANPDF_PYTHON to your Python if it is not on PATH
```

### Tests

```
python -m pytest engine/tests -q        # engine: 105 tests on synthetic documents
node scripts/ui-e2e.mjs                 # UI end-to-end: 28 scenarios (Linux: run under xvfb-run)
```

The engine tests generate their own fixtures (financial statements, bank statements spanning pages, scans, forms, encrypted and damaged files) under `engine/tests/_fixtures`; no real client document is used. The UI test needs the engine fixtures, so run the engine tests first. It drives the real application and writes screenshots and `results.json` to `test-results/ui`.

### Windows packaging

On Windows (the CI workflow does exactly this):

```
python -m pip install pefile
npm ci
python scripts/build_runtime_win.py     # assembles runtime\win: Python, Tesseract, Ghostscript, LibreOffice
npm run build
npx electron-builder --win nsis --x64 --publish never
# -> release\NayanPDF_Setup.exe
powershell -File scripts\win-acceptance.ps1 -Installer release\NayanPDF_Setup.exe -ReportDir acceptance
```

`build_runtime_win.py` downloads each component from its official source, verifies pinned SHA-256 hashes (and the Authenticode signature of the LibreOffice installer), extracts it without running any third-party installer except LibreOffice's administrative extract (`msiexec /a`, no system changes), and records versions and hashes in `runtime/win/MANIFEST.json`, which ships inside the application. Options: `--skip-libreoffice`, `--libreoffice-msi <file>`, `--only <component>`.

Pushing to a `claude/**` or `main` branch runs `.github/workflows/windows-installer.yml`: build, engine tests against the bundled runtime, UI test on Windows, installer build, install/offline/uninstall/reinstall acceptance test, then a pre-release with `NayanPDF_Setup.exe`, its hash and the acceptance report.

### Dependency management

- Python packages are pinned in `engine/requirements-win.txt` and installed from Windows wheels at build time; nothing is installed on the user's machine.
- Tesseract, its language data, Ghostscript and the Python runtime are pinned by version and SHA-256 in `scripts/build_runtime_win.py`. LibreOffice uses the latest stable release at build time (verified against the published `.sha256` and the Document Foundation's signature); set `LIBREOFFICE_VERSION` to pin it.
- Node packages are pinned by `package-lock.json`.
- To upgrade a component: change its version and hash, rebuild, run both test suites and the Windows workflow, and update `docs/DEPENDENCIES.md`.

### Repository layout

```
engine/                 Python processing engine and its tests
src/main, src/preload   Electron main process and preload bridge
src/renderer            React user interface
src/shared              IPC channel list and shared types
scripts/                build, packaging, Windows acceptance and UI test scripts
build/                  installer resources (icon, licence page, NSIS additions)
docs/                   feature register, test report, offline verification, dependencies
```
