import { getRequestValue, isSafeHttpUrl, normalizeUrl, scrapeWithBrowserless, scrapeWithFirecrawl, searchWithSerpApi, sendJson } from "./_utils";

const SOCIAL_HOSTS = [
  "instagram.com",
  "www.instagram.com",
  "tiktok.com",
  "www.tiktok.com",
  "x.com",
  "www.x.com",
  "twitter.com",
  "www.twitter.com",
  "facebook.com",
  "www.facebook.com",
  "youtube.com",
  "www.youtube.com",
  "youtu.be",
  "reddit.com",
  "www.reddit.com",
];

function getFirstPathSegment(inputUrl: string) {
  try {
    const parsed = new URL(inputUrl);
    return decodeURIComponent(parsed.pathname.split("/").filter(Boolean)[0] || "").replace(/^@/, "");
  } catch {
    return "";
  }
}

function getSocialSearch(inputUrl: string) {
  try {
    const parsed = new URL(inputUrl);
    const host = parsed.hostname.toLowerCase();
    const path = parsed.pathname;
    const handle = getFirstPathSegment(inputUrl);

    if (!SOCIAL_HOSTS.includes(host)) return null;

    if (host.includes("instagram.com")) {
      return {
        platform: "instagram",
        handle,
        searchQuery: handle ? `site:instagram.com/${handle} ${handle}` : `site:instagram.com ${inputUrl}`,
      };
    }

    if (host.includes("tiktok.com")) {
      const tiktokHandle = path.match(/@([^/]+)/)?.[1] || handle;
      return {
        platform: "tiktok",
        handle: tiktokHandle,
        searchQuery: tiktokHandle ? `site:tiktok.com/@${tiktokHandle} ${tiktokHandle}` : `site:tiktok.com ${inputUrl}`,
      };
    }

    if (host === "x.com" || host === "www.x.com" || host.includes("twitter.com")) {
      return {
        platform: "x/twitter",
        handle,
        searchQuery: handle ? `(site:x.com/${handle} OR site:twitter.com/${handle}) ${handle}` : `(site:x.com OR site:twitter.com) ${inputUrl}`,
      };
    }

    if (host.includes("facebook.com")) {
      return {
        platform: "facebook",
        handle,
        searchQuery: handle ? `site:facebook.com/${handle} ${handle}` : `site:facebook.com ${inputUrl}`,
      };
    }

    if (host.includes("youtube.com") || host === "youtu.be") {
      return {
        platform: "youtube",
        handle,
        searchQuery: `site:youtube.com OR site:youtu.be ${inputUrl}`,
      };
    }

    if (host.includes("reddit.com")) {
      return {
        platform: "reddit",
        handle,
        searchQuery: `site:reddit.com ${inputUrl}`,
      };
    }

    return null;
  } catch {
    return null;
  }
}

function buildSocialMarkdown(url: string, platform: string, handle: string, searchQuery: string, results: any[]) {
  const lines = [
    `Link sosial media terdeteksi: ${platform}`,
    handle ? `Username/kata kunci: ${handle}` : "Username/kata kunci: tidak terbaca dari URL",
    `URL: ${url}`,
    "",
    "Catatan penting: banyak halaman sosial media seperti Instagram, TikTok, X, Facebook, YouTube, dan Reddit sering menampilkan login page atau membatasi scraper. Karena itu server memakai hasil pencarian publik SerpApi, bukan membaca halaman login.",
    "",
    `Query publik: ${searchQuery}`,
    "",
    "Hasil pencarian publik:",
  ];

  if (!results.length) {
    lines.push("Tidak ada hasil publik yang ditemukan dari SerpApi.");
    return lines.join("\n");
  }

  results.slice(0, 5).forEach((item: any, index: number) => {
    lines.push(`${index + 1}. ${item.title || "No title"}`);
    lines.push(`   URL: ${item.url || item.link || ""}`);
    if (item.description || item.snippet) lines.push(`   Ringkasan: ${item.description || item.snippet}`);
  });

  return lines.join("\n");
}

export default async function handler(req: any, res: any) {
  if (req.method !== "POST") {
    return sendJson(res, 405, { success: false, ok: false, error: "Method not allowed. Gunakan POST." });
  }

  const url = normalizeUrl(getRequestValue(req, "url"));
  if (!isSafeHttpUrl(url)) {
    return sendJson(res, 400, { success: false, ok: false, error: "URL tidak valid atau tidak aman" });
  }

  const social = getSocialSearch(url);
  if (social && process.env.SERPAPI_API_KEY) {
    try {
      const result = await searchWithSerpApi(social.searchQuery);
      const markdown = buildSocialMarkdown(url, social.platform, social.handle, social.searchQuery, result.data || []);
      return sendJson(res, 200, {
        success: true,
        ok: true,
        source: "serpapi-social-fallback",
        url,
        platform: social.platform,
        searchQuery: social.searchQuery,
        warning: "Social media URL fallback: halaman sosial media sering login/blocked, jadi memakai hasil pencarian publik.",
        markdown,
        content: markdown,
        results: result.data || [],
        data: {
          markdown,
          results: result.data || [],
          metadata: {
            title: `${social.platform} public search fallback`,
            description: "Social media URL dibaca via hasil pencarian publik karena halaman asli sering login/blocked.",
            source: "serpapi-social-fallback",
            platform: social.platform,
            searchQuery: social.searchQuery,
          },
        },
      });
    } catch (error: any) {
      // Kalau SerpApi gagal, lanjut ke Firecrawl/Browserless sebagai fallback terakhir.
    }
  }

  const errors: string[] = [];

  if (process.env.FIRECRAWL_API_KEY) {
    try {
      const result = await scrapeWithFirecrawl(url);
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
      errors.push(`Firecrawl: ${error?.message || "gagal"}`);
    }
  } else {
    errors.push("Firecrawl: FIRECRAWL_API_KEY belum di-set");
  }

  if (process.env.BROWSERLESS_TOKEN) {
    try {
      const result = await scrapeWithBrowserless(url);
      return sendJson(res, 200, {
        success: true,
        ok: true,
        source: result.source,
        url,
        warning: errors.join(" | "),
        markdown: result.markdown,
        content: result.markdown,
        data: {
          markdown: result.markdown,
          metadata: {
            title: result.title,
            description: result.description,
            source: result.source,
            warning: errors.join(" | "),
          },
        },
      });
    } catch (error: any) {
      errors.push(`Browserless: ${error?.message || "gagal"}`);
    }
  } else {
    errors.push("Browserless: BROWSERLESS_TOKEN belum di-set");
  }

  return sendJson(res, 500, {
    success: false,
    ok: false,
    source: "none",
    url,
    error: errors.join(" | ") || "Gagal membaca website",
  });
}
