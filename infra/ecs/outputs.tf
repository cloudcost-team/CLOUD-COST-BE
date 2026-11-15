output "acm_validation_records" {
  description = "도메인 업체에 CNAME으로 추가할 인증서 확인 레코드"
  value = [for o in aws_acm_certificate.api.domain_validation_options : {
    name  = o.resource_record_name
    type  = o.resource_record_type
    value = o.resource_record_value
  }]
}

output "alb_dns_name" {
  description = "백엔드 도메인을 이 주소로 CNAME 연결한다"
  value       = aws_lb.main.dns_name
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "ecs_service" {
  value = aws_ecs_service.app.name
}

output "ecs_task_family" {
  value = aws_ecs_task_definition.app.family
}

output "rds_endpoint" {
  value = aws_db_instance.main.endpoint
}

output "ssm_secret_parameters" {
  description = "직접 값을 넣어야 하는 비밀값 자리"
  value       = local.secret_parameter_names
}
