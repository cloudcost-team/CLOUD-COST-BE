-- 낭비 탐지용 지표 (이슈 #24, ADR-009).
-- CPU 평균·최대는 사용 시간과 같은 CPUUtilization 조회에서 함께 받는다 (호출 수 그대로).
-- 네트워크 입출력은 최근 14일만 수집한다. 지표 수집 전에 저장한 행은 NULL로 남는다.

ALTER TABLE ec2_usage_hourly ADD COLUMN cpu_avg NUMERIC(7, 3);
ALTER TABLE ec2_usage_hourly ADD COLUMN cpu_max NUMERIC(7, 3);
ALTER TABLE ec2_usage_hourly ADD COLUMN network_in_bytes BIGINT;
ALTER TABLE ec2_usage_hourly ADD COLUMN network_out_bytes BIGINT;

-- 이 시각 전까지 최근 14일 범위의 지표를 수집했다. NULL이면 수집 전이라 다음 수집 때 최근 14일을 한 번 읽는다.
ALTER TABLE ec2_instances ADD COLUMN metrics_collected_until TIMESTAMP WITH TIME ZONE;
