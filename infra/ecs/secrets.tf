# 앱 비밀값: SSM Parameter Store SecureString (무료). 값은 사람이 직접 넣는다 (docs/ECS_MIGRATION.md 3단계).
# Terraform은 자리만 만들고 값은 건드리지 않는다 → 실제 비밀값이 tfstate에 남지 않는다.
locals {
  app_secret_params = {
    GOOGLE_CLIENT_SECRET = "google-client-secret"
    JWT_SECRET           = "jwt-secret"
    CCI_SECRET_KEY       = "secret-key"
  }
}

resource "aws_ssm_parameter" "app" {
  for_each = local.app_secret_params

  name  = "/${var.name}/${each.value}"
  type  = "SecureString"
  value = "CHANGE_ME"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "grafana_api_key" {
  count = var.grafana_prom_url == "" ? 0 : 1

  name  = "/${var.name}/grafana-api-key"
  type  = "SecureString"
  value = "CHANGE_ME"

  lifecycle {
    ignore_changes = [value]
  }
}
