# Elastic Relaxed Concurrent Queue

An implementation and analysis of a **Contention-Aware Adaptive Concurrent Queue** written in Java. This project explores the trade-off between strict FIFO ordering (Linearizability) and high throughput under heavy memory contention in multi-core environments.

This project was developed as the final assignment for the Multi-Core Programming course.

## Overview

Traditional concurrent queues, such as the classic **Michael & Scott Lock-Free Queue**, enforce strict First-In-First-Out (FIFO) semantics. While this guarantees absolute ordering, it introduces severe memory contention (on `compareAndSet` operations) under high-thread workloads, leading to performance degradation.

This repository implements an adaptive variation based on the concept of **Elastic Relaxation**:
* **Low Contention:** The queue operates with strict FIFO semantics.
* **High Contention:** The queue dynamically "stretches" its relaxation bound ($K$) by distributing operations across multiple parallel execution lanes/tails, reducing contention and maximizing throughput.
* **Normalization:** As the workload decreases, the queue elastically contracts back toward strict FIFO ordering.

## Features

* **Michael-Scott Queue Implementation:** A textbook lock-free queue utilizing atomic pointer management (`AtomicReference`).
* **Elastic Relaxed Queue Variant:** An adaptive, contention-aware queue that monitors CAS failure rates in real-time to dynamically adjust its relaxation bounds.
* **Performance Benchmark Suite:** Built-in multi-threaded tester to benchmark throughput (Operations/sec) across various thread counts (up to 1024 threads).

## Getting Started

### Prerequisites
* Java 8 or higher (Java 21 recommended for Virtual Threads benchmarking).
* Maven (Optional, for dependency management) or standard Java compiler (`javac`).

### Compilation & Running on Server

To comply with the course guidelines, the code is standalone and can be compiled and executed directly via the command line on the university server:

```bash
# Compile all source files
javac *.java

# Run the benchmark suite
java BenchmarkMain
