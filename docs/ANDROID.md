# N PDF for Android

N PDF for Android is the phone and tablet edition of N PDF. It scans documents with the phone's camera and reads, edits, annotates, fills, signs (with a picture of your signature), redacts, organises, compresses, protects, OCRs and exports PDFs to Word, Excel, PowerPoint and RTF, entirely on the device. It has no internet access at all: the app does not hold Android's network permission, so the operating system itself refuses it any connection. Every file is saved on the phone.

Certificate-based digital signatures are deliberately not part of the Android edition.

## Status

- **Current test build:** `android-v1.2.0-build.18` (GitHub pre-release), `N-PDF-Android.apk` 40,866,349 bytes (39 MB), SHA-256 `0a57d2e7005ce04410460ca5a89601c15f4fd593e7f52f26bb33fd2a07bd1ce0`, version 1.2.0 (code 18), built from commit `7da2abe`. The checksum and the permissions below were also checked on the published file after download.
- **Evidence (build 18, CI run 43718832205):** 34 / 34 JVM tests (scanner and Office export); 40 / 40 engine tests on an Android 14 emulator; 22 / 22 app tests on an Android 14 emulator and 22 / 22 on an Android 11 emulator. The app tests run on the release build, the same build that is published. Permission audit: `CAMERA` and AndroidX's app-internal `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, nothing else.
- **Not yet verified:** a physical phone and its camera, Android versions other than 11 and 14, audible speech from Read Out Loud, printing to a real printer, OCR accuracy for Hindi and Gujarati on real documents.

## Installing a test build

1. On the phone, open the newest `android-v…` pre-release on the repository's Releases page (build 18: https://github.com/nkpatel5369-web/Nayanpdf/releases/tag/android-v1.2.0-build.18) and download `N-PDF-Android.apk`. The `.sha256` file next to it holds the checksum.
2. **Uninstall the previous test build first** (long-press the icon, App info, Uninstall). Each test build is signed with a key made for that build only, so Android refuses to install one over another. Your PDFs and scans in Documents/N PDF and Pictures/N PDF are not touched; the recent-files list and saved signatures are removed with the app.
3. Open the downloaded file. Android asks you to allow installs from the browser or Files app the first time ("Install unknown apps"); allow it for that app only.
4. Google Play Protect may offer to scan an app from an unknown developer. Scanning is safe; the app sends nothing.
5. The first time you tap **Scan**, Android asks whether N PDF may use the camera. This is the only permission the app asks for. If you refuse, the scanner still imports photos.

Requirements: Android 8.0 or later (API 26+), 64-bit or 32-bit ARM, or x86_64. The download is 39 MB (the OCR language data for English, Hindi and Gujarati is inside it).

## What it does

