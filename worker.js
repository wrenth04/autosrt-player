addEventListener('fetch', event => {
  event.respondWith(handleRequest(event.request))
})

async function handleRequest(request) {
  const config = getConfig()
  if (!isValidHttpUrl(config.rawPrefix)) {
    return corsResponse('RAW_PREFIX is not configured correctly', 500)
  }

  if (request.method === 'OPTIONS') {
    return corsResponse('', 204)
  }

  const url = new URL(request.url)
  const path = url.pathname.replace(/^\/+/, '')

  if (!path) {
    return corsResponse('Missing path', 404)
  }

  const targetURL = new URL(path, `${normalizePrefix(config.rawPrefix)}/`)
  const headers = new Headers(request.headers)

  if (config.ghToken && shouldAttachGitHubToken(targetURL)) {
    headers.set('Authorization', `Bearer ${config.ghToken}`)
  }

  const init = {
    method: request.method,
    headers
  }

  if (request.method !== 'GET' && request.method !== 'HEAD') {
    init.body = request.body
  }

  const response = await fetch(targetURL.toString(), init)
  const responseHeaders = new Headers(response.headers)

  responseHeaders.delete('Content-Security-Policy')
  responseHeaders.set('Access-Control-Allow-Origin', '*')
  responseHeaders.set('Access-Control-Allow-Methods', '*')
  responseHeaders.set('Access-Control-Allow-Headers', '*')

  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers: responseHeaders
  })
}

function getConfig() {
  return {
    rawPrefix: readBinding('RAW_PREFIX') || '',
    ghToken: readBinding('GH_TOKEN') || ''
  }
}

function readBinding(name) {
  if (typeof globalThis !== 'undefined' && globalThis[name] != null) {
    return String(globalThis[name])
  }

  if (typeof process !== 'undefined' && process.env && process.env[name] != null) {
    return String(process.env[name])
  }

  return ''
}

function normalizePrefix(prefix) {
  return String(prefix).trim().replace(/\/+$/, '')
}

function isValidHttpUrl(value) {
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

function shouldAttachGitHubToken(targetURL) {
  return targetURL.hostname === 'raw.githubusercontent.com' || targetURL.hostname.endsWith('.githubusercontent.com')
}

function corsResponse(body, status) {
  return new Response(body, {
    status,
    headers: {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': '*',
      'Access-Control-Allow-Headers': '*',
      'Content-Type': 'text/plain; charset=utf-8'
    }
  })
}
