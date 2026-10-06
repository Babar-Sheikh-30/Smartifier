param([switch]$SetupAdmin, [string]$JavaHome)

$ErrorActionPreference = 'Stop'
$previousLocation = Get-Location
$previousAdminEmail = $env:SMARTIFIER_ADMIN_EMAIL
$previousAdminPassword = $env:SMARTIFIER_ADMIN_PASSWORD
$previousJavaHome = $env:JAVA_HOME

try {
    Set-Location -LiteralPath $PSScriptRoot
    if (-not $JavaHome) {
        # Use the Java installation on PATH; JAVA_HOME may still point at an older JDK.
        # Windows PowerShell wraps native stderr as error records, even on success.
        $savedErrorPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $javaSettings = & java -XshowSettings:properties -version 2>&1
        } finally { $ErrorActionPreference = $savedErrorPreference }
        $homeLine = $javaSettings | Where-Object { "$_" -match '^\s*java.home\s*=' } | Select-Object -First 1
        if ($homeLine) { $JavaHome = ("$homeLine" -split '=', 2)[1].Trim() }
    }
    if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
        throw 'Java JDK 25 is required. Supply -JavaHome with its installation path.'
    }
    $compilerVersion = & (Join-Path $JavaHome 'bin/javac.exe') -version 2>&1
    if ("$compilerVersion" -notmatch '^javac 25(?:\.|\s|$)') { throw 'This project requires Java JDK 25.' }
    $env:JAVA_HOME = $JavaHome
    if ($SetupAdmin) {
        $env:SMARTIFIER_ADMIN_EMAIL = (Read-Host 'Initial admin email').Trim()
        if ([string]::IsNullOrWhiteSpace($env:SMARTIFIER_ADMIN_EMAIL)) {
            throw 'Enter an admin email.'
        }
        $securePassword = Read-Host 'Initial admin password (12-64 characters)' -AsSecureString
        $confirmation = Read-Host 'Confirm admin password' -AsSecureString
        $env:SMARTIFIER_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
        $confirmText = [System.Net.NetworkCredential]::new('', $confirmation).Password
        if ($env:SMARTIFIER_ADMIN_PASSWORD -cne $confirmText) { throw 'Passwords do not match.' }
        if ($env:SMARTIFIER_ADMIN_PASSWORD.Length -lt 12 -or $env:SMARTIFIER_ADMIN_PASSWORD.Length -gt 64 -or
            [System.Text.Encoding]::UTF8.GetByteCount($env:SMARTIFIER_ADMIN_PASSWORD) -gt 72) {
            throw 'Use 12-64 characters and no more than 72 UTF-8 bytes.'
        }
        $confirmText = $null
        $securePassword.Dispose()
        $confirmation.Dispose()
    }
    if (-not (Test-Path -LiteralPath 'node_modules/@angular/cli')) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    & .\mvnw.cmd spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw 'Application startup failed. See the Maven output above.' }
} finally {
    $env:SMARTIFIER_ADMIN_EMAIL = $previousAdminEmail
    $env:SMARTIFIER_ADMIN_PASSWORD = $previousAdminPassword
    $env:JAVA_HOME = $previousJavaHome
    Set-Location -LiteralPath $previousLocation.Path
}
