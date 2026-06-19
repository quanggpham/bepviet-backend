// Anonymous storefront traffic: product list pages (incl. category filter), categories and banners.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:18080';

export const options = {
  scenarios: {
    storefront: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '60s',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // thresholds that never fail, only so the summary reports each endpoint separately
  thresholds: {
    'http_req_duration{name:products}': ['max>=0'],
    'http_req_duration{name:products_by_category}': ['max>=0'],
    'http_req_duration{name:categories}': ['max>=0'],
    'http_req_duration{name:banners}': ['max>=0'],
    checks: ['rate>=0'],
  },
};

export default function () {
  const r = Math.random();
  let res;
  if (r < 0.55) {
    const page = Math.floor(Math.random() * 50);
    res = http.get(`${BASE}/api/v1/products?page=${page}&size=20`, { tags: { name: 'products' } });
  } else if (r < 0.70) {
    const category = 1 + Math.floor(Math.random() * 20);
    res = http.get(`${BASE}/api/v1/products?categoryId=${category}&page=0&size=20`, { tags: { name: 'products_by_category' } });
  } else if (r < 0.85) {
    res = http.get(`${BASE}/api/v1/categories`, { tags: { name: 'categories' } });
  } else {
    res = http.get(`${BASE}/api/v1/banners`, { tags: { name: 'banners' } });
  }
  check(res, { 'status 200': (x) => x.status === 200 });
}
