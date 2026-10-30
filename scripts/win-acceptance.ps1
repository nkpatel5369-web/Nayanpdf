<#
  N PDF Windows acceptance test.

  Runs on a Windows machine (used by CI on windows-latest):
    0. For each -PreviousInstaller (X PDF 1.1, NayanPDF 1.0: earlier names of this
       app): install it, then N PDF over it; one installation left, old program,
       shortcuts and right-click commands gone, desktop shortcut and settings
       carried over
    1. Silent per-user install of N-PDF_Setup.exe
    2. Verifies executable, bundled engines, Start Menu shortcut, uninstall registration
    3. Blocks outbound network access for every N PDF executable (Windows Firewall)
    4. Runs the engine self-test and the full engine test suite with the INSTALLED runtime
    5. Launches the installed N-PDF.exe (home screen, then with a PDF), captures
       screenshots, and records any network connections made by its processes
    6. Silent uninstall: verifies removal while user documents and settings survive
    7. Reinstall and re-verify

  Writes <ReportDir>\report.json and report.md. Exit code 1 if a required check fails.
#>
param(
  [Parameter(Mandatory = $true)][string]$Installer,
  [string]$ReportDir = "acceptance",
  [string]$RepoRoot = (Resolve-Path "$PSScriptRoot\..").Path,
  [string]$TestDeps = "",
  # Installers of earlier releases (X PDF 1.1, NayanPDF 1.0): the upgrade from
  # each is tested first.
  [string[]]$PreviousInstaller = @()
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$ReportDir = (Resolve-Path $ReportDir).Path
$Installer = (Resolve-Path $Installer).Path
$results = New-Object System.Collections.ArrayList
$InstallDir = Join-Path $env:LOCALAPPDATA "Programs\N-PDF"
$StartMenu = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\N PDF.lnk"
$AppNames = '^(N PDF|X PDF|NayanPDF)( |$)'

function Record([string]$name, [bool]$ok, [string]$detail = "", [bool]$required = $true) {
  $null = $results.Add([pscustomobject]@{ check = $name; ok = $ok; required = $required; detail = $detail })
  $tag = if ($ok) { "PASS" } elseif ($required) { "FAIL" } else { "WARN" }
  Write-Host "[$tag] $name $detail"
}

function Install-App([string]$label) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $p = Start-Process -FilePath $Installer -ArgumentList "/S" -Wait -PassThru
  $sw.Stop()
  Record "${label}: installer exit code" ($p.ExitCode -eq 0) "exit=$($p.ExitCode) in $([int]$sw.Elapsed.TotalSeconds)s"
  Start-Sleep -Seconds 3
}

function Get-UninstallEntry {
  Get-ChildItem "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall" -ErrorAction SilentlyContinue |
    ForEach-Object { Get-ItemProperty $_.PSPath } | Where-Object { $_.DisplayName -match $AppNames } | Select-Object -First 1
}

