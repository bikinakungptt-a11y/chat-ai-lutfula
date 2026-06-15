import { getEnv, handleOptions, json } from './_utils.js';

export default async function handler(req, res) {
  if (handleOptions(req, res)) return;

  json(res, 200, {
    ok: true,
    service: 'chat-ai-lutfula-backend',
    firecrawlConfigured: Boolean(getEnv('FIRECRAWL_API_KEY')),
    apiNinjasConfigured: Boolean(getEnv('API_NINJAS_API_KEY'))
  });
}
