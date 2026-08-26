#!/usr/bin/env python3
"""Bounded B2 CPU benchmark collector (Python standard library only)."""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import json
import math
import os
import platform
import queue
import re
import subprocess
import tarfile
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

SOURCE_SIZE_BYTES = 1_474_560
WARMUP_UPLOAD_COUNT = 1
CONCURRENCY_MATRIX = (8, 16, 32)
CPU_QUOTA = 2
HEURISTIC_CPU_QUOTA = 1
MAVEN_IMAGE = "maven:3.9-amazoncorretto-21"
ALLOWED_ENV_KEYS = ("B2_ENDPOINT", "B2_BUCKET", "B2_ACCESS_KEY_ID", "B2_SECRET_ACCESS_KEY", "B2_OBJECT_PREFIX")
SECRET_ENV_KEYS = ("B2_ACCESS_KEY_ID", "B2_SECRET_ACCESS_KEY")
ANSI_CSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
PHASE_RE = re.compile(r"^B2_BENCH_PHASE\s+epoch_ms=(?P<epoch_ms>\d+)\s+phase=(?P<phase>setup|warmup|upload|verify|cleanup)\s+state=(?P<state>start|end)\s+transport=(?P<transport>\S+)\s+repetition=(?P<repetition>\d+)\s*$")
METRIC_RE = re.compile(r"^B2 benchmark (?P<transport>[A-Za-z0-9_-]+):\s*(?P<body>.*)$")
CLEANUP_RE = re.compile(r"^B2 benchmark cleanup confirmed zero versions and delete markers:\s*prefix=(?P<prefix>.+)$")


class BenchmarkError(RuntimeError):
    pass


class IncompleteBenchmark(BenchmarkError):
    pass


@dataclasses.dataclass(frozen=True)
class BenchmarkConfig:
    env_file: Path | None = None
    dry_run: bool = False
    count: int = 300
    repetitions: int = 3
    baseline_seconds: int = 60
    sample_seconds: int = 2
    output_dir: Path | None = None
    concurrency: tuple[int, ...] = CONCURRENCY_MATRIX
    image: str = MAVEN_IMAGE
    seed: int = 20260826
    skip_one_cpu: bool = False

    @property
    def nominal_payload_bytes_per_run(self) -> int:
        return (self.count + WARMUP_UPLOAD_COUNT) * SOURCE_SIZE_BYTES

    @property
    def nominal_total_bytes(self) -> int:
        one_cpu_runs = 0 if self.skip_one_cpu else 1
        return self.nominal_payload_bytes_per_run * self.repetitions * (len(self.concurrency) + one_cpu_runs)


@dataclasses.dataclass
class RunRecord:
    cpu_quota: int
    concurrency: int
    repetition: int
    returncode: int | None
    metrics: list[dict[str, Any]]
    phases: list[dict[str, Any]]
    cleanup_confirmed: bool
    stats: list[dict[str, Any]] = dataclasses.field(default_factory=list)
    host_samples: list[dict[str, Any]] = dataclasses.field(default_factory=list)
    cgroup_samples: list[dict[str, Any]] = dataclasses.field(default_factory=list)
    telemetry: dict[str, Any] = dataclasses.field(default_factory=dict)
    interrupted: bool = False
    incomplete: bool = False
    valid: bool | None = None
    log: str = ""
    error: str | None = None
    jfr_path: str | None = None
    container_stop_confirmed: bool = True
    cleanup_unknown: bool = False

    @property
    def ok(self) -> bool:
        if self.valid is not None:
            return self.valid
        return self.returncode == 0 and not self.interrupted and not self.incomplete and bool(self.metrics) and self.cleanup_confirmed and all(m.get("failures") == 0 and m.get("successes") == m.get("count") for m in self.metrics)


@dataclasses.dataclass
class ProcessOutcome:
    returncode: int | None
    log: str
    stats: list[dict[str, Any]]
    host_samples: list[dict[str, Any]]
    cgroup_samples: list[dict[str, Any]]
    interrupted: bool = False
    timed_out: bool = False
    container_stop_confirmed: bool = True
    cleanup_unknown: bool = False
    error: str | None = None


def _clean(line: str) -> str:
    return ANSI_CSI.sub("", line).strip()


def _scalar(raw: str) -> Any:
    value = raw.strip()
    if value.lower() in ("true", "false"):
        return value.lower() == "true"
    if re.fullmatch(r"[-+]?\d+", value):
        return int(value)
    if re.fullmatch(r"[-+]?(?:\d+\.\d*|\d*\.\d+)(?:[eE][-+]?\d+)?", value):
        return float(value)
    return value


def parse_phase_line(line: str) -> dict[str, Any] | None:
    match = PHASE_RE.match(_clean(line))
    if not match:
        return None
    value = match.groupdict()
    value["epoch_ms"], value["repetition"] = int(value["epoch_ms"]), int(value["repetition"])
    return value


def parse_metric_line(line: str) -> dict[str, Any] | None:
    match = METRIC_RE.match(_clean(line))
    if not match:
        return None
    aliases = {"elapsedSeconds": "elapsed_seconds", "filesPerSecond": "files_per_second", "MiBPerSecond": "mib_per_second", "cleanupConfirmed": "cleanup_confirmed", "failureCategories": "failure_categories", "httpAttempts": "http_attempts", "retryAttempts": "retry_attempts", "peakInFlight": "peak_in_flight"}
    parsed: dict[str, Any] = {"transport": match.group("transport")}
    for field in re.split(r",\s+(?=[A-Za-z][A-Za-z0-9_-]*\s*=)", match.group("body")):
        key_match = re.match(r"([A-Za-z][A-Za-z0-9_-]*)\s*=", field)
        if key_match:
            key = key_match.group(1)
            parsed[aliases.get(key, key)] = _scalar(field[key_match.end():])
    if "count" not in parsed:
        parsed["summary"] = True
    return parsed


