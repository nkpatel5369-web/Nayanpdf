# X PDF

X PDF (called NayanPDF until version 1.1) is a fully offline PDF editor and document toolkit for Windows 10 and 11 (64-bit). Open, edit existing text and images, organise pages, compress, convert to and from Word, Excel and PowerPoint, OCR scanned documents, annotate, sign (image signatures and certificate-based digital signatures), protect, permanently redact, compare and batch-process PDFs. All processing happens on your computer; X PDF has no account, activation, subscription, telemetry or update check, and never downloads anything.

| Document | Purpose |
|---|---|
| `docs/FEATURES.md` | Feature register: status of every feature, with test evidence |
| `docs/TEST_REPORT.md` | Tests executed, results, failures and tests not yet run |
| `docs/OFFLINE_VERIFICATION.md` | How offline operation was established |
| `docs/DEPENDENCIES.md` | Every bundled component, its licence and distribution conditions |

## Current delivery status

- **Windows installer:** built, installed and tested on a clean Windows machine by `.github/workflows/windows-installer.yml`. Every successful build is published as a GitHub pre-release (`v1.0.0-build.N`) with `X-PDF_Setup.exe`, its SHA-256 and the acceptance report. Use the newest pre-release.
- **Evidence on Windows (build 12):** the 105 engine tests pass against the bundled runtime and again against the installed copy with outbound traffic blocked; the 67-point acceptance test covering install, the security switches compiled into `X-PDF.exe`, offline self-test, app launch with zero network connections and zero listening ports, uninstall and reinstall passes; and the 32-scenario UI test, including text selection, markup, printing, saving and the Acrobat view modes (full screen included), passes.
- **Not yet verified:** installation under a standard (non-administrator) Windows account, and Windows 10 or Windows 11 desktop editions specifically. The CI machine is Windows Server 2025 (Windows 11 kernel 10.0.26100).

## 1. Installing

1. Download `X-PDF_Setup.exe` (and `X-PDF_Setup.exe.sha256`) from the repository's Releases page.
2. Optional: check the file hash in PowerShell: `Get-FileHash .\X-PDF_Setup.exe` and compare with the `.sha256` file.
3. Double-click `X-PDF_Setup.exe`. Accept the licence, choose the folder (default: `%LOCALAPPDATA%\Programs\X PDF`) and, on the last page, tick "Create a desktop shortcut" if you want one.
4. Start X PDF from the Start Menu.

No administrator rights are needed and nothing is downloaded during or after installation. The installer is not code-signed, so Windows SmartScreen may show "Windows protected your PC": choose **More info → Run anyway** (signing requires a paid certificate; see Known limitations).

During installation X PDF registers itself as an available program for `.pdf` files. It does not take over your default PDF viewer; set that in Windows Settings → Apps → Default apps if you want to.

## 2. Using X PDF

### Opening documents

Open with **Open** (Ctrl+O), by dropping files on the window, from the recent list on Home, or with "Open with → X PDF" in Explorer. Each document opens in its own tab (Ctrl+Tab moves between them). Password-protected files ask for the password.

A document opens with only its pages on screen, at Acrobat's *Automatic* zoom. As in Acrobat, a PDF can ask to open with its bookmarks, thumbnails or attachments showing, in a two-page layout or on a given page, and X PDF honours that. Many PDFs exported from Word or LibreOffice ask for their bookmarks. If you never want the panel, choose Settings → Page display → *Navigation panel when a document opens* → **Never**.

The **View** menu holds the page display options (single page, single page continuous, two page, two page scrolling, cover page), the zoom presets, rotate view, go to page, both panels, **Read mode** (Ctrl+H: toolbars hidden, Esc to leave), **Full screen** (Ctrl+L: one page at a time on black, arrow keys to turn pages, Esc to leave) and **Read Out Loud**. Hold the space bar and drag to move the page.

### The document workspace

The layout follows the current Acrobat (2023 and later):

