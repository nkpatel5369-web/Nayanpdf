; NayanPDF installer customisation (included by electron-builder).

; Finish page: "Run NayanPDF" plus an optional "Create a desktop shortcut" box.
!macro customFinishPage
  Function StartApp
    ${StdUtils.ExecShellAsUser} $0 "$launchLink" "open" ""
  FunctionEnd

  Function NayanCreateDesktopShortcut
    CreateShortCut "$DESKTOP\NayanPDF.lnk" "$INSTDIR\NayanPDF.exe" "" "$INSTDIR\NayanPDF.exe" 0
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
!macroend

!macro customUnInstall
  ; Remove the optional desktop shortcut and NayanPDF's own temporary files.
  ; Settings, saved signatures and every user document are left untouched.
  Delete "$DESKTOP\NayanPDF.lnk"
  RMDir /r "$TEMP\NayanPDF"
!macroend
