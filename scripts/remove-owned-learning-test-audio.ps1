$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$evidence = [IO.Path]::GetFullPath((Join-Path $root '../.codex-workspace/verification/ll/final-cleanup'))
$allowedRoots = @('build/live-level-test-audio', '../.codex-workspace/verification/ll/runtime/speaking-audio') |
    ForEach-Object { [IO.Path]::GetFullPath((Join-Path $root $_)) }

# 준비: 해당 테스트 서버와 DB 참조가 먼저 중지·초기화됐는지 확인한다.
if (@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {
    $_.LocalPort -in @(18766, 18767)
}).Count -ne 0) { throw 'Owned learning runtimes must be stopped' }
$reset = Get-Content -LiteralPath (Join-Path $evidence 'data-reset-result.json') -Raw | ConvertFrom-Json
if (-not $reset.applied -or -not $reset.preservedChecksumsMatch -or -not $reset.highWaterPreserved) {
    throw 'Verified scratch reset required before audio removal'
}
$files = @(Get-Content -LiteralPath (Join-Path $evidence 'owned-audio-files.json') -Raw | ConvertFrom-Json)
foreach ($file in $files) {
    $path = [IO.Path]::GetFullPath($file.path)
    $storeRoot = [IO.Path]::GetFullPath($file.root)
    if ($storeRoot -notin $allowedRoots -or -not $path.StartsWith($storeRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) { throw 'Audio target outside the two owned test stores' }
    $item = Get-Item -LiteralPath $path
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $file.sha256) { throw 'Audio target changed after audit' }
}

# 실행: 검토한 파일 하나씩만 제거한다. 디렉터리·다른 저장소·volume은 삭제하지 않는다.
foreach ($file in $files) { Remove-Item -LiteralPath $file.path }

# 검증: 목록의 파일만 사라졌고 저장소 루트는 다음 정상 녹음에 사용할 수 있다.
if (@($files | Where-Object { Test-Path -LiteralPath $_.path }).Count -ne 0) { throw 'Audited audio file remains' }
if (@($allowedRoots | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Container) }).Count -ne 0) {
    throw 'An owned audio directory was removed'
}
[IO.File]::WriteAllText((Join-Path $evidence 'audio-reset-result.json'), (@{removedFiles=$files.Count;
    removedBytes=($files | Measure-Object -Property bytes -Sum).Sum;rootsPreserved=$true} | ConvertTo-Json),
    [Text.UTF8Encoding]::new($false))
Write-Output "PASS: removed $($files.Count) audited synthetic audio files; both test store roots preserved"
