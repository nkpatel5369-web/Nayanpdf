# NayanPDF feature register

Status of every feature in the NayanPDF brief, using the five statuses the brief prescribes. A feature is marked **Fully implemented and tested** only where an automated test exercises it end to end and checks the output file; anything implemented but not covered by such a test is marked **Implemented with limitations** and the limitation says so.

## How to read the evidence column

- **E:** engine test in `engine/tests/` (pytest, synthetic documents, output files reopened and inspected).
- **U:** scenario in the UI end-to-end test `scripts/ui-e2e.mjs`, which drives the real Electron application, its main process and engine (see `docs/TEST_REPORT.md`).

**Platforms.** The evidence codes refer to tests run on the Linux development build. The same engine tests and UI scenarios have also been run on Windows (GitHub Actions, Windows Server 2025), against the bundled Windows runtime, and the installed application passed the 67-point Windows acceptance test (build 10). Windows 10/11 desktop editions and standard (non-administrator) accounts have not been tested.

## Status summary

| Status | Register lines |
|---|---|
| Fully implemented and tested | 170 |
| Implemented with limitations | 42 |
| Not implemented | 4 |
| Requires optional dependency | 0 |
| Requires paid licence | 1 |

Counts are register lines; a few features appear in more than one section because the brief lists them more than once. The four "Not implemented" lines are: in-place editing of rotated/vertical text, revocation checking and trusted timestamps (both need the internet), execution of form scripts (deliberately never run), and printing several documents in one action.

No processing feature needs a paid licence or a separately installed component: everything ships inside `NayanPDF_Setup.exe`. The one paid item is a code-signing certificate, which only removes the SmartScreen warning.

## 1. Home dashboard and workspace (brief §7)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Dashboard tiles for all 15 required tools (Edit, Merge, Split, Compress, Convert, Organize, OCR, Create, Annotate, Sign, Protect, Redact, Compare, Extract Images, Fill Forms) plus Watermark, Properties, Utilities | Fully implemented and tested | Tiles open the corresponding tool or document mode; merge, split, OCR, protect, compare and convert are driven through the UI by the test (U17–U22) |
| Open File action | Fully implemented and tested | U1 (command-line open), Open button and Ctrl+O use the same path |
| Drag-and-drop opening | Implemented with limitations | Implemented (files dropped anywhere are registered by the isolated preload script). Not covered by an automated test: Playwright cannot simulate an OS file drop |
| Recent documents list | Implemented with limitations | Implemented, with a "Clear" action in Settings. Not covered by an automated test |
| Multiple open documents in tabs | Fully implemented and tested | U: several scenarios run with two or three tabs open |
| Thumbnail panel, page navigation, page-number entry, zoom | Fully implemented and tested | U2 |
| Search within document | Fully implemented and tested | U3 |
| Document properties panel (pages, file size, page sizes, PDF version, encryption, fonts) | Fully implemented and tested | E: `test_probe`, `test_inspect_and_integrity`; panel shown in U screenshots |
| Save (Ctrl+S) and Save As (Ctrl+Shift+S) | Fully implemented and tested | U9: Save As writes a new file and the original is byte-identical afterwards. Overwriting the original requires an explicit confirmation |
| Light and dark themes | Fully implemented and tested | U27 |
| Windows display scaling / high-DPI | Implemented with limitations | Uses Chromium's native per-monitor DPI handling and vector UI icons. Not tested on a Windows high-DPI display |
| Conversion centre with output folder, page ranges, quality, OCR options | Fully implemented and tested | U15, U22 |
| Batch workspace with queue, status, output location, errors | Fully implemented and tested | U16, E: `test_batch_continues_after_invalid_file` |
| Settings: theme, default folder, OCR languages, export settings, compression preset, recent files, temp files | Implemented with limitations | All settings are stored locally and applied. Only the theme setting is exercised by an automated test (U27) |

