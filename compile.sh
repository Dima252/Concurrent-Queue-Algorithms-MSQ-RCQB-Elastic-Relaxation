#!/usr/bin/env bash
# compile.sh — build and run the Elastic Relaxed Queue benchmark
# Usage: bash compile.sh [run]
set -e

mkdir -p bin
find src -name "*.java" | xargs javac -d bin

echo "Build successful."

if [ "${1}" = "run" ]; then
    java -cp bin -Xss256k benchmark.BenchmarkMain
fi
