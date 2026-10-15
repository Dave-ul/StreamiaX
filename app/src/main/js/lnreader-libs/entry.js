// Bundled into assets/lnreader/libs.js by `npm run build`; host.js picks these up from __libs.
import * as cheerio from 'cheerio';
import * as htmlparser2 from 'htmlparser2';
import dayjs from 'dayjs';
import { URL, URLSearchParams } from 'whatwg-url-without-unicode';

globalThis.URL = URL;
globalThis.URLSearchParams = URLSearchParams;
globalThis.__libs = { cheerio, htmlparser2, dayjs };
