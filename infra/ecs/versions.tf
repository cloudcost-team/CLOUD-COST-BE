# ECS 한 달 운영용 인프라 (ADR-021, docs/ECS_MIGRATION.md)
# 만들기: terraform apply / 정리: terraform destroy (RDS 데이터까지 모두 지워진다)
terraform {
  required_version = ">= 1.7" # removed 블록

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "cloudcost-insight"
      ManagedBy = "terraform"
      Stack     = "ecs"
    }
  }
}

data "aws_caller_identity" "current" {}
