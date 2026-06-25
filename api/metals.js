const METALS = {
  xau: { symbol: 'XAU', name: 'Gold' },
  gold: { symbol: 'XAU', name: 'Gold' },
  emas: { symbol: 'XAU', name: 'Gold' },
  xauusd: { symbol: 'XAU', name: 'Gold' },
  xag: { symbol: 'XAG', name: 'Silver' },
  silver: { symbol: 'XAG', name: 'Silver' },
  perak: { symbol: 'XAG', name: 'Silver' },
  xagusd: { symbol: 'XAG', name: 'Silver' },
  xpt: { symbol: 'XPT', name: 'Platinum' },
  platinum: { symbol: 'XPT', name: 'Platinum' },
  xptusd: { symbol: 'XPT', name: 'Platinum' }
};

function escapeRegExp(input) {
  return String(input).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function detectSymbols(query, symbolsParam) {
  if (typeof symbolsParam === 'string' && symbolsParam.trim()) {
    return symbolsParam
      .split(',')
      .map((x) => x.trim().toUpperCase())
      .filter((x) => ['XAU', 'XAG', 'XPT'].includes(x))
      .slice(0, 3);
  }

  const q = String(query || '').toLowerCase();
  const symbols = new Set();
  for (const [alias, item] of Object.entries(METALS)) {
    const re = new RegExp(`(^|[^a-z0-9])${escapeRegExp(alias)}([^a-z0-9]|$)`, 'i');
    if (re.test(q)) symbols.add(item.symbol);
  }
  return Array.from(symbols).slice(0, 3);
}

function priceFromUsdBase(rate) {
  const n = Number(rate);
  if (!Number.isFinite(n) || n <= 0) return null;
  return 1 / n;
}

function nameForSymbol(symbol) {
  if (symbol === 'XAU') return 'Gold';
  if (symbol === 'XAG') return 'Silver';
  if (symbol === 'XPT') return 'Platinum';
  return symbol;
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'GET') return res.status(405).json({ error: 'Method not allowed' });

  const q = typeof req.query.q === 'string' ? req.query.q.trim() : '';
  const symbols = detectSymbols(q, req.query.symbols);
  if (!q && symbols.length === 0) return res.status(400).json({ error: 'Missing query parameter q or symbols' });
  if (symbols.length === 0) return res.status(400).json({ error: 'No supported metals keyword found', query: q });

  const apiKey = process.env.METALS_API_KEY;
  if (!apiKey) {
    return res.status(500).json({
      error: 'Metals API key not set',
      message: 'Set METALS_API_KEY in Vercel Environment Variables.'
    });
  }

  const baseUrl = process.env.METALS_API_URL || 'https://api.metals-api.com/v1/latest';
  const params = new URLSearchParams({
    access_key: apiKey,
    base: 'USD',
    symbols: symbols.join(',')
  });

  try {
    const response = await fetch(`${baseUrl}?${params.toString()}`, { headers: { accept: 'application/json' } });
    const text = await response.text();
    let json;
    try { json = JSON.parse(text); } catch (_) { json = { raw: text }; }

    if (!response.ok || json.success === false) {
      return res.status(response.ok ? 502 : response.status).json({
        error: 'Metals provider failed',
        status: response.status,
        details: json
      });
    }

    const rates = json.rates || json.data || {};
    const data = symbols.map((symbol) => {
      const rawRate = rates[symbol] ?? rates[symbol.toLowerCase()] ?? json[symbol] ?? json[symbol.toLowerCase()];
      const usdPerOunce = priceFromUsdBase(rawRate);
      return {
        symbol,
        name: nameForSymbol(symbol),
        usd_per_troy_ounce: usdPerOunce,
        provider_rate: rawRate ?? null
      };
    });

    return res.status(200).json({
      query: q,
      source: 'Metals API',
      base: 'USD',
      unit: 'troy_ounce',
      data
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Metals realtime failed',
      message: error instanceof Error ? error.message : String(error)
    });
  }
}
