# build.ps1 · Genera Arquitectura-BancaInternet-BP.pdf desde el Markdown maestro
#
# Cadena: pandoc 2.12 (HTML autocontenido + TOC) -> recoloca el índice tras la portada
#         -> Chrome headless --print-to-pdf
#
# Uso (desde cualquier carpeta):
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs\pdf\build.ps1   (desde la raíz del repositorio)
#
# Notas:
# - Pandoc se ejecuta desde docs/ para que las rutas ../diagrams/png/... resuelvan.
# - Se usa --metadata pagetitle (no title) para no duplicar el título: la portada va en el Markdown.
# - Las secuencias van como PNG (pandoc 2.12 no renderiza mermaid).

$ErrorActionPreference = "Stop"

$pdfDir = $PSScriptRoot                                   # ...\docs\pdf
$docs   = Split-Path -Parent $pdfDir                      # ...\docs
$mdName = "Arquitectura-BancaInternet-BP.md"
$htmlRel = "pdf/Arquitectura-BancaInternet-BP.html"
$html   = Join-Path $docs "pdf\Arquitectura-BancaInternet-BP.html"
$pdf    = Join-Path $docs "Arquitectura-BancaInternet-BP.pdf"
$md     = Join-Path $docs $mdName

# --- 1. Localizar herramientas ---------------------------------------------
$pandoc = (Get-Command pandoc -ErrorAction SilentlyContinue).Source
if (-not $pandoc) { $pandoc = Join-Path $env:USERPROFILE "anaconda3\Scripts\pandoc.exe" }
if (-not (Test-Path -LiteralPath $pandoc)) { throw "No se encontró pandoc." }

$chromeCandidatos = @(
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
    "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
    "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
)
$chrome = $chromeCandidatos | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (-not $chrome) { throw "No se encontró chrome.exe en Program Files ni en LocalAppData." }

Write-Host "pandoc : $pandoc"
Write-Host "chrome : $chrome"

# --- 2. Verificar figuras referenciadas --------------------------------------
$texto = [IO.File]::ReadAllText($md, [Text.Encoding]::UTF8)
$rutas = [regex]::Matches($texto, '\]\((\.\./diagrams/png/[^)\s]+)\)') |
         ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
