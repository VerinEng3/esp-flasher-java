# Monta o Python portátil com o esptool que vai dentro do instalador.
#
#   powershell -ExecutionPolicy Bypass -File installer\prepare-python.ps1 [-HostPython python]
#
# Resultado: target\python-embed\python\  (python.exe + Lib\site-packages\esptool...)
#
# - Python oficial "embeddable" do python.org, com hash SHA-256 conferido.
# - Pacotes de installer\python\requirements.txt, todos com hash fixo.
# - O Python embutido ignora PATH, PYTHONPATH e site-packages do sistema:
#   nada do que estiver instalado na máquina interfere, e nada é alterado nela.
#
# O Python usado para instalar os pacotes (-HostPython) precisa ser 3.13 64-bit,
# a mesma versão do embutido, para os pacotes binários serem compatíveis.

param(
    [string]$HostPython = "python"
)

$ErrorActionPreference = "Stop"

$PythonVersion = "3.13.13"
$EmbedSha256   = "8766a8775746235e23cf5aee5027ab1060bb981d93110577adcf3508aa0cbd55"
$EsptoolVersion = "5.6.0"

$Root      = Split-Path -Parent $PSScriptRoot
$Cache     = Join-Path $Root "target\cache"
$OutDir    = Join-Path $Root "target\python-embed\python"
$Zip       = Join-Path $Cache "python-$PythonVersion-embed-amd64.zip"
$Reqs      = Join-Path $PSScriptRoot "python\requirements.txt"
$Url       = "https://www.python.org/ftp/python/$PythonVersion/python-$PythonVersion-embed-amd64.zip"

# 1. Python do host precisa ser 3.13 64-bit
$hostVer = & $HostPython -c "import sys,struct;print('%d.%d %d' % (sys.version_info[0], sys.version_info[1], struct.calcsize('P')*8))"
if ($hostVer -ne "3.13 64") { throw "HostPython precisa ser Python 3.13 64-bit (encontrado: $hostVer)" }

# 2. Baixa (ou reaproveita do cache) e confere o hash
New-Item -ItemType Directory -Force $Cache | Out-Null
if (-not (Test-Path $Zip) -or (Get-FileHash $Zip -Algorithm SHA256).Hash.ToLower() -ne $EmbedSha256) {
    Write-Host "Baixando $Url"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $Url -OutFile $Zip -UseBasicParsing
}
$actual = (Get-FileHash $Zip -Algorithm SHA256).Hash.ToLower()
if ($actual -ne $EmbedSha256) { throw "Hash do Python embutido não confere: $actual" }

# 3. Extrai do zero
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force $OutDir | Out-Null
Expand-Archive -Path $Zip -DestinationPath $OutDir

$sig = Get-AuthenticodeSignature (Join-Path $OutDir "python.exe")
if ($sig.Status -ne "Valid" -or $sig.SignerCertificate.Subject -notmatch "Python Software Foundation") {
    throw "Assinatura do python.exe inválida: $($sig.Status) $($sig.SignerCertificate.Subject)"
}

# 4. Habilita Lib\site-packages no Python embutido (arquivo ._pth)
$pth = Get-ChildItem $OutDir -Filter "python*._pth" | Select-Object -First 1
$lines = Get-Content $pth.FullName
if ($lines -notcontains "Lib\site-packages") {
    $lines = @($lines | Where-Object { $_ -ne "." }) + @("Lib\site-packages", ".")
    Set-Content -Path $pth.FullName -Value $lines -Encoding ascii
}

# 5. Instala o esptool e dependências, com hash conferido
$site = Join-Path $OutDir "Lib\site-packages"
& $HostPython -m pip install --disable-pip-version-check --no-warn-script-location `
    --no-deps --require-hashes --target $site -r $Reqs
if ($LASTEXITCODE -ne 0) { throw "pip install falhou ($LASTEXITCODE)" }

# Scripts gerados pelo pip apontam para o Python do host: não servem aqui
$bin = Join-Path $site "bin"
if (Test-Path $bin) { Remove-Item $bin -Recurse -Force }

# 6. Confere que o esptool embutido funciona sozinho
$py = Join-Path $OutDir "python.exe"
$out = & $py -m esptool version 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $out -notmatch [regex]::Escape("v$EsptoolVersion")) {
    throw "esptool embutido não respondeu como esperado:`n$out"
}

$sizeMb = [math]::Round(((Get-ChildItem $OutDir -Recurse | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "Python $PythonVersion + esptool $EsptoolVersion prontos em $OutDir ($sizeMb MB)"
