; X PDF installer customisation (included by electron-builder).

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
!macroend

!macro customUnInstall
  ; Remove the optional desktop shortcut and X PDF's own temporary files.
  ; Settings, saved signatures and every user document are left untouched.
  Delete "$DESKTOP\X PDF.lnk"
  RMDir /r "$TEMP\X-PDF"
!macroend
