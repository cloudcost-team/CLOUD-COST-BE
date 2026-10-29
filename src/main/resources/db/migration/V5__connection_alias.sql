-- 연결 이름 (이슈 #15). 사용자가 화면에서 연결을 구분하려고 붙이는 이름이다. AWS에는 보내지 않는다.
-- 40자 제한은 애플리케이션(ConnectionAlias)에서 검사한다. 한글 등 다국어 문자를 위해 컬럼은 여유 있게 둔다.

ALTER TABLE aws_accounts ADD COLUMN alias VARCHAR(160);
