<#
  X PDF Windows acceptance test.

  Runs on a Windows machine (used by CI on windows-latest):
    0. With -PreviousInstaller: install NayanPDF 1.0, then X PDF over it; one
       installation left, old program and shortcuts gone, settings carried over
    1. Silent per-user install of X-PDF_Setup.exe
    2. Verifies executable, bundled engines, Start Menu shortcut, uninstall registration
    3. Blocks outbound network access for every X PDF executable (Windows Firewall)
    4. Runs the engine self-test and the full engine test suite with the INSTALLED runtime
    5. Launches the installed X-PDF.exe (home screen, then with a PDF), captures
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
  # An installer of the previous release (NayanPDF, the name before 1.1): when
  # given, the upgrade from it is tested first.
  [string]$PreviousInstaller = ""
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$ReportDir = (Resolve-Path $ReportDir).Path
$Installer = (Resolve-Path $Installer).Path
$results = New-Object System.Collections.ArrayList
$InstallDir = Join-Path $env:LOCALAPPDATA "Programs\X-PDF"
$StartMenu = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\X PDF.lnk"

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
    ForEach-Object { Get-ItemProperty $_.PSPath } | Where-Object { $_.DisplayName -like "X PDF*" } | Select-Object -First 1
}

# The folder the installer chose, from its uninstall registration (an upgrade may
# keep the previous folder).
function Sync-InstallDir {
  $u = Get-UninstallEntry
  if ($u -and $u.UninstallString -match '^"?([^"]+\\)[^"\\]+\.exe') { $script:InstallDir = $Matches[1].TrimEnd('\') }
}

# The uninstaller exactly as Windows Settings runs it: the file named in the
# registration (electron-builder names it after the executable: "Uninstall X-PDF.exe").
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

function Test-Upgrade {
  $legacyDir = Join-Path $env:LOCALAPPDATA "Programs\NayanPDF"
  $legacyData = Join-Path $env:APPDATA "NayanPDF"
  $p = Start-Process -FilePath $PreviousInstaller -ArgumentList "/S" -Wait -PassThru
  Start-Sleep -Seconds 3
  Record "Upgrade: NayanPDF 1.0 installed first" ($p.ExitCode -eq 0 -and (Test-Path (Join-Path $legacyDir "NayanPDF.exe"))) $legacyDir
  New-Item -ItemType Directory -Force -Path $legacyData | Out-Null
  [IO.File]::WriteAllText((Join-Path $legacyData "settings.json"), '{"reviewerName":"Upgrade Check"}')
  Install-App "Upgrade"
  $entries = @(Get-ChildItem "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall" -ErrorAction SilentlyContinue |
    ForEach-Object { Get-ItemProperty $_.PSPath } | Where-Object { $_.DisplayName -match '^(NayanPDF|X PDF)' })
  Record "Upgrade: one installation registered" ($entries.Count -eq 1) (($entries | ForEach-Object { "$($_.DisplayName) $($_.DisplayVersion)" }) -join ", ")
  Sync-InstallDir
  Record "Upgrade: X-PDF.exe installed" (Test-Path (Join-Path $InstallDir "X-PDF.exe")) $InstallDir
  Record "Upgrade: old NayanPDF.exe removed" (-not (Test-Path (Join-Path $legacyDir "NayanPDF.exe")))
  Record "Upgrade: no NayanPDF Start Menu shortcut left" (-not (Test-Path (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\NayanPDF.lnk")))
  $un = Get-Uninstaller
  Record "Upgrade: registered uninstaller present" ($un -and (Test-Path $un)) "$un"
  Test-ShellVerbs "Upgrade"
  Run-App "upgrade" @()
  $migrated = Join-Path $env:APPDATA "X PDF\settings.json"
  Record "Upgrade: settings carried over from NayanPDF" ((Test-Path $migrated) -and ((Get-Content $migrated -Raw) -match "Upgrade Check")) $migrated
  # Back to a clean machine for the fresh-install checks.
  $p = Invoke-Uninstall
  Record "Upgrade: uninstall after the upgrade" ($p -and $p.ExitCode -eq 0 -and $null -eq (Get-UninstallEntry)) "$(if ($p) { "exit=$($p.ExitCode)" } else { 'uninstaller not found' })"
  Remove-Item -Recurse -Force $legacyDir, $legacyData, (Join-Path $env:APPDATA "X PDF") -ErrorAction SilentlyContinue
  $script:InstallDir = Join-Path $env:LOCALAPPDATA "Programs\X-PDF"
}

# Explorer right-click commands (registered per user by the installer).
$Sfa = "HKCU:\Software\Classes\SystemFileAssociations"
function Get-VerbCommand([string]$ext, [string]$key) {
  $k = Join-Path $Sfa "$ext\shell\$key\command"
  if (Test-Path -LiteralPath $k) { return (Get-ItemProperty -LiteralPath $k).'(default)' }
  return $null
}

function Test-ShellVerbs([string]$label) {
  $exe = Join-Path $InstallDir "X-PDF.exe"
  $conv = Get-VerbCommand ".docx" "XPDF.ConvertToPdf"
  $comb = Get-VerbCommand ".pdf" "XPDF.Combine"
  Record "${label}: right-click 'Convert to PDF with X PDF' registered" ($conv -and $conv.Contains($exe) -and $conv.Contains("--convert-to-pdf")) "$conv"
  Record "${label}: right-click 'Combine files in X PDF' registered" ($comb -and $comb.Contains($exe) -and $comb.Contains("--combine")) "$comb"
  $types = @(".doc", ".xlsx", ".pptx", ".jpg", ".png", ".txt", ".html") | Where-Object { -not (Get-VerbCommand $_ "XPDF.ConvertToPdf") }
  Record "${label}: Convert to PDF registered for Office, image, text and HTML files" ($types.Count -eq 0) "$(if ($types.Count) { 'missing: ' + ($types -join ', ') } else { '.doc .xlsx .pptx .jpg .png .txt .html checked' })"
}

# Runs the registered command for a text file exactly as Explorer would and waits
# for the installed app to write the PDF next to it.
function Test-ShellConvert([string]$dir) {
  $txt = Join-Path $dir "right-click note.txt"
  [IO.File]::WriteAllText($txt, "Right-click conversion check`r`nLine two")
  $pdf = Join-Path $dir "right-click note.pdf"
  Remove-Item $pdf -ErrorAction SilentlyContinue
  $cmd = Get-VerbCommand ".txt" "XPDF.ConvertToPdf"
  if (-not $cmd -or $cmd -notmatch '^"([^"]+)"\s+(.*)$') { Record "Right-click Convert to PDF runs the installed app" $false "command not registered"; return }
  $exe = $Matches[1]
  $arg = $Matches[2].Replace("%1", $txt)
  $p = Start-Process -FilePath $exe -ArgumentList $arg -PassThru
  $deadline = (Get-Date).AddSeconds(120)
  while (-not (Test-Path $pdf) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
  Start-Sleep -Seconds 2
  $pages = if (Test-Path $pdf) { & (Join-Path $InstallDir "resources\python\python.exe") -I -c "import pymupdf,sys; d=pymupdf.open(sys.argv[1]); print(d.page_count, 'Right-click conversion check' in d[0].get_text())" $pdf } else { "" }
  Record "Right-click Convert to PDF runs the installed app and writes the PDF next to the file" ((Test-Path $pdf) -and ("$pages" -match "True")) "$pdf $pages"
  Get-Process -Name "X-PDF" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
  Get-Process -Name "soffice", "soffice.bin" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
}

function Verify-Install([string]$label) {
  Sync-InstallDir
  $exe = Join-Path $InstallDir "X-PDF.exe"
  Record "${label}: X-PDF.exe installed" (Test-Path $exe) $exe
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
    Record "${label}: executable version info" ($vi.ProductName -eq "X PDF") "ProductName=$($vi.ProductName) FileVersion=$($vi.FileVersion)" $false
    Check-Fuses $label $exe
  }
}

# Electron fuses are switches compiled into X-PDF.exe at packaging time.
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
    $_.Name -match '^(X-PDF|python|pythonw|tesseract|soffice|soffice\.bin|gswin64c)\.exe$' -or $_.Name -eq 'soffice.bin'
  } | Select-Object -ExpandProperty FullName
  $bin = Join-Path $InstallDir "resources\libreoffice\program\soffice.bin"
  if (Test-Path $bin) { $exes += $bin }
  $i = 0
  foreach ($e in ($exes | Sort-Object -Unique)) {
    $i++
    New-NetFirewallRule -DisplayName "X PDF offline test $i" -Group "XPDFOfflineTest" -Direction Outbound -Action Block -Program $e | Out-Null
  }
  Record "Outbound network blocked for X PDF executables" ($i -gt 0) "$i program rules"
}

function Unblock-Network { Get-NetFirewallRule -Group "XPDFOfflineTest" -ErrorAction SilentlyContinue | Remove-NetFirewallRule }

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
  $exe = Join-Path $InstallDir "X-PDF.exe"
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
  if ($PreviousInstaller) { Test-Upgrade } else { Record "Upgrade from NayanPDF 1.0 tested" $false "previous installer not available" $false }
  Install-App "Install"
  Verify-Install "Install"

  # A user document and settings that must survive uninstallation.
  $docs = Join-Path ([Environment]::GetFolderPath("MyDocuments")) "X-PDF-acceptance"
  New-Item -ItemType Directory -Force -Path $docs | Out-Null
  $userDoc = Join-Path $docs "user-document.pdf"
  $res = Engine-Env
  & (Join-Path $res "python\python.exe") -I -c "import pymupdf,sys; d=pymupdf.open(); p=d.new_page(); p.insert_text((72,100),'X PDF acceptance document',fontsize=18); [d.new_page().insert_text((72,100),'Page %d'%(i+2)) for i in range(4)]; d.save(sys.argv[1])" $userDoc
  Record "Test document created" (Test-Path $userDoc) $userDoc

  Block-Network
  Run-SelfTest "offline"
  if ($TestDeps) { Run-EngineSuite }
  Run-App "home" @()
  Run-App "document" @("`"$userDoc`"")
  Test-ShellConvert $docs

  $settingsDir = Join-Path $env:APPDATA "X PDF"
  Record "Settings folder created by the app" (Test-Path $settingsDir) $settingsDir $false

  # Uninstall
  $p = Invoke-Uninstall
  Record "Uninstall: exit code" ($p -and $p.ExitCode -eq 0) "$(if ($p) { "exit=$($p.ExitCode)" } else { 'uninstaller not found' })"
  Record "Uninstall: program files removed" (-not (Test-Path (Join-Path $InstallDir "X-PDF.exe")))
  Record "Uninstall: Start Menu shortcut removed" (-not (Test-Path $StartMenu))
  Record "Uninstall: registration removed" ($null -eq (Get-UninstallEntry))
  Record "Uninstall: right-click commands removed" ($null -eq (Get-VerbCommand ".docx" "XPDF.ConvertToPdf") -and $null -eq (Get-VerbCommand ".pdf" "XPDF.Combine"))
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
  $md = @("# X PDF Windows acceptance test", "", "Host: $([Environment]::OSVersion.VersionString) $(if ([Environment]::Is64BitOperatingSystem) {'x64'})", "Date: $(Get-Date -Format o)", "", "| Result | Check | Detail |", "|---|---|---|")
  foreach ($r in $results) { $md += "| $(if ($r.ok) {'PASS'} elseif ($r.required) {'FAIL'} else {'WARN'}) | $($r.check) | $($r.detail -replace '\|','/') |" }
  $md -join "`n" | Out-File -Encoding utf8 (Join-Path $ReportDir "report.md")
}

$failed = @($results | Where-Object { -not $_.ok -and $_.required })
Write-Host "Acceptance: $($results.Count) checks, $($failed.Count) required failure(s)"
if ($failed.Count -gt 0) { exit 1 }
exit 0
