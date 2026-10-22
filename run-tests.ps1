param([switch]$Benchmark)
$ErrorActionPreference = 'Stop'
$originalPath = $env:PATH
$originalJavaHome = $env:JAVA_HOME
Push-Location $PSScriptRoot
try {
    $env:PATH = ($env:PATH -split ';' | ForEach-Object { $_.Trim('"') }) -join ';'
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        $bundledJdk = 'C:\Program Files\JetBrains\IntelliJ IDEA 2023.3.6\jbr'
        if (Test-Path "$bundledJdk\bin\javac.exe") {
            $env:JAVA_HOME = $bundledJdk
            $env:PATH = "$bundledJdk\bin;$env:PATH"
        } else {
            throw 'Java 21 JDK를 설치하고 JAVA_HOME을 지정해 주세요.'
        }
    }
    $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mavenCommand) {
        $mavenExecutable = $mavenCommand.Source
    } else {
        $mavenExecutable = 'C:\Program Files\JetBrains\IntelliJ IDEA 2023.3.6\plugins\maven\lib\maven3\bin\mvn.cmd'
        if (-not (Test-Path $mavenExecutable)) {
            throw 'Maven 3.9 이상을 설치하고 PATH에 추가해 주세요.'
        }
    }
    $dockerOutput = & docker info --format '{{.ServerVersion}}' 2>&1
    $dockerStatus = $LASTEXITCODE
    $dockerText = $dockerOutput | Out-String
    if ($dockerStatus -ne 0 -or $dockerText -match 'error during connect|Access is denied') {
        throw "Docker 엔진에 연결할 수 없습니다. 실행 상태와 현재 터미널의 접근 권한을 확인해 주세요.`n$dockerText"
    }
    Write-Output $dockerText
    $mavenArguments = @('-B', '-Dmaven.repo.local=./.m2-local')
    if ($Benchmark) { $mavenArguments += '-Dlab.benchmark=true' }
    & $mavenExecutable @mavenArguments verify
    if ($LASTEXITCODE -ne 0) { throw '테스트 실패: target/surefire-reports를 확인해 주세요.' }
} finally {
    $env:PATH = $originalPath
    $env:JAVA_HOME = $originalJavaHome
    Pop-Location
}
