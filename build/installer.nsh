; N PDF installer customisation (included by electron-builder).

;; Explorer right-click commands for the current user, per file type (they work
;; whichever program opens that type): "Convert to PDF with N PDF" and "Combine
;; files in N PDF". Shown directly on Windows 10; on Windows 11 under "Show more
;; options". Explorer starts N-PDF.exe once per selected file; the running app
;; collects them into one request. src/main/shell.ts accepts the same types.
!define NPDF_SFA "Software\Classes\SystemFileAssociations"

!macro NPdfVerb EXT KEY LABEL FLAG
  WriteRegStr HKCU "${NPDF_SFA}\${EXT}\shell\${KEY}" "" "${LABEL}"
  WriteRegStr HKCU "${NPDF_SFA}\${EXT}\shell\${KEY}" "Icon" "$INSTDIR\N-PDF.exe,0"
  WriteRegStr HKCU "${NPDF_SFA}\${EXT}\shell\${KEY}" "MultiSelectModel" "Player"
  WriteRegStr HKCU "${NPDF_SFA}\${EXT}\shell\${KEY}\command" "" '"$INSTDIR\N-PDF.exe" ${FLAG} "%1"'
!macroend

!macro NPdfRegisterVerbs
  !insertmacro NPdfVerb ".doc" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".docx" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".odt" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".rtf" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".xls" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".xlsx" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".ods" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".csv" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".ppt" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".pptx" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".odp" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".png" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".jpg" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".jpeg" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".tif" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".tiff" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".bmp" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".gif" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".webp" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".txt" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".htm" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".html" "NPDF.ConvertToPdf" "Convert to PDF with N PDF" "--convert-to-pdf"
  !insertmacro NPdfVerb ".doc" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".docx" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".odt" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".rtf" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".xls" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".xlsx" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".ods" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".csv" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".ppt" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".pptx" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".odp" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".png" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".jpg" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".jpeg" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".tif" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".tiff" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".bmp" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".gif" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".webp" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".txt" "NPDF.Combine" "Combine files in N PDF" "--combine"
  !insertmacro NPdfVerb ".pdf" "NPDF.Combine" "Combine files in N PDF" "--combine"
  ; Tell Explorer that file-type commands changed.
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
!macroend

;; PREFIX: NPDF for this version's commands, XPDF for those of X PDF 1.1.
!macro NPdfRemoveVerbs PREFIX
  DeleteRegKey HKCU "${NPDF_SFA}\.bmp\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.bmp\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.csv\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.csv\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.doc\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.doc\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.docx\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.docx\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.gif\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.gif\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.htm\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.htm\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.html\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.html\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.jpeg\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.jpeg\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.jpg\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.jpg\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.odp\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.odp\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.ods\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.ods\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.odt\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.odt\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.pdf\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.pdf\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.png\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.png\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.ppt\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.ppt\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.pptx\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.pptx\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.rtf\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.rtf\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.tif\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.tif\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.tiff\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.tiff\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.txt\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.txt\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.webp\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.webp\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.xls\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.xls\shell\${PREFIX}.Combine"
  DeleteRegKey HKCU "${NPDF_SFA}\.xlsx\shell\${PREFIX}.ConvertToPdf"
  DeleteRegKey HKCU "${NPDF_SFA}\.xlsx\shell\${PREFIX}.Combine"
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
!macroend


; Finish page: "Run N PDF" plus an optional "Create a desktop shortcut" box.
!macro customFinishPage
  Function StartApp
    ${StdUtils.ExecShellAsUser} $0 "$launchLink" "open" ""
  FunctionEnd

  Function NayanCreateDesktopShortcut
    CreateShortCut "$DESKTOP\N PDF.lnk" "$INSTDIR\N-PDF.exe" "" "$INSTDIR\N-PDF.exe" 0
  FunctionEnd

  !define MUI_FINISHPAGE_RUN
  !define MUI_FINISHPAGE_RUN_FUNCTION "StartApp"
  !define MUI_FINISHPAGE_SHOWREADME ""
  !define MUI_FINISHPAGE_SHOWREADME_NOTCHECKED
  !define MUI_FINISHPAGE_SHOWREADME_TEXT "Create a desktop shortcut"
  !define MUI_FINISHPAGE_SHOWREADME_FUNCTION NayanCreateDesktopShortcut
  !insertmacro MUI_PAGE_FINISH
!macroend

!macro customInit
  ; An upgrade first runs the previous version's uninstaller, which deletes the
  ; desktop shortcut. Note now whether there is one (this app was NayanPDF up to
  ; 1.0 and X PDF in 1.1) so that customInstall can put it back.
  Var /GLOBAL NPdfHadDesktopLink
  StrCpy $NPdfHadDesktopLink "0"
  ${If} ${FileExists} "$DESKTOP\N PDF.lnk"
  ${OrIf} ${FileExists} "$DESKTOP\X PDF.lnk"
  ${OrIf} ${FileExists} "$DESKTOP\NayanPDF.lnk"
    StrCpy $NPdfHadDesktopLink "1"
  ${EndIf}
!macroend

!macro customInstall
  ; Nothing is downloaded during installation: every component is inside this installer.
  ; Shortcuts and right-click commands left by earlier names of this app would
  ; point to a program the upgrade has removed.
  Delete "$DESKTOP\NayanPDF.lnk"
  Delete "$SMPROGRAMS\NayanPDF.lnk"
  Delete "$DESKTOP\X PDF.lnk"
  Delete "$SMPROGRAMS\X PDF.lnk"
  !insertmacro NPdfRemoveVerbs "XPDF"
  ${If} $NPdfHadDesktopLink == "1"
    CreateShortCut "$DESKTOP\N PDF.lnk" "$INSTDIR\N-PDF.exe" "" "$INSTDIR\N-PDF.exe" 0
  ${EndIf}
  !insertmacro NPdfRegisterVerbs
!macroend

!macro customUnInstall
  ; Remove the optional desktop shortcut and N PDF's own temporary files.
  ; Settings, saved signatures and every user document are left untouched.
  Delete "$DESKTOP\N PDF.lnk"
  RMDir /r "$TEMP\N-PDF"
  ; Right-click commands (an upgrade writes them again after this).
  !insertmacro NPdfRemoveVerbs "NPDF"
!macroend
