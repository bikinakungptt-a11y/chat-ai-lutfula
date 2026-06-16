import { getRequestValue, isSafeHttpUrl, normalizeUrl, scrapeWithBrowserless, sendJson } from "./_utils";

export default async function handler(req: any, res: any) {
  if (req.method !== "POST") {
    return sendJson(res, 405, { success: false, ok: false, error: "Method not allowed. Gunakan POST." });
  }

  try {
    const url = normalizeUrl(getRequestValue(req, "url"));
    if (!isSafeHttpUrl(url)) {
      return sendJson(res, 400, { success: false, ok: false, error: "URL tidak valid atau tidak aman" });
    }

    const result = await scrapeWithBrowserless(url);
    return sendJson(res, 200, {
      success: true,
      ok: true,
      source: result.source,
      url,
      markdown: result.markdown,
      content: result.markdown,
      data: {
        markdown: result.markdown,
        metadata: {
          title: result.title,
          description: result.description,
          source: result.source,
        },
      },
    });
  } catch (error: any) {
    return sendJson(res, 500, {
      success: false,
      ok: false,
      source: "browserless",
      error: error?.message || "Browserless gagal membaca website",
    });
  }
}
