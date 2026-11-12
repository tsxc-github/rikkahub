#requires -Version 7.0
param([string]$ProbeFile='/mnt/coding/rikkahub-build/downloads/Get-VscodeProbe.ps1')
$ErrorActionPreference='Stop'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($ProbeFile,[ref]$tokens,[ref]$errors)
if($errors.Count){ throw "Parser errors: $errors" }
$function=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-LfProcess'},$true)
if(-not $function){throw 'Real transport helper missing'}
# Load ONLY our helper, not the top-level phone-connection code. Test the actual implementation.
. ([scriptblock]::Create($function.Extent.Text))
$count=1
$r=Invoke-LfProcess -FileName '/bin/sh' -ArgumentList @('-s') -InputText "cat <<'JS'`r`nSHELL_HEREDOC_OK`r`nJS"
if($r.ExitCode -ne 0 -or $r.Stdout.Trim() -cne 'SHELL_HEREDOC_OK'){throw "CRLF/no-final-LF shell heredoc failed: $r"}; $count++
$node=(Get-Command node -CommandType Application | Select-Object -First 1).Source
$inputText='"' + $node + '" <<' + "'JS'`r`nconsole.log('NODE_HEREDOC_OK');`r`nJS"
$r=Invoke-LfProcess -FileName '/bin/sh' -ArgumentList @('-s') -InputText $inputText
if($r.ExitCode -ne 0 -or $r.Stdout.Trim() -cne 'NODE_HEREDOC_OK'){throw "CRLF/no-final-LF Node heredoc failed: $r"}; $count++
$r=Invoke-LfProcess -FileName '/bin/sh' -ArgumentList @('-s') -InputText "printf STDOUT_OK; printf STDERR_OK >&2; exit 23"
if($r.ExitCode -ne 23 -or $r.Stdout -cne 'STDOUT_OK' -or $r.Stderr -cne 'STDERR_OK'){throw "Explicit exit-code/stream capture failed: $r"}; $count++
$payload = $ast.Find({param($node) $node -is [Management.Automation.Language.StringConstantExpressionAst] -and $node.StringConstantType -eq 'SingleQuotedHereString'},$true).Value
$js = [regex]::Match($payload,"(?s)<<'JS'\r?\n(.*?)\r?\nJS(?:\r?\n|$)").Groups[1].Value
if (-not $js) { throw 'Actual embedded diagnostic JS not found' }
$r=Invoke-LfProcess -FileName $node -ArgumentList @('--check','-') -InputText $js
if ($r.ExitCode -ne 0) { throw "Embedded Node probe syntax failed: $r" }; $count++
Write-Host "PWSH_TRANSPORT_TESTS_PASSED=$count"
