## 🔗 관련 이슈
- close #

---

## ✅ PR 유형
어떤 변경 사항이 있었나요?

- [ ] 새로운 기능 추가
- [ ] 버그 수정
- [ ] 리팩토링
- [ ] 코드에 영향을 주지 않는 변경사항(주석, 개행 등등..)
- [ ] 문서 수정
- [ ] 빌드 부분 혹은 패키지 매니저 수정
- [ ] 테스트 코드 추가
- [ ] CI/CD·배포 설정

---

## ✏️ 작업 내용
> [!CAUTION]
> API 변경이 있다면 Swagger 화면 또는 응답 예시 첨부는 **필수**입니다!
> **첨부 후 해당 콜아웃은 지워주세요!!**

<br>

---

## 🔒 체크리스트
- [ ] `./gradlew test` 통과
- [ ] 고객 AWS 계정에 허용된 2개 API(`ec2:DescribeInstances`, `cloudwatch:GetMetricStatistics`) 외 호출 없음
- [ ] 비밀값·실제 계정 ID·ARN·IP를 코드/문서에 넣지 않음
- [ ] API·테이블 변경 시 `docs/API.md`, `docs/DATA_MODEL.md` 함께 수정

---

## 💡 추가 사항
