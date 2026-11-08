# Builds the debug APK (FOSS variant: no Sentry, no Google services).
# Java is pointed at the Windows certificate store so Gradle downloads work
# behind the office TLS inspection. The fixed signing key is read from
# C:\Users\bmoeini\AndroidTools\keys\simpmusic-dark.env when it exists, so
# local builds and CI builds carry the same signature.
param([string]$Task = ":androidApp:assembleDebug")

$env:JAVA_HOME = "C:\Users\bmoeini\AndroidTools\jdk-21"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$trust = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
$env:GRADLE_OPTS = $trust

$keyEnv = "C:\Users\bmoeini\AndroidTools\keys\simpmusic-dark.env"
if (Test-Path $keyEnv) {
    Get-Content $keyEnv | ForEach-Object {
        if ($_ -match '^\s*([A-Z_]+)=(.*)$') { Set-Item -Path "env:$($Matches[1])" -Value $Matches[2] }
    }
}

Set-Location $PSScriptRoot
.\gradlew.bat $Task -PisFullBuild=false --console=plain "-Dorg.gradle.jvmargs=-Xmx5120m -XX:+UseParallelGC $trust"
exit $LASTEXITCODE
