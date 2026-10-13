package com.cloudcost.entrypoint.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "1. AWS 연결")
class CloudFormationTemplateController {

    private final String template;

    CloudFormationTemplateController() throws IOException {
        this.template = new ClassPathResource("cloudformation/customer-readonly-role.yaml")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @GetMapping("/api/v1/aws/cloudformation-template")
    @Operation(
            summary = "고객용 CloudFormation 템플릿 (YAML)",
            description = "관리자가 S3에 한 번 올리는 원본. 빠른 생성 링크가 이 파일을 가리킨다. "
                    + "S3가 없을 때는 이 파일을 내려받아 CloudFormation에 직접 올리고 stackParameters를 입력해도 된다.")
    ResponseEntity<String> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-yaml"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cloudcost-insight-readonly-role.yaml\"")
                .body(template);
    }
}
