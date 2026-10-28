param(
    [ValidateSet('Debug', 'Release')][string]$Variant = 'Debug',
    [int]$SocksPort = 0,
    [switch]$RefreshDependencies
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$repositories = Get-ChildItem (Join-Path $root 'app/src/main/jni') -Recurse -Force -Filter .git |
    ForEach-Object { $_.DirectoryName }

# Windows checkouts without symlink support contain target paths instead of C headers.
$links = foreach ($repository in $repositories) {
    $repoPath = $repository
    $safePath = $repoPath.Replace('\', '/')
    $entries = & git -C $repoPath -c "safe.directory=$safePath" ls-files -s
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect Git links in $repoPath" }

    foreach ($entry in $entries) {
        if ($entry -notmatch '^120000 [0-9a-f]+ 0\t(.+)$') { continue }
        $relativePath = $Matches[1]
        $path = Join-Path $repoPath $relativePath
        $item = Get-Item -LiteralPath $path
        if ($item.LinkType -eq 'SymbolicLink') { continue }

        $target = & git -C $repoPath -c "safe.directory=$safePath" show "HEAD:$relativePath"
        if ($LASTEXITCODE -ne 0) { throw "Cannot read Git link target for $path" }
        $target = $target.Trim()
        $original = [System.Text.Encoding]::UTF8.GetBytes($target)
        $targetPath = Join-Path (Split-Path $path -Parent) $target
        if (-not (Test-Path -LiteralPath $targetPath -PathType Leaf)) {
            throw "Missing target for $path : $target"
        }
        [pscustomobject]@{ Path = $path; Target = $targetPath; Original = $original }
    }
}

$replaced = @()
$buildExit = 1
$initScript = $null
try {
    if ($SocksPort -gt 0) {
        $initScript = Join-Path ([System.IO.Path]::GetTempPath()) ("gradle-proxy-$([guid]::NewGuid()).gradle")
        @"
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.SocketAddress
import java.net.InetSocketAddress
import java.io.IOException

ProxySelector.setDefault(new ProxySelector() {
    List<Proxy> select(URI uri) {
        if (uri.host in ['dl.google.com', 'localhost', '127.0.0.1']) return [Proxy.NO_PROXY]
        return [new Proxy(Proxy.Type.SOCKS, new InetSocketAddress('127.0.0.1', $SocksPort))]
    }
    void connectFailed(URI uri, SocketAddress address, IOException error) {}
})
"@ | Set-Content -LiteralPath $initScript -Encoding UTF8
    }

    foreach ($link in $links) {
        $replaced += $link
        [System.IO.File]::Copy($link.Target, $link.Path, $true)
    }
    $gradleArgs = @(":app:assemble$Variant", '--console=plain', '-Dorg.gradle.internal.http.connectionTimeout=120000', '-Dorg.gradle.internal.http.socketTimeout=120000')
    if ($initScript) { $gradleArgs += @('--init-script', $initScript) }
    if ($RefreshDependencies) { $gradleArgs += '--refresh-dependencies' }
    & (Join-Path $root 'gradlew.bat') @gradleArgs
    $buildExit = $LASTEXITCODE
} finally {
    foreach ($link in $replaced) {
        [System.IO.File]::WriteAllBytes($link.Path, $link.Original)
    }
    if ($initScript) { Remove-Item -LiteralPath $initScript -ErrorAction SilentlyContinue }
}

exit $buildExit
