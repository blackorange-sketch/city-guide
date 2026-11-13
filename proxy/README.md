# Кеш-проксі City Guide (Cloudflare Workers)

Прискорює завантаження й прибирає залежність від публічних серверів: кешує Overpass (добу) і Wikidata (тиждень),
а також (за бажанням) віддає рейтинги закладів із Foursquare, не розкриваючи ключ у застосунку.

## Розгортання (безкоштовно, ~5 хвилин)

**Варіант 1, через сайт:** dash.cloudflare.com → Workers & Pages → Create → Create Worker → Deploy → Edit code,
вставте вміст `worker.js` → Deploy. Адреса матиме вигляд `https://city-guide-proxy.<ваш-акаунт>.workers.dev`.

**Варіант 2, через консоль:**
```
cd proxy
npx wrangler deploy
```

## Підключення до застосунку

У GitHub: Settings → Secrets and variables → Actions → вкладка **Variables** → New repository variable:
`PROXY_URL` = адреса воркера (без `/` в кінці). Наступна збірка підхопить її. Без змінної застосунок
працює напряму, як раніше.

## Рейтинги закладів (необов'язково)

1. Створіть ключ на foursquare.com/developers (Places API).
2. У Cloudflare: Worker → Settings → Variables and Secrets → додайте секрет `FSQ_KEY`.

Увага: поля `rating` і `price` у Foursquare належать до Premium-тарифу. Якщо вони недоступні для вашого ключа,
застосунок просто покаже заклади без оцінок.

## Пошук за фото (необов'язково)

Маршрут `POST /identify` приймає JSON `{image: <base64 JPEG>, lang, hint}` і розпізнає пам'ятник,
будівлю чи картину моделлю Claude з розпізнаванням зображень.

1. Створіть ключ на console.anthropic.com (потрібна оплата за використання, фото ≈ копійки).
2. У Cloudflare: Worker → Settings → Variables and Secrets → секрет `ANTHROPIC_KEY`.
   Необов'язково: змінна `ANTHROPIC_MODEL` (за замовчуванням `claude-sonnet-5-5`).
3. Захист від чужих запитів (бажано): секрет `APP_KEY` на воркері й той самий секрет `PROXY_KEY`
   у GitHub (Settings → Secrets and variables → Actions → Secrets). Застосунок надсилає його в заголовку
   `x-app-key`. Ключ лежить усередині APK, тож це лише проста перепона, а не справжня автентифікація.

Без `ANTHROPIC_KEY` кнопка 📷 показує повідомлення, що пошук за фото не налаштований.
