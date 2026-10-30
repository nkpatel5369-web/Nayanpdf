# N PDF feature register

Status of every feature in the NayanPDF brief (the product's name up to version 1.0; X PDF in 1.1, N PDF from 1.2), using the five statuses the brief prescribes. A feature is marked **Fully implemented and tested** only where an automated test exercises it end to end and checks the output file; anything implemented but not covered by such a test is marked **Implemented with limitations** and the limitation says so.

## How to read the evidence column

- **E:** engine test in `engine/tests/` (pytest, synthetic documents, output files reopened and inspected).
- **U:** scenario in the UI end-to-end test `scripts/ui-e2e.mjs`, which drives the real Electron application, its main process and engine (see `docs/TEST_REPORT.md`).

**Platforms.** The evidence codes refer to tests run on the Linux development build. The same 123 engine tests and 42 UI scenarios have also been run on Windows (GitHub Actions, Windows Server 2025), against the bundled Windows runtime, and the installed application passed the 114-point Windows acceptance test, including the upgrades from X PDF 1.1 and NayanPDF 1.0 and the right-click commands (build 22). Windows 10/11 desktop editions and standard (non-administrator) accounts have not been tested.

## Status summary

| Status | Register lines |
|---|---|
| Fully implemented and tested | 199 |
| Implemented with limitations | 52 |
| Not implemented | 6 |
| Requires optional dependency | 0 |
| Requires paid licence | 1 |

Counts are register lines; a few features appear in more than one section because the brief lists them more than once. The six "Not implemented" lines are: in-place editing of rotated/vertical text, revocation checking and trusted timestamps (both need the internet), execution of form scripts (deliberately never run), form fields that depend on scripts (date/number formats, calculated fields, push buttons, barcodes), FDF comment files (XFDF is supported), and printing several documents in one action.

No processing feature needs a paid licence or a separately installed component: everything ships inside `N-PDF_Setup.exe`. The one paid item is a code-signing certificate, which only removes the SmartScreen warning.

## 1. Home dashboard and workspace (brief §7)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Dashboard tiles for all 15 required tools (Edit, Merge, Split, Compress, Convert, Organize, OCR, Create, Annotate, Sign, Protect, Redact, Compare, Extract Images, Fill Forms) plus Scan to PDF, Search Across Files, Prepare a Form, Watermark, Properties, Utilities | Fully implemented and tested | Tiles open the corresponding tool or document mode; merge, split, OCR, protect, compare and convert are driven through the UI by the test (U18–U23) |
| Open File action | Fully implemented and tested | U1 (command-line open), Open button and Ctrl+O use the same path |
| File Explorer right-click commands "Convert to PDF with N PDF" (Office, CSV, RTF, images, text, HTML) and "Combine files in N PDF" (the same plus PDFs), several files at once, as in Acrobat | Implemented with limitations | U39 (three files converted next to their originals, an existing PDF of the same name kept, results opened; three files of three kinds combined in the selected order); Windows acceptance test: commands registered after install and after the upgrade, the registered command run as Explorer runs it makes the installed app write the PDF, commands removed on uninstall. On Windows 11 they are under "Show more options" (the short menu needs a signed packaged extension). Explorer's own menu was not driven by a test |
| Drag-and-drop opening | Implemented with limitations | Implemented (files dropped anywhere are registered by the isolated preload script). Not covered by an automated test: Playwright cannot simulate an OS file drop |
| Recent documents list | Implemented with limitations | Implemented, with a "Clear" action in Settings. Not covered by an automated test |
| Multiple open documents in tabs | Fully implemented and tested | U: several scenarios run with two or three tabs open |
| Page thumbnails (right-hand rail), page navigation, page-number entry, zoom | Fully implemented and tested | U3 |
| Document opens with its pages only; navigation panel follows the PDF's initial view (/PageMode bookmarks, thumbnails, attachments), as in Acrobat; tools panel opens with a tool that needs it | Fully implemented and tested | U2 (financial.pdf asks for bookmarks and opens with them; tools panel closed), U30 (a plain PDF opens with no panels). Settings choice "never" / "always" is not covered by a test |
| Panel shortcuts: F4 right-hand panel, Shift+F4 All tools pane, Ctrl+D document properties | Fully implemented and tested | U2 |
| Layout of the current Acrobat (2023+): Menu at top left, Home tab, All tools pane (closed until opened) with View more, the active tool as a chip with a close button, right-hand icon rail (Comments, Page thumbnails, Bookmarks, Attachments, Document properties), floating quick tools, page controls at the bottom right | Fully implemented and tested | U31 (11 checks: menu items, properties from the menu, 17 tools and 25 with View more, tool chip and exit, quick tools without a pane, rail open/close, Home tab and back) |
| Read Out Loud (View menu): this page or to the end, pause, stop; voice and speed in Settings; Windows' installed voices only, nothing downloaded | Implemented with limitations | U35 checks what the app does: on Linux (no voices) it says none are installed and speaks nothing; on Windows CI (voices installed, no sound device) Windows reports a synthesis failure and the app tells the user to check speakers and leaves nothing running. **Spoken audio has not been verified** on any machine with a sound device |
| Page display: single page, single page continuous, two page, two page scrolling (View menu); the PDF's /PageLayout honoured | Fully implemented and tested | U29 (two page view, back to continuous), U30 (/TwoColumnLeft opens as two page scrolling) |
| Show cover page in two page view | Implemented with limitations | Implemented (View menu); not covered by an automated test |
| Zoom: Ctrl+1 actual size, Ctrl+0 fit page, Ctrl+2 fit width; default zoom "Automatic" | Fully implemented and tested | U29 |
| Page navigation keys: Home, End, Left/Right arrow (spread-aware), Ctrl+Shift+N go to page | Fully implemented and tested | U29 |
| Rotate view: Ctrl+Shift+Plus / Ctrl+Shift+Minus | Fully implemented and tested | U29 (the view turns and back; the file is unchanged) |
| Read mode (Ctrl+H): toolbars and panels hidden, floating page bar | Fully implemented and tested | U29 |
| Full screen mode (Ctrl+L): one fitted page on black, arrow keys / Enter / Space to turn pages, Esc to leave | Fully implemented and tested | U29 |
| Temporary Hand tool: hold Space and drag | Fully implemented and tested | U29 (the page moves; no text is selected) |
| Opening page and magnification saved in the PDF (/OpenAction) | Implemented with limitations | Honoured through PDF.js's destination handling; not covered by a discriminating test (the test files open on page 1) |
| Settings > Page display: default zoom, default page layout, navigation panel on open | Implemented with limitations | Stored and applied when a document opens; not covered by an automated test |
| Search within document | Fully implemented and tested | U4 |
| Document properties panel (pages, file size, page sizes, PDF version, encryption, fonts) | Fully implemented and tested | E: `test_probe`, `test_inspect_and_integrity`; panel shown in U screenshots |
| Save (Ctrl+S) and Save As (Ctrl+Shift+S) | Fully implemented and tested | U10: Save As writes a new file and the original is byte-identical afterwards. U28: Save As suggests the document's own name in its own folder (no added suffix); replacing the original needs one confirmation per document per session, after which Ctrl+S saves straight to it. The confirmation can be switched off in Settings |
| Light and dark themes | Fully implemented and tested | U40 |
| Windows display scaling / high-DPI | Implemented with limitations | Uses Chromium's native per-monitor DPI handling and vector UI icons. Not tested on a Windows high-DPI display |
| Conversion centre with output folder, page ranges, quality, OCR options | Fully implemented and tested | U16, U23 |
| Batch workspace with queue, status, output location, errors | Fully implemented and tested | U17, E: `test_batch_continues_after_invalid_file` |
| Settings: theme, default folder, OCR languages, export settings, compression preset, recent files, temp files | Implemented with limitations | All settings are stored locally and applied. Only the theme setting is exercised by an automated test (U40) |

## 2. PDF viewing (brief §8)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Open PDF files | Fully implemented and tested | E: `test_open_ordinary`; U1 |
| Drag-and-drop opening | Implemented with limitations | See §1 |
| Multiple documents | Fully implemented and tested | See §1 |
| Page navigation | Fully implemented and tested | U3 |
| Page thumbnails | Fully implemented and tested | U3 |
| Zoom in and out | Fully implemented and tested | U3 (99% to 113%) |
| Fit to page, fit to width | Fully implemented and tested | U29: Ctrl+2 makes the page fill the width, Ctrl+0 fits its height; both are re-applied on window resize |
| Rotate view (does not change the file) | Fully implemented and tested | U29 (Ctrl+Shift+Plus turns the page to landscape, Ctrl+Shift+Minus back); also in the toolbar and View menu |
| Search in searchable text | Fully implemented and tested | U4 |
| Text selection and copy (drag, double-click a word, Ctrl+A for the page, Ctrl+C) | Fully implemented and tested | U8 (selection copied to the clipboard), U9. A drag may start in the margin or over highlighted text. Scanned pages need OCR first |
| Metadata inspection | Fully implemented and tested | E: `test_metadata_edit_and_remove` |
| Printing (Acrobat-style dialog) | Implemented with limitations | N PDF's own dialog: printer, copies and collate, all/current/range, odd/even, reverse, Fit / Actual size / Shrink oversized / Custom scale / Multiple pages per sheet (2–16, order, borders), orientation, paper, two-sided, black and white, comments & forms, quality, live sheet preview; prints straight to the chosen printer (U27 checks the produced sheets; E: `test_print_prepare_comments_and_forms_options`, `test_print_prepare_respects_permission`). Limitations: pages are sent as images at the chosen resolution (150/300/600 dpi); printer-specific options (trays, stapling) are reached through the optional Windows dialog; not tested on a physical printer |
| Page count and file size | Fully implemented and tested | Page count in the toolbar, file size in Properties; E: `test_probe` |
| Password-protected PDFs (with the password) | Fully implemented and tested | E: `test_open_encrypted_requires_password` (the password prompt itself is not driven by the UI test) |
| Large documents without loading every page | Fully implemented and tested | E: `test_open_large` (300 pages). The viewer renders pages lazily (PDF.js) and the file is streamed by HTTP range requests from the local protocol |
| Corrupt, encrypted, malformed, unsupported files handled without crashing | Fully implemented and tested | E: `test_open_malformed_is_repaired_or_reported`, `test_open_truncated_reports_error`, `test_open_not_a_pdf` |

## 3. Editing existing content (brief §9)

N PDF edits the page content stream itself. It does not lay white boxes over the old text. Where the original embedded font has the glyphs needed, the new text is written in that original font; where it does not, a substitute font is used and the user is told.

| Feature | Status | Evidence / limitation |
|---|---|---|
| Select and modify existing text in place | Fully implemented and tested | E: `test_edit_existing_text_in_original_font` (reopened output, text and font checked); U5 |
| Delete existing text | Fully implemented and tested | E: `test_delete_existing_text_line` |
| Insert new text | Fully implemented and tested | E: `test_insert_text_and_image` |
| Replace selected text | Fully implemented and tested | Same mechanism as modify; E/U as above |
| Change font size and colour of edited text | Implemented with limitations | Implemented in the in-place editor. Not separately asserted by a test |
| Bold / italic on edited text | Implemented with limitations | Applied by switching to a substitute font family, because subset fonts in a PDF rarely contain a bold variant. The editor says so |
| Alignment (which edge stays fixed) | Implemented with limitations | Left/centre/right anchor, automatic right-anchoring for numbers in columns. Not separately asserted by a test |
| Move text lines and paragraphs | Fully implemented and tested | E: `test_edit_move_and_paragraph_mode` |
| Resize text boxes | Implemented with limitations | The engine reflows a paragraph into a new box; the UI does not yet offer a resize handle for existing paragraphs (new text boxes are sized by dragging) |
| Find and replace across the document | Fully implemented and tested | E: `test_find_and_replace`. Occurrences that cannot be replaced in place (missing glyphs, rotated text) are reported, not silently skipped |
| Missing glyphs in subset fonts | Fully implemented and tested | E: `test_edit_with_missing_glyphs_substitutes_and_reports` |
| Scanned pages: in-place editing refused with explanation | Fully implemented and tested | E: `test_edit_scanned_page_is_refused` (user is directed to OCR or Convert to Word) |
| Rotated or vertical text | Not implemented | Refused with an explanation; use Add Text or Convert to Word |
| Select, move, resize and delete existing images | Fully implemented and tested | E: `test_move_resize_delete_existing_image` (deleted image data is also removed from the file) |
| Replace an existing image | Fully implemented and tested | E: `test_replace_existing_image` (new image fitted without distortion; old image data removed) |
| Insert new images | Fully implemented and tested | E: `test_insert_text_and_image` |
| Undo and redo | Fully implemented and tested | U6. Every edit creates a revision; revisions live in the temp folder until the document is closed |
| Original preserved by default | Fully implemented and tested | U10, E: `test_sources_never_modified` |
| Alternative for heavy rewording (convert to Word, edit, convert back) | Fully implemented and tested | E: `test_pdf_to_word_flow`, `test_office_to_pdf_word_excel_ppt` |

## 4. Editing and review tools (brief §9D and §14)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Highlight, underline, strikethrough following the dragged text (character-precise, line by line) | Fully implemented and tested | E: `test_annotations_add_edit_delete_flatten`, `test_highlight_from_text_selection_snaps_to_characters`; U7, U8, U9 (saved file checked: marks sit exactly on the selected text) |
| Act on selected text from a toolbar or right-click menu: copy, highlight, underline, strikethrough, squiggly, note on text, mark for redaction | Fully implemented and tested | U8, U9 |
| Squiggly underline | Implemented with limitations | Implemented (same path as underline); not in the automated test |
| Sticky notes and text comments | Fully implemented and tested | E as above; U7 |
| Text callouts | Fully implemented and tested | E as above |
| Freehand drawing | Fully implemented and tested | E as above (ink) |
| Lines and arrows | Fully implemented and tested | E as above |
| Rectangles and circles | Fully implemented and tested | E as above |
| Stamps (standard, custom text, image) | Fully implemented and tested | E as above |
| Reviewer initials | Fully implemented and tested | E as above |
| Signature images | Fully implemented and tested | E: `test_signature_image_placement`; U13 |
| Change colour, size; edit; delete individually | Fully implemented and tested | E as above (update and delete operations) |
| Flatten annotations, with prior warning | Fully implemented and tested | E as above; the UI asks for confirmation before flattening |
| Shape insertion as page content (not annotation) | Implemented with limitations | Shapes are added as annotations; flatten them to make them part of the page content |
| Comment replies, stored as Acrobat stores them (Text annotations with /IRT and /RT /R) | Fully implemented and tested | E: `test_replies_and_review_status_are_stored_as_acrobat_threads`; U32 |
| Review status (Accepted, Rejected, Cancelled, Completed, None) and check marks | Fully implemented and tested | E as above; U32 |
| Comments panel: filter by text, type, reviewer, status; sort by page, date, reviewer, type | Fully implemented and tested | U32 (no match, then exactly one) |
| Export comments to XFDF and import them (threads, status and check marks come back) | Fully implemented and tested | E: XFDF round trip of 7 annotations, import of Acrobat-style XFDF with unsupported types reported; U32 (into a clean copy) |
| Comment summary PDF (page image beside each comment, replies and status) | Implemented with limitations | E and U32. Typeset with Latin fonts: comments written in Indian scripts may not display in the summary |
| Comment list as CSV for Excel (UTF-8 with BOM) | Fully implemented and tested | E; U32 |
| Links: drag a box and link it to a page of the document | Fully implemented and tested | E: links test; U34 (the link is followed to page 5) |
| Links to a web address (http, https, mailto; javascript:, file: and data: refused); N PDF itself never opens them | Fully implemented and tested | E: links test (add, refuse unsafe schemes, delete) |
| FDF comment files | Not implemented | XFDF only |

## 5. Page management (brief §10)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Organize view with thumbnails and drag-and-drop reordering | Implemented with limitations | Implemented; the automated UI test uses delete and apply (U11), not dragging |
| Merge PDFs (with per-file page selection and bookmarks) | Fully implemented and tested | E: `test_merge_three_pdfs`, `test_merge_with_page_selection`; U18 |
| Split: every N pages, ranges, single pages, odd/even, by bookmarks | Fully implemented and tested | E: `test_split_twenty_into_four`, `test_split_modes`; U19 |
| Extract, delete, rotate, reverse, duplicate pages | Fully implemented and tested | E: `test_delete_extract_rotate_reverse_duplicate`; U11 |
| Insert pages from another PDF; replace pages | Fully implemented and tested | E: `test_insert_and_replace` |
| Rearrange pages | Fully implemented and tested | E: `test_reorder_rotate_blank` |
| Insert blank pages | Fully implemented and tested | E: `test_reorder_rotate_blank` |
| Create a new PDF from selected pages | Fully implemented and tested | E: `test_delete_extract_rotate_reverse_duplicate` (extract) |
| Interleave pages from two documents | Fully implemented and tested | E: `test_assemble_multi_source_interleave` |
| Page numbers, headers and footers | Fully implemented and tested | E: `test_watermark_header_footer_bates` |
| Bookmarks and page labels | Fully implemented and tested | E: `test_bookmarks_and_labels` |
| Show intended sequence before processing | Fully implemented and tested | Organize view and merge list show the order; U11, U18 |
| Verify page count and order after processing | Fully implemented and tested | Every page operation compares page fingerprints against the plan before reporting success (E: all page tests) |

## 6. Compression (brief §11)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Presets: Maximum Quality, High Quality, Balanced, High Compression, Custom | Fully implemented and tested | E: `test_compress_presets_and_larger_is_reported` |
| Image resolution, quality, optimisation; removal of unneeded data | Fully implemented and tested | E: `test_compress_image_heavy_reports_actual_sizes` |
| Text and vectors preserved (no full-page rasterisation) | Fully implemented and tested | E: as above (text extraction compared before/after) |
| Target output size | Implemented with limitations | Steps through progressively stronger settings and reports the closest result; it cannot guarantee an arbitrary target (E: `test_compress_target_size`) |
| Original size, final size, absolute and % reduction, preset, status | Fully implemented and tested | U15. Reduction % = (original − final) / original × 100, using measured file sizes |
| Larger output reported honestly | Fully implemented and tested | E: `test_compress_presets_and_larger_is_reported` |
| Batch compression | Fully implemented and tested | U17 |

## 7. Conversion (brief §12)

| Feature | Status | Evidence / limitation |
|---|---|---|
| PDF to Word, layout-preserving mode | Fully implemented and tested | E: `test_pdf_to_word_layout`; U23. Uses pdf2docx; complex multi-column layouts may need tidying in Word |
| PDF to Word, easy-editing (flowing text) mode | Fully implemented and tested | E: `test_pdf_to_word_flow` |
| PDF to Word for scanned PDFs (OCR first) | Implemented with limitations | Automatic OCR when pages have no text. Exercised for Excel (E: `test_scanned_to_excel_with_ocr`), not separately for Word |
| PDF to Excel with tables, headings, numbers, dates, negatives | Fully implemented and tested | E: `test_pdf_to_excel_financial_values` (cell values compared with the source); U16 |
| Identifiers (account numbers, GSTIN, PAN, invoice numbers, leading zeros) kept as text | Fully implemented and tested | E: `test_parse_number_keeps_identifiers_as_text`, `test_pdf_to_excel_financial_values` |
| Tables spanning pages merged; rows split by a page break stitched | Fully implemented and tested | E: `test_pdf_to_excel_financial_values` (bank table across pages) |
| Review aid for uncertain pages | Fully implemented and tested | "Extraction Report" sheet: per page, how many printed digits reached cells; pages below threshold flagged |
| Merged cells | Implemented with limitations | Spanning text is placed in the first cell of the span; cells are not merged in the XLSX |
| Excel from scanned PDFs (OCR) | Fully implemented and tested | E: `test_scanned_to_excel_with_ocr`. Accuracy depends on scan quality |
| CSV export | Fully implemented and tested | E: `test_pdf_to_csv` |
| PDF to PowerPoint: image slides, editable text, hybrid | Fully implemented and tested | E: `test_pdf_to_ppt_modes`; U23. The options say plainly which mode gives editable slides and which gives page pictures |
| PDF to PNG, JPEG, TIFF (incl. multi-page), BMP, WebP; page ranges; dpi; quality; naming rule | Fully implemented and tested | E: `test_pdf_to_images` (dimensions, formats and file names checked) |
| PDF to plain text, HTML, Markdown | Fully implemented and tested | E: `test_pdf_to_text_layout_html_md` |
| Word, Excel, PowerPoint (and ODF, RTF, CSV) to PDF | Fully implemented and tested | E: `test_office_to_pdf_word_excel_ppt` — executed with LibreOffice on Linux, and on Windows with the bundled LibreOffice for Windows (engine suite against the bundled runtime and against the installed copy; the installed copy's self-test also converts a document) |
| Images to PDF (one or many; page size, orientation, margin, fit, quality) | Fully implemented and tested | E: `test_images_to_pdf` |
| Text to PDF | Fully implemented and tested | E: `test_text_to_pdf` |
| HTML to PDF | Implemented with limitations | Rendered by the bundled Chromium with scripts disabled and no network access, so remote images and styles referenced by the HTML are not loaded (U24: PDF produced, zero requests to the referenced host) |
| Scanned images to searchable PDF | Implemented with limitations | Images-to-PDF with the OCR option. The OCR step is tested on scanned PDFs (E: `test_ocr_creates_searchable_pdf`), not on this exact path |
| Unsupported conversions give a clear error, no misleading file | Fully implemented and tested | E: `test_open_not_a_pdf`, `test_batch_continues_after_invalid_file` |

## 8. OCR (brief §13)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Whole document and selected pages | Fully implemented and tested | E: `test_ocr_creates_searchable_pdf`; U20 (page 1 only) |
| Searchable PDF over the original scan image, verified after writing | Fully implemented and tested | E: as above (scan image unchanged, text searchable) |
| Plain-text extraction | Fully implemented and tested | E: `test_ocr_plain_text_extraction` |
| Pages that already have text are skipped | Fully implemented and tested | E: `test_ocr_skips_text_pages` |
| Rotated / sideways scans corrected | Fully implemented and tested | E: `test_ocr_turns_sideways_scan_upright` |
| Confidence per page; low-confidence pages flagged | Fully implemented and tested | E: `test_ocr_creates_searchable_pdf` (mean confidence present); flagged pages listed in the result |
| Table extraction from scans | Fully implemented and tested | E: `test_scanned_to_excel_with_ocr` |
| Batch OCR | Implemented with limitations | Uses the same job as single-file OCR; the batch UI path itself was exercised with compression (U17), not OCR |
| Languages bundled: English, Hindi, Gujarati, orientation detection | Implemented with limitations | Bundled in the Windows build (tessdata 4.1.0). Tests ran with English only. More languages can be added offline by copying `.traineddata` files |
| No runtime download of engines or language data | Fully implemented and tested | No download code exists; see `docs/OFFLINE_VERIFICATION.md` |

## 9. Signatures (brief §15)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Draw, type (two script fonts) or import a signature; initials | Fully implemented and tested | U13 (typed); drawing and import use the same placement path. E: `test_signature_image_placement` |
| Resize and reposition | Fully implemented and tested | Placed by dragging a box; when embedded in page content it can be moved/resized with the image editor (E: `test_move_resize_delete_existing_image`) |
| Add date | Implemented with limitations | Implemented ("Add date below signature"). Not covered by an automated test |
| Saved reusable signatures, protected | Implemented with limitations | Stored encrypted with Windows DPAPI (Electron safeStorage) under the user profile. Not covered by an automated test on Windows |
| Apply to this page, all pages or a page list | Implemented with limitations | Implemented; the test covers "this page" only |
| Certificate digital signature with a .pfx/.p12 file (PAdES, visible or invisible) | Fully implemented and tested | E: `test_digital_signature_sign_verify_and_tamper`; U26 (certificate created in the app, document signed, signature verified intact and valid) |
| Create a self-signed certificate | Fully implemented and tested | U26 |
| Signing with a USB token / smart card (PKCS#11) | Implemented with limitations | Implemented; **untested** because no hardware token was available. Requires the token vendor's driver (normally already installed with the token) |
| Signature verification and status display | Fully implemented and tested | E: as above, including detection of a tampered file |
| Revocation (CRL/OCSP) and trusted timestamps | Not implemented | Both need network access, which N PDF never uses. The verification panel states "Revocation not checked (offline)" |
| Warn before an operation could invalidate an existing signature | Fully implemented and tested | E: `test_signed_document_warns_before_modification` |
| Signature image never presented as a digital signature | Fully implemented and tested | Separate panels, wording and result messages; the image panel states it provides no cryptographic protection (U13, U26 screenshots) |

## 10. Security (brief §16)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Password encryption: AES-256, AES-128, RC4-128 (legacy) | Fully implemented and tested | E: `test_encrypt_restrict_and_authorised_decrypt`; U21 |
| Permission settings | Fully implemented and tested | E: as above |
| Authorised password removal | Fully implemented and tested | E: as above |
| No bypass of restrictions without the owner password | Fully implemented and tested | E: `test_restricted_without_owner_password_is_not_bypassed` |
| Encryption and permission inspection | Fully implemented and tested | E: `test_inspect_and_integrity` |
| Integrity check (structure, hash, incremental revisions) | Fully implemented and tested | E: `test_inspect_and_integrity` |
| Metadata inspection, editing, removal | Fully implemented and tested | E: `test_metadata_edit_and_remove` |
| Passwords never written to logs or settings | Implemented with limitations | By design: passwords travel only in memory between UI and engine; the job log records method names, not parameters. Verified by code review, not by an automated test |
| Permanent redaction: text, search layer, images under the area, annotations, form fields, bookmarks, metadata | Fully implemented and tested | E: `test_redaction_removes_text_and_verifies`, `test_redaction_of_ocr_text_layer`; U12 (PAN including inside a GSTIN removed; saved file checked) |
| Pattern search for PAN, GSTIN, Aadhaar, IFSC, e-mail, phone, account and card numbers, dates | Fully implemented and tested | E and U12 for PAN/GSTIN; other patterns are covered by the engine search test |
| Preview before applying; warning that it cannot be undone | Fully implemented and tested | U12 (marks reviewed before apply) |
| Post-redaction verification; refuses to save if anything is recoverable | Fully implemented and tested | E: `test_redaction_refuses_when_verification_fails` |

## 11. Metadata (brief §17)

| Feature | Status | Evidence / limitation |
|---|---|---|
| View and edit title, author, subject, keywords, creator, producer, creation and modification dates (Info and XMP kept in sync) | Fully implemented and tested | E: `test_metadata_edit_and_remove` |
| Remove selected or all metadata | Fully implemented and tested | E: as above |
| Warning that changes invalidate an existing digital signature | Fully implemented and tested | E: `test_signed_document_warns_before_modification` (same warning path) |
| Initial view saved in the PDF: navigation panel, page layout, magnification, open-to page, show document title (the tab shows the title) | Fully implemented and tested | E: initial view written and read back; U34 (saved with thumbnails, page 3, fit page; read back from the saved file) |

## 12. Watermarks, headers, footers, numbering (brief §18)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Text and image watermarks | Fully implemented and tested | E: `test_watermark_header_footer_bates`; U25 |
| Headers and footers with title, date, custom text, page tokens | Fully implemented and tested | E: as above |
| Page numbers in several formats | Fully implemented and tested | E: as above |
| Presets CONFIDENTIAL, DRAFT, FOR REVIEW, FINAL, SAMPLE (plus COPY, PAID, VERIFIED) | Fully implemented and tested | U25 |
| Font, size, colour, opacity, alignment, rotation, position, margins, page ranges | Fully implemented and tested | E: as above, plus `test_stamps_upright_on_rotated_pages` |
| Preview before applying | Fully implemented and tested | U25 (preview rendered by the engine from the real settings) |
| Background and border | Fully implemented and tested | E: `test_watermark_header_footer_bates` |

## 13. Comparison (brief §19)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Added, removed, modified text | Fully implemented and tested | E: `test_compare_detects_text_page_and_metadata_changes`; U22 |
| Page-count and page-order differences | Fully implemented and tested | E: as above |
| Image and layout differences (visual comparison) | Fully implemented and tested | E: `test_compare_visual_image_change` |
| Metadata differences | Fully implemented and tested | E: as above |
| Navigate between differences, side-by-side highlights | Fully implemented and tested | U22 |
| Comparison report as PDF | Implemented with limitations | Implemented; not covered by an automated test |
| Exhaustiveness | Implemented with limitations | The tool states that it cannot detect every possible change (for example invisible objects or form scripts) |

## 14. Forms (brief §20)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Identify fields; fill text, check boxes, radio buttons, drop-downs | Fully implemented and tested | E: `test_form_fill_and_flatten`; U14 |
| Save completed forms (fields stay interactive) | Fully implemented and tested | E and U14 |
| Print completed forms | Implemented with limitations | Print dialog → Comments & forms → "Form fields only" or "Document and markups" (E: `test_print_prepare_comments_and_forms_options`); see §2 Printing |
| Flatten, with warning | Fully implemented and tested | E: as above |
| Prepare a form: create text fields, check boxes, radio button groups, drop-downs, list boxes, signature fields | Fully implemented and tested | E: `test_every_field_type_is_created_with_its_properties`; U36 |
| Radio button groups as one field with a distinct value per button (filling selects exactly one) | Fully implemented and tested | E: `test_radio_group_fills_one_button_and_survives_reopening` (middle button of three, re-fill, flags kept); U36 (Composition on, Regular off in the saved file) |
| Field properties: name, tooltip, required, read only, multi-line, character limit, choices | Fully implemented and tested | E: `test_rename_properties_move_and_delete`; U36 (drop-down named Quarter with four choices) |
| Move, resize and delete fields; Delete key removes the selected widget | Fully implemented and tested | E as above (incl. one radio button and then the whole group); U36 (moved 40 px; deleted with the Delete key) |
| Detect form fields (underscores, ruled lines, empty boxes and check squares beside a label); suggestions accepted or dropped on the page | Implemented with limitations | E: `test_detect_suggests_blanks_with_their_labels`; U36 (5 found, one accepted, one dropped, the rest added). Rule-based, tested on synthetic forms only; Acrobat's detection is trained and will find fields these rules miss |
| Date/number formats, calculated fields, push buttons, barcode fields | Not implemented | Need form JavaScript, which N PDF never runs |
| Form scripts (JavaScript) | Not implemented | Never executed, for security. Calculated fields are not recalculated |
| Filling radio groups made by other software (one button on, the rest off; group value written as a PDF name) | Fully implemented and tested | E: radio fill test (the defect it guards also affected third-party forms) |

## 15. Creation and assembly (brief §21)

| Feature | Status | Evidence / limitation |
|---|---|---|
| PDF from Office files, images, text, HTML | Fully implemented and tested | See §7 (HTML: with limitations) |
| Assemble mixed source files into one PDF with bookmarks | Fully implemented and tested | E: `test_assemble_mixed` |
| Page size (A3, A4, A5, Letter, Legal, image size), orientation, margins, scaling, placement, order, image quality | Fully implemented and tested | E: `test_images_to_pdf`, `test_text_to_pdf` |
| Scan to PDF through Windows Image Acquisition (flatbed or feeder, colour/greyscale/black and white, 150-600 dpi), reorder and remove pages, one PDF with optional OCR | Implemented with limitations | U38: on Windows CI, which has no scanner, the tool reports that plainly and keeps Scan disabled; the whole flow (two pages from a simulated feeder, reordered, saved with OCR, opened) passes with a development-only simulated scanner. **Not tested with a physical scanner.** TWAIN-only scanners are not supported; PowerShell blocked by company policy is reported |

## 16. Utilities (brief §22)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Validation (structure check with qpdf) | Fully implemented and tested | E: `test_inspect_and_integrity` |
| Repair of damaged PDFs | Fully implemented and tested | E: `test_repair_malformed`. Severely truncated files are reported as unrecoverable rather than "repaired" |
| Page-size inspection and standardisation | Fully implemented and tested | E: `test_page_size_standardize_and_crop` |
| Page-dimension adjustment (crop / margins) | Fully implemented and tested | E: as above |
| Bookmarks and page labels | Fully implemented and tested | E: `test_bookmarks_and_labels` |
| Attachments: list, extract, add, remove | Fully implemented and tested | E: `test_attachments` |
| Image extraction | Fully implemented and tested | E: `test_extract_images` |
| Blank-page detection and removal | Fully implemented and tested | E: `test_blank_page_detection` |
| Bates numbering (continues across files in batch) | Fully implemented and tested | E: `test_watermark_header_footer_bates` |
| Linearisation (fast web view) | Fully implemented and tested | E: `test_linearize` |
| PDF/A-1b, 2b, 3b conversion | Implemented with limitations | Ghostscript conversion plus basic checks (fonts embedded, no encryption, output intent, XMP). **Not formally validated** (veraPDF needs Java and is not bundled); the result screen says so (E: `test_pdfa_conversion_with_basic_checks`) |
| Print selected pages (range, current page, odd/even, reverse) | Fully implemented and tested | U27 (pages 1-5, odd only) |
| Print multiple documents in one action | Not implemented | Print each open document (Ctrl+P per tab) |
| Backgrounds and borders | Fully implemented and tested | See §12 |

## 17. Batch processing (brief §23)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Operations: compress, OCR, Word, Excel, PowerPoint, images, text, watermark/numbers/Bates, remove metadata, extract pages, split every N pages, add password, PDF/A, Office to PDF, images to PDF | Implemented with limitations | Each operation runs the same engine job as the single-file tool (each of which is tested). The batch screen itself is tested with compression (U17) |
| Merging in batch | Implemented with limitations | Merging is inherently multi-file and lives in the Merge tool (U18), not the batch queue |
| Add files or whole folders (optionally with sub-folders) | Implemented with limitations | Folder scanning implemented; the test adds files |
| Output folder, naming rules with {name} and {date} | Fully implemented and tested | U17 |
| Never overwrite existing files | Fully implemented and tested | E: `test_no_overwrite_of_existing_output` (numbered name used instead) |
| Failure of one file does not stop the rest; accurate summary | Fully implemented and tested | U17 (2 succeeded, 1 failed), E: `test_batch_continues_after_invalid_file` |
| Retry failed, cancel | Implemented with limitations | Implemented (cancellation stops the engine process tree and removes partial output). Not covered by an automated test |
| Saved actions (Acrobat's Action Wizard): named step lists run in order on files or a folder; intermediate files in temp; only the final result written | Fully implemented and tested | E: `test_steps_run_in_order_and_the_source_is_untouched`, `test_a_failing_step_names_itself_and_writes_nothing`; U37 (two files, outputs checked, originals byte-identical) |
| Steps: OCR, compress, watermark, header & footer, page numbers, redact patterns, remove blank pages, rotate, flatten, set/remove properties, PDF/A, password | Implemented with limitations | E covers OCR, compress, watermark, page numbers, set/remove properties, blank pages, PAN redaction, rotate (failure path) and password; header & footer, flatten and PDF/A steps use the same engine operations as their single tools (each tested) but were not run inside an action |
| Password step: password given at run time, never saved or logged | Fully implemented and tested | E: `test_protect_is_last_and_takes_its_password_at_run_time`; U37 (actions.json checked: no password) |
| Ready-made actions (make scans searchable, client copy, mask PAN and Aadhaar, archive as PDF/A, confidential with page numbers) | Implemented with limitations | U37 checks the five are offered; they were not each run end to end |
| Search across files: a folder (with sub-folders) or all open documents; whole words, case, comments; open a result at its page with the term found | Fully implemented and tested | E: `test_search_across_files`; U33 (folder with a scan: 1 match in 1 of 3 files, the scan listed as not searched, the result opens with the term in the find bar) |

## 18. Professional document quality (brief §24)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Figures and signs never altered; Indian and Western digit grouping; (negatives); Dr/Cr kept | Fully implemented and tested | E: `test_parse_number_converts`, `test_drcr_marker_preserved_in_format` |
| Identifiers kept exactly | Fully implemented and tested | E: `test_parse_number_keeps_identifiers_as_text` |
| Row order preserved; no silent drops (digit coverage report) | Fully implemented and tested | E: `test_pdf_to_excel_financial_values` |
| Output not presented as a verified accounting record | Fully implemented and tested | Conversion screens and the Extraction Report say the output must be reviewed |

## 19. File integrity (brief §25)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Originals preserved; new destination by default | Fully implemented and tested | E: `test_sources_never_modified`, `test_refuses_to_replace_source`; U10 |
| Outputs validated (exist, reopen, expected pages) before success | Fully implemented and tested | Every engine operation writes to a temp file, validates, then moves into place (all E tests reopen outputs) |
| Duplicate names handled | Fully implemented and tested | E: `test_no_overwrite_of_existing_output` |
| Special characters and long paths | Fully implemented and tested | E: `test_special_characters_and_long_paths` (Windows `\\?\` long-path handling is in the code but was not exercised on Windows) |
| Unsupported formats detected | Fully implemented and tested | E: `test_open_not_a_pdf` |
| Temporary files cleaned up | Implemented with limitations | Per-job temp folders removed after each job; stale session folders removed at start-up; Settings shows usage. Not covered by an automated test |
| Disk full and access denied reported clearly | Implemented with limitations | Mapped to specific messages. Not covered by an automated test |
| No corrupt partial outputs | Fully implemented and tested | Atomic temp-then-move writes; E: `test_open_truncated_reports_error` path and the batch failure test leave no partial files |

## 20. Application security (brief §26)

| Control | Status | Evidence / limitation |
|---|---|---|
| Context isolation, no Node.js in the renderer, Chromium sandbox | Fully implemented and tested | Configured in `src/main/main.ts`; the UI test runs against this configuration |
| Restricted preload bridge with channel allowlist | Fully implemented and tested | `src/preload/preload.ts`, `src/shared/ipc.ts` |
| IPC validation: sender origin, argument types, method allowlist, paths limited to files the user chose | Fully implemented and tested | `src/main/ipc.ts`, `src/main/access.ts`; exercised by every UI scenario |
| No shell interpolation; external tools run with argument arrays | Fully implemented and tested | `engine/nayanpdf_engine/util/tools.py` |
| Navigation and new windows blocked; all non-local requests cancelled; spell-check dictionary downloads disabled in every session | Fully implemented and tested | U41 (no page requests; on Linux, no attempt by any app process through a recording proxy) |
| No open ports or listeners | Fully implemented and tested | Engine talks over stdin/stdout pipes only. Windows acceptance (builds 10 to 12, 19, 20 and 22): 0 listening TCP ports owned by N PDF processes, sampled throughout two launches of the installed app |
| No telemetry, updater, accounts, activation | Fully implemented and tested | None exists in the code; see `docs/OFFLINE_VERIFICATION.md` |
| Electron fuses (no ELECTRON_RUN_AS_NODE, no inspector, ASAR integrity) | Fully implemented and tested | Set at packaging time; read back from the installed executable by the Windows acceptance test (`NayanPDF.exe` in builds 10 to 13, `X-PDF.exe` in builds 19 and 20, `N-PDF.exe` from 1.2): Run-as-Node off, `NODE_OPTIONS` and inspector arguments ignored, ASAR integrity on, ASAR-only loading on |

## 21. Performance (brief §27)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Lazy page rendering and range-based file loading | Fully implemented and tested | E: `test_open_large` (300 pages) |
| Long jobs in background processes; UI stays usable | Fully implemented and tested | Jobs run in separate engine processes; U scenarios continue working while jobs run |
| Real progress only | Fully implemented and tested | Progress is reported per page by the engine; indeterminate otherwise |
| Hundreds of scanned pages | Implemented with limitations | OCR runs up to four pages in parallel; no timing benchmark has been run |

## 22. Keyboard shortcuts (brief §28)

| Shortcut | Status | Evidence / limitation |
|---|---|---|
| Ctrl+F search | Fully implemented and tested | U4 |
| Ctrl+P print | Fully implemented and tested | U27 |
| Ctrl+Shift+F search across files | Fully implemented and tested | U33 |
| Read Out Loud: Ctrl+Shift+V, Ctrl+Shift+B, Ctrl+Shift+C, Ctrl+Shift+E; Ctrl+Q exit | Implemented with limitations | Implemented; U35 starts reading from the View menu, the keys themselves are not exercised |
| Ctrl+O, Ctrl+S, Ctrl+Shift+S, Ctrl+Z, Ctrl+Y (and Ctrl+Shift+Z), Ctrl+= / Ctrl+-, Ctrl+W and Ctrl+F4, Ctrl+Tab / Ctrl+Shift+Tab, Ctrl+K (settings), Ctrl+A (select the page's text), Ctrl+C; PageUp/PageDown by page in single-page layouts | Implemented with limitations | Implemented and ignored while typing in a text field (Acrobat's assignments; Ctrl+Y stays Redo as in Windows). Ctrl+S, Ctrl+Shift+S, Ctrl+Z/Y, Ctrl+P, Ctrl+F and the view keys are exercised by the UI test; Ctrl+Tab, Ctrl+F4, Ctrl+K and PageUp/PageDown are not |

## 23. Installer (brief §29)

| Feature | Status | Evidence / limitation |
|---|---|---|
| `N-PDF_Setup.exe` (`NayanPDF_Setup.exe` up to 1.0, `X-PDF_Setup.exe` in 1.1), 64-bit, self-contained, nothing downloaded during or after installation | Fully implemented and tested | Windows acceptance test, build 22: installed copy passes its offline self-test (all nine engines including LibreOffice, Tesseract and Ghostscript) and the full engine test suite with outbound traffic blocked |
| Installs to the user's profile without requesting administrator rights | Implemented with limitations | Per-user installer that does not request elevation; on CI it ran under an administrator account, so a standard-account install has not been executed |
| Choice of installation folder | Implemented with limitations | Offered on the installer's folder page; the automated test uses the silent default |
| Start Menu shortcut, icon, version information, uninstall registration | Fully implemented and tested | Windows acceptance test |
| Optional desktop shortcut | Implemented with limitations | Check box on the installer's last page; not exercised by the silent test. Kept across an upgrade (Windows acceptance test, build 22) and removed by uninstall |
| Uninstall removes program, shortcut and registration; keeps user documents and settings | Fully implemented and tested | Windows acceptance test, followed by a successful reinstall |
| Installed executable named `N-PDF.exe` (`NayanPDF.exe` up to 1.0, `X-PDF.exe` in 1.1) | Fully implemented and tested | Windows acceptance test, build 22 |
| Upgrade in place from X PDF 1.1 and NayanPDF 1.0: one installation afterwards, old program, shortcuts and right-click commands replaced, a desktop shortcut kept, settings, recent files, saved signatures and actions carried over | Implemented with limitations | Windows acceptance test, build 22: X PDF build 20 (with its right-click commands) and, separately, NayanPDF build 13 installed, a desktop shortcut and a settings file planted, then N PDF over it: one registration, old executable, Start Menu shortcut and old right-click commands gone, desktop shortcut renamed "N PDF" and pointing at `N-PDF.exe`, registered uninstaller present and working, settings file carried over. U42: the first start copies settings and saved actions from an X PDF folder. Saved signatures written by a real earlier session were not part of the test |
| Code signing of the installer and executable | Requires paid licence | Needs a purchased code-signing certificate; until then Windows SmartScreen warns on first run |
