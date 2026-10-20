$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$signingDirectory = Join-Path $projectRoot '.signing'
$keyPath = Join-Path $signingDirectory 'baixador-release.jks'
$configPath = Join-Path $signingDirectory 'keystore.properties'
if ((Test-Path -LiteralPath $keyPath) -or (Test-Path -LiteralPath $configPath)) {
    throw 'A chave ou configuração já existe. Preserve-a para permitir atualizações do APK.'
}
New-Item -ItemType Directory -Force -Path $signingDirectory | Out-Null
$passwordBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($passwordBytes)
$rng.Dispose()
$env:BAIXADOR_SIGNING_PASSWORD = [Convert]::ToBase64String($passwordBytes)
try {
    & keytool -genkeypair -keystore $keyPath -storetype JKS `
        -alias baixador -keyalg RSA -keysize 3072 -validity 10000 `
        -storepass:env BAIXADOR_SIGNING_PASSWORD -keypass:env BAIXADOR_SIGNING_PASSWORD `
        -dname 'CN=Baixador, OU=Personal Android App' -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao gerar a chave.' }
    Set-Content -LiteralPath $configPath -Encoding ascii `
        -Value "storePassword=$env:BAIXADOR_SIGNING_PASSWORD"
    Write-Output 'Chave criada em .signing. Faça backup privado de toda essa pasta; ela não deve ir para o GitHub.'
} finally {
    Remove-Item Env:BAIXADOR_SIGNING_PASSWORD
}