def parse_benchmark_log(text: str) -> dict[str, Any]:
    phases, metrics, cleanup = [], [], []
    for line in text.splitlines():
        if (value := parse_phase_line(line)) is not None:
            phases.append(value)
        if (value := parse_metric_line(line)) is not None:
            metrics.append(value)
        match = CLEANUP_RE.match(_clean(line))
        if match:
            cleanup.append({"prefix": match.group("prefix")})
    return {"phases": phases, "metrics": metrics, "cleanup_proofs": cleanup}


def weighted_throughput(runs: Iterable[Mapping[str, Any]], value_key: str = "successes") -> float:
    total_value = total_seconds = 0.0
    for run in runs:
        try:
            value, seconds = float(run.get(value_key)), float(run.get("elapsed_seconds", run.get("elapsedSeconds")))
        except (TypeError, ValueError):
            continue
        if value >= 0 and seconds > 0:
            total_value, total_seconds = total_value + value, total_seconds + seconds
    return total_value / total_seconds if total_seconds else 0.0


def weighted_files_per_second(runs: Iterable[Mapping[str, Any]]) -> float:
    prepared = []
    for run in runs:
        if run.get("successes") is not None:
            prepared.append(run)
            continue
        seconds = run.get("elapsed_seconds", run.get("elapsedSeconds"))
        rate = run.get("files_per_second", run.get("filesPerSecond"))
        try:
            prepared.append({"successes": float(rate) * float(seconds), "elapsed_seconds": seconds})
        except (TypeError, ValueError):
            continue
    return weighted_throughput(prepared, "successes")


def _value(record: RunRecord | Mapping[str, Any], name: str, default: Any = None) -> Any:
    return record.get(name, default) if isinstance(record, Mapping) else getattr(record, name, default)


def _is_ok(record: RunRecord | Mapping[str, Any]) -> bool:
    if isinstance(record, Mapping):
        if "ok" in record:
            return bool(record["ok"])
        metrics = record.get("metrics", []) or []
        return bool(metrics) and bool(record.get("cleanup_confirmed", True)) and all(m.get("failures") == 0 and m.get("successes") == m.get("count") for m in metrics)
    return record.ok


def choose_minimal_concurrency(records: Iterable[RunRecord | Mapping[str, Any]]) -> int | None:
    groups: dict[int, list[Mapping[str, Any]]] = {}
    for record in records:
        concurrency, metrics = int(_value(record, "concurrency", 0) or 0), _value(record, "metrics", []) or []
        if concurrency and metrics and _is_ok(record):
            groups.setdefault(concurrency, []).extend(metrics)
    rates = {key: weighted_files_per_second(value) for key, value in groups.items()}
    rates = {key: value for key, value in rates.items() if value > 0}
    if not rates:
        return None
    threshold = max(rates.values()) * 0.90
    return min(key for key, value in rates.items() if value >= threshold)


def fixed_matrix(config: BenchmarkConfig) -> list[tuple[int, int]]:
    return [(CPU_QUOTA, concurrency) for concurrency in config.concurrency]


def required_phase_pairs(phases: Iterable[Mapping[str, Any]]) -> bool:
    pairs = {(str(value.get("phase")), str(value.get("state"))) for value in phases}
    return all((phase, state) in pairs for phase in ("setup", "warmup", "upload", "verify", "cleanup") for state in ("start", "end"))


def metric_unavailable(reason: str = "not reported") -> dict[str, Any]:
    return {"available": False, "value": None, "reason": reason}


def metric_value(value: Any) -> dict[str, Any]:
    return {"available": True, "value": value, "reason": None}


def _bytes(raw: str) -> float | None:
    match = re.match(r"^\s*([\d.,]+)\s*([KMGT]?i?B)?\s*$", raw, re.IGNORECASE)
    if not match:
        return None
    try:
        value = float(match.group(1).replace(",", ""))
    except ValueError:
        return None
    scale = {"b": 1, "kb": 1000, "mb": 1000**2, "gb": 1000**3, "kib": 1024, "mib": 1024**2, "gib": 1024**3}
    return value * scale.get((match.group(2) or "B").lower(), 1)


def parse_docker_stats_line(line: str) -> dict[str, Any] | None:
    try:
        raw = json.loads(_clean(line))
    except json.JSONDecodeError:
        return None
    try:
        cpu = float(str(raw.get("CPUPerc", "")).rstrip("%"))
    except ValueError:
        cpu = None
    memory = _bytes(str(raw.get("MemUsage", "")).split("/", 1)[0])
    network = [part.strip() for part in str(raw.get("NetIO", "")).split("/", 1)]
    rx, tx = (_bytes(network[0]) if network else None), (_bytes(network[1]) if len(network) == 2 else None)
    return {"timestamp_utc": utc_now(), "timestamp_ms": int(time.time() * 1000), "name": raw.get("Name") or raw.get("ID"), "cpu_percent": metric_unavailable("Docker CPU percentage unavailable") if cpu is None else metric_value(cpu), "memory_bytes": metric_unavailable("Docker memory usage unavailable") if memory is None else metric_value(memory), "network_rx_bytes": metric_unavailable("Docker network receive unavailable") if rx is None else metric_value(rx), "network_tx_bytes": metric_unavailable("Docker network transmit unavailable") if tx is None else metric_value(tx)}


