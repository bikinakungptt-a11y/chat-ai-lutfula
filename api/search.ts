import { getRequestValue, searchWithSerpApi, sendJson } from "./_utils";

export default async function handler(req: any, res: any) {
  if (!["GET", "POST"].includes(req.method)) {
    return sendJson(res, 405, { success: false, ok: false, error: "Method not allowed" });
  }

  try {
    const query = getRequestValue(req, "q") || getRequestValue(req, "query");
    if (!query) {
      return sendJson(res, 400, { success: false, ok: false, error: "Query kosong. Kirim ?q=... atau body { query }" });
    }

    const result = await searchWithSerpApi(query);
    return sendJson(res, 200, {
      success: true,
      ok: true,
      source: result.source,
      query: result.query,
      data: result.data,
      results: result.data,
    });
  } catch (error: any) {
    return sendJson(res, 500, {
      success: false,
      ok: false,
      source: "serpapi",
      error: error?.message || "Search gagal",
    });
  }
}
