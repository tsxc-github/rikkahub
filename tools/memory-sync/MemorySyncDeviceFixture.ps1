# Dot-sourced by Run-MemorySyncTests.ps1 only after explicit device/install authorization.
# No existing SSH config, keys, user home, services, or firewall are modified.
function Invoke-FixtureProcess([string]$File, [string[]]$Arguments, [string]$InputText = '') {
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $File; $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $psi.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.Write($InputText); $process.StandardInput.Close()
    if (!$process.WaitForExit(30000)) { $process.Kill($true); throw 'Ephemeral fixture command timed out' }
    $result = $stdout.GetAwaiter().GetResult(); $null = $stderr.GetAwaiter().GetResult()
    $code = $process.ExitCode; $process.Dispose()
    # Never echo private input or command output on error.
    if ($code -ne 0) { throw "Ephemeral fixture command $([IO.Path]::GetFileName($File)) exited $code" }
    return $result
}
function Invoke-FixtureRpc([hashtable]$Request) {
    $response = Invoke-FixtureProcess $script:fixturePython @($script:fixtureHelper, 'rpc', '--root', $script:fixtureRoot) (($Request | ConvertTo-Json -Depth 8 -Compress) + "`n")
    $parsed = $response | ConvertFrom-Json
    if (!$parsed.ok) { throw "Ephemeral helper RPC failed: $($parsed.error.code)" }
    return $parsed
}
function Start-MemorySyncDeviceFixture {
    if (!$Device -or !$InstallTestApp) { throw 'Creating an SSH fixture requires both -Device and -InstallTestApp' }
    if (!$IsLinux) { throw 'Ephemeral SSH fixture requires Linux with an existing OpenSSH server binary; Windows reports this coverage NOT_RUN' }
    $script:fixturePython = Require-Command $Python
    $script:fixtureHelper = Join-Path $RepoRoot 'tools/memory-sync/memory_sync.py'
    $sshd = Get-Command 'sshd' -ErrorAction SilentlyContinue
    $script:sshdBinary = if ($sshd) { $sshd.Source } elseif (Test-Path -LiteralPath '/usr/sbin/sshd') { '/usr/sbin/sshd' } else { throw 'OpenSSH sshd is missing; install it manually, never auto-install' }
    $keygen = Require-Command 'ssh-keygen'
    $script:fixtureDirectory = Join-Path ([IO.Path]::GetTempPath()) "rikkahub-memory-ssh-$runId"
    New-Item -ItemType Directory -Path $fixtureDirectory | Out-Null
    $null = Invoke-FixtureProcess '/bin/chmod' @('700', $fixtureDirectory)
    $script:fixtureRoot = Join-Path $fixtureDirectory 'data'
    $hostKey = Join-Path $fixtureDirectory 'host-key'; $clientKey = Join-Path $fixtureDirectory 'client-key'
    $null = Invoke-FixtureProcess $keygen @('-q', '-t', 'ed25519', '-N', '', '-f', $hostKey)
    $null = Invoke-FixtureProcess $keygen @('-q', '-t', 'ed25519', '-N', '', '-f', $clientKey)
    $authorized = Join-Path $fixtureDirectory 'authorized_keys'
    Copy-Item -LiteralPath "$clientKey.pub" -Destination $authorized
    $null = Invoke-FixtureProcess '/bin/chmod' @('600', $authorized)
    $username = (Invoke-FixtureProcess '/usr/bin/id' @('-un')).Trim()
    if ($username -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Unsupported local username for restricted ephemeral SSH fixture' }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start(); $script:fixturePort = $listener.LocalEndpoint.Port; $listener.Stop()
    # Quote only locally generated paths; ForceCommand ignores every client-supplied command.
    function ShellQuote([string]$Value) {
        $quote = [string][char]39
        return $quote + $Value.Replace($quote, $quote + '"' + $quote + '"' + $quote) + $quote
    }
    $forced = (ShellQuote $fixturePython) + ' ' + (ShellQuote $fixtureHelper) + ' rpc --root ' + (ShellQuote $fixtureRoot)
    $config = @"
ListenAddress 127.0.0.1
Port $fixturePort
AddressFamily inet
HostKey "$hostKey"
PidFile "$fixtureDirectory/sshd.pid"
AuthorizedKeysFile "$authorized"
StrictModes yes
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM yes
PermitRootLogin prohibit-password
AllowUsers $username
AllowAgentForwarding no
AllowTcpForwarding no
X11Forwarding no
PermitTunnel no
PermitTTY no
PermitUserRC no
DisableForwarding yes
PrintMotd no
PrintLastLog no
LogLevel ERROR
ForceCommand $forced
"@
    $configPath = Join-Path $fixtureDirectory 'sshd_config'
    [IO.File]::WriteAllText($configPath, $config)
    $null = Invoke-FixtureProcess $sshdBinary @('-t', '-f', $configPath)
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $sshdBinary; $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    foreach ($arg in @('-D', '-e', '-f', $configPath)) { $psi.ArgumentList.Add($arg) }
    $script:fixtureSshd = [Diagnostics.Process]::Start($psi)
    $script:fixtureSshdOutput = $fixtureSshd.StandardOutput.ReadToEndAsync()
    $script:fixtureSshdErrors = $fixtureSshd.StandardError.ReadToEndAsync()
    $ready = $false
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        if ($fixtureSshd.HasExited) { throw 'Ephemeral sshd exited before readiness; no existing daemon was changed' }
        $socket = [Net.Sockets.TcpClient]::new()
        try { $socket.Connect([Net.IPAddress]::Loopback, $fixturePort); $ready = $true; break } catch { Start-Sleep -Milliseconds 100 } finally { $socket.Dispose() }
    }
    if (!$ready) { throw 'Ephemeral loopback SSH listener did not become ready' }
    $script:fixtureSpace = [guid]::NewGuid().ToString()
    $null = Invoke-FixtureRpc @{ protocol = 1; op = 'create'; spaceId = $fixtureSpace }
    $null = Invoke-FixtureRpc @{ protocol = 1; op = 'put'; spaceId = $fixtureSpace; path = '/profile.md'; expectedToken = '0'; operationId = [guid]::NewGuid().ToString(); content = 'server-initial-fixture' }
    $reverseList = (Adb @('reverse', '--list')) -join "`n"
    if ($reverseList -match "tcp:$fixturePort(?:\s|$)") { throw 'Refusing to overwrite an existing ADB reverse mapping' }
    Adb @('reverse', '--no-rebind', "tcp:$fixturePort", "tcp:$fixturePort")
    $script:fixtureReverseAdded = $true
    $public = (Get-Content -LiteralPath "$hostKey.pub" -Raw).Trim().Split(' ')
    $payload = [ordered]@{
        runId = $runId; spaceId = $fixtureSpace; remoteRoot = $fixtureRoot
        host = '127.0.0.1'; port = $fixturePort; username = $username
        privateKey = [IO.File]::ReadAllText($clientKey); hostKeyType = $public[0]; hostKey = $public[1]
    } | ConvertTo-Json -Compress
    $script:fixtureDeviceFile = "files/memory-sync-fixture-$runId.json"
    $null = Invoke-FixtureProcess $adb @('-s', $deviceSerial, 'exec-in', 'run-as', 'me.rerere.rikkahub.memoryprobe', 'sh', '-c', "'umask 077; mkdir -p files; cat > $fixtureDeviceFile'") $payload
    $report.device.ssh = @{ status = 'READY'; transport = 'LOOPBACK_SSH_ADB_REVERSE'; fixtureRunId = $runId }
    'Ephemeral host keys and private fixture delivered without logging credentials.'
}
function Assert-MemorySyncDeviceFixtureResult {
    $result = Invoke-FixtureRpc @{ protocol = 1; op = 'list'; spaceId = $fixtureSpace }
    $profile = @($result.documents | Where-Object { $_.path -eq '/profile.md' })
    $deleted = @($result.documents | Where-Object { $_.path -eq '/device-created.md' })
    if ($profile.Count -ne 1 -or $profile[0].content -ne 'resolved-device-fixture') { throw 'Host helper did not observe final device conflict resolution' }
    if ($deleted.Count -ne 1 -or $null -ne $deleted[0].content -or [long]$deleted[0].token -lt 2) { throw 'Host helper did not observe the device tombstone' }
    $report.device.ssh.status = 'PASS'
    'Host helper independently confirmed device push, conflict resolution and durable tombstone.'
}
function Stop-MemorySyncDeviceFixture {
    $errors = [Collections.Generic.List[string]]::new()
    try { if ($script:fixtureDeviceFile) { Adb @('shell', 'run-as', 'me.rerere.rikkahub.memoryprobe', 'rm', '-f', $fixtureDeviceFile) } } catch { $errors.Add('Could not remove private device fixture') }
    try { if ($script:fixtureReverseAdded) { Adb @('reverse', '--remove', "tcp:$fixturePort") } } catch { $errors.Add('Could not remove this run ADB reverse mapping') }
    try {
        if ($script:fixtureSshd) {
            if (!$fixtureSshd.HasExited) { $fixtureSshd.Kill($true); $fixtureSshd.WaitForExit() }
            $fixtureSshd.Dispose()
        }
    } finally {
        if ($script:fixtureDirectory -and (Test-Path -LiteralPath $fixtureDirectory)) { Remove-Item -LiteralPath $fixtureDirectory -Recurse -Force }
    }
    if ($errors.Count) { throw ($errors -join '; ') }
}
