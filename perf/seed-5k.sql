-- Fake catalog for load tests: 20 categories, 5,000 products, 10,000 likes, review stats and 5 banners.
SELECT setseed(0.42);

INSERT INTO categories (name, description)
SELECT 'Perf category ' || g, 'Load-test category ' || g FROM generate_series(1, 17) g;

INSERT INTO products (name, description, price, stock_quantity, image_url, is_active, category_id, created_at, updated_at)
SELECT 'Mon an ' || g,
       'Mo ta san pham thu ' || g || ' dung cho bai do tai',
       (20000 + floor(random() * 180) * 1000)::numeric,
       (10 + floor(random() * 500))::bigint,
       'https://img.test/p' || g || '.png',
       true,
       (SELECT id FROM categories ORDER BY id OFFSET (g % 20) LIMIT 1),
       now() - (g || ' minutes')::interval, now()
FROM generate_series(1, 5000 - (SELECT count(*) FROM products)) g;

INSERT INTO users (email, full_name, password, role, create_at)
SELECT 'perf-' || g || '@test.vn', 'Perf user ' || g, '{noop}unused', 'USER', now()
FROM generate_series(1, 200) g;

INSERT INTO product_likes (user_id, product_id, created_at)
SELECT DISTINCT ON (u.id, p.id) u.id, p.id, now()
FROM users u
CROSS JOIN LATERAL (SELECT id FROM products ORDER BY random() + u.id * 0 LIMIT 50) p
WHERE u.email LIKE 'perf-%';

INSERT INTO product_stats (product_id, avg_rating, total_reviews, rating_distribution, updated_at)
SELECT id, round((3 + random() * 2)::numeric, 2), floor(random() * 40)::bigint, '{}', now() FROM products
ON CONFLICT (product_id) DO NOTHING;

INSERT INTO banners (title, subtitle, image_url, is_active, display_order, badge_icon, created_at, updated_at)
SELECT 'Banner ' || g, 'Khuyen mai ' || g, 'https://img.test/b' || g || '.png', true, g, 'NONE', now(), now()
FROM generate_series(1, 5) g;

ANALYZE;
SELECT 'categories', count(*) FROM categories UNION ALL SELECT 'products', count(*) FROM products
UNION ALL SELECT 'likes', count(*) FROM product_likes UNION ALL SELECT 'stats', count(*) FROM product_stats
UNION ALL SELECT 'banners', count(*) FROM banners;
