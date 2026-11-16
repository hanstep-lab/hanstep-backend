# 데모 사용자(demo-e9-ph-reyes) 금융 데이터 준비.
# bootRun 중인 로컬 Spring(기본 http://localhost:8080)에 진입 → 마이데이터 동의 → 기관 연결·Mock 적재 → 필수 확보액 → 목표 → 금융 분석 순서로 호출한다.
# 마지막 금융 분석은 Spring 이 AI 서버(INT-01)를 호출하므로 AI 서버도 떠 있어야 한다.
# 이미 동의·연결돼 있어도 다시 실행할 수 있다(409 는 건너뛰고 다음 단계로). 토큰·키 값은 출력하지 않는다.
# 사용: powershell -ExecutionPolicy Bypass -File scripts\demo-setup.ps1 [-BaseUrl http://localhost:8080]
param(
    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$EntryToken = 'demo-e9-ph-reyes'

# 필수 확보액·목표 값은 AI 챗봇 평가 fixture(hanstep-AI ai/tests/fixtures/chat/finance_context_minh.json)와 같게 맞춘 값.
# 고정생활비 520,000 / 가족 송금 700,000 / 변동지출 450,000, 목표 600만 원(2027-09-30).
$Commitments = @(
    @{ commitmentType = 'LIVING_MINIMUM'; label = '고정생활비'; amount = @{ amount = 520000; currency = 'KRW' } },
    @{ commitmentType = 'REMITTANCE'; label = '가족 송금'; amount = @{ amount = 700000; currency = 'KRW' } },
    @{ commitmentType = 'VARIABLE_EXPENSE'; label = '변동지출'; amount = @{ amount = 450000; currency = 'KRW' } }
)
$Goals = @(
    @{ goalType = 'LUMP_SUM'; targetAmount = @{ amount = 6000000; currency = 'KRW' }; targetDate = '2027-09-30' }
)

# 본문은 UTF-8 바이트로 보낸다(Windows PowerShell 5.1 기본 인코딩으로 보내면 한글 label 이 깨짐).
function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [hashtable]$Headers = @{}
    )
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $Headers }
    if ($null -ne $Body) {
        $json = ConvertTo-Json -InputObject $Body -Depth 6 -Compress
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes($json)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        return @{ Ok = $true; Status = 0; Data = (Invoke-RestMethod @params) }
    } catch {
        $status = 0
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
        }
        return @{ Ok = $false; Status = $status; Data = $null }
    }
}

# 409 는 건너뛰고 계속, 그 밖의 실패는 중단.
function Assert-Step {
    param([string]$Name, $Result)
    if ($Result.Ok) {
        Write-Host "[OK]   $Name"
        return
    }
    if ($Result.Status -eq 409) {
        Write-Host "[SKIP] $Name (409, 이미 처리됨)"
        return
    }
    Write-Host "[FAIL] $Name (HTTP $($Result.Status))"
    exit 1
}

# 1. 진입(AUTH-01)
$entry = Invoke-Api -Method Post -Path '/api/v1/auth/entry' -Body @{ entryToken = $EntryToken }
Assert-Step '진입' $entry
$userId = $entry.Data.user.id
$auth = @{ Authorization = "Bearer $($entry.Data.accessToken)" }

# 2. 마이데이터 동의(CNS-02)
$consent = Invoke-Api -Method Post -Path '/api/v1/consents' -Headers $auth -Body @{
    consents = @(
        @{ consentType = 'MYDATA'; policyVersion = 'mydata-2026-11'; granted = $true },
        @{ consentType = 'MYDATA_TERMS'; policyVersion = 'mydata-2026-11'; granted = $true }
    )
}
Assert-Step '마이데이터 동의' $consent

# 3. 기관 연결·Mock 적재(MYD-02). 기관 ID 는 MYD-01 응답의 SHINHAN_BANK·SHINHAN_CARD, 못 찾으면 [1, 2]
$institutionIds = @(1, 2)
$institutions = Invoke-Api -Method Get -Path '/api/v1/mydata/institutions' -Headers $auth
if ($institutions.Ok) {
    $found = @($institutions.Data.institutions |
        Where-Object { $_.institutionCode -in @('SHINHAN_BANK', 'SHINHAN_CARD') } |
        ForEach-Object { [long]$_.id })
    if ($found.Count -eq 2) {
        $institutionIds = $found
    }
}
$connectHeaders = $auth.Clone()
$connectHeaders['Idempotency-Key'] = [guid]::NewGuid().ToString()
$connect = Invoke-Api -Method Post -Path '/api/v1/mydata/connections' -Headers $connectHeaders -Body @{
    institutionIds = $institutionIds
    scopes = @('ACCOUNT_BALANCE', 'TRANSACTIONS', 'CARD_USAGE')
    lookbackMonths = 12
}
Assert-Step '기관 연결·Mock 적재' $connect

# 4. 필수 확보액(CMT-02, 전체 교체)
$commitment = Invoke-Api -Method Put -Path '/api/v1/commitments' -Headers $auth -Body @{ items = $Commitments }
Assert-Step '필수 확보액 저장' $commitment

# 5. 목표(GOL-02, 전체 교체)
$goal = Invoke-Api -Method Put -Path '/api/v1/goals' -Headers $auth -Body @{ goals = $Goals }
Assert-Step '목표 저장' $goal

# 6. 금융 분석(FIN-05)
$analysisHeaders = $auth.Clone()
$analysisHeaders['Idempotency-Key'] = [guid]::NewGuid().ToString()
$analysis = Invoke-Api -Method Post -Path '/api/v1/finance/analyses' -Headers $analysisHeaders
Assert-Step '금융 분석' $analysis

Write-Host ''
Write-Host "사용자 id: $userId"
if ($analysis.Ok) {
    Write-Host ('여유자금: {0:N0} 원' -f [long]$analysis.Data.spare.amount)
    Write-Host ('변동지출(월평균): {0:N0} 원' -f [long]$analysis.Data.variableExpense.avg.amount)
}
