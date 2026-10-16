# Installs a portable JDK in this project only; does not change PATH or JAVA_HOME globally.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsDirectory = Join-Path $projectRoot '.tools'
$jdkDirectory = Join-Path $toolsDirectory 'jdk-17'
if (Test-Path -LiteralPath (Join-Path $jdkDirectory 'bin/javac.exe')) {
    Write-Host "Java 17 is already available at $jdkDirectory"
    exit 0
}
New-Item -ItemType Directory -Force -Path $toolsDirectory | Out-Null
$archive = Join-Path $toolsDirectory 'temurin-jdk17.zip'
$uri = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
$expectedSha256 = 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
Write-Host 'Downloading the official Eclipse Temurin 17 portable JDK (Windows x64)...'
Invoke-WebRequest -UseBasicParsing -Uri $uri -OutFile $archive
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedSha256) {
    throw 'JDK checksum mismatch. The archive has not been extracted.'
}
Expand-Archive -LiteralPath $archive -DestinationPath $toolsDirectory -Force
$extractedDirectory = Join-Path $toolsDirectory 'jdk-17.0.20.1+1'
$allowedRoot = [IO.Path]::GetFullPath($toolsDirectory) + [IO.Path]::DirectorySeparatorChar
foreach ($candidate in @($extractedDirectory, $jdkDirectory)) {
    if (-not [IO.Path]::GetFullPath($candidate).StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to move a JDK directory outside this project tool folder.'
    }
}
Move-Item -LiteralPath $extractedDirectory -Destination $jdkDirectory
Remove-Item -LiteralPath $archive
& (Join-Path $jdkDirectory 'bin/java.exe') -version
Write-Host 'Ready. Run .\mvnw.cmd verify or .\mvnw.cmd -q compile exec:java.'
