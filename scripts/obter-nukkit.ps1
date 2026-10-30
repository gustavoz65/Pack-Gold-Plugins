# Baixa o core validado do repositorio Nukkit-Gold-Edition para lib\nukkit-gold.jar (usado so para compilar).
param([string]$Ref = 'main')
$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$base = "https://raw.githubusercontent.com/gustavoz65/Nukkit-Gold-Edition/$Ref/builds"
$temp = Join-Path ([IO.Path]::GetTempPath()) ('nukkit-' + [Guid]::NewGuid())
Invoke-WebRequest "$base/nukkit-gold-0.15.10.jar" -OutFile $temp -UseBasicParsing
$linha = (Invoke-WebRequest "$base/SHA256SUMS" -UseBasicParsing).Content -split "`n" | Where-Object { $_ -match '\s+nukkit-gold-0\.15\.10\.jar\s*$' }
$esperado = ($linha -split '\s+')[0]
$obtido = (Get-FileHash $temp -Algorithm SHA256).Hash.ToLower()
if (!$esperado -or $esperado -ne $obtido) { Remove-Item $temp; throw 'SHA-256 do core nao confere.' }
New-Item -ItemType Directory -Force (Join-Path $raiz 'lib') | Out-Null
Move-Item $temp (Join-Path $raiz 'lib\nukkit-gold.jar') -Force
Write-Host "Core salvo em lib\nukkit-gold.jar ($obtido)"