| Area | What is there |
|---|---|
| Menu (☰, top left) | Create PDF, Scan to PDF, Open, Save, Save As, Print, Protect, Document properties, Close, All tools, Search across files, Combine files, Batch processing, Saved actions, Preferences, Exit |
| Tabs | Home, then one tab per open document |
| Document bar | **All tools** (Shift+F4) on the left with the active tool shown as a chip; Undo/Redo, Find, View, Save, Save As and Print on the right |
| All tools pane (left, closed until you open it) | Edit text & images, Add comments, Organize pages, Fill in a form, Prepare a form, Sign, Redact, Protect, Watermark/header/page numbers, Compress, Export/convert, OCR, Combine, Compare, Search across files, Print; **View more** adds Split, Create PDF, Scan to PDF, Extract images, Properties & metadata, More utilities, Batch processing and Saved actions |
| Right-hand rail (F4) | Comments, Page thumbnails, Bookmarks, Attachments, Document properties (Ctrl+D), one at a time |
| Quick tools (floating on the page) | Select, Sticky note, Highlight, Draw, Text box, Sign, without opening a pane |
| Page controls (bottom right) | Page number, previous/next, zoom, fit width, fit page, rotate |

What the tools do:

| Tool | What it does |
|---|---|
| View (no tool) | Read, search (Ctrl+F), zoom, rotate the view, print. Select text by dragging (or double-click a word, Ctrl+A for the page): a small toolbar offers Copy, Highlight, Underline, Strikethrough, Squiggly, Note and Redact; right-click gives the same actions plus zoom, rotate and print |
| Edit text & images | Click a line of existing text to change it in place (original font where possible); drag to move it; select images to move, resize, replace or delete; add text and images; find and replace; **Link**: drag a box and link it to a page or a web address |
| Add comments | Highlight, underline, strike out or squiggly across the text; notes, text boxes, callouts, lines, arrows, shapes, freehand, stamps, initials; flatten |
| Comments panel (right) | Every comment with its replies; reply, set a review status (Accepted, Rejected, Cancelled, Completed, None), tick a check mark; filter by text, type, reviewer and status, sort by page, date, reviewer or type; export to and import from **XFDF**, a **comment summary** PDF, a **CSV** list for Excel |
| Organize pages | Thumbnails: drag to reorder, rotate, delete, duplicate, insert blank pages or pages from another PDF, extract |
| Fill in a form | Fill text fields, check boxes, radio buttons, lists; flatten |
| Prepare a form | Add text fields, check boxes, radio button groups, drop-downs, list boxes and signature fields; set name, tooltip, required, read only, multi-line, character limit and choices; move, resize, delete. **Detect form fields** suggests fields from underlines, ruled lines, boxes and check squares next to labels; each suggestion is accepted or dropped on the page |
| Redact | Mark text, areas or patterns (PAN, GSTIN, Aadhaar, IFSC, account and card numbers, e-mail, phone, dates); review; apply permanently with verification |
| Sign | Draw, type or import a signature image and place it; or apply a certificate-based digital signature (.pfx file or USB token) and verify signatures |
| Document properties (Ctrl+D) | Properties and the **initial view** saved in the PDF: navigation panel, page layout, magnification, open-to page, show the document title |
| Read Out Loud (View menu) | Reads the page or the document to the end with the speech voices installed in Windows (Ctrl+Shift+V page, Ctrl+Shift+B to the end, Ctrl+Shift+C pause, Ctrl+Shift+E stop); voice and speed in Settings |

### Printing

Ctrl+P (or the printer button, or right-click → Print) opens X PDF's print dialog, laid out like Acrobat's:

