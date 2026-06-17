import { getRequestValue, sendJson } from "./_utils";

function pickNumber(...values: any[]): number {
  for (const value of values) {
    const num = Number(value);
    if (Number.isFinite(num) && num > 0) return num;
  }
  return 0;
}

function extractGoldPrice(json: any): number {
  const metals = json?.metals || json?.rates?.metals || json?.rates || {};
  return pickNumber(
    metals?.gold,
    metals?.Gold,
    metals?.XAU,
    metals?.xau,
    json?.gold,
    json?.XAU,
    json?.price
  );
}

export default async function handler(req: any, res: any) {
  if (!["GET", "POST"].includes(req.method)) {
    return sendJson(res, 405, { success: false, ok: false, error: "Method not allowed" });
  }

  try {
    const apiKey = process.env.METALS_API_KEY || process.env.METALS_DEV_API_KEY;
    if (!apiKey) {
      return sendJson(res, 500, {
        success: false,
        ok: false,
        source: "metals.dev",
        error: "METALS_API_KEY/METALS_DEV_API_KEY belum di-set di Vercel",
      });
    }

    const currency = (getRequestValue(req, "currency") || "USD").toUpperCase();
    const unit = getRequestValue(req, "unit") || "toz";

    const url = new URL("https://api.metals.dev/v1/latest");
    url.searchParams.set("api_key", apiKey);
    url.searchParams.set("currency", currency);
    url.searchParams.set("unit", unit);

    const response = await fetch(url.toString());
    const text = await response.text();

    let json: any = {};
    try {
      json = JSON.parse(text);
    } catch {
      json = {};
    }

    if (!response.ok) {
      return sendJson(res, response.status, {
        success: false,
        ok: false,
        source: "metals.dev",
        error: json?.error || json?.message || `Metals API gagal HTTP ${response.status}`,
      });
    }

    const price = extractGoldPrice(json);
    if (!price) {
      return sendJson(res, 502, {
        success: false,
        ok: false,
        source: "metals.dev",
        error: "Harga XAU tidak ditemukan di response Metals API",
      });
    }

    return sendJson(res, 200, {
      success: true,
      ok: true,
      source: "metals.dev via Vercel backend",
      symbol: "XAU",
      currency,
      unit,
      price,
      priceFormatted: price.toFixed(2),
    });
  } catch (error: any) {
    return sendJson(res, 500, {
      success: false,
      ok: false,
      source: "metals.dev",
      error: error?.message || "Metals backend gagal",
    });
  }
}
