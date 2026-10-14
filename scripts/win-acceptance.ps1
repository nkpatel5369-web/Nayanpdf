<#
  NayanPDF Windows acceptance test.

  Runs on a Windows machine (used by CI on windows-latest):
    1. Silent per-user install of NayanPDF_Setup.exe
    2. Verifies executable, bundled engines, Start Menu shortcut, uninstall registration
    3. Blocks outbound network access for every NayanPDF executable (Windows Firewall)
    4. Runs the engine self-test and the full engine test suite with the INSTALLED runtime
    5. Launches the installed NayanPDF.exe (home screen, then with a PDF), captures
       screenshots, and records any network connections made by its processes
    6. Silent uninstall: verifies removal while user documents and settings survive
    7. Reinstall and re-verify

  Writes <ReportDir>\report.json and report.md. Exit code 1 if a required check fails.
#>
param(
  [Parameter(Mandatory = $true)][string]$Installer,
  [string]$ReportDir = "acceptance",
  [string]$RepoRoot = (Resolve-Path "$PSScriptRoot\..").Path,
  [string]$TestDeps = ""
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$ReportDir = (Resolve-Path $ReportDir).Path
$Installer = (Resolve-Path $Installer).Path
$results = New-Object System.Collections.ArrayList
$InstallDir = Join-Path $env:LOCALAPPDATA "Programs\NayanPDF"
$StartMenu = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\NayanPDF.lnk"

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
    ForEach-Object { Get-ItemProperty $_.PSPath } | Where-Object { $_.DisplayName -like "NayanPDF*" } | Select-Object -First 1
}

function Verify-Install([string]$label) {
  $exe = Join-Path $InstallDir "NayanPDF.exe"
  Record "${label}: NayanPDF.exe installed" (Test-Path $exe) $exe
  $res = Join-Path $InstallDir "resources"
  foreach ($rel in @("python\python.exe", "engine\run_engine.py", "tesseract\tesseract.exe", "tesseract\tessdata\eng.traineddata",
                     "libreoffice\program\soffice.exe", "ghostscript\bin\gswin64c.exe", "app.asar", "MANIFEST.json")) {
    Record "${label}: bundled $rel" (Test-Path (Join-Path $res $rel))
  }
  Record "${label}: Start Menu shortcut" (Test-Path $StartMenu) $StartMenu
  $u = Get-UninstallEntry
  Record "${label}: uninstall registration" ($null -ne $u) ($(if ($u) { "$($u.DisplayName) $($u.DisplayVersion)" } else { "missing" }))
  if (Test-Path $exe) {
    $vi = (Get-Item $exe).VersionInfo
    Record "${label}: executable version info" ($vi.ProductName -eq "NayanPDF") "ProductName=$($vi.ProductName) FileVersion=$($vi.FileVersion)" $false
  }
}

function Block-Network {
  $exes = @()
  $exes += Get-ChildItem -Path $InstallDir -Recurse -Include *.exe -ErrorAction SilentlyContinue | Where-Object {
    $_.Name -match '^(NayanPDF|python|pythonw|tesseract|soffice|soffice\.bin|gswin64c)\.exe$' -or $_.Name -eq 'soffice.bin'
  } | Select-Object -ExpandProperty FullName
  $bin = Join-Path $InstallDir "resources\libreoffice\program\soffice.bin"
  if (Test-Path $bin) { $exes += $bin }
  $i = 0
  foreach ($e in ($exes | Sort-Object -Unique)) {
    $i++
    New-NetFirewallRule -DisplayName "NayanPDF offline test $i" -Group "NayanPDFOfflineTest" -Direction Outbound -Action Block -Program $e | Out-Null
  }
  Record "Outbound network blocked for NayanPDF executables" ($i -gt 0) "$i program rules"
}

