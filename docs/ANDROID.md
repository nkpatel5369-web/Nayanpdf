# N PDF for Android

N PDF for Android is the phone and tablet edition of N PDF. It reads, edits, annotates, fills, signs (with a picture of your signature), redacts, organises, compresses, protects and OCRs PDFs entirely on the device. It has no internet access at all: the app does not hold Android's network permission, so the operating system itself refuses it any connection.

Certificate-based digital signatures are deliberately not part of the Android edition.

## Status

- **Current test build:** `android-v1.2.0-build.5` (GitHub pre-release), `N-PDF-Android.apk` 37,645,534 bytes (36 MB), SHA-256 `762560a571979436f6fa70371c569cc5aea05ea38c70e076d551787c5f3609a6`, version 1.2.0 (code 5), built from commit `a57a84c`.
- **Evidence (build 5):** 34 / 34 engine tests and 14 / 14 app tests passed on an Android 14 emulator; 14 / 14 app tests passed on an Android 11 emulator. The app tests run on the release build, the same build that is published. The permission audit passed: the only permission in the APK is `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which is internal to the app (AndroidX adds it so that the app's own broadcast receivers cannot be reached by other apps) and gives no access to anything.
- **Not yet verified:** a physical phone, Android versions other than 11 and 14, audible speech from Read Out Loud, printing to a real printer.

## Installing a test build

1. On the phone, open the newest `android-v…` pre-release on the repository's Releases page (build 5: https://github.com/nkpatel5369-web/Nayanpdf/releases/tag/android-v1.2.0-build.5) and download `N-PDF-Android.apk`. The `.sha256` file next to it holds the checksum.
2. Open the downloaded file. Android asks you to allow installs from the browser or Files app the first time ("Install unknown apps"); allow it for that app only.
3. Google Play Protect may offer to scan an app from an unknown developer. Scanning is safe; the app sends nothing.
4. **Each test build is signed with a key made for that build only.** Before installing a newer test build, uninstall the previous one (long-press the icon, App info, Uninstall). Your PDFs are not touched. The recent-files list and any saved signatures are removed with the app.

Requirements: Android 8.0 or later (API 26+), 64-bit or 32-bit ARM, or x86_64. The download is about 36 MB (the OCR language data for English, Hindi and Gujarati is inside it).

## What it does

| Area | Features |
|---|---|
| Reading | Continuous scrolling, pinch zoom from half to eight times the fit-to-width size, go to page, bookmarks (document outline), links, find with match count, select and copy text with drag handles, Read Out Loud with the phone's own text-to-speech voices, dark theme |
| Comment | Highlight, underline, strike-through, sticky note, text box, freehand drawing, rectangle, ellipse, arrow, colour choice, list of all comments, undo and redo |
| Edit | Edit existing text in place (line or paragraph, keeps the original font when the embedded font has the characters, otherwise a matching substitute and N PDF says so), add text, find and replace, add an image, move, resize, replace or delete an existing image |
| Fill & Sign | Fill form fields by tapping (text, tick boxes, radio buttons, lists), list of fields, signature drawn with a finger or typed, saved signatures (encrypted on the phone with the Android Keystore), date stamp. A signature placed this way is a picture, not a cryptographic digital signature |
| Redact | Mark text, mark an area, find and mark (PAN, GSTIN, Aadhaar-like numbers, IFSC, e-mail addresses, Indian mobile numbers, or your own words), apply. Applying removes the text, image pixels and line art under each mark from the file and then verifies the result before it is accepted |
| Pages | Organise pages (rotate, delete, drag to reorder, extract, insert a blank page, insert another PDF), combine files, split (by ranges or every N pages) |
| Tools | Photos to PDF (gallery or camera, optional OCR), make a scanned PDF searchable with OCR (English, Hindi, Gujarati), compress, protect with a password (AES-256), remove a password (only with the password and full rights; N PDF never breaks encryption), flatten comments and fields, document properties, print through Android's print service, share |

Every change is made in memory first. **Save** writes back to the file you opened (N PDF asks once before the first overwrite); **Save as** writes a copy and leaves the original untouched. N PDF never deletes your source files.

Not in the Android edition (Windows only): certificate digital signatures, conversion to and from Word, Excel and PowerPoint, document comparison, batch processing, saved actions, scanner support, form preparation and XFDF comment exchange.

## No internet access: how it is enforced and verified

- The app manifest requests no network permission, and actively strips `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and `CHANGE_NETWORK_STATE` if any library tried to add them. Without `INTERNET`, Android's kernel refuses the app a network socket.
- Plain-text traffic is disabled and the app trusts no certificate authority at all, so even a future code change could not open a secure connection without a deliberate configuration change.
- Cloud backup and device-to-device transfer of app data are switched off.
- No analytics, advertising, crash-reporting or update libraries are included. OCR language data is inside the app; nothing is downloaded at any time.
- Files are reached only through Android's file picker or when you open a PDF from another app, and only the files you choose. The app holds no storage, camera, location, contacts, phone or Bluetooth permission (the camera is used through Android's own camera app when you choose "Take photo").

Verification in every build:

- the build fails if the APK requests any permission matching INTERNET, NETWORK, WIFI, BLUETOOTH, NEARBY, LOCATION, CAMERA, external storage, CONTACTS, SMS or PHONE (checked with `aapt2 dump permissions`);
- on each emulator, a test running inside the app's own process confirms that no network permission is held, that opening a socket is refused by Android itself, that a DNS lookup fails, that cloud backup is off and that plain-text traffic is not permitted.

You can check it yourself on the phone: Settings, Apps, N PDF, Permissions lists none.

## Speed

