# N PDF for Android

N PDF for Android is the phone and tablet edition of N PDF. It scans documents with the phone's camera and reads, edits, annotates, fills, signs (with a picture of your signature), redacts, organises, compresses, protects, OCRs and exports PDFs to Word, Excel, PowerPoint and RTF, entirely on the device. It has no internet access at all: the app does not hold Android's network permission, so the operating system itself refuses it any connection. Every file is saved on the phone.

Certificate-based digital signatures are deliberately not part of the Android edition.

## Status

- **Scanner and redesign build (1.2.0, build after 5):** under test on CI. It is not published until the JVM tests, the permission audit and every emulator test pass; this section will then name the build, its size and SHA-256.
- **Last published test build:** `android-v1.2.0-build.5` (GitHub pre-release, before the scanner and redesign), `N-PDF-Android.apk` 37,645,534 bytes, SHA-256 `762560a571979436f6fa70371c569cc5aea05ea38c70e076d551787c5f3609a6`. Evidence for build 5: 34 / 34 engine tests and 14 / 14 app tests passed on an Android 14 emulator; 14 / 14 app tests passed on an Android 11 emulator.
- **Not yet verified:** a physical phone and its camera, Android versions other than 11 and 14, audible speech from Read Out Loud, printing to a real printer.

## Installing a test build

1. On the phone, open the newest `android-v…` pre-release on the repository's Releases page (https://github.com/nkpatel5369-web/Nayanpdf/releases) and download `N-PDF-Android.apk`. The `.sha256` file next to it holds the checksum.
2. **Uninstall the previous test build first** (long-press the icon, App info, Uninstall). Each test build is signed with a key made for that build only, so Android refuses to install one over another. Your PDFs and scans in Documents/N PDF and Pictures/N PDF are not touched; the recent-files list and saved signatures are removed with the app.
3. Open the downloaded file. Android asks you to allow installs from the browser or Files app the first time ("Install unknown apps"); allow it for that app only.
4. Google Play Protect may offer to scan an app from an unknown developer. Scanning is safe; the app sends nothing.
5. The first time you tap **Scan**, Android asks whether N PDF may use the camera. This is the only permission the app asks for. If you refuse, the scanner still imports photos.

Requirements: Android 8.0 or later (API 26+), 64-bit or 32-bit ARM, or x86_64. The download is about 40 MB (the OCR language data for English, Hindi and Gujarati is inside it).

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

Measured in build 5 on the CI emulators (x86_64, software graphics, so a phone's GPU is not used; numbers on a phone will differ):

| Measurement | Android 14 | Android 11 |
|---|---|---|
| 300-page document: tap to first page visible on screen (whole app, including starting the screen) | 536 ms | 533 ms |
| All test documents (1 to 300 pages): tap to first page visible | 425 to 741 ms | 421 to 615 ms |
| PDF engine alone: open the 300-page document and draw page 1 at 1080 px wide | 22 ms | not run (engine tests run on Android 14) |

Opening time does not grow with page count: the 300-page document opens as fast as a 2-page one.

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
- **MuPDF is pinned to 1.28.2**, the version the Windows build uses. MuPDF 1.28.5 shifts the text that follows removed characters (found by this project's redaction test); do not upgrade until that test passes on the new version.
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

Executed on GitHub Actions (ubuntu-latest), emulators x86_64 with the Google APIs system image. Results per test are in each run's artifact `n-pdf-android` (JUnit XML, logcat, screenshots).

The scanner and redesign build is under test; its results will be recorded here once the run completes. Build 5 (run 42152297461, before the scanner): engine 34 / 34 passed on Android 14; app 14 / 14 passed on Android 14 and on Android 11.