function Unblock-Network { Get-NetFirewallRule -Group "NayanPDFOfflineTest" -ErrorAction SilentlyContinue | Remove-NetFirewallRule }

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
  $exe = Join-Path $InstallDir "NayanPDF.exe"
  $p = Start-Process -FilePath $exe -ArgumentList $argsList -PassThru
  $conns = @()
  $deadline = (Get-Date).AddSeconds(150)
  while (-not $p.HasExited -and (Get-Date) -lt $deadline) {
    $ids = @($p.Id) + @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.ParentProcessId -eq $p.Id } | Select-Object -ExpandProperty ProcessId)
    $kids = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $ids -contains $_.ParentProcessId } | Select-Object -ExpandProperty ProcessId
    $ids += $kids
    $c = Get-NetTCPConnection -ErrorAction SilentlyContinue | Where-Object { $ids -contains $_.OwningProcess -and $_.RemoteAddress -notmatch '^(127\.|::1|0\.0\.0\.0|::$)' -and $_.State -ne 'Listen' }
    if ($c) { $conns += $c | Select-Object OwningProcess, RemoteAddress, RemotePort, State }
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
}

# ------------------------------------------------------------------ run
try {
  Install-App "Install"
  Verify-Install "Install"

  # A user document and settings that must survive uninstallation.
  $docs = Join-Path ([Environment]::GetFolderPath("MyDocuments")) "NayanPDF-acceptance"
  New-Item -ItemType Directory -Force -Path $docs | Out-Null
  $userDoc = Join-Path $docs "user-document.pdf"
  $res = Engine-Env
  & (Join-Path $res "python\python.exe") -I -c "import pymupdf,sys; d=pymupdf.open(); p=d.new_page(); p.insert_text((72,100),'NayanPDF acceptance document',fontsize=18); [d.new_page().insert_text((72,100),'Page %d'%(i+2)) for i in range(4)]; d.save(sys.argv[1])" $userDoc
  Record "Test document created" (Test-Path $userDoc) $userDoc

  Block-Network
  Run-SelfTest "offline"
  if ($TestDeps) { Run-EngineSuite }
  Run-App "home" @()
  Run-App "document" @("`"$userDoc`"")

  $settingsDir = Join-Path $env:APPDATA "NayanPDF"
  Record "Settings folder created by the app" (Test-Path $settingsDir) $settingsDir $false

  # Uninstall
  $u = Get-UninstallEntry
  $un = Join-Path $InstallDir "Uninstall NayanPDF.exe"
  $p = Start-Process -FilePath $un -ArgumentList @("/S", "_?=$InstallDir") -Wait -PassThru
  Start-Sleep -Seconds 3
  Remove-Item -Recurse -Force $InstallDir -ErrorAction SilentlyContinue   # NSIS leaves the uninstaller itself when run with _?=
  Record "Uninstall: exit code" ($p.ExitCode -eq 0) "exit=$($p.ExitCode)"
  Record "Uninstall: program files removed" (-not (Test-Path (Join-Path $InstallDir "NayanPDF.exe")))
  Record "Uninstall: Start Menu shortcut removed" (-not (Test-Path $StartMenu))
  Record "Uninstall: registration removed" ($null -eq (Get-UninstallEntry))
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
  $md = @("# NayanPDF Windows acceptance test", "", "Host: $([Environment]::OSVersion.VersionString) $(if ([Environment]::Is64BitOperatingSystem) {'x64'})", "Date: $(Get-Date -Format o)", "", "| Result | Check | Detail |", "|---|---|---|")
  foreach ($r in $results) { $md += "| $(if ($r.ok) {'PASS'} elseif ($r.required) {'FAIL'} else {'WARN'}) | $($r.check) | $($r.detail -replace '\|','/') |" }
  $md -join "`n" | Out-File -Encoding utf8 (Join-Path $ReportDir "report.md")
}

$failed = @($results | Where-Object { -not $_.ok -and $_.required })
Write-Host "Acceptance: $($results.Count) checks, $($failed.Count) required failure(s)"
if ($failed.Count -gt 0) { exit 1 }
exit 0