Opening a document reads only the page dictionaries (not the pages) to lay out the scroll view, then draws the first page from a cached display list; pages are drawn on two background threads, sharp tiles are added when you zoom in, and nothing waits for pages you have not reached.

Measured in build 5 on the CI emulators (x86_64, software graphics, so a phone's GPU is not used; numbers on a phone will differ):

| Measurement | Android 14 | Android 11 |
|---|---|---|
| 300-page document: tap to first page visible on screen (whole app, including starting the screen) | 536 ms | 533 ms |
| All test documents (1 to 300 pages): tap to first page visible | 425 to 741 ms | 421 to 615 ms |
| PDF engine alone: open the 300-page document and draw page 1 at 1080 px wide | 22 ms | not run (engine tests run on Android 14) |

Opening time does not grow with page count: the 300-page document opens as fast as a 2-page one.

## Known limitations

- **Tested on emulators only.** The automated tests run on Android 14 and Android 11 emulators (x86_64, Google APIs image). No physical phone has been tested yet; test on one or two of your own phones before relying on it.
- **Test builds cannot update in place** (one key per build). A permanent signing key is needed for normal updates and for any store listing.
- **Editing existing text** has the same limits as on Windows: the embedded font subset may lack a character (a substitute font is then used and N PDF tells you), rotated or vertical text cannot be edited in place, scanned pages must be made searchable with OCR first, and Hindi or Gujarati typed in a substitute font is written letter by letter, so conjuncts may not join correctly.
- **OCR** accuracy depends on the scan; recognised text is never guaranteed to be correct.
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
| AndroidX (core, appcompat, activity, lifecycle, recyclerview, exifinterface, documentfile), Material Components | see `android/app/build.gradle.kts` | User interface | Apache-2.0 |
| Kotlin standard library and coroutines | 2.2.21 / 1.10.2 | Language runtime | Apache-2.0 |

All native libraries are built for 16 KB memory pages, as Android 15 and later require.

## Building from source

Requirements: JDK 21 and the Android SDK (platform 36). Network access is needed once, at build time only, to fetch the Gradle dependencies and the three OCR language files (their SHA-256 hashes are pinned in `android/app/build.gradle.kts`; a mismatch stops the build).

```
cd android
./gradlew :app:assembleRelease                      # APK in app/build/outputs/apk/release/
./gradlew :engine:connectedDebugAndroidTest         # engine tests on a connected device or emulator
./gradlew :app:connectedReleaseAndroidTest          # app tests (release build)
```

Without the `NPDF_KEYSTORE` and `NPDF_KEYSTORE_PASSWORD` environment variables the release APK is signed with the local debug key. The CI workflow `.github/workflows/android.yml` creates a fresh key for every build, runs the permission audit and all tests on both emulators, and publishes the APK as a pre-release only when everything passes.

Code layout: `android/engine` (PDF engine on top of MuPDF, with its instrumented tests and synthetic test documents), `android/app` (screens, viewer, tools, OCR, app tests). The synthetic test documents are made by `scripts/make_android_test_assets.py`.

## Test evidence

Executed on GitHub Actions (ubuntu-latest), emulators x86_64 with the Google APIs system image, build 5 (run 42152297461). Results per test are in the run artifact `n-pdf-android` (JUnit XML, per-test logcat).

| Suite | What it covers | Android 14 | Android 11 |
|---|---|---|---|
| Engine (34 tests) | Opening (normal, password-protected, damaged), text extraction and search, editing existing text (original and substitute fonts, paragraph mode, a page stored rotated, undo/redo, abandoned edit restores the document), images (insert with EXIF orientation, move, resize, replace, delete), annotations, form filling and flattening, redaction by pattern and by area (text gone, scan pixels blackened, neighbouring text not moved), page operations, merge, split, compress, AES-256 protection and removal, OCR text layer, metadata, opening speed, restricted documents refusing edits without the owner password | 34 / 34 passed | not run |
| App, release build (14 tests) | No network permission held; Android refuses a socket (`EPERM`) and a DNS lookup (`SecurityException`); backup and plain-text traffic off; 300-page document opens in under 2 s and jumps to the last page; search; add a note through the comment tools and undo it; edit existing text through the edit tools and save; fill a text field and tick a box by tapping; draw and place a signature; delete a page in the page organiser; Read Out Loud (speaks or explains that no voice is installed); home screen; every icon loads; OCR of a scanned financial statement | 14 / 14 passed | 14 / 14 passed |

OCR result on the scanned test page (English, both emulators): 49 words, mean confidence 94; the statement's row labels ("Particulars", "Revenue from operations" and the rest) and every amount were read.

Defects found by these tests and fixed before build 5:

| Defect | Cause | Fix |
|---|---|---|
| App crashed during text search and selection | MuPDF's Java `StructuredText.search` aborts the process (invalid JNI reference) | Search and selection implemented in N PDF's own text model |
| App crashed when adding photos | MuPDF's Java `Image.getXResolution` dereferences a null pointer | Image size computed without the resolution call |
| Photos placed sideways | MuPDF reports JPEG orientation in its own numbering, not EXIF | Converted to EXIF numbering before placing |
| Text beside a redaction moved into the black box; same risk for in-place edits | MuPDF 1.28.5 drops the width of the first removed character when it re-spaces the rest of the line | MuPDF pinned to 1.28.2 (the Windows version, correct output confirmed); redaction now also refuses any result in which neighbouring text moved |
| OCR skipped the first column of tables | A bitmap carries no resolution; Tesseract guessed 401 dpi and its layout analysis dropped the column | The real resolution (300 dpi) is passed to Tesseract |
| Annotation operations failed on notes | `getQuadPointCount` throws for annotation types without quad points | Checked with `hasQuadPoints` first |
