param([string]$Email = 'admin123@smartifier.com')

$ErrorActionPreference = 'Stop'
$setupContainer = 'smartifier-admin-setup-' + [guid]::NewGuid().ToString('N')
$previousEmail = $env:SMARTIFIER_ADMIN_EMAIL
$previousPassword = $env:SMARTIFIER_ADMIN_PASSWORD
$containerStarted = $false
$passwordInput = $null
$confirmationInput = $null
Push-Location -LiteralPath $PSScriptRoot
try {
    $passwordInput = Read-Host 'New admin password (12-64 characters, uppercase, lowercase, number, symbol)' -AsSecureString
    $confirmationInput = Read-Host 'Confirm password' -AsSecureString
    $passwordText = [System.Net.NetworkCredential]::new('', $passwordInput).Password
    $confirmationText = [System.Net.NetworkCredential]::new('', $confirmationInput).Password
    if ($passwordText -cne $confirmationText) { throw 'Passwords do not match.' }
    if ($passwordText.Length -lt 12 -or $passwordText.Length -gt 64 -or
        [System.Text.Encoding]::UTF8.GetByteCount($passwordText) -gt 72 -or
        $passwordText -cnotmatch '[A-Z]' -or $passwordText -cnotmatch '[a-z]' -or
        $passwordText -notmatch '[0-9]' -or $passwordText -notmatch '[^A-Za-z0-9\s]') {
        throw 'Use 12-64 characters with uppercase, lowercase, a number, and a symbol; at most 72 UTF-8 bytes.'
    }
    $env:SMARTIFIER_ADMIN_EMAIL = $Email.Trim()
    $env:SMARTIFIER_ADMIN_PASSWORD = $passwordText
    # Compose passes these values from the process environment, without putting
    # the password in a command argument or writing it to a file.
    & docker compose -f docker-compose.yml -f docker-compose.local.yml run -d --no-deps --name $setupContainer -e SMARTIFIER_ADMIN_EMAIL -e SMARTIFIER_ADMIN_PASSWORD backend
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the temporary admin setup container.' }
    $containerStarted = $true
    $deadline = (Get-Date).AddSeconds(90)
    do {
        $setupLogs = & docker logs $setupContainer 2>&1 | Out-String
        if ($setupLogs -match 'Initial admin account created\.') {
            Write-Host "Admin created: $Email. Sign in at http://localhost:8080/login"
            return
        }
        if ($setupLogs -match 'Initial admin already exists') {
            Write-Host "Admin already exists: $Email. Its password was not changed."
            return
        }
        $running = & docker inspect --format '{{.State.Running}}' $setupContainer
        if ($LASTEXITCODE -ne 0 -or $running -ne 'true') {
            throw 'Admin setup failed. The email may belong to an existing member. Check the application configuration.'
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'Timed out waiting for admin creation.'
} finally {
    if ($containerStarted) { & docker rm -f $setupContainer | Out-Null }
    $env:SMARTIFIER_ADMIN_EMAIL = $previousEmail
    $env:SMARTIFIER_ADMIN_PASSWORD = $previousPassword
    $passwordText = $null
    $confirmationText = $null
    if ($passwordInput) { $passwordInput.Dispose() }
    if ($confirmationInput) { $confirmationInput.Dispose() }
    Pop-Location
}
