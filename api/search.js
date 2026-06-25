function cleanText(input) {
  return String(input || '')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/\s+/g, ' ')
    .trim();
}

function shortText(input, max = 420) {
  const text = cleanText(input);
  if (text.length <= max) return text;
  return text.slice(0, max).replace(/\s+\S*$/, '') + '...';
}

function rootUrl(input) {
  try {
    const u = new URL(String(input || '').trim());
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return '';
    return u.origin;
  } catch (_) {
    return '';
  }
}

async function readPageWithBrowserless(pageUrl) {
  const tokenName = 'BROWSERLESS' + '_TOKEN';
  const token = process.env[tokenName];
  if (!token || !pageUrl) return '';

  const base = process.env.BROWSERLESS_URL || 'https://chrome.browserless.io/content';
  const joiner = base.includes('?') ? '&' : '?';
  const endpoint = base + joiner + 'token=' + encodeURIComponent(token);

  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      url: pageUrl,
      gotoOptions: {
        waitUntil: 'networkidle2',
        timeout: 15000
      }
    })
  });

  if (!response.ok) return '';
  const html = await response.text();
  return shortText(html, 900);
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'GET') return res.status(405).json({ error: 'Method not allowed' });

  const q = typeof req.query.q === 'string' ? req.query.q.trim() : '';
  const mode = typeof req.query.mode === 'string' ? req.query.mode.trim().toLowerCase() : 'cari';
  const targetRoot = rootUrl(req.query.url);
  if (!q && !targetRoot) return res.status(400).json({ error: 'Missing query parameter q or url' });

  const envName = 'FIRECRAWL' + '_API_KEY';
  const token = process.env[envName];
  if (!token) return res.status(500).json({ error: envName + ' not set' });

  try {
    if (targetRoot) {
      const pageText = await readPageWithBrowserless(targetRoot);
      if (pageText) {
        return res.status(200).json({
          query: q || targetRoot,
          mode: 'website',
          url: targetRoot,
          data: [{
            title: targetRoot,
            description: pageText,
            url: targetRoot,
            reader: 'browserless'
          }]
        });
      }
    }

    const isBeritaMode = mode === 'berita' || mode === 'news';
    const searchLimit = isBeritaMode ? 20 : 5;
    const url = 'https://' + ['api', 'firecrawl', 'dev'].join('.') + '/v1/search';
    const h = {};
    h['Content-Type'] = 'application/json';
    h[['Authori', 'zation'].join('')] = ['Bearer', token].join(' ');

    const searchBody = { query: targetRoot || q, limit: searchLimit };
    if (isBeritaMode) searchBody.tbs = 'sbd:1,qdr:d';

    const r = await fetch(url, {
      method: 'POST',
      headers: h,
      body: JSON.stringify(searchBody)
    });

    const t = await r.text();
    let j;
    try { j = JSON.parse(t); } catch (_) { j = { raw: t }; }

    if (!r.ok) return res.status(r.status).json({ error: 'Search provider failed', status: r.status, details: j });

    const rows = Array.isArray(j.data) ? j.data : (Array.isArray(j.results) ? j.results : []);
    const data = [];

    for (let i = 0; i < rows.length; i++) {
      const x = rows[i] || {};
      const pageUrl = x.url || x.sourceURL || x.metadata?.sourceURL || '';
      let description = x.description || x.snippet || x.content || x.markdown || '';
      let reader = 'firecrawl';

      if (i < 3 && pageUrl && cleanText(description).length < 120) {
        try {
          const pageText = await readPageWithBrowserless(pageUrl);
          if (pageText && pageText.length > cleanText(description).length) {
            description = pageText;
            reader = 'browserless';
          }
        } catch (_) {}
      }

      data.push({
        title: x.title || x.metadata?.title || 'No Title',
        description: shortText(description, 420),
        url: pageUrl,
        reader
      });
    }

    return res.status(200).json({ query: targetRoot || q, mode, limit: searchLimit, todayOnly: isBeritaMode, data });
  } catch (e) {
    return res.status(500).json({ error: 'Realtime search failed', message: e instanceof Error ? e.message : String(e) });
  }
}
