function Import-SlopbookLocalEnvironment {
    param([Parameter(Mandatory)][string] $Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return }
    $lineNumber = 0
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        $lineNumber++
        $entry = $line.Trim()
        if (-not $entry -or $entry.StartsWith('#')) { continue }
        if ($entry -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') {
            throw "Invalid local environment entry at line $lineNumber. Expected NAME=value."
        }
        $variableName = $Matches[1]
        $variableValue = $Matches[2].Trim()
        if ($variableValue.Length -ge 2 -and
            (($variableValue.StartsWith('"') -and $variableValue.EndsWith('"')) -or
             ($variableValue.StartsWith("'") -and $variableValue.EndsWith("'")))) {
            $variableValue = $variableValue.Substring(1, $variableValue.Length - 2)
        }
        # Treat values as literal data; never execute or expand env-file contents.
        # Existing process settings take precedence over this development file.
        if ($null -eq [Environment]::GetEnvironmentVariable($variableName, 'Process')) {
            [Environment]::SetEnvironmentVariable($variableName, $variableValue, 'Process')
        }
    }
}
