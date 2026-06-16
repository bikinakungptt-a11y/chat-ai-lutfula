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

function clean(value, fallback, max = 32) {
  const result = String(value || fallback).trim().replace(/[^a-zA-Z0-9,_-]/g, "").slice(0, max);
  return result || fallback;
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

  const apiKey = (process.env.METALS_API_KEY || "").trim();
  if (!apiKey) {
    sendJson(res, 500, {
      error: "METALS_API_KEY belum disetel di Vercel Environment Variables.",
    });
    return;
  }

  const currency = clean(req.query.currency, "USD", 12).toUpperCase();
  const unit = clean(req.query.unit, "toz", 12).toLowerCase();

  const target = new URL("https://api.metals.dev/v1/latest");
  target.searchParams.set("api_key", apiKey);
  target.searchParams.set("currency", currency);
  target.searchParams.set("unit", unit);

  try {
    const upstream = await fetch(target.toString(), {
      headers: { Accept: "application/json" },
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
        error: "Metals.Dev gagal mengambil harga logam.",
        provider: "metals.dev",
        status: upstream.status,
        details: data,
      });
      return;
    }

    sendJson(res, 200, {
      status: "success",
      provider: "metals.dev",
      currency,
      unit,
      generatedAt: new Date().toISOString(),
      data,
    });
  } catch (error) {
    sendJson(res, 502, {
      error: "Gagal menghubungi Metals.Dev.",
      message: error instanceof Error ? error.message : String(error),
    });
  }
}
