# RDS PostgreSQL 16: 한 달 운영용 최소 구성 (AZ 1개, 20GB). 비밀번호는 RDS가 만들어 Secrets Manager에 둔다.
# 운영 DB는 새로 시작한다 (기존 EC2 DB를 옮기지 않는다). Flyway가 앱 시작 때 테이블을 만든다.
resource "aws_db_subnet_group" "main" {
  name       = "${var.name}-db"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "main" {
  identifier     = "${var.name}-db"
  engine         = "postgres"
  engine_version = "16"
  instance_class = "db.t4g.micro"

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name                     = "cci"
  username                    = "cci"
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period    = 1
  auto_minor_version_upgrade = true
  apply_immediately          = true

  # 한 달 뒤 terraform destroy로 지울 수 있게 (데이터는 남기지 않는다)
  deletion_protection = false
  skip_final_snapshot = true
}
