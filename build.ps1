param(
    [string]$IdePath,
    [string]$JavaHome,
    [switch]$RunIde
)

$ErrorActionPreference = 'Stop'
if ($IdePath) {
    $IdePath = (Resolve-Path -LiteralPath $IdePath).Path
    if (-not (Test-Path -LiteralPath (Join-Path $IdePath 'product-info.json'))) {
        throw 'IdePath must point to the IDE root containing product-info.json.'
    }
}
if ($JavaHome) { $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path }
elseif ($IdePath -and (Test-Path -LiteralPath (Join-Path $IdePath 'jbr\bin\javac.exe'))) {
    $env:JAVA_HOME = Join-Path $IdePath 'jbr'
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    throw 'Set JAVA_HOME to JDK 21 or provide -JavaHome or -IdePath.'
}
$taskArguments = @('--console=plain')
# Java does not automatically honor HTTPS_PROXY, unlike many native downloaders.
if ($env:HTTPS_PROXY) {
    $buildProxy = [Uri]$env:HTTPS_PROXY
    if ($buildProxy.Scheme -eq 'http' -and -not $buildProxy.UserInfo) {
        $taskArguments += @("-Dhttps.proxyHost=$($buildProxy.Host)", "-Dhttps.proxyPort=$($buildProxy.Port)",
                           "-Dhttp.proxyHost=$($buildProxy.Host)", "-Dhttp.proxyPort=$($buildProxy.Port)")
    }
}
if ($IdePath) { $taskArguments += "-PlocalIdePath=$IdePath" }
if ($RunIde) { $taskArguments += 'runIde' }
else { $taskArguments += @('test', 'buildPlugin') }
Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot 'gradlew.bat') @taskArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
