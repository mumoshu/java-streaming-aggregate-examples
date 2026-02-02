#!/bin/bash
#
# Run memory benchmarks using Docker containers for isolation.
# All arguments are passed directly to the BenchmarkOrchestrator.
#
# Usage:
#   ./benchmark.sh                    # Run with defaults
#   ./benchmark.sh --orders=500000    # Custom order count
#   ./benchmark.sh --cpus=4           # Custom CPU cores per container
#   ./benchmark.sh --pageSize=100     # Single page size only

set -e

mvn compile -q
mvn dependency:copy-dependencies -DoutputDirectory=target/dependency -q
java -cp target/classes:target/dependency/* com.example.benchmark.BenchmarkOrchestrator "$@"
