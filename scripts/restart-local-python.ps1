$ErrorActionPreference = 'Stop'
$aiRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../CatPjt-TranslaCat-ai'))

# 준비: 이 작업의 loopback 합성 서버만 식별한다. 다른 프로세스는 종료하지 않는다.
$listener = @(Get-NetTCPConnection -LocalPort 18765 -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -eq '127.0.0.1' })
if ($listener.Count -gt 1) { throw 'Expected at most one local synthetic Python listener' }
if ($listener.Count -eq 1) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener[0].OwningProcess)"
    if ($process.CommandLine -notlike '*uvicorn scripts.synthetic_model_server:app*' -or $process.CommandLine -notlike '*18765*') {
        throw 'Refusing to restart a process outside this synthetic task'
    }
    Stop-Process -Id $process.ProcessId
}
$env:TRANSLACAT_TEST_MODEL_EXECUTION = '1'

# 실행: 모델 출력만 합성 Provider로 바꾸는 테스트 앱을 다시 시작한다.
$stamp = [guid]::NewGuid().ToString('N')
$started = Start-Process -FilePath "$aiRoot/.venv/Scripts/python.exe" `
    -ArgumentList '-m uvicorn scripts.synthetic_model_server:app --host 127.0.0.1 --port 18765 --log-level warning' `
    -WorkingDirectory $aiRoot -WindowStyle Hidden `
    -RedirectStandardOutput "$aiRoot/.tmp_ktor_m0/synthetic-$stamp.out.log" `
    -RedirectStandardError "$aiRoot/.tmp_ktor_m0/synthetic-$stamp.err.log" -PassThru

# 검증: 테스트 앱 readiness를 확인하되 응답 본문이나 자격증명은 출력하지 않는다.
$ready = $false
$deadline = (Get-Date).AddSeconds(25)
while (-not $ready -and (Get-Date) -lt $deadline) {
    try { $ready = (Invoke-WebRequest 'http://127.0.0.1:18765/' -SkipHttpErrorCheck -TimeoutSec 2).StatusCode -eq 200 }
    catch { Start-Sleep -Milliseconds 300 }
}
if (-not $ready) { throw "Synthetic Python startup failed; inspect local log synthetic-$stamp.err.log" }
Write-Output "Synthetic Python ready: PID=$($started.Id), loopback only, paid model calls=0"
