#requires -Version 7.0
# Read-only VS Code binary diagnostics plus temporary self-owned socket probes. No package installs.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$HostName,
    [ValidateRange(1024,65535)][int]$Port = 2222,
    [string]$IdentityFile = '.\p0-computer',
    [string]$KnownHosts,
    [ValidatePattern('^[0-9a-f]{40}$')][string]$Commit = '261c84cb1eddf11235f9e2c921425e9663fbe556'
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

function Invoke-LfProcess {
    param([string]$FileName, [string[]]$ArgumentList, [string]$InputText, [int]$TimeoutSeconds = 60)
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $FileName
    $info.UseShellExecute = $false
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($argument in $ArgumentList) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw 'Could not start diagnostic process' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        # Do not use a PowerShell native pipeline: Windows can append CRLF to a heredoc terminator.
        $lf = $InputText.Replace("`r`n", "`n").Replace("`r", "`n").TrimEnd([char[]]"`n") + "`n"
        $process.StandardInput.Write($lf)
        $process.StandardInput.Close()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill($true)
            $process.WaitForExit()
            throw 'Diagnostic timed out; only this local child process was terminated'
        }
        [pscustomobject]@{ ExitCode = $process.ExitCode; Stdout = $stdout.GetAwaiter().GetResult(); Stderr = $stderr.GetAwaiter().GetResult() }
    } finally { $process.Dispose() }
}

$address = $null
if (-not [Net.IPAddress]::TryParse($HostName,[ref]$address) -or $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or $address.ToString() -cne $HostName) { throw 'Pass the exact phone IPv4.' }
if (-not $KnownHosts) { $KnownHosts = Join-Path (Get-Location).Path ".rikka-ssh-hosts-$HostName-$Port" }
if (-not (Test-Path -LiteralPath $KnownHosts -PathType Leaf)) { throw 'Pass -KnownHosts with the trust file used for your successful phone connection.' }
if (-not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) { throw 'Local test private key not found; do not upload the private key.' }
$hostOption = 'UserKnownHostsFile="' + ((Resolve-Path -LiteralPath $KnownHosts).Path -replace '\\','/') + '"'
$remote = @'
set +e
COMMIT=__COMMIT__
P1="/root/.vscode-server/bin/$COMMIT/node"
P2="/root/.vscode-server/cli/servers/Stable-$COMMIT/server/node"
printf 'PROBE_SCRIPT_VERSION=3\n=== ROOTFS ===\n'
uname -m
cat /etc/alpine-release 2>/dev/null
printf 'SSH_SESSION_TMPDIR=%s\n' "${TMPDIR-}"
printf '\n=== LOADERS ===\n'
ls -l /lib/ld-musl-aarch64.so.1 /lib/ld-linux-aarch64.so.1 2>&1
GOOD=''
for n in "$P1" "$P2"; do
  printf '\n=== NODE %s ===\n' "$n"
  if [ ! -e "$n" ]; then printf 'FILE_MISSING\n'; continue; fi
  ls -l "$n"
  if command -v file >/dev/null 2>&1; then file "$n"; fi
  printf '%s\n' '-- version --'
  "$n" --version 2>&1
  rc=$?
  printf 'NODE_VERSION_EXIT=%s\n' "$rc"
  if [ "$rc" = 0 ]; then GOOD="$n"; fi
  printf '%s\n' '-- ldd (first 20 lines) --'
  ldd "$n" 2>&1 | head -n 20
 done
if [ -z "$GOOD" ]; then printf '\nNO_WORKING_VSCODE_NODE_FOR_SOCKET_PROBE\n'; exit 0; fi
printf '\n=== ELF INTERPRETERS + SOCKET PROBES ===\n'
"$GOOD" - "$P1" "$P2" <<'JS'
const fs = require('fs'), net = require('net'), crypto = require('crypto'), os = require('os');
console.log('NODE_TMPDIR=' + os.tmpdir());
for (const p of process.argv.slice(2)) {
  try {
    const b = fs.readFileSync(p);
    if (b[4] !== 2 || b[5] !== 1) { console.log('ELF_FORMAT_UNHANDLED', p); continue; }
    const offset = Number(b.readBigUInt64LE(32)), size = b.readUInt16LE(54), count = b.readUInt16LE(56);
    let found = false;
    for (let i = 0; i < count; i++) {
      const h = offset + size * i;
      if (b.readUInt32LE(h) === 3) {
        const start = Number(b.readBigUInt64LE(h + 8)), length = Number(b.readBigUInt64LE(h + 32));
        const interpreter = b.subarray(start, start + length).toString().replace(/\0.*$/, '');
        console.log(JSON.stringify({type:'ELF_INTERPRETER',file:p,interpreter,loaderExists:fs.existsSync(interpreter)})); found=true;
      }
    }
    if (!found) console.log('NO_PT_INTERP', p);
  } catch (e) { console.log('ELF_READ_ERROR', p, e.code || e.message); }
}
async function probe(label, options) {
  const server = net.createServer();
  return new Promise(resolve => {
    let done = false;
    const finish = result => { if(done) return; done=true; clearTimeout(timer); console.log(JSON.stringify({type:'LISTEN_PROBE',label,...result})); resolve(); };
    const timer = setTimeout(() => { server.close(() => {}); finish({ok:false,code:'TIMEOUT'}); },3000);
    server.once('error', error => finish({ok:false,code:error.code,message:error.message}));
    server.listen(options, () => server.close(() => finish({ok:true})));
  });
}
(async () => {
  await probe('TCP_LOOPBACK',{host:'127.0.0.1',port:0});
  await probe('UNIX_SHORT',{path:'/tmp/rp-'+crypto.randomBytes(4).toString('hex')});
  await probe('UNIX_VSCODE_LENGTH',{path:'/tmp/code-'+crypto.randomUUID()});
  await probe('UNIX_SESSION_TMP',{path:os.tmpdir()+'/code-'+crypto.randomUUID()});
})().catch(e => { console.log('PROBE_ERROR',e.message); process.exitCode=1; });
JS
'@
$remote = $remote.Replace('__COMMIT__',$Commit)
$report = Join-Path (Get-Location).Path ('vscode-probe-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
$ssh = (Get-Command ssh -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$arguments = @('-T','-i',(Resolve-Path -LiteralPath $IdentityFile).Path,'-p',"$Port",'-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-o',$hostOption,"root@$HostName",'sh -s')
$result = Invoke-LfProcess -FileName $ssh -ArgumentList $arguments -InputText $remote
$text = "[STDOUT]`n" + $result.Stdout + "`n[STDERR]`n" + $result.Stderr + "`nSSH_EXIT=" + $result.ExitCode + "`n"
$text | Set-Content -LiteralPath $report -Encoding utf8NoBOM
Write-Host $text
Write-Host "Report saved: $report"
if ($result.ExitCode -ne 0) { throw "Diagnostic SSH failed, exit=$($result.ExitCode). For encrypted keys use your SSH agent; do not disable host-key checking." }