- **Printer** (your Windows default or any installed printer; X PDF remembers your choice), **copies** and collate, **black and white**.
- **Pages to print:** all, current page or a range such as `1-3, 5, 8-`, with odd/even pages only and reverse order.
- **Page sizing & handling:** Fit, Actual size, Shrink oversized pages, Custom scale (%), or Multiple pages per sheet (2, 4, 6, 9 or 16, horizontal or vertical order, optional page border).
- **Orientation** (auto/portrait/landscape), **paper size** (A4, A3, A5, Letter, Legal), **print on both sides** (long or short edge), **comments & forms** (document and markups, document only, form fields only) and **quality** (150/300/600 dpi).
- A **preview** shows each sheet exactly as it will print. **Print** sends it straight to the chosen printer. For printer-specific options (paper tray, stapling, colour profiles) use **Printer settings (Windows dialog)…**, which opens the Windows dialog with the same layout.

Every change creates a revision you can undo (Ctrl+Z) and redo (Ctrl+Y). **Nothing touches your original file until you save.** Save As (Ctrl+Shift+S) suggests the document's own name in its own folder, as Acrobat does; type a new name to keep the original. Replacing the original asks once per document; after you agree, Ctrl+S saves straight to it until you close the document. The Settings option *Allow "Save" to overwrite the original file without an extra warning* removes that question.

**More tools** in the toolbar adds watermarks, headers/footers, page numbers, Bates numbers, backgrounds and borders (with a live preview), passwords and permissions, compression, OCR and properties.

### Tools, Convert, Batch, Saved actions, Search and Scan