def parse_proc_stat(text: str) -> dict[str, int] | None:
    line = next((line for line in text.splitlines() if line.startswith("cpu ")), None)
    if line is None:
        return None
    try:
        values = [int(item) for item in line.split()[1:]]
    except ValueError:
        return None
    if len(values) < 4:
        return None
    names = ("user", "nice", "system", "idle", "iowait", "irq", "softirq", "steal")
    return {name: values[index] if index < len(values) else 0 for index, name in enumerate(names)}


def parse_meminfo(text: str) -> dict[str, int] | None:
    result = {}
    for line in text.splitlines():
        match = re.match(r"^(MemTotal|MemAvailable):\s+(\d+)\s+kB", line)
        if match:
            result[match.group(1)] = int(match.group(2)) * 1024
    return result or None


def parse_cgroup_cpu_stat(text: str) -> dict[str, int] | None:
    result = {}
    for line in text.splitlines():
        key, _, value = line.partition(" ")
        if key in {"usage_usec", "nr_periods", "nr_throttled", "throttled_usec"}:
            try:
                result[key] = int(value)
            except ValueError:
                return None
    return result or None


def delta_counter(before: Mapping[str, int] | None, after: Mapping[str, int] | None) -> dict[str, Any]:
    if before is None or after is None:
        return metric_unavailable("cgroup CPU counters unavailable")
    return metric_value({key: after[key] - before.get(key, 0) for key in after})


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def redact(text: str, secrets: Iterable[str] = ()) -> str:
    for secret in secrets:
        if secret:
            text = text.replace(secret, "[REDACTED]")
    text = re.sub(r"(?i)(B2_(?:ACCESS_KEY_ID|SECRET_ACCESS_KEY)|AWS_(?:ACCESS_KEY_ID|SECRET_ACCESS_KEY))\s*[=:]\s*[^\s,;]+", r"\1=[REDACTED]", text)
    text = re.sub(r"(?i)(x-amz-signature|signature)\s*[=:]\s*[A-F0-9]{16,}", r"\1=[REDACTED]", text)
    text = re.sub(r"(?i)(authorization:\s*aws4-hmac-sha256\s+credential=)[^\s,]+", r"\1[REDACTED]", text)
    return text


def parse_env_file(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    with path.open(encoding="utf-8") as stream:
        for raw in stream:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("export "):
                line = line[7:].lstrip()
            key, separator, value = line.partition("=")
            if separator and key.strip() in ALLOWED_ENV_KEYS:
                value = value.strip()
                if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
                    value = value[1:-1]
                if "\x00" in value or "\r" in value or "\n" in value:
                    raise BenchmarkError(f"{key} contains an invalid value")
                result[key.strip()] = value
    missing = [key for key in ALLOWED_ENV_KEYS if not result.get(key, "").strip()]
    if missing:
        raise BenchmarkError("env file is missing required benchmark keys: " + ", ".join(missing))
    if not result["B2_OBJECT_PREFIX"].strip("/").strip():
        raise BenchmarkError("B2_OBJECT_PREFIX must be non-empty for an isolated benchmark")
    return result


def parse_args(argv: Sequence[str] | None = None) -> BenchmarkConfig:
    parser = argparse.ArgumentParser(description="Run a bounded B2 CPU benchmark")
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--count", type=int, default=300)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--baseline-seconds", type=int, default=60)
    parser.add_argument("--sample-seconds", type=int, default=2)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--concurrency", nargs="+", type=int, choices=(4, 8, 16, 32), default=CONCURRENCY_MATRIX,
                        help="2-CPU concurrency values (default: 8 16 32)")
    parser.add_argument("--skip-one-cpu", action="store_true", help="run only the requested 2-CPU comparison")
    args = parser.parse_args(argv)
    if not args.dry_run and args.env_file is None:
        parser.error("--env-file is required unless --dry-run is used")
    for value, low, high, name in ((args.count, 1, 500, "count"), (args.repetitions, 1, 5, "repetitions"), (args.baseline_seconds, 0, 300, "baseline-seconds"), (args.sample_seconds, 1, 10, "sample-seconds")):
        if not low <= value <= high:
            parser.error(f"--{name} must be between {low} and {high}")
    if len(set(args.concurrency)) != len(args.concurrency):
        parser.error("--concurrency values must be unique")
    return BenchmarkConfig(args.env_file, args.dry_run, args.count, args.repetitions, args.baseline_seconds,
                           args.sample_seconds, args.output_dir, concurrency=tuple(args.concurrency),
                           skip_one_cpu=args.skip_one_cpu)


def _minimal_env(credentials: Mapping[str, str] | None = None) -> dict[str, str]:
    env = {"PATH": os.environ.get("PATH", ""), "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"}
    if credentials:
        env.update({key: credentials[key] for key in ALLOWED_ENV_KEYS if key in credentials})
    return env


def _uid_gid() -> tuple[int, int]:
    if not hasattr(os, "getuid") or not hasattr(os, "getgid") or os.getuid() == 0:
        raise BenchmarkError("live B2 benchmark requires a non-root host UID")
    return os.getuid(), os.getgid()


# Kept as small test seams for the offline process-boundary tests.
_host_uid_gid = _uid_gid


def _owner_name(prefix: str = "b2cpu") -> str:
    return f"{prefix}-{dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:12]}"


def _common_docker_args(config: BenchmarkConfig, name: str, cpu: int, project: Path | None, cache: Path | None) -> list[str]:
    uid, gid = _host_uid_gid()
    if not name.startswith("b2cpu-"):
        raise BenchmarkError("refusing a non-owned container name")
    args = ["docker", "run", "--pull=never", "--rm", "--name", name, "--cpus", str(cpu), "--memory", "3g", "--memory-swap", "3g", "--pids-limit", "512", "--user", f"{uid}:{gid}", "--env", "HOME=/home/bench", "--env", "MAVEN_CONFIG=/home/bench/.m2", "--env", "MAVEN_OPTS=-Xmx512m -XX:MaxDirectMemorySize=256m"]
    if project is not None and cache is not None:
        args += ["--volume", f"{project.resolve()}:/workspace:rw", "--volume", f"{cache.resolve()}:/home/bench/.m2:rw", "--workdir", "/workspace"]
    return args


