const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function sendJson(res, status, body) {
  res.writeHead(status, {
    "Content-Type": "application/json",
    "Cache-Control": "no-cache",
    ...corsHeaders,
  });
  res.end(JSON.stringify(body));
}

export default async function handler(req, res) {
  if (req.method === "OPTIONS") {
    res.writeHead(204, corsHeaders);
    res.end();
    return;
  }

  if (req.method !== "GET") {
    sendJson(res, 405, { error: "Method tidak didukung." });
    return;
  }

  const apiKey = (process.env.COINGECKO_API_KEY || "").trim();
  if (!apiKey) {
    sendJson(res, 500, {
      error: "COINGECKO_API_KEY belum disetel di Vercel Environment Variables.",
    });
    return;
  }

  const ids = String(req.query.ids || "bitcoin").trim().slice(0, 256) || "bitcoin";
  const vsCurrencies = String(req.query.vs_currencies || req.query.vs || "usd,idr")
    .trim()
    .replace(/\s+/g, "")
    .slice(0, 256) || "usd,idr";

  const target = new URL("https://api.coingecko.com/api/v3/simple/price");
  target.searchParams.set("ids", ids);
  target.searchParams.set("vs_currencies", vsCurrencies);
  target.searchParams.set("include_market_cap", "true");
  target.searchParams.set("include_24hr_vol", "true");
  target.searchParams.set("include_24hr_change", "true");
  target.searchParams.set("include_last_updated_at", "true");

  try {
    const upstream = await fetch(target.toString(), {
      headers: {
        Accept: "application/json",
        "x-cg-demo-api-key": apiKey,
      },
    });

    const text = await upstream.text();
    let data;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = { message: text.slice(0, 1000) };
    }

    if (!upstream.ok) {
      sendJson(res, upstream.status, {
        error: "CoinGecko gagal mengambil harga crypto.",
        provider: "coingecko",
        status: upstream.status,
        details: data,
      });
      return;
    }

    sendJson(res, 200, {
      status: "success",
      provider: "coingecko",
      ids,
      vs_currencies: vsCurrencies,
      generatedAt: new Date().toISOString(),
      data,
    });
  } catch (error) {
    sendJson(res, 502, {
      error: "Gagal menghubungi CoinGecko.",
      message: error instanceof Error ? error.message : String(error),
    });
  }
}
