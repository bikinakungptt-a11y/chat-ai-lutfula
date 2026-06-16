const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function sendJson(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-cache", ...corsHeaders });
  res.end(JSON.stringify(body));
}

function envValue(parts) {
  return String(process.env[parts.join("_")] || "").trim();
}

async function fetchJson(url) {
  const response = await fetch(url, { headers: { Accept: "application/json" } });
  const text = await response.text();
  try {
    return { ok: response.ok, status: response.status, data: text ? JSON.parse(text) : null };
  } catch {
    return { ok: response.ok, status: response.status, data: { message: text.slice(0, 1000) } };
  }
}

export default async function handler(req, res) {
  if (req.method === "OPTIONS") {
    res.writeHead(204, corsHeaders);
    res.end();
    return;
  }
  if (req.method !== "GET") return sendJson(res, 405, { success: false, error: "Method tidak didukung." });

  const lat = String(req.query?.lat || "").trim();
  const lon = String(req.query?.lon || req.query?.lng || "").trim();
  if (!lat || !lon) return sendJson(res, 400, { success: false, error: "Parameter lat dan lon wajib diisi." });

  const locationToken = envValue(["LOCATIONIQ", "API", "KEY"]);
  const mapboxToken = envValue(["MAPBOX", "ACCESS", "TOKEN"]) || envValue(["MAPBOX", "API", "KEY"]);
  if (!locationToken && !mapboxToken) return sendJson(res, 500, { success: false, error: "Credential maps belum disetel di Vercel." });

  const results = [];
  const errors = [];

  if (locationToken) {
    try {
      const url = new URL("https://us1.locationiq.com/v1/reverse");
      url.searchParams.set("key", locationToken);
      url.searchParams.set("lat", lat);
      url.searchParams.set("lon", lon);
      url.searchParams.set("format", "json");
      url.searchParams.set("addressdetails", "1");
      url.searchParams.set("normalizeaddress", "1");
      const response = await fetchJson(url.toString());
      if (response.ok && response.data) results.push({ provider: "locationiq", name: response.data.display_name || "", lat: Number(lat), lon: Number(lon) });
      else errors.push({ provider: "locationiq", status: response.status, details: response.data });
    } catch (error) {
      errors.push({ provider: "locationiq", message: error instanceof Error ? error.message : String(error) });
    }
  }

  if (mapboxToken) {
    try {
      const url = new URL(`https://api.mapbox.com/geocoding/v5/mapbox.places/${encodeURIComponent(`${lon},${lat}`)}.json`);
      url.searchParams.set("access_token", mapboxToken);
      url.searchParams.set("limit", "1");
      url.searchParams.set("language", "id");
      const response = await fetchJson(url.toString());
      if (response.ok && Array.isArray(response.data?.features)) {
        results.push(...response.data.features.map((feature) => ({ provider: "mapbox", name: feature.place_name || feature.text || "", lat: Number(lat), lon: Number(lon) })));
      } else {
        errors.push({ provider: "mapbox", status: response.status, details: response.data });
      }
    } catch (error) {
      errors.push({ provider: "mapbox", message: error instanceof Error ? error.message : String(error) });
    }
  }

  return sendJson(res, 200, { success: results.length > 0, lat: Number(lat), lon: Number(lon), count: results.length, results, errors });
}