def build_prepare_args(config: BenchmarkConfig, *, container_name: str, project_dir: Path, maven_cache: Path) -> list[str]:
    return _common_docker_args(config, container_name, CPU_QUOTA, project_dir, maven_cache) + [config.image, "mvn", "-B", "-DskipTests", "-Dmaven.repo.local=/home/bench/.m2/repository", "test-compile"]


def build_docker_args(config: BenchmarkConfig, *, container_name: str, output_path: Path, cpu_quota: int, concurrency: int, repetition: int, project_dir: Path, maven_cache: Path, jfr_path: Path | None = None) -> list[str]:
    jfr_path = jfr_path or output_path.with_suffix(".jfr")
    args = _common_docker_args(config, container_name, cpu_quota, project_dir, maven_cache)
    args += ["--env", "B2_ENDPOINT", "--env", "B2_BUCKET", "--env", "B2_ACCESS_KEY_ID", "--env", "B2_SECRET_ACCESS_KEY", "--env", "B2_OBJECT_PREFIX", "--volume", f"{output_path.parent.resolve()}:/artifacts:rw"]
    arg_line = f"-Xmx512m -XX:MaxDirectMemorySize=256m -XX:StartFlightRecording=filename=/artifacts/{jfr_path.name},settings=/workspace/scripts/b2-cpu.jfc,dumponexit=true,maxsize=64m"
    return args + [config.image, "mvn", "-B", "-Dtest=B2TransportBenchmarkIT", "-Db2.benchmark.enabled=true", "-Db2.benchmark.transports=sync", f"-Db2.benchmark.count={config.count}", f"-Db2.benchmark.concurrency={concurrency}", "-Db2.benchmark.repetitions=1", f"-Db2.benchmark.seed={config.seed + repetition}", "-Dmaven.repo.local=/home/bench/.m2/repository", f"-DargLine={arg_line}", "test"]


def _check_image(config: BenchmarkConfig, env: Mapping[str, str]) -> None:
    result = subprocess.run(["docker", "image", "inspect", config.image], env=dict(env), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, timeout=30, check=False)
    if result.returncode:
        raise BenchmarkError(f"cached Docker image is unavailable: {config.image}")


def _stop_owned(name: str, env: Mapping[str, str]) -> bool:
    """Stop exactly one generated container; return whether Docker confirmed it."""
    if not name.startswith("b2cpu-"):
        return False
    try:
        stopped = subprocess.run(["docker", "stop", "--time", "10", name], env=dict(env), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30, check=False)
        if getattr(stopped, "returncode", 1) == 0:
            return True
    except (OSError, subprocess.SubprocessError):
        pass
    # A failed stop can leave a credentialed test container writing to B2.  A
    # bounded exact-name kill is the only fallback; never use a broad selector.
    try:
        killed = subprocess.run(["docker", "kill", name], env=dict(env), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30, check=False)
        return getattr(killed, "returncode", 1) == 0
    except (OSError, subprocess.SubprocessError):
        return False


_stop_owned_container = _stop_owned


def _reap(process: subprocess.Popen[str] | None, timeout: float = 10) -> bool:
    if process is None:
        return True
    if process.poll() is None:
        try:
            process.terminate()
        except OSError:
            pass
    try:
        process.wait(timeout=timeout)
        return True
    except subprocess.TimeoutExpired:
        pass
    try:
        process.kill()
    except OSError:
        pass
    try:
        process.wait(timeout=timeout)
        return True
    except subprocess.TimeoutExpired:
        # The process is no longer controllable; retain the explicit unknown
        # state in the caller rather than spinning or killing another process.
        return False


