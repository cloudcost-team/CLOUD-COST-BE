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
    rules = [{
      rulePriority = 1
      description  = "Keep last 5 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 5
      }
      action = { type = "expire" }
    }]
  })
}
