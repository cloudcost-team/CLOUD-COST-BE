# 앱 비밀값: SSM Parameter Store SecureString (무료).
# 파라미터는 Terraform이 만들지 않는다. 사람이 CLI로 직접 만든다 (docs/ECS_MIGRATION.md 1단계).
# Terraform 리소스나 data 소스로 두면 refresh 때 복호화된 값이 tfstate에 평문으로 저장되기 때문이다
# (ignore_changes는 저장을 막지 못한다). 여기서는 이름으로 ARN만 만들어 태스크 정의에 넘긴다.
locals {
  ssm_prefix = "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/${var.name}"

  app_secret_params = {
    GOOGLE_CLIENT_SECRET = "google-client-secret"
    JWT_SECRET           = "jwt-secret"
    CCI_SECRET_KEY       = "secret-key"
  }

  app_secret_arns    = { for env_name, param in local.app_secret_params : env_name => "${local.ssm_prefix}/${param}" }
  grafana_secret_arn = "${local.ssm_prefix}/grafana-api-key"

  # 사람이 만들어야 하는 파라미터 이름 (outputs)
  secret_parameter_names = concat(
    [for param in values(local.app_secret_params) : "/${var.name}/${param}"],
    var.grafana_prom_url == "" ? [] : ["/${var.name}/grafana-api-key"],
  )
}
