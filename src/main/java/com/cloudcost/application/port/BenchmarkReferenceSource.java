package com.cloudcost.application.port;

import com.cloudcost.domain.benchmark.BenchmarkReference;

/** 팀 벤치마킹 비교 기준 (교체 가능한 리소스 파일, ADR-013) */
public interface BenchmarkReferenceSource {

    BenchmarkReference reference();
}