# The folder the installer chose, from its uninstall registration (an upgrade may
# keep the previous folder).
function Sync-InstallDir {
  $u = Get-UninstallEntry
  if ($u -and $u.UninstallString -match '^"?([^"]+\\)[^"\\]+\.exe') { $script:InstallDir = $Matches[1].TrimEnd('\') }
}

# The uninstaller exactly as Windows Settings runs it: the file named in the
# registration (electron-builder names it after the executable: "Uninstall N-PDF.exe").
function Get-Uninstaller {
  $u = Get-UninstallEntry
  if ($u -and $u.UninstallString -match '^"([^"]+)"') { return $Matches[1] }
  return $null
}

function Invoke-Uninstall {
  $exe = Get-Uninstaller
  if (-not $exe -or -not (Test-Path $exe)) { return $null }
  $dir = Split-Path $exe
  $p = Start-Process -FilePath $exe -ArgumentList @("/S", "_?=$dir") -Wait -PassThru
  Start-Sleep -Seconds 3
  Remove-Item -Recurse -Force $dir -ErrorAction SilentlyContinue   # NSIS leaves the uninstaller itself when run with _?=
  return $p
}

# Earlier names of this app: its program, settings folder, shortcut name and
# right-click command prefix.
$Legacy = [ordered]@{
  "X PDF"    = @{ Exe = "X-PDF.exe"; Data = "X PDF"; Link = "X PDF.lnk"; Verbs = "XPDF" }
  "NayanPDF" = @{ Exe = "NayanPDF.exe"; Data = "NayanPDF"; Link = "NayanPDF.lnk"; Verbs = "" }
}

function Test-Upgrade([string]$prev) {
  $p = Start-Process -FilePath $prev -ArgumentList "/S" -Wait -PassThru
  Start-Sleep -Seconds 3
  $oldUn = Get-Uninstaller
  $oldDir = if ($oldUn) { Split-Path $oldUn } else { "" }
  $kind = @($Legacy.Keys | Where-Object { $oldDir -and (Test-Path (Join-Path $oldDir $Legacy[$_].Exe)) }) | Select-Object -First 1
  Record "Upgrade from $(if ($kind) { $kind } else { '?' }): previous version installed first" ($p.ExitCode -eq 0 -and $kind) "$(Split-Path -Leaf $prev) -> $oldDir"
  if (-not $kind) { $null = Invoke-Uninstall; return }
  $L = $Legacy[$kind]
  $label = "Upgrade from $kind"
  $oldExe = Join-Path $oldDir $L.Exe
  $hadVerbs = [bool]($L.Verbs -and (Get-VerbCommand ".docx" "$($L.Verbs).ConvertToPdf"))
  # Settings and a desktop shortcut that the upgrade must carry over.
  $legacyData = Join-Path $env:APPDATA $L.Data
  New-Item -ItemType Directory -Force -Path $legacyData | Out-Null
  [IO.File]::WriteAllText((Join-Path $legacyData "settings.json"), '{"reviewerName":"Upgrade Check"}')
  $desktop = [Environment]::GetFolderPath("Desktop")
  $wsh = New-Object -ComObject WScript.Shell
  $lnk = $wsh.CreateShortcut((Join-Path $desktop $L.Link))
  $lnk.TargetPath = $oldExe
  $lnk.Save()
  Install-App $label
  $entries = @(Get-ChildItem "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall" -ErrorAction SilentlyContinue |
    ForEach-Object { Get-ItemProperty $_.PSPath } | Where-Object { $_.DisplayName -match $AppNames })
  Record "${label}: one installation registered" ($entries.Count -eq 1) (($entries | ForEach-Object { "$($_.DisplayName) $($_.DisplayVersion)" }) -join ", ")
  Sync-InstallDir
  $newExe = Join-Path $InstallDir "N-PDF.exe"
  Record "${label}: N-PDF.exe installed" (Test-Path $newExe) $InstallDir
  Record "${label}: old $($L.Exe) removed" (-not (Test-Path $oldExe))
  Record "${label}: no $kind Start Menu shortcut left" (-not (Test-Path (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\$($L.Link)")))
  $newLink = Join-Path $desktop "N PDF.lnk"
  $target = if (Test-Path $newLink) { $wsh.CreateShortcut($newLink).TargetPath } else { "" }
  Record "${label}: desktop shortcut kept, renamed N PDF and pointing at N-PDF.exe" ((-not (Test-Path (Join-Path $desktop $L.Link))) -and $target -eq $newExe) "target=$target"
  if ($L.Verbs) {
    $left = (Get-VerbCommand ".docx" "$($L.Verbs).ConvertToPdf") -or (Get-VerbCommand ".pdf" "$($L.Verbs).Combine")
    Record "${label}: its right-click commands removed" (-not $left) "registered before the upgrade: $hadVerbs"
  }
  $un = Get-Uninstaller
  Record "${label}: registered uninstaller present" ($un -and (Test-Path $un)) "$un"
  Test-ShellVerbs $label
  Run-App "upgrade-$($L.Exe -replace '\.exe$', '')" @()
  $migrated = Join-Path $env:APPDATA "N PDF\settings.json"
  Record "${label}: settings carried over" ((Test-Path $migrated) -and ((Get-Content $migrated -Raw) -match "Upgrade Check")) $migrated
  # Back to a clean machine for the next test.
  $p = Invoke-Uninstall
  Record "${label}: uninstall after the upgrade" ($p -and $p.ExitCode -eq 0 -and $null -eq (Get-UninstallEntry)) "$(if ($p) { "exit=$($p.ExitCode)" } else { 'uninstaller not found' })"
  Record "${label}: uninstall removes the desktop shortcut" (-not (Test-Path $newLink))
  Remove-Item -Recurse -Force $oldDir, $legacyData, (Join-Path $env:APPDATA "N PDF") -ErrorAction SilentlyContinue
  Remove-Item -Force (Join-Path $desktop $L.Link), $newLink -ErrorAction SilentlyContinue
  $script:InstallDir = Join-Path $env:LOCALAPPDATA "Programs\N-PDF"
}

# Explorer right-click commands (registered per user by the installer).
$Sfa = "HKCU:\Software\Classes\SystemFileAssociations"
function Get-VerbCommand([string]$ext, [string]$key) {
  $k = Join-Path $Sfa "$ext\shell\$key\command"
  if (Test-Path -LiteralPath $k) { return (Get-ItemProperty -LiteralPath $k).'(default)' }
  return $null
}

function Test-ShellVerbs([string]$label) {
  $exe = Join-Path $InstallDir "N-PDF.exe"
  $conv = Get-VerbCommand ".docx" "NPDF.ConvertToPdf"
  $comb = Get-VerbCommand ".pdf" "NPDF.Combine"
  Record "${label}: right-click 'Convert to PDF with N PDF' registered" ($conv -and $conv.Contains($exe) -and $conv.Contains("--convert-to-pdf")) "$conv"
  Record "${label}: right-click 'Combine files in N PDF' registered" ($comb -and $comb.Contains($exe) -and $comb.Contains("--combine")) "$comb"
  $types = @(".doc", ".xlsx", ".pptx", ".jpg", ".png", ".txt", ".html") | Where-Object { -not (Get-VerbCommand $_ "NPDF.ConvertToPdf") }
  Record "${label}: Convert to PDF registered for Office, image, text and HTML files" ($types.Count -eq 0) "$(if ($types.Count) { 'missing: ' + ($types -join ', ') } else { '.doc .xlsx .pptx .jpg .png .txt .html checked' })"
}

# Runs the registered command for a text file exactly as Explorer would and waits
# for the installed app to write the PDF next to it.
function Test-ShellConvert([string]$dir) {
  $txt = Join-Path $dir "right-click note.txt"
  [IO.File]::WriteAllText($txt, "Right-click conversion check`r`nLine two")
  $pdf = Join-Path $dir "right-click note.pdf"
  Remove-Item $pdf -ErrorAction SilentlyContinue
  $cmd = Get-VerbCommand ".txt" "NPDF.ConvertToPdf"
  if (-not $cmd -or $cmd -notmatch '^"([^"]+)"\s+(.*)$') { Record "Right-click Convert to PDF runs the installed app" $false "command not registered"; return }
  $exe = $Matches[1]
  $arg = $Matches[2].Replace("%1", $txt)
  $p = Start-Process -FilePath $exe -ArgumentList $arg -PassThru
  $deadline = (Get-Date).AddSeconds(120)
  while (-not (Test-Path $pdf) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
  Start-Sleep -Seconds 2
  $pages = if (Test-Path $pdf) { & (Join-Path $InstallDir "resources\python\python.exe") -I -c "import pymupdf,sys; d=pymupdf.open(sys.argv[1]); print(d.page_count, 'Right-click conversion check' in d[0].get_text())" $pdf } else { "" }
  Record "Right-click Convert to PDF runs the installed app and writes the PDF next to the file" ((Test-Path $pdf) -and ("$pages" -match "True")) "$pdf $pages"
  Get-Process -Name "N-PDF" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
  Get-Process -Name "soffice", "soffice.bin" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
}

function Verify-Install([string]$label) {
  Sync-InstallDir
  $exe = Join-Path $InstallDir "N-PDF.exe"
  Record "${label}: N-PDF.exe installed" (Test-Path $exe) $exe
  $res = Join-Path $InstallDir "resources"
  foreach ($rel in @("python\python.exe", "engine\run_engine.py", "tesseract\tesseract.exe", "tesseract\tessdata\eng.traineddata",
                     "libreoffice\program\soffice.exe", "ghostscript\bin\gswin64c.exe", "app.asar", "MANIFEST.json")) {
    Record "${label}: bundled $rel" (Test-Path (Join-Path $res $rel))
  }
  Record "${label}: Start Menu shortcut" (Test-Path $StartMenu) $StartMenu
  Test-ShellVerbs $label
  $un = Get-Uninstaller
  Record "${label}: registered uninstaller present" ($un -and (Test-Path $un)) "$un"
  $u = Get-UninstallEntry
  Record "${label}: uninstall registration" ($null -ne $u) ($(if ($u) { "$($u.DisplayName) $($u.DisplayVersion)" } else { "missing" }))
  if (Test-Path $exe) {
    $vi = (Get-Item $exe).VersionInfo
    Record "${label}: executable version info" ($vi.ProductName -eq "N PDF") "ProductName=$($vi.ProductName) FileVersion=$($vi.FileVersion)" $false
    Check-Fuses $label $exe
  }
}

# Electron fuses are switches compiled into N-PDF.exe at packaging time.
# Read them back from the installed file rather than trusting the build config.
function Check-Fuses([string]$label, [string]$exe) {
  $tool = Join-Path $RepoRoot "node_modules\@electron\fuses\dist\bin.js"
  if (-not (Test-Path $tool)) { Record "${label}: Electron fuses read back" $false "fuse reader not available" $false; return }
  try { $out = (& node $tool read --app $exe 2>&1 | Out-String) } catch { $out = "$_" }
  $want = [ordered]@{ RunAsNode = "Disabled"; EnableNodeOptionsEnvironmentVariable = "Disabled"; EnableNodeCliInspectArguments = "Disabled";
                      EnableEmbeddedAsarIntegrityValidation = "Enabled"; OnlyLoadAppFromAsar = "Enabled"; GrantFileProtocolExtraPrivileges = "Disabled" }
  $bad = @($want.Keys | Where-Object { $out -notmatch "$_ is $($want[$_])" })
  $detail = if ($bad.Count) { "not as expected: $($bad -join ', ')" } else { ($want.Keys | ForEach-Object { "$_ $($want[$_])" }) -join "; " }
  Record "${label}: Electron fuses read back" ($bad.Count -eq 0) $detail $false
}

function Block-Network {
  $exes = @()
  $exes += Get-ChildItem -Path $InstallDir -Recurse -Include *.exe -ErrorAction SilentlyContinue | Where-Object {
    $_.Name -match '^(N-PDF|python|pythonw|tesseract|soffice|soffice\.bin|gswin64c)\.exe$' -or $_.Name -eq 'soffice.bin'
  } | Select-Object -ExpandProperty FullName
  $bin = Join-Path $InstallDir "resources\libreoffice\program\soffice.bin"
  if (Test-Path $bin) { $exes += $bin }
  $i = 0
  foreach ($e in ($exes | Sort-Object -Unique)) {
    $i++
    New-NetFirewallRule -DisplayName "N PDF offline test $i" -Group "NPDFOfflineTest" -Direction Outbound -Action Block -Program $e | Out-Null
  }
  Record "Outbound network blocked for N PDF executables" ($i -gt 0) "$i program rules"
}

function Unblock-Network { Get-NetFirewallRule -Group "NPDFOfflineTest" -ErrorAction SilentlyContinue | Remove-NetFirewallRule }

function Engine-Env {
  $res = Join-Path $InstallDir "resources"
  $env:NAYANPDF_TESSERACT = Join-Path $res "tesseract\tesseract.exe"
  $env:NAYANPDF_TESSDATA = Join-Path $res "tesseract\tessdata"
  $env:NAYANPDF_SOFFICE = Join-Path $res "libreoffice\program\soffice.exe"
  $env:NAYANPDF_GS = Join-Path $res "ghostscript\bin\gswin64c.exe"
  $env:NAYANPDF_GS_ICC = Join-Path $res "ghostscript\iccprofiles\srgb.icc"
  $env:NAYANPDF_RESOURCES = Join-Path $res "assets"
  return $res
}

function Run-SelfTest([string]$label) {
  $res = Engine-Env
  $py = Join-Path $res "python\python.exe"
  $req = '{"id":1,"method":"selftest.run","params":{}}' + "`n" + '"__exit__"' + "`n"
  $out = $req | & $py -I -X utf8 (Join-Path $res "engine\run_engine.py") 2> (Join-Path $ReportDir "$label-selftest-stderr.txt")
  $line = $out | Where-Object { $_ -match '"id": 1' } | Select-Object -First 1
  $line | Out-File -Encoding utf8 (Join-Path $ReportDir "$label-selftest.json")
  if (-not $line) { Record "${label}: engine self-test (installed runtime, offline)" $false "no response"; return }
  $j = $line | ConvertFrom-Json
  foreach ($r in $j.result.results) { Record "${label}: self-test - $($r.check)" ([bool]$r.ok) $r.detail }
}

function Run-EngineSuite {
  $res = Engine-Env
  $py = Join-Path $res "python\python.exe"
  $env:NAYANPDF_ENGINE_DIR = Join-Path $res "engine"
  $env:PYTHONPATH = $TestDeps
  $log = Join-Path $ReportDir "engine-tests-installed.txt"
  & $py -X utf8 -m pytest (Join-Path $RepoRoot "engine\tests") -q -p no:cacheprovider --junitxml (Join-Path $ReportDir "engine-tests-installed.xml") *> $log
  $code = $LASTEXITCODE
  $summary = (Get-Content $log | Select-String -Pattern "passed|failed" | Select-Object -Last 1).Line
  Record "Engine test suite with the installed runtime (offline)" ($code -eq 0) $summary
  Remove-Item Env:\PYTHONPATH -ErrorAction SilentlyContinue
  Remove-Item Env:\NAYANPDF_ENGINE_DIR -ErrorAction SilentlyContinue
}

function Run-App([string]$label, [string[]]$argsList) {
  $out = Join-Path $ReportDir "smoke-$label"
  New-Item -ItemType Directory -Force -Path $out | Out-Null
  $env:NAYANPDF_SMOKE = "1"
  $env:NAYANPDF_SMOKE_OUT = $out
  $exe = Join-Path $InstallDir "N-PDF.exe"
  $p = Start-Process -FilePath $exe -ArgumentList $argsList -PassThru
  $conns = @()
  $listen = @()
  $deadline = (Get-Date).AddSeconds(150)
  while (-not $p.HasExited -and (Get-Date) -lt $deadline) {
    $ids = @($p.Id) + @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.ParentProcessId -eq $p.Id } | Select-Object -ExpandProperty ProcessId)
    $kids = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $ids -contains $_.ParentProcessId } | Select-Object -ExpandProperty ProcessId
    $ids += $kids
    $c = Get-NetTCPConnection -ErrorAction SilentlyContinue | Where-Object { $ids -contains $_.OwningProcess -and $_.RemoteAddress -notmatch '^(127\.|::1|0\.0\.0\.0|::$)' -and $_.State -ne 'Listen' }
    if ($c) { $conns += $c | Select-Object OwningProcess, RemoteAddress, RemotePort, State }
    $l = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $ids -contains $_.OwningProcess }
    if ($l) { $listen += $l | Select-Object OwningProcess, LocalAddress, LocalPort }
    Start-Sleep -Milliseconds 400
  }
  if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force; Record "${label}: app exited by itself" $false "killed after timeout" }
  Remove-Item Env:\NAYANPDF_SMOKE; Remove-Item Env:\NAYANPDF_SMOKE_OUT
  $smoke = Join-Path $out "smoke.json"
  if (Test-Path $smoke) {
    $s = Get-Content $smoke -Raw | ConvertFrom-Json
    Record "${label}: UI launched and rendered" ([bool]$s.ok) "title=$($s.title)"
    if ($s.engine) { Record "${label}: engine reached from packaged app" ([bool]$s.engine.ok) "$(@($s.engine.results).Count) checks" }
    if ($s.document) { Record "${label}: PDF opened and displayed" ([bool]$s.document.ok) $s.document.detail }
    if ($s.edit) { Record "${label}: document edited (temporary revisions)" ([bool]$s.edit.ok) $s.edit.detail }
  } else {
    Record "${label}: UI launched and rendered" $false "no smoke.json"
  }
  $uniq = $conns | Sort-Object RemoteAddress, RemotePort -Unique
  ($uniq | ConvertTo-Json) | Out-File -Encoding utf8 (Join-Path $out "network.json")
  Record "${label}: no outbound network connections" (@($uniq).Count -eq 0) "$(@($uniq).Count) remote endpoint(s) observed"
  $ports = @($listen | Sort-Object LocalAddress, LocalPort -Unique)
  ($ports | ConvertTo-Json) | Out-File -Encoding utf8 (Join-Path $out "listening.json")
  Record "${label}: no listening TCP ports" ($ports.Count -eq 0) "$($ports.Count) listening port(s) observed$(if ($ports.Count) { ': ' + (($ports | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)" }) -join ', ') })" $false
}

