import http from 'k6/http';
import { check, sleep } from 'k6';

const mode = __ENV.K6_MODE || 'catalog';
const allowed = ['catalog', 'orders', 'downloads'];

if (!allowed.includes(mode)) {
    throw new Error(`K6_MODE inválido: ${mode}`);
}

const baseUrl = __ENV.K6_BASE_URL || 'http://localhost:8080';
const runId = (__ENV.K6_RUN_ID || String(Date.now()))
    .replace(/[^a-zA-Z0-9_-]/g, '_')
    .slice(0, 50);
const vus = Number(__ENV.PERF_VUS || (mode === 'downloads' ? 2 : 5));
const duration = __ENV.PERF_DURATION || '30s';
const baselineP95Ms = { catalog: 50, orders: 100, downloads: 150 };
const p95Ms = Number(__ENV.K6_P95_MS || baselineP95Ms[mode]);
const maxErrorRate = Number(__ENV.K6_MAX_ERROR_RATE || 0.01);
const token = __ENV.K6_CUSTOMER_TOKEN || '';

if (mode !== 'catalog' && !token) {
    throw new Error('Falta K6_CUSTOMER_TOKEN');
}
if (mode === 'orders' && !__ENV.K6_PRODUCT_ID) {
    throw new Error('Falta K6_PRODUCT_ID');
}
if (mode === 'downloads' && !__ENV.K6_DOWNLOAD_PRODUCT_ID) {
    throw new Error('Falta K6_DOWNLOAD_PRODUCT_ID');
}

export const options = {
    scenarios: {
        [mode]: {
            executor: 'constant-vus',
            vus,
            duration,
            gracefulStop: '10s',
        },
    },
    thresholds: {
        http_req_duration: [`p(95)<${p95Ms}`],
        http_req_failed: [`rate<${maxErrorRate}`],
        checks: ['rate>0.99'],
    },
};

export default function () {
    if (mode === 'catalog') {
        const response = http.get(
            `${baseUrl}/api/products?q=&page=0&size=12`,
            { tags: { name: 'catalog-list' } },
        );

        check(response, {
            'catálogo responde 200': (r) => r.status === 200,
            'catálogo devuelve items': (r) =>
                r.status === 200 && Array.isArray(r.json('items')),
        });
        sleep(0.2);
        return;
    }

    const headers = { Authorization: `Bearer ${token}` };

    if (mode === 'orders') {
        const key = `k6-${runId}-${__VU}-${__ITER}`;
        const response = http.post(
            `${baseUrl}/api/orders`,
            JSON.stringify({ productIds: [__ENV.K6_PRODUCT_ID] }),
            {
                headers: {
                    ...headers,
                    'Content-Type': 'application/json',
                    'Idempotency-Key': key,
                },
                tags: { name: 'create-order' },
            },
        );

        check(response, {
            'pedido creado': (r) => r.status === 201,
        });
        sleep(0.5);
        return;
    }

    const response = http.get(
        `${baseUrl}/api/library/${__ENV.K6_DOWNLOAD_PRODUCT_ID}/download`,
        {
            headers,
            responseType: 'none',
            tags: { name: 'download' },
        },
    );

    check(response, {
        'descarga responde 200': (r) => r.status === 200,
    });
    sleep(1);
}

export function handleSummary(data) {
    const output = __ENV.K6_REPORT || `/results/${mode}-${runId}.json`;

    return {
        [output]: JSON.stringify({
            runId,
            mode,
            commit: __ENV.K6_COMMIT || 'local',
            cacheMode: __ENV.K6_CACHE_MODE || 'n/a',
            vus,
            duration,
            p95LimitMs: p95Ms,
            maxErrorRate,
            finishedAt: new Date().toISOString(),
            metrics: data.metrics,
        }, null, 2),
        stdout: `k6 ${mode}: p95=${data.metrics.http_req_duration?.values?.['p(95)']} ms; reporte=${output}\n`,
    };
}