- **Tools** (left rail) has single-purpose tools: merge, split, compress, OCR, protect/unlock, compare, extract images, watermark and numbering, properties/metadata, Prepare a Form, Scan to PDF, Search Across Files and utilities (repair, PDF/A, linearise, attachments, bookmarks, page labels, blank-page removal, page-size standardisation and cropping).
- **Convert** turns PDFs into Word, Excel/CSV, PowerPoint, images (PNG, JPEG, TIFF, BMP, WebP), text, HTML and Markdown, and turns Office files, images, text and HTML into PDF.
- **Batch** applies one operation to many files or whole folders with a naming rule. One file failing does not stop the rest; failed files can be retried.
- **Saved actions** (Acrobat's Action Wizard) run several steps in order on files or a folder and keep the list for next time: OCR, compress, watermark, header & footer, page numbers, redact PAN/Aadhaar and other patterns, remove blank pages, rotate, flatten, set or remove document properties, PDF/A, password. Five ready-made actions are included (make scans searchable, client copy, mask PAN and Aadhaar, archive as PDF/A, confidential with page numbers). A password step asks for the password at each run; passwords are never saved.
- **Search across files** (Ctrl+Shift+F) finds a word, number or phrase in every PDF of a folder (with sub-folders) or in all open documents, optionally in comments too; a result opens the file at that page with the term highlighted. Password-protected and scanned files are listed as not searched, with the reason.
- **Scan to PDF** takes pages from the scanner connected to the computer (flatbed or document feeder; colour, greyscale or black and white; 150 to 600 dpi), lets you reorder or remove pages and saves one PDF, searchable if OCR is ticked. It uses Windows Image Acquisition, the scanner support built into Windows.

Results go next to the source file unless you choose another folder (or set a default in Settings). X PDF never overwrites an existing file: it adds a number, for example `report_compressed (1).pdf`.

### Reading conversion results professionally

- **PDF to Excel** converts a cell to a number only when the printed text is an unambiguous number (Indian or Western grouping, negatives in brackets, Dr/Cr kept as a format). Account numbers, GSTIN/PAN, invoice references and anything with leading zeros stay as text. The **Extraction Report** sheet shows, page by page, how many printed digits reached the cells and flags pages to check. Treat the output as a working copy that must be reviewed, not as a verified accounting record.
- **OCR** reports a confidence figure per page and flags weak pages. OCR text is never guaranteed to be correct.
- **PDF/A** output passes basic checks but is not formally validated; do not file it where certified compliance is required without validating it in a dedicated tool.
- **Signature images** show intent only. Only the certificate-based **digital signature** protects the document against later changes; X PDF says which is which wherever signatures appear.

### Keyboard shortcuts

The assignments follow Acrobat on Windows. Shortcuts are ignored while you are typing in a text field.

| Area | Shortcuts |
|---|---|
| Files | Ctrl+O open, Ctrl+S save, Ctrl+Shift+S save as, Ctrl+P print, Ctrl+W or Ctrl+F4 close the document, Ctrl+Tab / Ctrl+Shift+Tab next / previous document, Ctrl+K settings, Ctrl+Shift+F search across files, Ctrl+Q exit |
| Editing | Ctrl+Z undo, Ctrl+Y or Ctrl+Shift+Z redo, Ctrl+A select the page's text, Ctrl+C copy, Ctrl+F find |
| Zoom | Ctrl+= / Ctrl+- zoom in / out, Ctrl+1 actual size, Ctrl+0 fit page, Ctrl+2 fit width |
| Pages | Home / End first / last page, Left / Right arrow previous / next page, PageUp / PageDown (one page at a time in single-page layouts), Ctrl+Shift+N go to page, Ctrl+Shift+Plus / Ctrl+Shift+Minus rotate the view |
| Panels and modes | F4 right-hand panel, Shift+F4 All tools pane, Ctrl+D document properties, Ctrl+H read mode, Ctrl+L full screen, Esc leave read mode or full screen, hold Space and drag to move the page |
| Read Out Loud | Ctrl+Shift+V read this page, Ctrl+Shift+B read to the end, Ctrl+Shift+C pause or resume, Ctrl+Shift+E stop |

### Where X PDF keeps its data

| Data | Location |
|---|---|
| Settings, recent list, saved signatures (encrypted with your Windows account), saved actions (`actions.json`, never with passwords) | `%APPDATA%\X PDF` |
| Log file (no document content, passwords or file contents) | `%APPDATA%\X PDF\logs` |
| Undo revisions, job working files and scanned pages | `%TEMP%\X-PDF` (removed when documents close and at the next start; Settings shows the size and can clean it up) |

Upgrading from X PDF (the name before version 1.1): the first start copies `%APPDATA%\X PDF` to `%APPDATA%\X PDF`, so settings, recent files, saved signatures and saved actions carry over. The old folder is left in place; delete it when you no longer need it.

## 3. Uninstalling

Use Windows Settings → Apps → Installed apps → X PDF → Uninstall (Windows 10: Settings → Apps → Apps & features). This removes the program, its shortcuts and its temporary folder. **Your documents are never touched.** Settings, saved signatures and saved actions in `%APPDATA%\X PDF` are kept so a reinstall picks them up; delete that folder manually to remove them as well.

## 4. Known limitations

- **Windows coverage:** tested on Windows Server 2025 in CI; not yet on a Windows 10 or 11 desktop or under a standard user account.
- **Editing existing text** depends on how the PDF was made. Text is edited in its original font when the embedded font contains the needed characters; otherwise a similar font is substituted and X PDF tells you. Rotated or vertical text, text drawn as outlines and scanned pages cannot be edited in place (OCR or Convert to Word are offered instead). Long replacements are not reflowed into following lines; use paragraph mode or convert to Word for heavy rewording. Resizing an existing paragraph box is not yet available in the editor.
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
- **Prepare a form:** detection is rule-based (underlines, ruled lines, boxes, check squares beside a label), not trained like Acrobat's; every suggestion is reviewed before it is added. Date and number formats, calculated fields, push buttons and barcode fields are not available (they need form JavaScript, which X PDF never runs).
- **Comment review:** comments are exchanged as XFDF (FDF is not supported). The comment summary is typeset with Latin fonts; comments in Indian scripts may not display in the summary PDF.
- **Read Out Loud** uses the speech voices installed in Windows; without any, it says so. Spoken audio was not verified in CI, because the build machines have no sound device.
- **Saved actions** run the listed step types only: no conditional steps, no per-file prompts, no Bates numbering across files (use Batch for that). An image watermark step needs its image chosen again after X PDF restarts.
- **Scan to PDF** uses Windows Image Acquisition (WIA), not TWAIN; scanners that only offer a TWAIN driver are not listed (scan to a folder on the device, then use Create PDF → Images with OCR). It runs a fixed script in Windows PowerShell, which some company policies block; X PDF then says so. It has **not been tested with a physical scanner**; the automated tests cover the no-scanner case on Windows and the full flow with a simulated scanner.
- **Code signing:** the installer and executable are not signed (no paid certificate), so SmartScreen warns on first run.
- **Licence:** X PDF is AGPL-3.0 because it bundles MuPDF and Ghostscript under their AGPL terms. Free for personal use; if you distribute it, you must also offer its source (see `docs/DEPENDENCIES.md`).

## 5. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| "Windows protected your PC" when running the installer | Unsigned installer. More info → Run anyway |
| "Password required" or "Permission restricted" | The PDF is encrypted or restricts that action. Enter the open password, or the owner password to lift restrictions. X PDF does not bypass protection |
| "No editable text was found" when editing | The page is a scan or the text is outlines. Run OCR, then use Add Text, or Convert to Word |
| A replacement character looks different | The original embedded font lacks that character; a substitute font was used (the warning says so) |
| Office to PDF fails or hangs | Close other LibreOffice windows; delete `%APPDATA%\X PDF\office-profiles` and retry. Password-protected Office files are not supported |
| OCR result is poor | Rescan at 300 dpi, choose the correct language in Settings, or try 400 dpi in the OCR tool |
| "OCR language not installed" | Copy the `.traineddata` file into the tessdata folder (see Known limitations) |
| A job seems stuck | Open Activity (top-right) and cancel it; cancelling stops the engine process and removes partial output |
| Output name has "(1)" | A file with that name already existed; X PDF never overwrites |
| Temp folder is large | Close documents you no longer need, or Settings → Temporary files → "Clean up leftovers now" |
| Anything else | Check `%APPDATA%\X PDF\logs\nayanpdf.log` (contains operation names and error codes, never document contents or passwords) |

## 6. Development

### Architecture

```
X-PDF.exe (Electron 43)
├── main process (TypeScript, src/main)
│   ├── security: sandbox, context isolation, request blocking, IPC validation, path access registry
│   ├── document sessions: revisions for undo/redo, atomic save, nayan-doc:// streaming to the viewer
│   ├── job manager: queue, concurrency, cancellation (process-tree kill), partial-output cleanup
│   └── engine pool: persistent query/edit workers + one process per long job
├── preload (src/preload): allowlisted channels only, file-drop path registration
├── renderer (React 19 + PDF.js 5, src/renderer): workspace, tools, convert, batch, saved actions, settings
├── scan (src/main/scan.ts): Windows Image Acquisition through a fixed PowerShell script
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
python -m pytest engine/tests -q        # engine: 123 tests on synthetic documents
node scripts/ui-e2e.mjs                 # UI end-to-end: 40 scenarios (Linux: run under xvfb-run)
node scripts/make-icons.mjs             # redraws build/icon.png and build/icon.ico from build/icon.svg
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
# -> release\X-PDF_Setup.exe
powershell -File scripts\win-acceptance.ps1 -Installer release\X-PDF_Setup.exe -ReportDir acceptance
```

`build_runtime_win.py` downloads each component from its official source, verifies pinned SHA-256 hashes (and the Authenticode signature of the LibreOffice installer), extracts it without running any third-party installer except LibreOffice's administrative extract (`msiexec /a`, no system changes), and records versions and hashes in `runtime/win/MANIFEST.json`, which ships inside the application. Options: `--skip-libreoffice`, `--libreoffice-msi <file>`, `--only <component>`.

Pushing to a `claude/**` or `main` branch runs `.github/workflows/windows-installer.yml`: build, engine tests against the bundled runtime, UI test on Windows, installer build, install/offline/uninstall/reinstall acceptance test, then a pre-release with `X-PDF_Setup.exe`, its hash and the acceptance report.

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
