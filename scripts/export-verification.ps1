$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
[xml]$coverage = Get-Content -LiteralPath 'target/site/jacoco/jacoco.xml' -Raw
$suites = @(Get-ChildItem -LiteralPath 'target/surefire-reports' -Filter 'TEST-*.xml' | ForEach-Object {
    [xml]$report = Get-Content -LiteralPath $_.FullName -Raw
    [ordered]@{
        name = $report.testsuite.name
        tests = [int]$report.testsuite.tests
        failures = [int]$report.testsuite.failures
        errors = [int]$report.testsuite.errors
        skipped = [int]$report.testsuite.skipped
    }
})
function Get-LineCounter($node) {
    $line = $node.counter | Where-Object { $_.type -eq 'LINE' }
    $covered = [int]$line.covered
    $total = $covered + [int]$line.missed
    return [ordered]@{ covered = $covered; total = $total; percent = [math]::Round(100.0 * $covered / $total, 2) }
}
$targets = [ordered]@{}
foreach ($name in @('gate','spaces','payment')) {
    $package = $coverage.report.package | Where-Object { $_.name -eq ('com/smartparking/' + $name) }
    $targets[$name] = Get-LineCounter $package
}
$facades = $coverage.report.package | Where-Object { $_.name -eq 'com/smartparking/facade' }
foreach ($name in @('ParkingFacade','AdministrationFacade')) {
    $class = $facades.class | Where-Object { $_.name -eq ('com/smartparking/facade/' + $name) }
    $targets[$name] = Get-LineCounter $class
}
$covered = ($targets.Values | ForEach-Object { $_.covered } | Measure-Object -Sum).Sum
$total = ($targets.Values | ForEach-Object { $_.total } | Measure-Object -Sum).Sum
$result = [ordered]@{
    verified_at = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(5)).ToString('o')
    build_command = '.\mvnw.cmd clean verify'
    test_suites = $suites
    tests = ($suites | ForEach-Object { $_.tests } | Measure-Object -Sum).Sum
    failures = ($suites | ForEach-Object { $_.failures } | Measure-Object -Sum).Sum
    errors = ($suites | ForEach-Object { $_.errors } | Measure-Object -Sum).Sum
    skipped = ($suites | ForEach-Object { $_.skipped } | Measure-Object -Sum).Sum
    required_coverage_targets = $targets
    required_scope = [ordered]@{ covered = $covered; total = $total; percent = [math]::Round(100.0 * $covered / $total, 2) }
    whole_project = Get-LineCounter $coverage.report
    minimum_per_target_percent = 80
    cli_evidence = 'docs/evidence/cli-verification.txt'
}
$destination = Join-Path $projectRoot 'docs/evidence'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
[IO.File]::WriteAllText((Join-Path $destination 'verification.json'), ($result | ConvertTo-Json -Depth 7), [Text.UTF8Encoding]::new($false))
$result | ConvertTo-Json -Depth 7
