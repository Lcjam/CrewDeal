\set ON_ERROR_STOP on

-- buyer1의 검증된 BCrypt 해시를 복제해 S1-a 전용 구매자 200명을 만든다.
-- 회원가입 API를 추가하지 않고 17.5의 VU 1:1 픽스처만 준비한다.
INSERT INTO users (email, password_hash, display_name, role, created_at)
SELECT 'buyer' || lpad(sequence::text, 3, '0') || '@groupdrop.test', seed.password_hash,
       'load-buyer-' || lpad(sequence::text, 3, '0'), 'BUYER', now()
  FROM generate_series(1, 200) AS sequence
 CROSS JOIN (SELECT password_hash FROM users WHERE email = 'buyer1@groupdrop.test') seed
ON CONFLICT (email) DO NOTHING;

SELECT count(*) AS s1a_buyer_count
  FROM users
 WHERE email ~ '^buyer[0-9]{3}@groupdrop[.]test$';

SELECT 1 / CASE WHEN count(*) = 200 THEN 1 ELSE 0 END AS buyer_fixture_assertion
  FROM users
 WHERE email ~ '^buyer[0-9]{3}@groupdrop[.]test$';
