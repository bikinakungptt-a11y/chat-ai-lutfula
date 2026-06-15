import { getEnv, handleOptions, isValidDate, json, safeError, todayJakartaDate } from './_utils.js';

export default async function handler(req, res) {
  if (handleOptions(req, res)) return;

  try {
    const date = String(req.query?.date || todayJakartaDate()).trim();
    const country = String(req.query?.country || 'ID').trim().toUpperCase();

    if (!isValidDate(date)) {
      return json(res, 400, { ok: false, error: 'Invalid date. Use YYYY-MM-DD.' });
    }

    const apiKey = getEnv('API_NINJAS_API_KEY');
    if (!apiKey) {
      return json(res, 500, { ok: false, error: 'API_NINJAS_API_KEY is not configured on backend.' });
    }

    const endpoint = `https://api.api-ninjas.com/v1/isworkingday?country=${encodeURIComponent(country)}&date=${encodeURIComponent(date)}`;
    const upstream = await fetch(endpoint, {
      headers: { 'X-Api-Key': apiKey }
    });

    const text = await upstream.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = { raw: text.slice(0, 1000) };
    }

    if (!upstream.ok) {
      return json(res, upstream.status, {
        ok: false,
        error: 'API Ninjas isworkingday failed.',
        status: upstream.status,
        details: data
      });
    }

    json(res, 200, {
      ok: true,
      date,
      country,
      isWorkingDay: Boolean(data?.is_working_day),
      raw: data
    });
  } catch (error) {
    json(res, 500, { ok: false, error: safeError(error) });
  }
}