class StatsStream:
    def __init__(self, sample_seconds: int, env: Mapping[str, str], owner: str):
        self.sample_seconds, self.env, self.owner = sample_seconds, dict(env), owner
        self.samples: list[dict[str, Any]] = []
        self.process: subprocess.Popen[str] | None = None
        self.thread: threading.Thread | None = None
        self.thread_started = False
        self.stop_event = threading.Event()

    def start(self) -> None:
        self.process = subprocess.Popen(["docker", "stats", "--all", "--no-trunc", "--format", "{{json .}}"], env=self.env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8", errors="replace")
        self.thread = threading.Thread(target=self._read, daemon=True)
        self.thread.start()
        self.thread_started = True

    def _read(self) -> None:
        assert self.process is not None and self.process.stdout is not None
        last: dict[str, float] = {}
        for line in self.process.stdout:
            if self.stop_event.is_set():
                break
            sample = parse_docker_stats_line(line)
            if sample is None:
                continue
            name, now = str(sample.get("name") or "unknown"), time.monotonic()
            if now - last.get(name, 0) >= self.sample_seconds:
                self.samples.append(sample)
                last[name] = now

    def stop(self) -> None:
        self.stop_event.set()
        _reap(self.process, 2)
        if self.thread is not None and self.thread_started:
            self.thread.join(timeout=2)
        if self.process is not None and self.process.stdout is not None:
            self.process.stdout.close()


def _host_sample() -> dict[str, Any]:
    sample = {"timestamp_utc": utc_now(), "timestamp_ms": int(time.time() * 1000)}
    try:
        cpu = parse_proc_stat(Path("/proc/stat").read_text(encoding="utf-8"))
        memory = parse_meminfo(Path("/proc/meminfo").read_text(encoding="utf-8"))
    except OSError as exc:
        sample["cpu"], sample["memory"] = metric_unavailable(str(exc)), metric_unavailable(str(exc))
        return sample
    sample["cpu"] = metric_unavailable("/proc/stat unavailable") if cpu is None else metric_value(cpu)
    sample["memory"] = metric_unavailable("/proc/meminfo unavailable") if memory is None else metric_value(memory)
    return sample


def _cgroup_sample(name: str, env: Mapping[str, str]) -> dict[str, Any]:
    result = subprocess.run(["docker", "exec", name, "cat", "/sys/fs/cgroup/cpu.stat"], env=dict(env), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=5, check=False)
    value = parse_cgroup_cpu_stat(result.stdout) if result.returncode == 0 else None
    return {"timestamp_utc": utc_now(), "timestamp_ms": int(time.time() * 1000), "cpu": metric_unavailable("cgroup CPU counters unavailable") if value is None else metric_value(value)}


def _owned_process(args: Sequence[str], env: Mapping[str, str], name: str, timeout_seconds: int, sample_seconds: int, collect_stats: bool = True) -> ProcessOutcome:
    process: subprocess.Popen[str] | None = None
    stream: StatsStream | None = None
    output = [f"B2 CPU collector container: {name}\n"]
    output_size = len(output[0])
    hosts, cgroups = [], []
    q: queue.Queue[str | None] = queue.Queue()
    reader: threading.Thread | None = None
    reader_started = False
    started = next_sample = 0.0
    done = interrupted = timed_out = False
    stop_confirmed, cleanup_unknown, error = True, False, None

    def stop_and_reap() -> None:
        nonlocal stop_confirmed, cleanup_unknown
        stop_confirmed = _stop_owned_container(name, env)
        cleanup_unknown = not stop_confirmed
        if not _reap(process):
            cleanup_unknown = True

    def append_output(line: str) -> None:
        nonlocal output_size
        if output_size < 10_000_000:
            output.append(line)
            output_size += len(line)

    try:
        # Every child/thread startup is inside this lifecycle guard.  If a
        # collector setup error occurs after docker run started, stop only the
        # generated container name before returning a partial outcome.
        process = subprocess.Popen(list(args), env=dict(env), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
        if collect_stats:
            stream = StatsStream(sample_seconds, env, name)
            stream.start()

        def read_output() -> None:
            assert process is not None and process.stdout is not None
            for line in process.stdout:
                q.put(line)
            q.put(None)

        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        reader_started = True
        started, next_sample = time.monotonic(), 0.0
        while not done or process.poll() is None:
            now = time.monotonic()
            if now - started > timeout_seconds:
                timed_out = True
                stop_and_reap()
                break
            if collect_stats and now >= next_sample:
                hosts.append(_host_sample())
                try:
                    cgroups.append(_cgroup_sample(name, env))
                except (OSError, subprocess.SubprocessError):
                    cgroups.append({"timestamp_utc": utc_now(), "timestamp_ms": int(time.time() * 1000), "cpu": metric_unavailable("cgroup sample failed")})
                next_sample = now + sample_seconds
            try:
                line = q.get(timeout=0.2)
            except queue.Empty:
                continue
            if line is None:
                done = True
            else:
                append_output(line)
        if not timed_out:
            process.wait()
    except KeyboardInterrupt:
        interrupted = True
        stop_and_reap()
    except Exception as exc:
        error = str(exc)
        if process is None or process.poll() is None:
            stop_and_reap()
    finally:
        if reader is not None and reader_started:
            reader.join(timeout=2)
        while True:
            try:
                line = q.get_nowait()
            except queue.Empty:
                break
            if line:
                append_output(line)
        if stream is not None:
            stream.stop()
    return ProcessOutcome(process.returncode if process is not None else None, "".join(output), stream.samples if stream else [], hosts, cgroups, interrupted, timed_out, stop_confirmed, cleanup_unknown, error)


def _phase_window(phases: Sequence[Mapping[str, Any]], name: str) -> tuple[int, int] | None:
    starts = [int(value["epoch_ms"]) for value in phases if value.get("phase") == name and value.get("state") == "start"]
    ends = [int(value["epoch_ms"]) for value in phases if value.get("phase") == name and value.get("state") == "end"]
    return (min(starts), max(ends)) if starts and ends and min(starts) <= max(ends) else None


def _ordered_phases(phases: Sequence[Mapping[str, Any]]) -> bool:
    windows = [_phase_window(phases, name) for name in ("setup", "warmup", "upload", "verify", "cleanup")]
    return all(window is not None for window in windows) and all(
        windows[index][1] <= windows[index + 1][0] for index in range(len(windows) - 1)
    )


def _telemetry_summary(stats: Sequence[Mapping[str, Any]], phases: Sequence[Mapping[str, Any]], cgroups: Sequence[Mapping[str, Any]], owner: str | None = None) -> dict[str, Any]:
    if owner is not None:
        stats = [item for item in stats if item.get("name") == owner]
    upload = _phase_window(phases, "upload")
    def in_window(sample: Mapping[str, Any], window: tuple[int, int] | None) -> bool:
        timestamp = int(sample.get("timestamp_ms", 0))
        return window is not None and window[0] <= timestamp <= window[1]
    def cpu_summary(items: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
        values = [float(item["cpu_percent"]["value"]) for item in items if item.get("cpu_percent", {}).get("available")]
        return metric_unavailable("Docker CPU samples unavailable") if not values else metric_value({"mean": sum(values) / len(values), "max": max(values)})
    upload_stats = [item for item in stats if in_window(item, upload)]
    outside_stats = [item for item in stats if not in_window(item, upload)] if upload else []
    memory = [float(item["memory_bytes"]["value"]) for item in stats if item.get("memory_bytes", {}).get("available")]
    def network(field: str) -> dict[str, Any]:
        values = [float(item[field]["value"]) for item in stats if item.get(field, {}).get("available")]
        return metric_unavailable("Docker network samples unavailable") if len(values) < 2 else metric_value({"first": values[0], "last": values[-1], "delta": values[-1] - values[0]})
    counters = [item.get("cpu", {}).get("value") for item in cgroups if item.get("cpu", {}).get("available")]
    throttle = metric_unavailable("cgroup CPU counters unavailable") if len(counters) < 2 else delta_counter(counters[0], counters[-1])
    return {"upload_cpu": cpu_summary(upload_stats), "outside_cpu": cpu_summary(outside_stats), "max_memory_bytes": metric_unavailable("Docker memory samples unavailable") if not memory else metric_value(max(memory)), "network_rx": network("network_rx_bytes"), "network_tx": network("network_tx_bytes"), "throttling": throttle}


def validate_run(*, returncode: int | None, parsed: Mapping[str, Any], expected_count: int, interrupted: bool = False) -> tuple[bool, str | None, list[dict[str, Any]]]:
    metrics = [item for item in parsed.get("metrics", []) if item.get("transport") == "sync" and "count" in item]
    if returncode != 0:
        return False, f"benchmark exited with status {returncode}", metrics
    if interrupted:
        return False, "benchmark interrupted; cleanup cannot be claimed", metrics
    if not metrics:
        return False, "measured metric is missing", metrics
    if len(metrics) != 1:
        return False, "expected exactly one measured Sync metric", metrics
    metric = metrics[0]
    if metric.get("count") != expected_count:
        return False, "measured run count does not match requested count", metrics
    if metric.get("successes") != expected_count or metric.get("failures") != 0:
        return False, "measured uploads include failures or an incomplete count", metrics
    try:
        elapsed = float(metric.get("elapsed_seconds"))
    except (TypeError, ValueError):
        elapsed = 0.0
    if not math.isfinite(elapsed) or elapsed <= 0:
        return False, "measured elapsed time is invalid", metrics
    if metric.get("bytes") != expected_count * SOURCE_SIZE_BYTES:
        return False, "measured payload bytes do not match requested count", metrics
    if not required_phase_pairs(parsed.get("phases", [])) or not _ordered_phases(parsed.get("phases", [])):
        return False, "one or more benchmark phase markers are incomplete", metrics
    if not parsed.get("cleanup_proofs"):
        return False, "cleanup proof is missing", metrics
    return True, None, metrics


def execute_one_run(config: BenchmarkConfig, *, credentials: Mapping[str, str], output_path: Path, cpu_quota: int, concurrency: int, repetition: int, project_dir: Path, maven_cache: Path) -> RunRecord:
    container, jfr, env = _owner_name(), output_path.with_suffix(".jfr"), _minimal_env(credentials)
    args = build_docker_args(config, container_name=container, output_path=output_path, cpu_quota=cpu_quota, concurrency=concurrency, repetition=repetition, project_dir=project_dir, maven_cache=maven_cache, jfr_path=jfr)
    process_error = None
    try:
        # The Java harness itself caps aggregate upload waiting at 15 minutes.
        outcome = _owned_process(args, env, container, max(1800, config.baseline_seconds + 180), config.sample_seconds)
    except (OSError, subprocess.SubprocessError) as exc:
        outcome, process_error = ProcessOutcome(None, "", [], [], []), str(exc)
    process_error = process_error or outcome.error
    parsed = parse_benchmark_log(outcome.log)
    valid, reason, metrics = validate_run(returncode=outcome.returncode, parsed=parsed, expected_count=config.count, interrupted=outcome.interrupted)
    telemetry = _telemetry_summary(outcome.stats, parsed["phases"], outcome.cgroup_samples, container)
    host_available = any(
        sample.get("cpu", {}).get("available") and sample.get("memory", {}).get("available")
        for sample in outcome.host_samples
    )
    if process_error:
        valid, reason = False, process_error
    elif outcome.timed_out:
        valid, reason = False, "benchmark container deadline expired"
    elif outcome.cleanup_unknown or not outcome.container_stop_confirmed:
        valid, reason = False, "owned container stop/cleanup status is unknown"
    elif not host_available or not telemetry["upload_cpu"]["available"] or not telemetry["max_memory_bytes"]["available"]:
        valid, reason = False, "required owned-container telemetry is unavailable"
    elif not jfr.is_file() or jfr.stat().st_size <= 0:
        valid, reason = False, "JFR recording is missing or empty"
    error = redact(reason, (credentials.get(key, "") for key in SECRET_ENV_KEYS)) if reason else None
    safe_log = redact(outcome.log, (credentials.get(key, "") for key in SECRET_ENV_KEYS))
    output_path.write_text(safe_log, encoding="utf-8")
    cleanup_confirmed = not (outcome.interrupted or outcome.timed_out or outcome.cleanup_unknown) and bool(parsed.get("cleanup_proofs"))
    return RunRecord(cpu_quota, concurrency, repetition, outcome.returncode, metrics, list(parsed["phases"]), cleanup_confirmed, outcome.stats, outcome.host_samples, outcome.cgroup_samples, telemetry, outcome.interrupted, not valid, valid, safe_log, error, str(jfr), outcome.container_stop_confirmed, outcome.cleanup_unknown)


def run_sweep(config: BenchmarkConfig, run_one: Callable[[int, int, int], RunRecord]) -> tuple[list[RunRecord], bool, int | None]:
    records: list[RunRecord] = []
    for cpu, concurrency in fixed_matrix(config):
        for repetition in range(1, config.repetitions + 1):
            record = run_one(cpu, concurrency, repetition)
            records.append(record)
            if not record.ok:
                return records, False, None
    if config.skip_one_cpu:
        return records, True, None
    selected = choose_minimal_concurrency(records)
    if selected is None:
        return records, False, None
    for repetition in range(1, config.repetitions + 1):
        record = run_one(HEURISTIC_CPU_QUOTA, selected, repetition)
        records.append(record)
        if not record.ok:
            return records, False, selected
    return records, True, selected


class ArtifactWriter:
    def __init__(self, root: Path | None):
        self.root = (root or Path("target")).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.stem = "b2-cpu-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:10]
        self.run_dir, self.archive_path = self.root / self.stem, self.root / f"{self.stem}.tar.gz"
        self.files: list[Path] = []
        self.run_dir.mkdir()

    def path(self, name: str) -> Path:
        path = (self.run_dir / name).resolve()
        if self.run_dir not in path.parents:
            raise BenchmarkError("artifact path escapes unique run directory")
        path.parent.mkdir(parents=True, exist_ok=True)
        return path

    def write_text(self, name: str, value: str) -> Path:
        path = self.path(name)
        path.write_text(value, encoding="utf-8")
        self.register(path)
        return path

    def write_json(self, name: str, value: Any) -> Path:
        return self.write_text(name, json.dumps(value, indent=2, sort_keys=True) + "\n")

    def register(self, path: Path) -> None:
        path = path.resolve()
        if path not in self.files:
            self.files.append(path)

    def finalize(self, paths: Iterable[Path]) -> Path:
        with tarfile.open(self.archive_path, "w:gz") as archive:
            for path in dict.fromkeys(paths):
                path = path.resolve()
                if path.is_file() and self.run_dir in path.parents:
                    archive.add(path, arcname=str(path.relative_to(self.run_dir)))
        return self.archive_path


def _record_json(record: RunRecord) -> dict[str, Any]:
    return dataclasses.asdict(record)


def _summary_md(config: BenchmarkConfig, records: Sequence[RunRecord], complete: bool, selected: int | None) -> str:
    status = "dry-run" if config.dry_run else "complete" if complete else "incomplete"
    lines = [
        "# B2 CPU benchmark", "",
        f"- Status: {status}",
        f"- Generated (UTC): {utc_now()}",
        f"- Image: `{config.image}` (cached image only)",
        f"- Measured uploads per run: {config.count} × {SOURCE_SIZE_BYTES:,} bytes",
        f"- Nominal payload per run: {config.nominal_payload_bytes_per_run:,} bytes "
        f"({config.nominal_payload_bytes_per_run / 1024**2:.2f} MiB)",
        f"- Nominal logical payload for planned sweep: {config.nominal_total_bytes:,} bytes",
        "- Transport: Sync only. Retries can increase transferred bytes and temporary storage.",
        "- Docker 100% means one logical CPU; quota is not pinning or total host isolation.",
        ("- 1-CPU comparison: disabled." if config.skip_one_cpu else
         "- 1-CPU selection is the smallest concurrency within 90% of the best pooled files/s; "
         "this is a heuristic, not a production recommendation."),
        "", "## Upload results", "",
        "| CPU quota | Concurrency | Repeat | Files/s | Retries | Failures | Cleanup | Status |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | :---: | :---: |",
    ]
    for record in records:
        metric = record.metrics[-1] if record.metrics else {}
        lines.append(
            f"| {record.cpu_quota} | {record.concurrency} | {record.repetition} "
            f"| {metric.get('files_per_second', 'unavailable')} "
            f"| {metric.get('retry_attempts', 'unavailable')} "
            f"| {metric.get('failures', 'unavailable')} "
            f"| {'yes' if record.cleanup_confirmed else 'unconfirmed'} "
            f"| {'ok' if record.ok else 'failed'} |"
        )
    lines += [
        "", "## Sampled CPU and memory", "",
        "CPU columns are sample mean / maximum percentages. Short spikes may be missed.",
        "Outside upload includes JVM startup, warmup, version checks and cleanup.",
        "", "| CPU quota | Concurrency | Repeat | Upload CPU % | Outside CPU % | Peak RAM MiB |",
        "| ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for record in records:
        def cpu_text(field: str) -> str:
            item = record.telemetry.get(field, {})
            if not item.get("available"):
                return "unavailable"
            return f"{item['value']['mean']:.1f} / {item['value']['max']:.1f}"

        memory = record.telemetry.get("max_memory_bytes", {})
        memory_text = f"{memory['value'] / 1024**2:.1f}" if memory.get("available") else "unavailable"
        lines.append(
            f"| {record.cpu_quota} | {record.concurrency} | {record.repetition} "
            f"| {cpu_text('upload_cpu')} | {cpu_text('outside_cpu')} | {memory_text} |"
        )
    lines += ["", "## Pooled throughput", ""]
    for cpu, concurrency in sorted({(record.cpu_quota, record.concurrency) for record in records}):
        metrics = [metric for record in records
                   if record.ok and record.cpu_quota == cpu and record.concurrency == concurrency
                   for metric in record.metrics]
        if metrics:
            lines.append(
                f"- {cpu} CPU / concurrency {concurrency}: "
                f"{weighted_files_per_second(metrics):.3f} files/s across {len(metrics)} valid runs."
            )
    if not config.skip_one_cpu:
        lines += ["", f"Selected 1-CPU concurrency: {selected if selected is not None else 'unavailable'}", ""]
    for record in records:
        if record.error:
            lines.append(f"- Incomplete run {record.cpu_quota} CPU / {record.concurrency} / {record.repetition}: {record.error}")
    return "\n".join(lines)


def dry_run(config: BenchmarkConfig) -> Path:
    writer = ArtifactWriter(config.output_dir)
    plan = {"status": "dry-run", "image": config.image, "transport": "sync", "count": config.count, "repetitions": config.repetitions, "baseline_seconds": config.baseline_seconds, "sample_seconds": config.sample_seconds, "matrix": [{"cpu_quota": cpu, "concurrency": value} for cpu, value in fixed_matrix(config)], "one_cpu_enabled": not config.skip_one_cpu, "nominal_payload_bytes_per_run": config.nominal_payload_bytes_per_run, "nominal_total_bytes": config.nominal_total_bytes, "credentials_read": False, "docker_invoked": False}
    return writer.finalize([writer.write_json("summary.json", plan), writer.write_text("summary.md", _summary_md(config, [], False, None))])


def run_live(config: BenchmarkConfig) -> Path:
    writer = ArtifactWriter(config.output_dir)
    credentials: dict[str, str] = {}
    records: list[RunRecord] = []
    selected: int | None = None
    complete = False
    failure: str | None = None
    baseline: ProcessOutcome | None = None
    baseline_payload: dict[str, Any] | None = None
    try:
        print(f"B2 CPU benchmark: image={config.image}, count={config.count}, repetitions={config.repetitions}, baseline_seconds={config.baseline_seconds}")
        print(f"B2 CPU benchmark budget: nominal_payload_per_run={config.nominal_payload_bytes_per_run} bytes, planned_total={config.nominal_total_bytes} bytes")
        if platform.system() != "Linux":
            raise BenchmarkError("live B2 benchmark requires Linux")
        if config.env_file is None:
            raise BenchmarkError("--env-file is required in live mode")
        credentials = parse_env_file(config.env_file)
        project, cache = Path.cwd(), Path.home() / ".m2"
        cache.mkdir(parents=True, exist_ok=True)
        env = _minimal_env()
        _check_image(config, env)
        prep_name = _owner_name()
        prep = _owned_process(build_prepare_args(config, container_name=prep_name, project_dir=project, maven_cache=cache), env, prep_name, 900, config.sample_seconds, False)
        writer.write_text("build-preparation.log", redact(prep.log, (credentials.get(key, "") for key in SECRET_ENV_KEYS)))
        if prep.returncode != 0 or prep.interrupted or prep.timed_out or prep.error or prep.cleanup_unknown:
            raise BenchmarkError("Maven build preparation failed")
        if config.baseline_seconds:
            baseline_name = _owner_name()
            baseline = _owned_process(_common_docker_args(config, baseline_name, CPU_QUOTA, None, None) + [config.image, "sleep", str(config.baseline_seconds)], env, baseline_name, config.baseline_seconds + 60, config.sample_seconds)
            baseline_payload = {"stats": baseline.stats, "host_samples": baseline.host_samples, "cgroup_samples": baseline.cgroup_samples}
            writer.write_text("baseline.log", redact(baseline.log, (credentials.get(key, "") for key in SECRET_ENV_KEYS)))
            writer.write_json("baseline.json", baseline_payload)
            if baseline.returncode != 0 or baseline.interrupted or baseline.timed_out or baseline.error or baseline.cleanup_unknown or not baseline.stats or not baseline.host_samples:
                raise BenchmarkError("baseline telemetry or process is incomplete")

        def run_one(cpu: int, concurrency: int, repetition: int) -> RunRecord:
            print(f"B2 CPU benchmark: cpu_quota={cpu}, concurrency={concurrency}, repetition={repetition}")
            log_path = writer.path(f"runs/cpu{cpu}-c{concurrency}-r{repetition}.log")
            record = execute_one_run(config, credentials=credentials, output_path=log_path, cpu_quota=cpu, concurrency=concurrency, repetition=repetition, project_dir=project, maven_cache=cache)
            writer.register(log_path)
            if record.jfr_path:
                writer.register(Path(record.jfr_path))
            return record

        records, complete, selected = run_sweep(config, run_one)
    except (BenchmarkError, OSError, subprocess.SubprocessError, KeyboardInterrupt) as exc:
        failure = str(exc)
    finally:
        writer.write_json("metrics/runs.json", [_record_json(record) for record in records])
        safe_failure = redact(failure or "", (credentials.get(key, "") for key in SECRET_ENV_KEYS))
        writer.write_json("summary.json", {"status": "complete" if complete and failure is None else "incomplete", "error": safe_failure or None, "image": config.image, "transport": "sync", "count": config.count, "repetitions": config.repetitions, "nominal_payload_bytes_per_run": config.nominal_payload_bytes_per_run, "nominal_total_bytes": config.nominal_total_bytes, "one_cpu_enabled": not config.skip_one_cpu, "selected_one_cpu_concurrency": selected, "baseline": baseline_payload, "records": [_record_json(record) for record in records]})
        writer.write_text("summary.md", _summary_md(config, records, complete and failure is None, selected))
        archive = writer.finalize(writer.files)
    if failure is not None or not complete:
        raise IncompleteBenchmark(f"{failure or 'a benchmark run failed'}; archive={archive}")
    return archive


def main(argv: Sequence[str] | None = None) -> int:
    try:
        config = parse_args(argv)
        archive = dry_run(config) if config.dry_run else run_live(config)
        print(archive)
        return 0
    except (BenchmarkError, OSError, subprocess.SubprocessError) as exc:
        print(f"b2 CPU benchmark: {exc}", file=os.sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