## 2. PDF viewing (brief §8)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Open PDF files | Fully implemented and tested | E: `test_open_ordinary`; U1 |
| Drag-and-drop opening | Implemented with limitations | See §1 |
| Multiple documents | Fully implemented and tested | See §1 |
| Page navigation | Fully implemented and tested | U2 |
| Page thumbnails | Fully implemented and tested | U2 |
| Zoom in and out | Fully implemented and tested | U2 (99% to 113%) |
| Fit to page, fit to width | Implemented with limitations | Implemented; fit-to-width is re-applied on window resize. No automated assertion on the resulting scale |
| Rotate view (does not change the file) | Implemented with limitations | Implemented in the viewer toolbar. Not covered by an automated test |
| Search in searchable text | Fully implemented and tested | U3 |
| Text selection and copy (drag, double-click a word, Ctrl+A for the page, Ctrl+C) | Fully implemented and tested | U7 (selection copied to the clipboard), U8. A drag may start in the margin or over highlighted text. Scanned pages need OCR first |
| Metadata inspection | Fully implemented and tested | E: `test_metadata_edit_and_remove` |
| Printing (Acrobat-style dialog) | Implemented with limitations | NayanPDF's own dialog: printer, copies and collate, all/current/range, odd/even, reverse, Fit / Actual size / Shrink oversized / Custom scale / Multiple pages per sheet (2–16, order, borders), orientation, paper, two-sided, black and white, comments & forms, quality, live sheet preview; prints straight to the chosen printer (U26 checks the produced sheets; E: `test_print_prepare_comments_and_forms_options`, `test_print_prepare_respects_permission`). Limitations: pages are sent as images at the chosen resolution (150/300/600 dpi); printer-specific options (trays, stapling) are reached through the optional Windows dialog; not tested on a physical printer |
| Page count and file size | Fully implemented and tested | Page count in the toolbar, file size in Properties; E: `test_probe` |
| Password-protected PDFs (with the password) | Fully implemented and tested | E: `test_open_encrypted_requires_password` (the password prompt itself is not driven by the UI test) |
| Large documents without loading every page | Fully implemented and tested | E: `test_open_large` (300 pages). The viewer renders pages lazily (PDF.js) and the file is streamed by HTTP range requests from the local protocol |
| Corrupt, encrypted, malformed, unsupported files handled without crashing | Fully implemented and tested | E: `test_open_malformed_is_repaired_or_reported`, `test_open_truncated_reports_error`, `test_open_not_a_pdf` |

## 3. Editing existing content (brief §9)

NayanPDF edits the page content stream itself. It does not lay white boxes over the old text. Where the original embedded font has the glyphs needed, the new text is written in that original font; where it does not, a substitute font is used and the user is told.

| Feature | Status | Evidence / limitation |
|---|---|---|
| Select and modify existing text in place | Fully implemented and tested | E: `test_edit_existing_text_in_original_font` (reopened output, text and font checked); U4 |
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
| Undo and redo | Fully implemented and tested | U5. Every edit creates a revision; revisions live in the temp folder until the document is closed |
| Original preserved by default | Fully implemented and tested | U9, E: `test_sources_never_modified` |
| Alternative for heavy rewording (convert to Word, edit, convert back) | Fully implemented and tested | E: `test_pdf_to_word_flow`, `test_office_to_pdf_word_excel_ppt` |

