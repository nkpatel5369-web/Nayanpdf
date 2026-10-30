; X PDF installer customisation (included by electron-builder).

;; Explorer right-click commands for the current user, per file type (they work
;; whichever program opens that type): "Convert to PDF with X PDF" and "Combine
;; files in X PDF". Shown directly on Windows 10; on Windows 11 under "Show more
;; options". Explorer starts X-PDF.exe once per selected file; the running app
;; collects them into one request. src/main/shell.ts accepts the same types.
!define XPDF_SFA "Software\Classes\SystemFileAssociations"

!macro XPdfVerb EXT KEY LABEL FLAG
  WriteRegStr HKCU "${XPDF_SFA}\${EXT}\shell\${KEY}" "" "${LABEL}"
  WriteRegStr HKCU "${XPDF_SFA}\${EXT}\shell\${KEY}" "Icon" "$INSTDIR\X-PDF.exe,0"
  WriteRegStr HKCU "${XPDF_SFA}\${EXT}\shell\${KEY}" "MultiSelectModel" "Player"
  WriteRegStr HKCU "${XPDF_SFA}\${EXT}\shell\${KEY}\command" "" '"$INSTDIR\X-PDF.exe" ${FLAG} "%1"'
!macroend

!macro XPdfRegisterVerbs
  !insertmacro XPdfVerb ".doc" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".docx" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".odt" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".rtf" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".xls" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".xlsx" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".ods" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".csv" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".ppt" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".pptx" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".odp" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".png" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".jpg" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".jpeg" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".tif" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".tiff" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".bmp" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".gif" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".webp" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".txt" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".htm" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".html" "XPDF.ConvertToPdf" "Convert to PDF with X PDF" "--convert-to-pdf"
  !insertmacro XPdfVerb ".doc" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".docx" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".odt" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".rtf" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".xls" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".xlsx" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".ods" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".csv" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".ppt" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".pptx" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".odp" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".png" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".jpg" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".jpeg" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".tif" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".tiff" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".bmp" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".gif" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".webp" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".txt" "XPDF.Combine" "Combine files in X PDF" "--combine"
  !insertmacro XPdfVerb ".pdf" "XPDF.Combine" "Combine files in X PDF" "--combine"
  ; Tell Explorer that file-type commands changed.
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
!macroend

!macro XPdfRemoveVerbs
  DeleteRegKey HKCU "${XPDF_SFA}\.bmp\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.bmp\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.csv\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.csv\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.doc\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.doc\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.docx\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.docx\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.gif\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.gif\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.htm\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.htm\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.html\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.html\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.jpeg\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.jpeg\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.jpg\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.jpg\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.odp\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.odp\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.ods\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.ods\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.odt\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.odt\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.pdf\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.pdf\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.png\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.png\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.ppt\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.ppt\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.pptx\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.pptx\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.rtf\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.rtf\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.tif\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.tif\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.tiff\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.tiff\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.txt\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.txt\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.webp\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.webp\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.xls\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.xls\shell\XPDF.Combine"
  DeleteRegKey HKCU "${XPDF_SFA}\.xlsx\shell\XPDF.ConvertToPdf"
  DeleteRegKey HKCU "${XPDF_SFA}\.xlsx\shell\XPDF.Combine"
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
!macroend


; Finish page: "Run X PDF" plus an optional "Create a desktop shortcut" box.
!macro customFinishPage
  Function StartApp
    ${StdUtils.ExecShellAsUser} $0 "$launchLink" "open" ""
  FunctionEnd

  Function NayanCreateDesktopShortcut
    CreateShortCut "$DESKTOP\X PDF.lnk" "$INSTDIR\X-PDF.exe" "" "$INSTDIR\X-PDF.exe" 0
  FunctionEnd

  !define MUI_FINISHPAGE_RUN
  !define MUI_FINISHPAGE_RUN_FUNCTION "StartApp"
  !define MUI_FINISHPAGE_SHOWREADME ""
  !define MUI_FINISHPAGE_SHOWREADME_NOTCHECKED
  !define MUI_FINISHPAGE_SHOWREADME_TEXT "Create a desktop shortcut"
  !define MUI_FINISHPAGE_SHOWREADME_FUNCTION NayanCreateDesktopShortcut
  !insertmacro MUI_PAGE_FINISH
!macroend

!macro customInstall
  ; Nothing is downloaded during installation: every component is inside this installer.
  ; Shortcuts left by NayanPDF (this app's name before 1.1) would point to a
  ; program the upgrade has removed.
  Delete "$DESKTOP\NayanPDF.lnk"
  Delete "$SMPROGRAMS\NayanPDF.lnk"
  !insertmacro XPdfRegisterVerbs
!macroend

!macro customUnInstall
  ; Remove the optional desktop shortcut and X PDF's own temporary files.
  ; Settings, saved signatures and every user document are left untouched.
  Delete "$DESKTOP\X PDF.lnk"
  RMDir /r "$TEMP\X-PDF"
  ; Right-click commands (an upgrade writes them again after this).
  !insertmacro XPdfRemoveVerbs
!macroend
