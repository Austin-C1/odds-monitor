param(
    [string]$Root = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')).Path 'frontend\dist'),
    [string]$ListenHost = '127.0.0.1',
    [int]$Port = 18881,
    [string]$BackendUrl = 'http://127.0.0.1:18000'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$resolvedRoot = [System.IO.Path]::GetFullPath($Root)
$backendBase = $BackendUrl.TrimEnd('/')

if (-not (Test-Path $resolvedRoot -PathType Container)) {
    throw "Frontend root not found: $resolvedRoot"
}

function Get-ContentType {
    param(
        [string]$FilePath
    )

    switch ([System.IO.Path]::GetExtension($FilePath).ToLowerInvariant()) {
        '.css' { return 'text/css; charset=utf-8' }
        '.gif' { return 'image/gif' }
        '.html' { return 'text/html; charset=utf-8' }
        '.ico' { return 'image/x-icon' }
        '.jpg' { return 'image/jpeg' }
        '.jpeg' { return 'image/jpeg' }
        '.js' { return 'text/javascript; charset=utf-8' }
        '.json' { return 'application/json; charset=utf-8' }
        '.map' { return 'application/json; charset=utf-8' }
        '.png' { return 'image/png' }
        '.svg' { return 'image/svg+xml' }
        '.txt' { return 'text/plain; charset=utf-8' }
        '.webp' { return 'image/webp' }
        '.woff' { return 'font/woff' }
        '.woff2' { return 'font/woff2' }
        default { return 'application/octet-stream' }
    }
}

function Test-IsHtmlRoute {
    param(
        [string]$RequestPath
    )

    return [string]::IsNullOrEmpty([System.IO.Path]::GetExtension($RequestPath)) `
        -and -not $RequestPath.StartsWith('/api', [System.StringComparison]::OrdinalIgnoreCase) `
        -and -not $RequestPath.StartsWith('/ws', [System.StringComparison]::OrdinalIgnoreCase)
}

function Resolve-FrontendPath {
    param(
        [string]$RequestPath
    )

    $rawPath = if ([string]::IsNullOrWhiteSpace($RequestPath)) { '/' } else { $RequestPath.Split('?')[0] }
    $decodedPath = [System.Uri]::UnescapeDataString($rawPath)
    $relativePath = if ($decodedPath -eq '/') { 'index.html' } else { $decodedPath.TrimStart('/').Replace('/', '\') }
    $candidatePath = [System.IO.Path]::GetFullPath((Join-Path $resolvedRoot $relativePath))

    if (-not $candidatePath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $null
    }

    if (Test-Path $candidatePath -PathType Container) {
        $candidatePath = Join-Path $candidatePath 'index.html'
    }

    if (Test-Path $candidatePath -PathType Leaf) {
        return $candidatePath
    }

    if (Test-IsHtmlRoute -RequestPath $decodedPath) {
        return (Join-Path $resolvedRoot 'index.html')
    }

    return $null
}

function Copy-ResponseHeaders {
    param(
        [System.Net.Http.HttpResponseMessage]$BackendResponse,
        [System.Net.HttpListenerResponse]$ClientResponse
    )

    foreach ($header in $BackendResponse.Headers.GetEnumerator()) {
        if ($header.Key -in @('Transfer-Encoding', 'Connection', 'Keep-Alive')) {
            continue
        }
        $ClientResponse.Headers[$header.Key] = ($header.Value -join ', ')
    }

    foreach ($header in $BackendResponse.Content.Headers.GetEnumerator()) {
        if ($header.Key -in @('Content-Length', 'Content-Type')) {
            continue
        }
        $ClientResponse.Headers[$header.Key] = ($header.Value -join ', ')
    }
}

function Invoke-BackendProxy {
    param(
        [System.Net.HttpListenerContext]$Context,
        [System.Net.Http.HttpClient]$HttpClient
    )

    $request = $Context.Request
    $response = $Context.Response
    $targetUri = [System.Uri]::new("$backendBase$($request.RawUrl)")
    $backendRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($request.HttpMethod), $targetUri)

    foreach ($headerName in $request.Headers.AllKeys) {
        if ($headerName -in @('Host', 'Content-Length', 'Connection', 'Expect', 'Proxy-Connection')) {
            continue
        }
        [void]$backendRequest.Headers.TryAddWithoutValidation($headerName, $request.Headers[$headerName])
    }

    if ($request.HasEntityBody) {
        $bodyBytes = [byte[]]::new($request.ContentLength64)
        $offset = 0
        while ($offset -lt $bodyBytes.Length) {
            $read = $request.InputStream.Read($bodyBytes, $offset, $bodyBytes.Length - $offset)
            if ($read -le 0) { break }
            $offset += $read
        }
        $backendRequest.Content = [System.Net.Http.ByteArrayContent]::new($bodyBytes)
        if (-not [string]::IsNullOrWhiteSpace($request.ContentType)) {
            $backendRequest.Content.Headers.TryAddWithoutValidation('Content-Type', $request.ContentType) | Out-Null
        }
    }

    $backendResponse = $HttpClient.SendAsync($backendRequest).GetAwaiter().GetResult()
    $bytes = $backendResponse.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()

    $response.StatusCode = [int]$backendResponse.StatusCode
    if ($backendResponse.Content.Headers.ContentType) {
        $response.ContentType = $backendResponse.Content.Headers.ContentType.ToString()
    }
    Copy-ResponseHeaders -BackendResponse $backendResponse -ClientResponse $response
    $response.ContentLength64 = $bytes.Length
    if ($request.HttpMethod -ne 'HEAD') {
        $response.OutputStream.Write($bytes, 0, $bytes.Length)
    }
}

$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://$ListenHost`:$Port/")
$listener.Start()
$httpClient = [System.Net.Http.HttpClient]::new()

Write-Output "OddsMonitor frontend server listening at http://$ListenHost`:$Port/"

try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $response = $context.Response

        try {
            $method = $context.Request.HttpMethod
            if ($context.Request.RawUrl.StartsWith('/api', [System.StringComparison]::OrdinalIgnoreCase)) {
                Invoke-BackendProxy -Context $context -HttpClient $httpClient
                continue
            }

            if ($method -ne 'GET' -and $method -ne 'HEAD') {
                $body = [System.Text.Encoding]::UTF8.GetBytes('Method Not Allowed')
                $response.StatusCode = 405
                $response.ContentType = 'text/plain; charset=utf-8'
                $response.ContentLength64 = $body.Length
                if ($method -ne 'HEAD') {
                    $response.OutputStream.Write($body, 0, $body.Length)
                }
                continue
            }

            $filePath = Resolve-FrontendPath -RequestPath $context.Request.RawUrl
            if (-not $filePath) {
                $body = [System.Text.Encoding]::UTF8.GetBytes('Not Found')
                $response.StatusCode = 404
                $response.ContentType = 'text/plain; charset=utf-8'
                $response.ContentLength64 = $body.Length
                if ($method -ne 'HEAD') {
                    $response.OutputStream.Write($body, 0, $body.Length)
                }
                continue
            }

            $bytes = [System.IO.File]::ReadAllBytes($filePath)
            $response.StatusCode = 200
            $response.ContentType = Get-ContentType -FilePath $filePath
            $response.ContentLength64 = $bytes.Length
            $response.Headers['Cache-Control'] = 'no-store, no-cache, must-revalidate, max-age=0'
            $response.Headers['Pragma'] = 'no-cache'
            $response.Headers['Expires'] = '0'

            if ($method -ne 'HEAD') {
                $response.OutputStream.Write($bytes, 0, $bytes.Length)
            }
        }
        catch {
            Write-Warning $_
            try {
                $body = [System.Text.Encoding]::UTF8.GetBytes('Internal Server Error')
                $response.StatusCode = 500
                $response.ContentType = 'text/plain; charset=utf-8'
                $response.ContentLength64 = $body.Length
                $response.OutputStream.Write($body, 0, $body.Length)
            }
            catch {
                Write-Warning $_
            }
        }
        finally {
            try {
                $response.OutputStream.Close()
            }
            catch {
            }
            try {
                $response.Close()
            }
            catch {
            }
        }
    }
}
finally {
    $httpClient.Dispose()
    $listener.Stop()
    $listener.Close()
}