## 4. Editing and review tools (brief §9D and §14)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Highlight, underline, strikethrough following the dragged text (character-precise, line by line) | Fully implemented and tested | E: `test_annotations_add_edit_delete_flatten`, `test_highlight_from_text_selection_snaps_to_characters`; U6, U7, U8 (saved file checked: marks sit exactly on the selected text) |
| Act on selected text from a toolbar or right-click menu: copy, highlight, underline, strikethrough, squiggly, note on text, mark for redaction | Fully implemented and tested | U7, U8 |
| Squiggly underline | Implemented with limitations | Implemented (same path as underline); not in the automated test |
| Sticky notes and text comments | Fully implemented and tested | E as above; U6 |
| Text callouts | Fully implemented and tested | E as above |
| Freehand drawing | Fully implemented and tested | E as above (ink) |
| Lines and arrows | Fully implemented and tested | E as above |
| Rectangles and circles | Fully implemented and tested | E as above |
| Stamps (standard, custom text, image) | Fully implemented and tested | E as above |
| Reviewer initials | Fully implemented and tested | E as above |
| Signature images | Fully implemented and tested | E: `test_signature_image_placement`; U12 |
| Change colour, size; edit; delete individually | Fully implemented and tested | E as above (update and delete operations) |
| Flatten annotations, with prior warning | Fully implemented and tested | E as above; the UI asks for confirmation before flattening |
| Shape insertion as page content (not annotation) | Implemented with limitations | Shapes are added as annotations; flatten them to make them part of the page content |

## 5. Page management (brief §10)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Organize view with thumbnails and drag-and-drop reordering | Implemented with limitations | Implemented; the automated UI test uses delete and apply (U10), not dragging |
| Merge PDFs (with per-file page selection and bookmarks) | Fully implemented and tested | E: `test_merge_three_pdfs`, `test_merge_with_page_selection`; U17 |
| Split: every N pages, ranges, single pages, odd/even, by bookmarks | Fully implemented and tested | E: `test_split_twenty_into_four`, `test_split_modes`; U18 |
| Extract, delete, rotate, reverse, duplicate pages | Fully implemented and tested | E: `test_delete_extract_rotate_reverse_duplicate`; U10 |
| Insert pages from another PDF; replace pages | Fully implemented and tested | E: `test_insert_and_replace` |
| Rearrange pages | Fully implemented and tested | E: `test_reorder_rotate_blank` |
| Insert blank pages | Fully implemented and tested | E: `test_reorder_rotate_blank` |
| Create a new PDF from selected pages | Fully implemented and tested | E: `test_delete_extract_rotate_reverse_duplicate` (extract) |
| Interleave pages from two documents | Fully implemented and tested | E: `test_assemble_multi_source_interleave` |
| Page numbers, headers and footers | Fully implemented and tested | E: `test_watermark_header_footer_bates` |
| Bookmarks and page labels | Fully implemented and tested | E: `test_bookmarks_and_labels` |
| Show intended sequence before processing | Fully implemented and tested | Organize view and merge list show the order; U10, U17 |
| Verify page count and order after processing | Fully implemented and tested | Every page operation compares page fingerprints against the plan before reporting success (E: all page tests) |

## 6. Compression (brief §11)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Presets: Maximum Quality, High Quality, Balanced, High Compression, Custom | Fully implemented and tested | E: `test_compress_presets_and_larger_is_reported` |
| Image resolution, quality, optimisation; removal of unneeded data | Fully implemented and tested | E: `test_compress_image_heavy_reports_actual_sizes` |
| Text and vectors preserved (no full-page rasterisation) | Fully implemented and tested | E: as above (text extraction compared before/after) |
| Target output size | Implemented with limitations | Steps through progressively stronger settings and reports the closest result; it cannot guarantee an arbitrary target (E: `test_compress_target_size`) |
| Original size, final size, absolute and % reduction, preset, status | Fully implemented and tested | U14. Reduction % = (original − final) / original × 100, using measured file sizes |
| Larger output reported honestly | Fully implemented and tested | E: `test_compress_presets_and_larger_is_reported` |
| Batch compression | Fully implemented and tested | U16 |

## 7. Conversion (brief §12)

