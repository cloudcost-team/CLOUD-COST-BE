# ECS on EC2: t3.small 1대에 Spring Boot 컨테이너 2개 (Fargate보다 싸고 공인 IP도 1개만 쓴다).
# EC2가 죽으면 Auto Scaling 그룹이 새로 띄우고, 컨테이너가 죽으면 ECS가 다시 띄운다.

resource "aws_ecs_cluster" "main" {
  name = "${var.name}-cluster"

  setting {
    name  = "containerInsights"
    value = "disabled" # 비용 (지표는 Grafana Cloud로 본다)
  }
}

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.name}-app"
  retention_in_days = 14
}

# ── 컨테이너를 돌릴 EC2 ───────────────────────────────────
data "aws_ssm_parameter" "ecs_ami" {
  name = "/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_id"
}

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_host" {
  name               = "${var.name}-ecs-host"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "ecs_host" {
  role       = aws_iam_role.ecs_host.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEC2ContainerServiceforEC2Role"
}

# SSH 대신 Session Manager로 들어간다 (22번 포트를 열지 않는다)
resource "aws_iam_role_policy_attachment" "ecs_host_ssm" {
  role       = aws_iam_role.ecs_host.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "ecs_host" {
  name = "${var.name}-ecs-host"
  role = aws_iam_role.ecs_host.name
}

resource "aws_launch_template" "ecs_host" {
  name_prefix   = "${var.name}-ecs-host-"
  image_id      = data.aws_ssm_parameter.ecs_ami.value
  instance_type = var.instance_type

  iam_instance_profile {
    arn = aws_iam_instance_profile.ecs_host.arn
  }

  network_interfaces {
    associate_public_ip_address = true
    security_groups             = [aws_security_group.ecs_host.id]
  }

  metadata_options {
    http_tokens = "required"
    # 1: 컨테이너(bridge)는 EC2 메타데이터에 닿지 못한다 → EC2 Role을 빌려 쓰지 못하고 Task Role만 쓴다
    http_put_response_hop_limit = 1
  }

  user_data = base64encode(<<-EOT
    #!/bin/bash
    echo "ECS_CLUSTER=${aws_ecs_cluster.main.name}" >> /etc/ecs/ecs.config
    echo "ECS_CONTAINER_STOP_TIMEOUT=40s" >> /etc/ecs/ecs.config
  EOT
  )

  tag_specifications {
    resource_type = "instance"
    tags          = { Name = "${var.name}-ecs-host" }
  }
}

resource "aws_autoscaling_group" "ecs_host" {
  name                = "${var.name}-ecs-host"
  min_size            = 1
  max_size            = 1
  desired_capacity    = 1
  vpc_zone_identifier = aws_subnet.public[*].id
  health_check_type   = "EC2"

  launch_template {
    id      = aws_launch_template.ecs_host.id
    version = "$Latest"
  }

  tag {
    key                 = "Name"
    value               = "${var.name}-ecs-host"
    propagate_at_launch = true
  }
}

# ── 태스크 실행 Role (이미지 pull, 로그, 비밀값 읽기) ─────────
data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "task_execution" {
  name               = "${var.name}-task-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "task_execution" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "task_execution_secrets" {
  name = "read-app-secrets"
  role = aws_iam_role.task_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ssm:GetParameters"]
        Resource = "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/${var.name}/*"
      },
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = aws_db_instance.main.master_user_secret[0].secret_arn
      },
    ]
  })
}

# ── 앱이 쓰는 Role: 지금 EC2의 백엔드 Role 그대로 (Trust Policy에 ecs-tasks.amazonaws.com 추가는 사람이 한다) ──
data "aws_iam_role" "backend" {
  name = var.backend_role_name
}

# ── 태스크 정의 ────────────────────────────────────────────
locals {
  alloy_enabled = var.grafana_prom_url != ""

  app_container = {
    name              = "app"
    image             = "${aws_ecr_repository.app.repository_url}:latest"
    essential         = true
    memory            = 900 # t3.small(2GB)에 2개 + Alloy가 들어가게
    memoryReservation = 640
    portMappings      = [{ containerPort = 8080, hostPort = 0, protocol = "tcp" }]

    environment = [for k, v in merge({
      SPRING_PROFILES_ACTIVE = "prod"
      APP_ENV                = "prod"
      AUTH_MODE              = "google"
      AWS_REGION             = var.region
      MANAGEMENT_PORT        = "8080"
      DB_URL                 = "jdbc:postgresql://${aws_db_instance.main.endpoint}/cci"
      DB_USERNAME            = "cci"
      GOOGLE_CLIENT_ID       = var.google_client_id
      CCI_BACKEND_ROLE_ARN   = data.aws_iam_role.backend.arn
      JAVA_OPTS              = "-XX:InitialRAMPercentage=10 -XX:MaxRAMPercentage=60 -XX:+UseG1GC -Duser.timezone=UTC"
    }, var.app_env) : { name = k, value = v }]

    secrets = concat(
      [{ name = "DB_PASSWORD", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::" }],
      [for env_name, arn in local.app_secret_arns : { name = env_name, valueFrom = arn }],
    )

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "app"
      }
    }
  }

  alloy_container = {
    name              = "alloy"
    image             = "grafana/alloy:v1.20.1"
    essential         = false
    memory            = 192
    memoryReservation = 96
    links             = ["app"]
    entryPoint        = ["/bin/sh", "-c"]
    command           = ["printf '%s' \"$ALLOY_CONFIG\" > /tmp/config.alloy && exec /bin/alloy run --storage.path=/tmp/alloy /tmp/config.alloy"]

    environment = [
      { name = "ALLOY_CONFIG", value = file("${path.module}/../../deploy/alloy/config.ecs.alloy") },
      { name = "GRAFANA_CLOUD_PROM_URL", value = var.grafana_prom_url },
      { name = "GRAFANA_CLOUD_PROM_USER", value = var.grafana_prom_user },
    ]

    secrets = [{ name = "GRAFANA_CLOUD_API_KEY", valueFrom = local.grafana_secret_arn }]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "alloy"
      }
    }
  }
}

resource "aws_ecs_task_definition" "app" {
  family                   = "${var.name}-app"
  requires_compatibilities = ["EC2"]
  network_mode             = "bridge"
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = data.aws_iam_role.backend.arn

  # 두 컨테이너의 모양이 달라 조건식 하나로 리스트를 만들 수 없어 JSON을 이어 붙인다
  container_definitions = "[${join(",", compact([
    jsonencode(local.app_container),
    local.alloy_enabled ? jsonencode(local.alloy_container) : "",
  ]))}]"
}

# ── 서비스: ALB 뒤에서 컨테이너 2개 ─────────────────────────
resource "aws_ecs_service" "app" {
  name            = "${var.name}-app"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.app_count
  launch_type     = "EC2"

  # EC2 한 대에 3개는 안 들어가므로 하나씩 바꾼다 (하나가 바뀌는 동안 다른 하나가 응답)
  deployment_minimum_healthy_percent = 50
  deployment_maximum_percent         = 100
  health_check_grace_period_seconds  = 120

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "app"
    container_port   = 8080
  }

  # 이후 배포는 GitHub Actions가 새 태스크 정의로 바꾼다 (Terraform이 되돌리지 않게)
  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener.https, aws_autoscaling_group.ecs_host]
}
