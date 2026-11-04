#!/usr/bin/env python3
"""
팀 벤치마킹 ① 과다 사양 비율의 비교 기준을 Azure Public Dataset V2 원본으로 다시 계산한다 (ADR-013).

자료: https://github.com/Azure/AzurePublicDataset/blob/master/AzurePublicDatasetV2.md
      vmtable.csv.gz (링크: AzurePublicDatasetLinksV2.txt). 마이크로소프트 내부(first-party) 서비스 VM의 30일 기록이다.

계산
- 구독(subscriptionid)별 VM 수가 MIN_VMS~MAX_VMS인 구독만 쓴다 (기본 3~20대).
- 과다 사양 VM = p95maxcpu(5분 최대 CPU의 P95) < THRESHOLD (기본 10%).
- 구독마다 과다 사양 VM 비율을 구해, 구독들의 평균·중앙값·백분위 표(5% 단위)를 낸다.
- 출력은 src/main/resources/benchmark/reference.json의 "oversized"에 그대로 붙일 수 있는 JSON이다.

실행 (표준 라이브러리만 사용, 파일은 스트리밍으로 읽는다)
    python3 scripts/benchmark/azure_oversized.py vmtable.csv.gz > oversized.json
"""
import argparse
import csv
import gzip
import json
import statistics
from collections import defaultdict

# vmtable.csv에는 헤더가 없다. 열 순서는 데이터셋 분석 노트북과 같다.
COLUMNS = ["vmid", "subscriptionid", "deploymentid", "vmcreated", "vmdeleted",
           "maxcpu", "avgcpu", "p95maxcpu", "vmcategory", "vmcorecount", "vmmemory"]


def percentile(sorted_values, p):
    """nearest-rank 백분위 (p: 0~100)"""
    if not sorted_values:
        raise ValueError("값이 없습니다")
    rank = max(1, -(-p * len(sorted_values) // 100))  # ceil
    return sorted_values[int(rank) - 1]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("vmtable", help="vmtable.csv.gz 경로")
    parser.add_argument("--min-vms", type=int, default=3)
    parser.add_argument("--max-vms", type=int, default=20)
    parser.add_argument("--threshold", type=float, default=10.0, help="과다 사양 기준 P95 (%%)")
    args = parser.parse_args()

    total = defaultdict(int)
    oversized = defaultdict(int)
    opener = gzip.open if args.vmtable.endswith(".gz") else open
    with opener(args.vmtable, "rt", newline="") as f:
        for row in csv.reader(f):
            rec = dict(zip(COLUMNS, row))
            sub = rec["subscriptionid"]
            total[sub] += 1
            if float(rec["p95maxcpu"]) < args.threshold:
                oversized[sub] += 1

    ratios = sorted(100.0 * oversized[s] / n for s, n in total.items() if args.min_vms <= n <= args.max_vms)
    result = {
        "source": "Azure Public Dataset V2",
        "description": (f"마이크로소프트 내부 서비스 VM 30일 기록 중 VM {args.min_vms}~{args.max_vms}대 구독 {len(ratios):,}곳의 "
                        f"과다 사양(5분 최대 CPU의 P95 {args.threshold:g}% 미만) VM 비율. 측정 방식이 달라 참고용"),
        "sampleSize": len(ratios),
        "averagePercent": round(statistics.mean(ratios)),
        "medianPercent": round(statistics.median(ratios)),
        "percentiles": [{"percentile": p, "ratioPercent": round(percentile(ratios, p), 1)} for p in range(5, 100, 5)],
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
