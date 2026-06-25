const ALIASES = {
  btc: 'bitcoin',
  bitcoin: 'bitcoin',
  eth: 'ethereum',
  ethereum: 'ethereum',
  sol: 'solana',
  solana: 'solana',
  bnb: 'binancecoin',
  binancecoin: 'binancecoin',
  xrp: 'ripple',
  ripple: 'ripple',
  doge: 'dogecoin',
  dogecoin: 'dogecoin',
  usdt: 'tether',
  tether: 'tether',
  ada: 'cardano',
  cardano: 'cardano',
  trx: 'tron',
  tron: 'tron',
  ton: 'the-open-network',
  toncoin: 'the-open-network',
  matic: 'matic-network',
  polygon: 'matic-network',
  shib: 'shiba-inu',
  shiba: 'shiba-inu',
  pepe: 'pepe'
};

const LABELS = {
  bitcoin: { name: 'Bitcoin', symbol: 'BTC' },
  ethereum: { name: 'Ethereum', symbol: 'ETH' },
  solana: { name: 'Solana', symbol: 'SOL' },
  binancecoin: { name: 'BNB', symbol: 'BNB' },
  ripple: { name: 'XRP', symbol: 'XRP' },
  dogecoin: { name: 'Dogecoin', symbol: 'DOGE' },
  tether: { name: 'Tether', symbol: 'USDT' },
  cardano: { name: 'Cardano', symbol: 'ADA' },
  tron: { name: 'TRON', symbol: 'TRX' },
  'the-open-network': { name: 'Toncoin', symbol: 'TON' },
  'matic-network': { name: 'Polygon', symbol: 'MATIC' },
  'shiba-inu': { name: 'Shiba Inu', symbol: 'SHIB' },
  pepe: { name: 'Pepe', symbol: 'PEPE' }
};

function escapeRegExp(input) {
  return String(input).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function detectCryptoIds(query, idsParam) {
  if (typeof idsParam === 'string' && idsParam.trim()) {
    return idsParam
      .split(',')
      .map((x) => x.trim().toLowerCase())
      .filter(Boolean)
      .slice(0, 8);
  }

  const q = String(query || '').toLowerCase();
  const ids = new Set();

  for (const [alias, id] of Object.entries(ALIASES)) {
    const re = new RegExp(`(^|[^a-z0-9])${escapeRegExp(alias)}([^a-z0-9]|$)`, 'i');
    if (re.test(q)) ids.add(id);
  }

  if (ids.size === 0 && /(crypto|kripto|cryptocurrency)/i.test(q)) {
    ids.add('bitcoin');
    ids.add('ethereum');
    ids.add('solana');
  }

  return Array.from(ids).slice(0, 8);
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'GET') return res.status(405).json({ error: 'Method not allowed' });

  const q = typeof req.query.q === 'string' ? req.query.q.trim() : '';
  const ids = detectCryptoIds(q, req.query.ids);
  if (!q && ids.length === 0) return res.status(400).json({ error: 'Missing query parameter q or ids' });
  if (ids.length === 0) return res.status(400).json({ error: 'No supported crypto keyword found', query: q });

  const demoKey = process.env.CRYPTO_API_KEY || process.env.COINGECKO_API_KEY || process.env.CG_DEMO_API_KEY;
  const proKey = process.env.COINGECKO_PRO_API_KEY || process.env.CG_PRO_API_KEY;
  if (!demoKey && !proKey) {
    return res.status(500).json({
      error: 'Crypto API key not set',
      message: 'Set CRYPTO_API_KEY or COINGECKO_API_KEY in Vercel Environment Variables.'
    });
  }

  const usePro = Boolean(proKey);
  const baseUrl = usePro ? 'https://pro-api.coingecko.com/api/v3' : 'https://api.coingecko.com/api/v3';
  const params = new URLSearchParams({
    ids: ids.join(','),
    vs_currencies: 'usd,idr',
    include_market_cap: 'true',
    include_24hr_vol: 'true',
    include_24hr_change: 'true',
    include_last_updated_at: 'true'
  });

  const headers = { accept: 'application/json' };
  if (usePro) headers['x-cg-pro-api-key'] = proKey;
  else headers['x-cg-demo-api-key'] = demoKey;

  try {
    const response = await fetch(`${baseUrl}/simple/price?${params.toString()}`, { headers });
    const text = await response.text();
    let json;
    try { json = JSON.parse(text); } catch (_) { json = { raw: text }; }

    if (!response.ok) {
      return res.status(response.status).json({ error: 'Crypto provider failed', status: response.status, details: json });
    }

    const data = ids
      .filter((id) => json && json[id])
      .map((id) => {
        const item = json[id] || {};
        const label = LABELS[id] || { name: id, symbol: id };
        return {
          id,
          name: label.name,
          symbol: label.symbol,
          usd: item.usd ?? null,
          idr: item.idr ?? null,
          usd_24h_change: item.usd_24h_change ?? null,
          usd_market_cap: item.usd_market_cap ?? null,
          usd_24h_vol: item.usd_24h_vol ?? null,
          last_updated_at: item.last_updated_at ?? null
        };
      });

    return res.status(200).json({
      query: q,
      ids,
      source: 'CoinGecko',
      uses_key: true,
      data
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Crypto realtime failed',
      message: error instanceof Error ? error.message : String(error)
    });
  }
}
