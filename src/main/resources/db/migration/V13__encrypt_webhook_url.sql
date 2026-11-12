-- Discord 웹훅 주소를 앱에서 AES-256-GCM으로 암호화해 저장한다 (SecretCipher, 키는 CCI_SECRET_KEY).
-- 암호문("v1:" + base64(IV + 암호문 + 태그))이 원문보다 길어 칸을 늘린다. 이미 있는 평문 값은 그대로 읽히고 다음 저장 때 암호화된다.
ALTER TABLE notification_channels ALTER COLUMN webhook_url SET DATA TYPE VARCHAR(600);