| Area | Features |
|---|---|
| Scan | Document scanner with live page detection and auto-capture (see the next section) |
| Reading | Continuous scrolling, pinch zoom from half to eight times the fit-to-width size, page scrubber for long documents, go to page, bookmarks (document outline), links, find with match count, select and copy text with drag handles, Read Out Loud with the phone's own text-to-speech voices, dark pages for night reading, keep the screen on |
| Comment | Highlight, underline, strike-through, sticky note, text box, freehand drawing, rectangle, ellipse, arrow, colour choice, list of all comments, undo and redo |
| Edit | Edit existing text in place (line or paragraph, keeps the original font when the embedded font has the characters, otherwise a matching substitute and N PDF says so), edit the text of a scanned page after OCR, add text, find and replace, add an image, move, resize, replace or delete an existing image |
| Fill & Sign | Fill form fields by tapping (text, tick boxes, radio buttons, lists), list of fields, signature drawn with a finger, typed, or picked from saved signatures (encrypted on the phone with the Android Keystore), date stamp. A signature placed this way is a picture, not a cryptographic digital signature |
| Redact | Mark text, mark an area, find and mark (PAN, GSTIN, Aadhaar-like numbers, IFSC, e-mail addresses, Indian mobile numbers, or your own words), apply. Applying removes the text, image pixels and line art under each mark from the file and then verifies the result before it is accepted |
| Pages | Organise pages (rotate, delete, drag to reorder, extract, insert a blank page, insert another PDF), combine files, split (by ranges or every N pages) |
| Export | Word (.docx), Excel (.xlsx), PowerPoint (.pptx) and Rich Text (.rtf), made on the phone. Scanned pages without text are offered OCR first |
| Tools | Photos to PDF (through the scanner's review screen, so photos get the same crop, filters and clean-up), make a scanned PDF searchable with OCR (English, Hindi, Gujarati), compress, protect with a password (AES-256), remove a password (only with the password and full rights; N PDF never breaks encryption), flatten comments and fields, document properties, print through Android's print service, share |

Every change to an opened PDF is made in memory first. **Save** writes back to the file you opened (N PDF asks once before the first overwrite); **Save as** writes a copy and leaves the original untouched. N PDF never deletes your source files: scanning and photo import leave the photos in your gallery as they were.

Not in the Android edition (Windows only): certificate digital signatures, conversion from Word, Excel and PowerPoint to PDF, document comparison, batch processing, saved actions, scanner-hardware support, form preparation and XFDF comment exchange.

## Document scanner

Open it with **Scan** on the Files tab, from Tools, or by long-pressing the app icon. Everything runs on the phone; nothing is uploaded.

| Step | What you get |
|---|---|
| Capture | Live outline of the page with auto-capture once it holds steady (switch to Manual at the top), batch scanning for stacks of pages or a book (capture follows each page turn), flash off / on / automatic, tap to focus, QR codes read on the spot (copy, share, or hand a link to your browser app), import from your photos or from another app's share sheet |
| Modes | Document; Whiteboard (the whole picture, whiteboard filter); Book (both pages in one shot, split into two pages, curved page edges straightened); ID card (front and back on one A4 page at the card's true size, ready to print as a photocopy); Business card (contact details read from the card and handed to your Contacts app, where you confirm them) |
| Review | Pages arrive corrected for perspective, at the page's real proportions even when photographed at an angle; swipe through pages; crop with a magnifier and edge handles, Auto-detect or No crop; Straighten bends an edge to flatten curved pages; rotate; six filters with live previews (Original, Auto colour, Light text, Greyscale, Black & white, Whiteboard) for one page or all; brightness and contrast; clean-up eraser that fills from the surroundings or with a colour you pick; remove fingers holding the page; markup with pen, box, oval, line and arrow; page size (fit, A4, Letter, Legal); reorder by dragging and edit several pages at once; retake; add pages; delete |
| Save | PDF (searchable through OCR in English, Hindi and Gujarati, on every page) or JPEG pictures; password protection; Best, Balanced or Small picture quality; a file name suggested from the page itself (for example "Tax Invoice 2026-03-31") or the default "Scan 2026-11-15 10.32"; saved in Documents/N PDF (pictures in Pictures/N PDF) or a folder you choose; then open it in N PDF, share it, or save the business card's contact |

A scan you have not saved is kept on the phone, so closing the app does not lose it; the scanner offers to continue it next time. Scanner defaults (auto-capture, border adjustment after each capture, default filter, OCR on save, OCR languages, save folder) are in Settings.

### Compared with Adobe Scan's paid tier

Adobe's own help pages list the paid tier's features. This is where each stands in N PDF, which has no internet access by design.

| Adobe Scan paid feature | N PDF |
|---|---|
| Combine files (up to 20) | Combine files, no fixed limit |
| Export PDF to Word, Excel, PowerPoint, rich text (online at Adobe) | Export on the phone to Word, Excel, PowerPoint and RTF. Text and tables are rebuilt (amounts become numbers in Excel); slides show each page as a picture. Complex layouts need checking |
| High-speed scan | Batch scanning |
| Edit text in scanned documents, and while saving | Edit text in a saved scan: the recognised words you tap are replaced in a matching size and colour; the scan's pixels under them are removed |
| Password-protect scans | Password on save, and Protect with password for any PDF |
| Compress files | Picture quality on save, and Compress for any PDF |
| OCR for up to 100 pages (5 free) | OCR on every page, no limit |
| More Adobe cloud storage | Not applicable: files stay on the phone |
| AI Assistant, generative summary | Not included: they need Adobe's online AI service, which contradicts the no-internet rule |

Adobe Scan recognises about 20 languages; N PDF includes English, Hindi and Gujarati.

## Design

The interface follows Apple's Human Interface Guidelines in its structure and behaviour, built with N PDF's own artwork:

- three tabs at the bottom (Files, Tools, Settings) with large titles that give way to a small centred title as you scroll;
- inset grouped lists with coloured icon tiles, switches and values on the right, as in a settings app;
- forms open as sheets from the bottom, choices as action sheets with a separate Cancel, confirmations as centred alerts with the preferred choice in bold and destructive choices in red;
- segmented controls for small sets of options, short confirmation banners instead of pop-up messages, light haptic ticks on taps;
- full light and dark appearances (Automatic, Light or Dark in Settings), with dark pages available separately for reading;
- the Inter typeface (SIL Open Font License) and line icons drawn for N PDF. Apple's own fonts and SF Symbols are licensed for Apple platforms only and are not used.

## No internet access: how it is enforced and verified

- The app manifest requests no network permission, and actively strips `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and `CHANGE_NETWORK_STATE` if any library tried to add them. Without `INTERNET`, Android's kernel refuses the app a network socket.
- Plain-text traffic is disabled and the app trusts no certificate authority at all, so even a future code change could not open a secure connection without a deliberate configuration change.
- Cloud backup and device-to-device transfer of app data are switched off.
- No analytics, advertising, crash-reporting or update libraries are included. OCR language data is inside the app; nothing is downloaded at any time.
- **The camera is the only permission.** It is asked for when you first tap Scan and is used only while the scanner is on screen; pictures go straight into the scan on the phone. Photos are imported through Android's photo picker (no storage permission), files are opened through Android's file picker, a business card's details are handed to your Contacts app for you to confirm (no contacts permission), and a QR code's link is handed to your browser app only when you tap Open. The app holds no storage, location, contacts, microphone, phone or Bluetooth permission.
- Scans and exports are saved with Android's media store into Documents/N PDF and Pictures/N PDF, which needs no permission on Android 10 and later. On Android 8 and 9 N PDF asks you once to choose a folder instead.

Verification in every build:

- the build fails if the APK requests any permission other than `CAMERA` and AndroidX's app-internal `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (checked with `aapt2 dump permissions`), and separately fails on any permission matching INTERNET, NETWORK, WIFI, BLUETOOTH, NEARBY, LOCATION, external storage, CONTACTS, SMS, PHONE, AUDIO or MEDIA;
- on each emulator, a test running inside the app's own process confirms that no network permission is held, that the only permissions are those two, that opening a socket is refused by Android itself, that a DNS lookup fails, that cloud backup is off and that plain-text traffic is not permitted.

You can check it yourself on the phone: Settings, Apps, N PDF, Permissions lists Camera only.

## Speed

Opening a document reads only the page dictionaries (not the pages) to lay out the scroll view, then draws the first page from a cached display list; pages are drawn on two background threads, sharp tiles are added when you zoom in, and nothing waits for pages you have not reached. The scanner analyses a small copy of each camera frame on a background thread and draws the preview on its own surface, so the screen stays responsive while it looks for the page.

Measured in build 18 on the CI emulators (x86_64, software graphics on shared CI machines, so a phone's GPU is not used; numbers on a phone will differ, and they vary from run to run: the 300-page figure was 346 to 612 ms across the last three runs):

| Measurement | Android 14 | Android 11 |
|---|---|---|
| 300-page document: tap to first page visible on screen (whole app, including starting the screen) | 612 ms | 579 ms |
| Each test document (1 to 300 pages), first open: tap to first page visible | 612 to 985 ms | 503 to 796 ms |
| A document opened again moments later | 75 to 158 ms | 78 to 211 ms |
| PDF engine alone: open the 300-page document and draw page 1 at 1080 px wide | 77 ms | not run (engine tests run on Android 14) |
| Scanner, 1600 x 1200 photo: find the page outline (refined to sub-pixel) | 275 ms | 253 ms |
| Scanner: correct the perspective and apply the Auto colour filter | 235 ms | 231 ms |
| Scanner: save one page as a searchable PDF (OCR included) | 452 ms | 424 ms |
| Export a 7-page financial statement to Word, Excel, PowerPoint and RTF (engine) | 2,712 ms | not run |

Opening time does not grow with page count: the 300-page document opens as fast as a 2-page one. The live camera analysis runs on a 640 x 480 copy of each frame (16 ms per frame on the CI machine's processor in the JVM tests), off the main thread.

## Known limitations

- **Tested on emulators only.** The automated tests run on Android 14 and Android 11 emulators (x86_64, Google APIs image). The camera tests use the emulator's simulated camera, which shows a synthetic scene, not a document; page detection, perspective correction and filters are tested on synthetic photographs instead. No physical phone has been tested yet: scan a few real documents on your own phone before relying on it.
- **Test builds cannot update in place** (one key per build). A permanent signing key is needed for normal updates and for any store listing.
- **Scanner:** detection needs the page to stand out from its background (a white page on a white desk may need manual corners); curved book pages are straightened along their edges, which is simpler than Adobe's AI-based page flattening; the business-card reader suggests details from the OCR text and you confirm them in Contacts. A page's proportions are read from the perspective of its outline; when it is tilted one way only, a phone's main lens is assumed, so a photo taken with an ultra-wide or telephoto lens and then imported can come out a few per cent off (up to 9 % at a 30° tilt in simulation; under 1 % on average across all simulated shots). Resize sets the PDF page to A4, Letter or Legal and centres the scan on it; it does not stretch the scan.
- **Export to Office** rebuilds text, paragraphs and simple tables; multi-column layouts, merged cells, charts and complex formatting need checking, and PowerPoint slides show each page as a picture.
- **Editing existing text** has the same limits as on Windows: the embedded font subset may lack a character (a substitute font is then used and N PDF tells you), rotated or vertical text cannot be edited in place, and Hindi or Gujarati typed in a substitute font is written letter by letter, so conjuncts may not join correctly. On a scanned page the replacement text is set in a substitute font sized to the recognised words.
- **OCR** accuracy depends on the scan; recognised text is never guaranteed to be correct. Three languages are included (English, Hindi, Gujarati).
- **Saving on Android 8 and 9** needs a folder chosen once, because the shared Documents folder cannot be written without the storage permission N PDF does not hold.
- **Read Out Loud** needs a text-to-speech voice installed on the phone; without one, N PDF says so. Spoken audio was not verified in the automated tests (emulators have no sound output).
- **Printing** uses whichever print services the phone has; it was not tested with a physical printer.
- **MuPDF is pinned to 1.28.2**, the version the Windows build uses. MuPDF 1.28.5 shifts the text that follows removed characters (found by this project's redaction test); do not upgrade until that test passes on the new version. MuPDF 1.28.2's Java bindings also mishandle references in two calls (see Test evidence); N PDF avoids both, and CI enforces it. Recheck both points before any upgrade.
- **Licence:** AGPL-3.0, because MuPDF is AGPL. Free for personal use; anyone who distributes the app must also offer its source code.

## Bundled components

| Component | Version | Role | Licence |
|---|---|---|---|
| MuPDF (Android "fitz" library) | 1.28.2 | Rendering, text extraction, editing, annotations, forms, redaction, encryption | AGPL-3.0 (or Artifex commercial licence) |
| Tesseract4Android (Tesseract, Leptonica, libjpeg, libpng) | 4.8.0 | OCR | Apache-2.0 (Tesseract, Tesseract4Android); BSD-style (Leptonica); IJG (libjpeg); libpng licence |
| Tesseract language data, tessdata_fast | 4.1.0 (eng, hin, guj) | OCR models, SHA-256 pinned at build time | Apache-2.0 |
| Glyphless font from Tesseract | 4.1.0 | Invisible text layer after OCR | Apache-2.0 |
| CameraX (core, camera2, lifecycle, view) | 1.6.2 | Scanner camera | Apache-2.0 |
| ZXing core | 3.5.4 | QR codes in the scanner | Apache-2.0 |
| Inter typeface | 4.001 | Interface font | SIL Open Font License 1.1 |
| AndroidX (core, appcompat, activity, lifecycle, recyclerview, exifinterface, documentfile), Material Components | see `android/app/build.gradle.kts` | User interface | Apache-2.0 |
| Kotlin standard library and coroutines | 2.2.21 / 1.10.2 | Language runtime | Apache-2.0 |

Page detection, perspective correction, filters, clean-up, ID-card and book layouts, and the Word, Excel, PowerPoint and RTF writers are N PDF's own code (module `android/scankit`), with no further libraries. All native libraries are built for 16 KB memory pages, as Android 15 and later require.

## Building from source

Requirements: JDK 21 and the Android SDK (platform 36). Network access is needed once, at build time only, to fetch the Gradle dependencies and the three OCR language files (their SHA-256 hashes are pinned in `android/app/build.gradle.kts`; a mismatch stops the build).

```
cd android
./gradlew :scankit:test                             # scanner and Office export tests on the JVM
./gradlew :app:assembleRelease                      # APK in app/build/outputs/apk/release/
./gradlew :engine:connectedDebugAndroidTest         # engine tests on a connected device or emulator
./gradlew :app:connectedReleaseAndroidTest          # app tests (release build)
```

Without the `NPDF_KEYSTORE` and `NPDF_KEYSTORE_PASSWORD` environment variables the release APK is signed with the local debug key. The CI workflow `.github/workflows/android.yml` creates a fresh key for every build, runs the JVM tests, the permission audit and all tests on both emulators, captures light and dark screenshots of the main screens, and publishes the APK as a pre-release only when everything passes.

Code layout: `android/scankit` (scanner algorithms and Office writers, plain Kotlin, tested on the JVM with synthetic photographs), `android/engine` (PDF engine on top of MuPDF, with its instrumented tests and synthetic test documents), `android/app` (screens, viewer, scanner, tools, OCR, app tests). The synthetic test documents are made by `scripts/make_android_test_assets.py`; the icons by `android/tools/make_icons.py`.

## Test evidence

Executed on GitHub Actions (ubuntu-latest), emulators x86_64 with the Google APIs system image, build 18 (run 43718832205). Results per test are in the run artifact `n-pdf-android` (JUnit XML, logcat, light and dark screenshots of 15 screens on each emulator).

| Suite | What it covers | Result |
|---|---|---|
| JVM, scanner and Office export (34 tests) | Page detection on 150 synthetic photos (five backgrounds, tilt, shadow, noise, blur): 150 found; corners refined to 0.3 to 0.4 px; curved book edges (30 px bow measured as 29.6 and 30.4); page proportions over 1,063 pinhole-camera shots of three page sizes, five lenses and 75 poses (mean error 0.7 %, worst 9.4 %, against 11.8 % and 37.5 % from edge lengths alone); perspective correction against the original page; shadow removal (paper brightness spread 32.8 to 1.4); clean-up and finger removal; ID-card and book layouts; dates, file names, business cards, QR codes; Word, Excel (typed numbers, Indian grouping), PowerPoint and RTF files checked as valid | 34 / 34 passed |
| Engine (40 tests), Android 14 | Opening (normal, password-protected, damaged), text extraction and search, editing existing text (original and substitute fonts, paragraph mode, a page stored rotated, undo and redo, abandoned edit restores the document), editing text on a scanned page, images (insert with EXIF orientation, move, resize, replace, delete), annotations, form filling and flattening, redaction by pattern and by area (text gone, scan pixels removed, neighbouring text not moved), page operations, merge, split, compress, AES-256 protection and removal, OCR text layer (including a new scan page, and with Java's garbage collector forced to run before saving), export of a financial statement to all four Office formats, metadata, opening speed, restricted documents refusing edits | 40 / 40 passed |
| App, release build (22 tests), Android 14 and Android 11 | No network permission held and only the camera requested; Android refuses a socket (`EPERM`) and a DNS lookup; backup and plain-text traffic off; 300-page document opens fast; search; comment, edit text, fill a form, place a signature, delete a page through the screens; Read Out Loud; every icon loads; OCR of a scanned financial statement (49 words, mean confidence 94); scanner pipeline on a camera-geometry photo (outline within 0.04 % of the diagonal, proportions 622 x 804 against the true 850 x 1100, name suggested from the page, saved to Documents/N PDF and searchable); book halves, ID card on A4, clean-up and markup, and an unsaved scan reloaded from the phone's storage; the emulator camera taking a picture and opening the review; an imported photo saved from the review screen; export to Word from the reader; the Files, Tools and Settings tabs; dark pages; screenshots of every main screen in light and dark | 22 / 22 passed on each |

Defects found by these tests and fixed before build 18:

| Defect | Cause | Fix |
|---|---|---|
| Saved scans were not searchable (each letter preceded by an unknown character); the same fault could corrupt files after text edits, inserted images or OCR | Two calls in MuPDF 1.28.2's Java bindings release a reference they do not own (`PDFObject.push` and `PDFDocument.addObject`); when Java's collector ran before the save, MuPDF freed an object the document still used. Whether it struck depended on timing | Both calls replaced everywhere by helpers that count references correctly; engine tests force the collector to run before saving; CI fails if either call is used again |
| Pages photographed at an angle came out squashed (on average 11.8 % off, up to 37.5 %) | The corrected page was sized from the outline's longest edges | The page's real proportions are recovered from the outline's perspective, with a fallback to the edges when the estimate cannot be trusted |
| The camera screen kept the phone's main thread busy (the tests stalled waiting for it; on a phone it costs battery) | The preview was drawn through the main thread, and the status text and outline were reset on every frame | The preview has its own surface; the screen updates only when something changes. Captures are about 12 MP instead of the sensor maximum (50 or 200 MP on some phones) |
| A thin dark line along the edges of corrected pages | The outline's edge pixels are part paper, part background | The page is cut 0.3 % inside the detected edge |
| A red status bar on Android 8 to 14; tab labels off-centre; long file names cut to a few letters in the reader; the same name suggested twice in different case | Screens laid out for Android 15's edge-to-edge drawing; labels stretched to the tab's width; a centred title with little room; suggestions compared case-sensitively | Edge-to-edge drawing on every version; centred labels; title after the back button, shortened in the middle; suggestions unique ignoring case and covered headings |
| The reader's menu had 20 commands; Export and Dark pages were out of reach on small screens | One flat list | 12 entries, with Export and Document tools grouped in submenus |

Defects found before build 5:

| Defect | Cause | Fix |
|---|---|---|
| App crashed during text search and selection | MuPDF's Java `StructuredText.search` aborts the process (invalid JNI reference) | Search and selection implemented in N PDF's own text model |
| App crashed when adding photos | MuPDF's Java `Image.getXResolution` dereferences a null pointer | Image size computed without the resolution call |
| Photos placed sideways | MuPDF reports JPEG orientation in its own numbering, not EXIF | Converted to EXIF numbering before placing |
| Text beside a redaction moved into the black box; same risk for in-place edits | MuPDF 1.28.5 drops the width of the first removed character when it re-spaces the rest of the line | MuPDF pinned to 1.28.2 (the Windows version, correct output confirmed); redaction now also refuses any result in which neighbouring text moved |
| OCR skipped the first column of tables | A bitmap carries no resolution; Tesseract guessed 401 dpi and its layout analysis dropped the column | The real resolution (300 dpi) is passed to Tesseract |
| Annotation operations failed on notes | `getQuadPointCount` throws for annotation types without quad points | Checked with `hasQuadPoints` first |
