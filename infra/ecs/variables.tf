variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "name" {
  description = "리소스 이름 앞에 붙는 짧은 이름"
  type        = string
  default     = "cci"
}

variable "api_domain" {
  description = "백엔드 도메인 (Vercel /api/v1 리라이트가 가리키는 주소). ACM 인증서를 이 이름으로 발급한다"
  type        = string
}

variable "budget_email" {
  description = "AWS Budgets 알림을 받을 이메일"
  type        = string
}

variable "budget_limit_usd" {
  description = "월 예산 (USD). 크레딧 잔액보다 작게 둔다"
  type        = number
  default     = 70
}

variable "backend_role_name" {
  description = "지금 EC2가 쓰는 백엔드 Role 이름. ECS Task Role로 그대로 쓴다 (고객 Trust Policy가 이 ARN만 믿는다)"
  type        = string
  default     = "CloudCostInsightBackendRole"
}

variable "github_deploy_role_name" {
  description = "GitHub Actions OIDC 배포 Role 이름. ECR push·ECS 배포 권한을 붙인다"
  type        = string
  default     = "CloudCostInsightGithubDeployRole"
}

variable "instance_type" {
  description = "ECS 컨테이너를 돌릴 EC2 (메모리 2GB에 Spring Boot 2개)"
  type        = string
  default     = "t3.small"
}

variable "app_count" {
  description = "Spring Boot 컨테이너 수. 2개면 배포할 때 하나씩 바꿔 끊기지 않는다"
  type        = number
  default     = 2
}

variable "google_client_id" {
  description = "Google OAuth 클라이언트 ID (비밀 아님)"
  type        = string
}

variable "app_env" {
  description = "그 밖의 비밀이 아닌 환경변수 (지금 서버 .env의 CCI_TEMPLATE_URL, CCI_CALLBACK_TOPIC_ARN, 큐 주소 등)"
  type        = map(string)
  default     = {}
}

variable "grafana_prom_url" {
  description = "Grafana Cloud remote_write 주소. 비우면 Alloy 사이드카를 띄우지 않는다"
  type        = string
  default     = ""
}

variable "grafana_prom_user" {
  description = "Grafana Cloud Prometheus 사용자 ID (비밀 아님)"
  type        = string
  default     = ""
}
