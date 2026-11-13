// Кеш-проксі для City Guide на Cloudflare Workers (безкоштовний план достатній).
// Маршрути:
//   POST /overpass  : тіло як у Overpass (data=...), перегони дзеркал + кеш на добу
//   GET  /wikidata  : проксі до wikidata.org/w/api.php з кешем на тиждень
//   GET  /fsq       : проксі до Foursquare Places (потрібен секрет FSQ_KEY), кеш на 6 годин

const OVERPASS_MIRRORS = [
  "https://overpass-api.de/api/interpreter",
  "https://lz4.overpass-api.de/api/interpreter",
  "https://overpass.kumi.systems/api/interpreter",
  "https://overpass.private.coffee/api/interpreter",
  "https://overpass.openstreetmap.fr/api/interpreter",
];
const STAGGER_MS = 2000;

async function sha256(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function json(text, status = 200, maxAge = 0) {
  return new Response(text, {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": maxAge ? `public, max-age=${maxAge}` : "no-store",
      "access-control-allow-origin": "*",
    },
  });
}

async function raceOverpass(body) {
  let done = false;
  const controllers = [];
  const attempts = OVERPASS_MIRRORS.map(
    (endpoint, i) =>
      new Promise((resolve, reject) => {
        setTimeout(async () => {
          if (done) return reject(new Error("skipped"));
          try {
            const controller = new AbortController();
            controllers.push(controller);
            const response = await fetch(endpoint, {
              method: "POST",
              headers: {
                "content-type": "application/x-www-form-urlencoded",
                "user-agent": "CityGuideProxy/1.0",
              },
              body,
              signal: controller.signal,
            });
            if (!response.ok) throw new Error(`${endpoint} ${response.status}`);
            resolve(await response.text());
          } catch (e) {
            reject(e);
          }
        }, i * STAGGER_MS);
      })
  );
  try {
    return await Promise.any(attempts);
  } finally {
    done = true;
    controllers.forEach((c) => c.abort());
  }
}

async function cached(request, ctx, keyUrl, maxAge, produce) {
  const cache = caches.default;
  const key = new Request(keyUrl);
  const hit = await cache.match(key);
  if (hit) return hit;
  const { text, status } = await produce();
  const response = json(text, status, status === 200 ? maxAge : 0);
  // Відповіді з "remark" (таймаут Overpass) не кешуємо
  if (status === 200 && !text.includes('"remark"')) ctx.waitUntil(cache.put(key, response.clone()));
  return response;
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    try {
      if (url.pathname === "/overpass" && request.method === "POST") {
        const body = await request.text();
        const hash = await sha256(body);
        return await cached(request, ctx, `https://cache.local/overpass/${hash}`, 86400, async () => ({
          text: await raceOverpass(body),
          status: 200,
        }));
      }

      if (url.pathname === "/wikidata") {
        return await cached(request, ctx, `https://cache.local/wikidata${url.search}`, 604800, async () => {
          const r = await fetch("https://www.wikidata.org/w/api.php" + url.search, {
            headers: { "user-agent": "CityGuideProxy/1.0" },
          });
          return { text: await r.text(), status: r.status };
        });
      }

      if (url.pathname === "/fsq") {
        if (!env.FSQ_KEY) return json("{}", 404);
        return await cached(request, ctx, `https://cache.local/fsq${url.search}`, 21600, async () => {
          const r = await fetch("https://places-api.foursquare.com/places/search" + url.search, {
            headers: {
              accept: "application/json",
              authorization: `Bearer ${env.FSQ_KEY}`,
              "X-Places-Api-Version": "2025-06-17",
            },
          });
          return { text: await r.text(), status: r.status };
        });
      }

      return new Response("City Guide proxy", { status: 200 });
    } catch (e) {
      return json(JSON.stringify({ error: String(e) }), 502);
    }
  },
};
