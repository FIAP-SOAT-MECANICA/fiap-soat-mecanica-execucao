param([int] $Port = 8099)
$ErrorActionPreference = 'Stop'
$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://localhost:$Port/")
$listener.Start()
Write-Host "Mock local do catálogo OS em http://localhost:$Port"
try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $path = $context.Request.Url.AbsolutePath
        if ($context.Request.HttpMethod -eq 'GET' -and $path -eq '/health') {
            $body = '{"status":"UP"}'
            $context.Response.StatusCode = 200
        } elseif ($context.Request.HttpMethod -eq 'GET' -and
                $path -match '^/servicos/[0-9a-fA-F-]{36}$') {
            $body = '{"nome":"Troca de filtro (mock)","statusRecursoEnum":"ATIVO"}'
            $context.Response.StatusCode = 200
        } else {
            $body = '{"erro":"Não encontrado"}'
            $context.Response.StatusCode = 404
        }
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
        $context.Response.ContentType = 'application/json; charset=utf-8'
        $context.Response.ContentLength64 = $bytes.Length
        $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
        $context.Response.Close()
    }
} finally {
    $listener.Close()
}