# ------------------------------------------------------------------ run
try {
  $prevs = @($PreviousInstaller | Where-Object { $_ })
  foreach ($prev in $prevs) { Test-Upgrade $prev }
  if ($prevs.Count -lt 2) { Record "Upgrade from X PDF 1.1 and NayanPDF 1.0 tested" $false "$($prevs.Count) previous installer(s) available" $false }
  Install-App "Install"
  Verify-Install "Install"

  # A user document and settings that must survive uninstallation.
  $docs = Join-Path ([Environment]::GetFolderPath("MyDocuments")) "N-PDF-acceptance"
  New-Item -ItemType Directory -Force -Path $docs | Out-Null
  $userDoc = Join-Path $docs "user-document.pdf"
  $res = Engine-Env
  & (Join-Path $res "python\python.exe") -I -c "import pymupdf,sys; d=pymupdf.open(); p=d.new_page(); p.insert_text((72,100),'N PDF acceptance document',fontsize=18); [d.new_page().insert_text((72,100),'Page %d'%(i+2)) for i in range(4)]; d.save(sys.argv[1])" $userDoc
  Record "Test document created" (Test-Path $userDoc) $userDoc

  Block-Network
  Run-SelfTest "offline"
  if ($TestDeps) { Run-EngineSuite }
  Run-App "home" @()
  Run-App "document" @("`"$userDoc`"")
  Test-ShellConvert $docs

  $settingsDir = Join-Path $env:APPDATA "N PDF"
  Record "Settings folder created by the app" (Test-Path $settingsDir) $settingsDir $false

  # Uninstall
  $p = Invoke-Uninstall
  Record "Uninstall: exit code" ($p -and $p.ExitCode -eq 0) "$(if ($p) { "exit=$($p.ExitCode)" } else { 'uninstaller not found' })"
  Record "Uninstall: program files removed" (-not (Test-Path (Join-Path $InstallDir "N-PDF.exe")))
  Record "Uninstall: Start Menu shortcut removed" (-not (Test-Path $StartMenu))
  Record "Uninstall: registration removed" ($null -eq (Get-UninstallEntry))
  Record "Uninstall: right-click commands removed" ($null -eq (Get-VerbCommand ".docx" "NPDF.ConvertToPdf") -and $null -eq (Get-VerbCommand ".pdf" "NPDF.Combine"))
  Record "Uninstall: user document preserved" (Test-Path $userDoc) $userDoc
  Record "Uninstall: user settings preserved" (Test-Path $settingsDir) $settingsDir $false

  # Reinstall
  Unblock-Network
  Install-App "Reinstall"
  Verify-Install "Reinstall"
  Block-Network
  Run-SelfTest "reinstall"
} finally {
  Unblock-Network
  $json = $results | ConvertTo-Json -Depth 4
  $json | Out-File -Encoding utf8 (Join-Path $ReportDir "report.json")
  $md = @("# N PDF Windows acceptance test", "", "Host: $([Environment]::OSVersion.VersionString) $(if ([Environment]::Is64BitOperatingSystem) {'x64'})", "Date: $(Get-Date -Format o)", "", "| Result | Check | Detail |", "|---|---|---|")
  foreach ($r in $results) { $md += "| $(if ($r.ok) {'PASS'} elseif ($r.required) {'FAIL'} else {'WARN'}) | $($r.check) | $($r.detail -replace '\|','/') |" }
  $md -join "`n" | Out-File -Encoding utf8 (Join-Path $ReportDir "report.md")
}

$failed = @($results | Where-Object { -not $_.ok -and $_.required })
Write-Host "Acceptance: $($results.Count) checks, $($failed.Count) required failure(s)"
if ($failed.Count -gt 0) { exit 1 }
exit 0