| Feature | Status | Evidence / limitation |
|---|---|---|
| PDF to Word, layout-preserving mode | Fully implemented and tested | E: `test_pdf_to_word_layout`; U22. Uses pdf2docx; complex multi-column layouts may need tidying in Word |
| PDF to Word, easy-editing (flowing text) mode | Fully implemented and tested | E: `test_pdf_to_word_flow` |
| PDF to Word for scanned PDFs (OCR first) | Implemented with limitations | Automatic OCR when pages have no text. Exercised for Excel (E: `test_scanned_to_excel_with_ocr`), not separately for Word |
| PDF to Excel with tables, headings, numbers, dates, negatives | Fully implemented and tested | E: `test_pdf_to_excel_financial_values` (cell values compared with the source); U15 |
| Identifiers (account numbers, GSTIN, PAN, invoice numbers, leading zeros) kept as text | Fully implemented and tested | E: `test_parse_number_keeps_identifiers_as_text`, `test_pdf_to_excel_financial_values` |
| Tables spanning pages merged; rows split by a page break stitched | Fully implemented and tested | E: `test_pdf_to_excel_financial_values` (bank table across pages) |
| Review aid for uncertain pages | Fully implemented and tested | "Extraction Report" sheet: per page, how many printed digits reached cells; pages below threshold flagged |
| Merged cells | Implemented with limitations | Spanning text is placed in the first cell of the span; cells are not merged in the XLSX |
| Excel from scanned PDFs (OCR) | Fully implemented and tested | E: `test_scanned_to_excel_with_ocr`. Accuracy depends on scan quality |
| CSV export | Fully implemented and tested | E: `test_pdf_to_csv` |
| PDF to PowerPoint: image slides, editable text, hybrid | Fully implemented and tested | E: `test_pdf_to_ppt_modes`; U22. The options say plainly which mode gives editable slides and which gives page pictures |
| PDF to PNG, JPEG, TIFF (incl. multi-page), BMP, WebP; page ranges; dpi; quality; naming rule | Fully implemented and tested | E: `test_pdf_to_images` (dimensions, formats and file names checked) |
| PDF to plain text, HTML, Markdown | Fully implemented and tested | E: `test_pdf_to_text_layout_html_md` |
| Word, Excel, PowerPoint (and ODF, RTF, CSV) to PDF | Fully implemented and tested | E: `test_office_to_pdf_word_excel_ppt` — executed with LibreOffice on Linux, and on Windows with the bundled LibreOffice for Windows (engine suite against the bundled runtime and against the installed copy; the installed copy's self-test also converts a document) |
| Images to PDF (one or many; page size, orientation, margin, fit, quality) | Fully implemented and tested | E: `test_images_to_pdf` |
| Text to PDF | Fully implemented and tested | E: `test_text_to_pdf` |
| HTML to PDF | Implemented with limitations | Rendered by the bundled Chromium with scripts disabled and no network access, so remote images and styles referenced by the HTML are not loaded (U23: PDF produced, zero requests to the referenced host) |
| Scanned images to searchable PDF | Implemented with limitations | Images-to-PDF with the OCR option. The OCR step is tested on scanned PDFs (E: `test_ocr_creates_searchable_pdf`), not on this exact path |
| Unsupported conversions give a clear error, no misleading file | Fully implemented and tested | E: `test_open_not_a_pdf`, `test_batch_continues_after_invalid_file` |

## 8. OCR (brief §13)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Whole document and selected pages | Fully implemented and tested | E: `test_ocr_creates_searchable_pdf`; U19 (page 1 only) |
| Searchable PDF over the original scan image, verified after writing | Fully implemented and tested | E: as above (scan image unchanged, text searchable) |
| Plain-text extraction | Fully implemented and tested | E: `test_ocr_plain_text_extraction` |
| Pages that already have text are skipped | Fully implemented and tested | E: `test_ocr_skips_text_pages` |
| Rotated / sideways scans corrected | Fully implemented and tested | E: `test_ocr_turns_sideways_scan_upright` |
| Confidence per page; low-confidence pages flagged | Fully implemented and tested | E: `test_ocr_creates_searchable_pdf` (mean confidence present); flagged pages listed in the result |
| Table extraction from scans | Fully implemented and tested | E: `test_scanned_to_excel_with_ocr` |
| Batch OCR | Implemented with limitations | Uses the same job as single-file OCR; the batch UI path itself was exercised with compression (U16), not OCR |
| Languages bundled: English, Hindi, Gujarati, orientation detection | Implemented with limitations | Bundled in the Windows build (tessdata 4.1.0). Tests ran with English only. More languages can be added offline by copying `.traineddata` files |
| No runtime download of engines or language data | Fully implemented and tested | No download code exists; see `docs/OFFLINE_VERIFICATION.md` |

## 9. Signatures (brief §15)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Draw, type (two script fonts) or import a signature; initials | Fully implemented and tested | U12 (typed); drawing and import use the same placement path. E: `test_signature_image_placement` |
| Resize and reposition | Fully implemented and tested | Placed by dragging a box; when embedded in page content it can be moved/resized with the image editor (E: `test_move_resize_delete_existing_image`) |
| Add date | Implemented with limitations | Implemented ("Add date below signature"). Not covered by an automated test |
| Saved reusable signatures, protected | Implemented with limitations | Stored encrypted with Windows DPAPI (Electron safeStorage) under the user profile. Not covered by an automated test on Windows |
| Apply to this page, all pages or a page list | Implemented with limitations | Implemented; the test covers "this page" only |
| Certificate digital signature with a .pfx/.p12 file (PAdES, visible or invisible) | Fully implemented and tested | E: `test_digital_signature_sign_verify_and_tamper`; U25 (certificate created in the app, document signed, signature verified intact and valid) |
| Create a self-signed certificate | Fully implemented and tested | U25 |
| Signing with a USB token / smart card (PKCS#11) | Implemented with limitations | Implemented; **untested** because no hardware token was available. Requires the token vendor's driver (normally already installed with the token) |
| Signature verification and status display | Fully implemented and tested | E: as above, including detection of a tampered file |
| Revocation (CRL/OCSP) and trusted timestamps | Not implemented | Both need network access, which NayanPDF never uses. The verification panel states "Revocation not checked (offline)" |
| Warn before an operation could invalidate an existing signature | Fully implemented and tested | E: `test_signed_document_warns_before_modification` |
| Signature image never presented as a digital signature | Fully implemented and tested | Separate panels, wording and result messages; the image panel states it provides no cryptographic protection (U12, U25 screenshots) |

## 10. Security (brief §16)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Password encryption: AES-256, AES-128, RC4-128 (legacy) | Fully implemented and tested | E: `test_encrypt_restrict_and_authorised_decrypt`; U20 |
| Permission settings | Fully implemented and tested | E: as above |
| Authorised password removal | Fully implemented and tested | E: as above |
| No bypass of restrictions without the owner password | Fully implemented and tested | E: `test_restricted_without_owner_password_is_not_bypassed` |
| Encryption and permission inspection | Fully implemented and tested | E: `test_inspect_and_integrity` |
| Integrity check (structure, hash, incremental revisions) | Fully implemented and tested | E: `test_inspect_and_integrity` |
| Metadata inspection, editing, removal | Fully implemented and tested | E: `test_metadata_edit_and_remove` |
| Passwords never written to logs or settings | Implemented with limitations | By design: passwords travel only in memory between UI and engine; the job log records method names, not parameters. Verified by code review, not by an automated test |
| Permanent redaction: text, search layer, images under the area, annotations, form fields, bookmarks, metadata | Fully implemented and tested | E: `test_redaction_removes_text_and_verifies`, `test_redaction_of_ocr_text_layer`; U11 (PAN including inside a GSTIN removed; saved file checked) |
| Pattern search for PAN, GSTIN, Aadhaar, IFSC, e-mail, phone, account and card numbers, dates | Fully implemented and tested | E and U11 for PAN/GSTIN; other patterns are covered by the engine search test |
| Preview before applying; warning that it cannot be undone | Fully implemented and tested | U11 (marks reviewed before apply) |
| Post-redaction verification; refuses to save if anything is recoverable | Fully implemented and tested | E: `test_redaction_refuses_when_verification_fails` |

## 11. Metadata (brief §17)

| Feature | Status | Evidence / limitation |
|---|---|---|
| View and edit title, author, subject, keywords, creator, producer, creation and modification dates (Info and XMP kept in sync) | Fully implemented and tested | E: `test_metadata_edit_and_remove` |
| Remove selected or all metadata | Fully implemented and tested | E: as above |
| Warning that changes invalidate an existing digital signature | Fully implemented and tested | E: `test_signed_document_warns_before_modification` (same warning path) |

## 12. Watermarks, headers, footers, numbering (brief §18)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Text and image watermarks | Fully implemented and tested | E: `test_watermark_header_footer_bates`; U24 |
| Headers and footers with title, date, custom text, page tokens | Fully implemented and tested | E: as above |
| Page numbers in several formats | Fully implemented and tested | E: as above |
| Presets CONFIDENTIAL, DRAFT, FOR REVIEW, FINAL, SAMPLE (plus COPY, PAID, VERIFIED) | Fully implemented and tested | U24 |
| Font, size, colour, opacity, alignment, rotation, position, margins, page ranges | Fully implemented and tested | E: as above, plus `test_stamps_upright_on_rotated_pages` |
| Preview before applying | Fully implemented and tested | U24 (preview rendered by the engine from the real settings) |
| Background and border | Fully implemented and tested | E: `test_watermark_header_footer_bates` |

## 13. Comparison (brief §19)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Added, removed, modified text | Fully implemented and tested | E: `test_compare_detects_text_page_and_metadata_changes`; U21 |
| Page-count and page-order differences | Fully implemented and tested | E: as above |
| Image and layout differences (visual comparison) | Fully implemented and tested | E: `test_compare_visual_image_change` |
| Metadata differences | Fully implemented and tested | E: as above |
| Navigate between differences, side-by-side highlights | Fully implemented and tested | U21 |
| Comparison report as PDF | Implemented with limitations | Implemented; not covered by an automated test |
| Exhaustiveness | Implemented with limitations | The tool states that it cannot detect every possible change (for example invisible objects or form scripts) |

## 14. Forms (brief §20)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Identify fields; fill text, check boxes, radio buttons, drop-downs | Fully implemented and tested | E: `test_form_fill_and_flatten`; U13 |
| Save completed forms (fields stay interactive) | Fully implemented and tested | E and U13 |
| Print completed forms | Implemented with limitations | Print dialog → Comments & forms → "Form fields only" or "Document and markups" (E: `test_print_prepare_comments_and_forms_options`); see §2 Printing |
| Flatten, with warning | Fully implemented and tested | E: as above |
| Form scripts (JavaScript) | Not implemented | Never executed, for security. Calculated fields are not recalculated |

## 15. Creation and assembly (brief §21)

| Feature | Status | Evidence / limitation |
|---|---|---|
| PDF from Office files, images, text, HTML | Fully implemented and tested | See §7 (HTML: with limitations) |
| Assemble mixed source files into one PDF with bookmarks | Fully implemented and tested | E: `test_assemble_mixed` |
| Page size (A3, A4, A5, Letter, Legal, image size), orientation, margins, scaling, placement, order, image quality | Fully implemented and tested | E: `test_images_to_pdf`, `test_text_to_pdf` |

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
| Print selected pages (range, current page, odd/even, reverse) | Fully implemented and tested | U26 (pages 1-5, odd only) |
| Print multiple documents in one action | Not implemented | Print each open document (Ctrl+P per tab) |
| Backgrounds and borders | Fully implemented and tested | See §12 |

## 17. Batch processing (brief §23)

| Feature | Status | Evidence / limitation |
|---|---|---|
| Operations: compress, OCR, Word, Excel, PowerPoint, images, text, watermark/numbers/Bates, remove metadata, extract pages, split every N pages, add password, PDF/A, Office to PDF, images to PDF | Implemented with limitations | Each operation runs the same engine job as the single-file tool (each of which is tested). The batch screen itself is tested with compression (U16) |
| Merging in batch | Implemented with limitations | Merging is inherently multi-file and lives in the Merge tool (U17), not the batch queue |
| Add files or whole folders (optionally with sub-folders) | Implemented with limitations | Folder scanning implemented; the test adds files |
| Output folder, naming rules with {name} and {date} | Fully implemented and tested | U16 |
| Never overwrite existing files | Fully implemented and tested | E: `test_no_overwrite_of_existing_output` (numbered name used instead) |
| Failure of one file does not stop the rest; accurate summary | Fully implemented and tested | U16 (2 succeeded, 1 failed), E: `test_batch_continues_after_invalid_file` |
| Retry failed, cancel | Implemented with limitations | Implemented (cancellation stops the engine process tree and removes partial output). Not covered by an automated test |

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
| Originals preserved; new destination by default | Fully implemented and tested | E: `test_sources_never_modified`, `test_refuses_to_replace_source`; U9 |
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
| Navigation and new windows blocked; all non-local requests cancelled; spell-check dictionary downloads disabled in every session | Fully implemented and tested | U28 (no page requests; on Linux, no attempt by any app process through a recording proxy) |
| No open ports or listeners | Fully implemented and tested | Engine talks over stdin/stdout pipes only. Windows acceptance (build 10): 0 listening TCP ports owned by NayanPDF processes, sampled throughout two launches of the installed app |
| No telemetry, updater, accounts, activation | Fully implemented and tested | None exists in the code; see `docs/OFFLINE_VERIFICATION.md` |
| Electron fuses (no ELECTRON_RUN_AS_NODE, no inspector, ASAR integrity) | Fully implemented and tested | Set at packaging time; read back from the installed `NayanPDF.exe` by the Windows acceptance test (build 10): Run-as-Node off, `NODE_OPTIONS` and inspector arguments ignored, ASAR integrity on, ASAR-only loading on |

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
| Ctrl+F search | Fully implemented and tested | U3 |
| Ctrl+P print | Fully implemented and tested | U26 |
| Ctrl+O, Ctrl+S, Ctrl+Shift+S, Ctrl+Z, Ctrl+Y (and Ctrl+Shift+Z), Ctrl+Plus, Ctrl+Minus, Ctrl+0, Ctrl+W, Ctrl+A (select the page's text), Ctrl+C | Implemented with limitations | Implemented and ignored while typing in a text field; not all are exercised by the automated test |

## 23. Installer (brief §29)

| Feature | Status | Evidence / limitation |
|---|---|---|
| `NayanPDF_Setup.exe`, 64-bit, self-contained, nothing downloaded during or after installation | Fully implemented and tested | Windows acceptance test: installed copy passes its offline self-test (all nine engines including LibreOffice, Tesseract and Ghostscript) and the full engine test suite with outbound traffic blocked |
| Installs to the user's profile without requesting administrator rights | Implemented with limitations | Per-user installer that does not request elevation; on CI it ran under an administrator account, so a standard-account install has not been executed |
| Choice of installation folder | Implemented with limitations | Offered on the installer's folder page; the automated test uses the silent default |
| Start Menu shortcut, icon, version information, uninstall registration | Fully implemented and tested | Windows acceptance test |
| Optional desktop shortcut | Implemented with limitations | Check box on the installer's last page; not exercised by the silent test |
| Uninstall removes program, shortcut and registration; keeps user documents and settings | Fully implemented and tested | Windows acceptance test, followed by a successful reinstall |
| Installed executable named `NayanPDF.exe` | Fully implemented and tested | Windows acceptance test |
| Code signing of the installer and executable | Requires paid licence | Needs a purchased code-signing certificate; until then Windows SmartScreen warns on first run |
