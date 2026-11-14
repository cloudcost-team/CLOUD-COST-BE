resource "aws_ecr_repository" "app" {
  name                 = "cloudcost-insight"
  image_tag_mutability = "MUTABLE"
  force_delete         = true # terraform destroy 때 이미지까지 지운다

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    # 태그 없는 이미지만 하루 뒤 지우고, 태그 있는 이미지는 최근 30개를 남긴다.
    # (배포가 연달아 실패해도 지금 돌고 있는 이미지와 롤백용 이미지가 지워지지 않게 넉넉히 둔다)
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged after 1 day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep last 30 tagged images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 30
        }
        action = { type = "expire" }
      },
    ]
  })
}