$faltan = @()
foreach ($r in $rutas) {
    $abs = Join-Path $docs ($r -replace '/', '\')
    if (-not (Test-Path -LiteralPath $abs)) { $faltan += $r }
}
Write-Host ("figuras referenciadas: {0} | faltantes: {1}" -f $rutas.Count, $faltan.Count)
foreach ($f in $faltan) { Write-Host "  FALTA: $f" }

# Para cada figura que aún no existe se genera un marcador visible (no se escribe en diagrams/).
# Cuando el PNG real exista, la siguiente ejecución lo usa directamente.
$dirFalt = Join-Path $pdfDir "faltantes"
if (-not (Test-Path -LiteralPath $dirFalt)) { New-Item -ItemType Directory -Path $dirFalt | Out-Null }
Add-Type -AssemblyName System.Drawing
$mdTmpRel = "pdf/_build-tmp.md"
$textoTmp = $texto
foreach ($f in $faltan) {
    $nombre = Split-Path $f -Leaf
    $png = Join-Path $dirFalt $nombre
    $bmp = New-Object System.Drawing.Bitmap 1600, 900
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::FromArgb(255, 247, 248, 250))
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 154, 169, 188)), 6
    $pen.DashStyle = [System.Drawing.Drawing2D.DashStyle]::Dash
    $g.DrawRectangle($pen, 30, 30, 1540, 840)
    $f1 = New-Object System.Drawing.Font("Segoe UI", 64, [System.Drawing.FontStyle]::Bold)
    $f2 = New-Object System.Drawing.Font("Segoe UI", 40)
    $brush = [System.Drawing.Brushes]::DimGray
    $g.DrawString("FIGURA PENDIENTE DE GENERAR", $f1, $brush, 110, 340)
    $g.DrawString($nombre, $f2, $brush, 110, 470)
    $g.Dispose(); $bmp.Save($png, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
    $textoTmp = $textoTmp.Replace("](" + $f + ")", "](pdf/faltantes/" + $nombre + ")")
}
[IO.File]::WriteAllText((Join-Path $docs ($mdTmpRel -replace '/', '\')), $textoTmp, (New-Object Text.UTF8Encoding($false)))

# --- 3. Pandoc -> HTML autocontenido con índice -------------------------------
Push-Location $docs
try {
    $pargs = @(
        $mdTmpRel,
        "-f", "markdown",
        "-t", "html5",
        "-s", "--self-contained",
        "--toc", "--toc-depth=2",
        "-c", "pdf/estilo.css",
        "--metadata", "pagetitle=Arquitectura de la banca por internet BP",
        "-o", $htmlRel
    )
    & $pandoc @pargs
    if ($LASTEXITCODE -ne 0) { throw "pandoc terminó con código $LASTEXITCODE" }
}
finally {
    Pop-Location
    $tmpAbs = Join-Path $docs ($mdTmpRel -replace '/', '\')
    if (Test-Path -LiteralPath $tmpAbs) { Remove-Item -LiteralPath $tmpAbs -Force }
}

# --- 4. Recolocar el índice después de la portada ----------------------------
$h = [IO.File]::ReadAllText($html, [Text.Encoding]::UTF8)
$nav = [regex]::Match($h, '<nav id="TOC".*?</nav>', 'Singleline')
if ($nav.Success) {
    $toc = $nav.Value
    $h = $h.Remove($nav.Index, $nav.Length)
    $marca = '<!-- fin-portada -->'
    $pos = $h.IndexOf($marca)
    if ($pos -ge 0) {
        $bloque = "<div class=`"indice`"><h2>&Iacute;ndice</h2>$toc</div>"
        $h = $h.Insert($pos + $marca.Length, $bloque)
    }
    else {
        Write-Warning "No se encontró la marca de portada; el índice queda al inicio."
        $h = $h.Insert(0, "<div class=`"indice`"><h2>&Iacute;ndice</h2>$toc</div>")
    }
}
else {
    Write-Warning "pandoc no generó índice (TOC)."
}
[IO.File]::WriteAllText($html, $h, (New-Object Text.UTF8Encoding($false)))
Write-Host ("HTML: {0} ({1:N0} KB)" -f $html, ((Get-Item $html).Length / 1KB))

# --- 5. Chrome headless -> PDF ----------------------------------------------
if (Test-Path -LiteralPath $pdf) { Remove-Item -LiteralPath $pdf -Force }
# Chrome escribe primero en una ruta temporal sin espacios (el paso de argumentos con espacios
# falla en Windows PowerShell 5.1); luego se copia al destino del repositorio.
$perfil = Join-Path $env:TEMP "chrome-perfil-bp-pdf"
$tmpPdf = Join-Path $env:TEMP ("bp-build-" + [guid]::NewGuid().ToString("N") + ".pdf")
$uri = ([System.Uri]::new($html)).AbsoluteUri
$eapPrev = $ErrorActionPreference
$ErrorActionPreference = "Continue"   # Chrome escribe avisos de actualizador en stderr; no son errores
& $chrome --headless=new --no-pdf-header-footer --disable-gpu --no-sandbox --no-first-run "--user-data-dir=$perfil" --virtual-time-budget=15000 "--print-to-pdf=$tmpPdf" $uri 2>$null | Out-Null
$ErrorActionPreference = $eapPrev
if (-not (Test-Path -LiteralPath $tmpPdf)) { throw "Chrome no generó el PDF." }
Copy-Item -LiteralPath $tmpPdf -Destination $pdf -Force
Remove-Item -LiteralPath $tmpPdf -Force

# --- 6. Resumen -------------------------------------------------------------
$bytes = [IO.File]::ReadAllBytes($pdf)
$latin = [Text.Encoding]::GetEncoding(28591).GetString($bytes)
$paginas = ([regex]::Matches($latin, '/Type\s*/Page[^s]')).Count
Write-Host ("PDF: {0} ({1:N0} KB, {2} páginas)" -f $pdf, ($bytes.Length / 1KB), $paginas)
