import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts import b2_cpu_benchmark as bench


def complete_sync_log(count=1):
    return "\n".join([
        "B2_BENCH_PHASE epoch_ms=1000 phase=setup state=start transport=sync repetition=1",
        "B2_BENCH_PHASE epoch_ms=1001 phase=setup state=end transport=sync repetition=1",
        "B2_BENCH_PHASE epoch_ms=1002 phase=warmup state=start transport=sync repetition=1",
        "B2_BENCH_PHASE epoch_ms=1003 phase=warmup state=end transport=sync repetition=1",
        "B2_BENCH_PHASE epoch_ms=1004 phase=upload state=start transport=sync repetition=1",
        "B2_BENCH_PHASE epoch_ms=1005 phase=upload state=end transport=sync repetition=1",
        f"B2 benchmark sync: count={count}, bytes={count * bench.SOURCE_SIZE_BYTES}, elapsedSeconds=1.0, filesPerSecond={count}.0, successes={count}, failures=0",
        "B2_BENCH_PHASE epoch_ms=1006 phase=verify state=start transport=sync repetition=1",
        "B2_BENCH_PHASE epoch_ms=1007 phase=verify state=end transport=sync repetition=1",
        "B2_BENCH_PHASE epoch_ms=1008 phase=cleanup state=start transport=sync repetition=1",
        "B2 benchmark cleanup confirmed zero versions and delete markers: prefix=validator/bench/run",
        "B2_BENCH_PHASE epoch_ms=1009 phase=cleanup state=end transport=sync repetition=1",
    ])


class B2CpuBenchmarkParsingTest(unittest.TestCase):
    def test_phase_and_metric_lines_are_parsed_without_losing_unknown_fields(self):
        phase = bench.parse_phase_line(
            "B2_BENCH_PHASE epoch_ms=1700000000123 phase=upload state=start "
            "transport=sync repetition=2"
        )
        self.assertEqual(
            phase,
            {
                "epoch_ms": 1700000000123,
                "phase": "upload",
                "state": "start",
                "transport": "sync",
                "repetition": 2,
            },
        )

        metric = bench.parse_metric_line(
            "B2 benchmark sync: count=300, bytes=442368000, elapsedSeconds=12.5, "
            "filesPerSecond=24.0, MiBPerSecond=33.75, successes=300, failures=0, "
            "cleanupConfirmed=true, retryAttempts=2"
        )
        self.assertEqual(metric["transport"], "sync")
        self.assertEqual(metric["count"], 300)
        self.assertEqual(metric["successes"], 300)
        self.assertEqual(metric["cleanup_confirmed"], True)
        self.assertEqual(metric["retry_attempts"], 2)

    def test_weighted_throughput_uses_bytes_and_elapsed_time(self):
        self.assertAlmostEqual(
            bench.weighted_throughput(
                [{"successes": 2, "elapsed_seconds": 2.0},
                 {"successes": 6, "elapsed_seconds": 3.0}]
            ),
            8 / 5.0,
        )


class B2CpuBenchmarkSweepTest(unittest.TestCase):
    def _record(self, cpu, concurrency, repetition, rate=10.0, cleanup=True):
        metric = {
            "transport": "sync",
            "count": 2,
            "successes": 2,
            "failures": 0,
            "elapsed_seconds": 2.0 / rate,
        }
        return bench.RunRecord(
            cpu_quota=cpu,
            concurrency=concurrency,
            repetition=repetition,
            returncode=0,
            metrics=[metric],
            phases=[],
            cleanup_confirmed=cleanup,
            valid=cleanup,
        )

    def test_fixed_matrix_and_smallest_within_ninety_percent(self):
        config = bench.BenchmarkConfig(repetitions=2)
        self.assertEqual(bench.fixed_matrix(config), [(2, 8), (2, 16), (2, 32)])
        records = [
            self._record(2, 8, 1, 9), self._record(2, 8, 2, 9),
            self._record(2, 16, 1, 10), self._record(2, 16, 2, 10),
            self._record(2, 32, 1, 11), self._record(2, 32, 2, 11),
        ]
        self.assertEqual(bench.choose_minimal_concurrency(records), 16)

    def test_failed_or_unclean_run_stops_before_later_matrix_entries(self):
        config = bench.BenchmarkConfig(repetitions=2)
        calls = []

        def run_one(cpu, concurrency, repetition):
            calls.append((cpu, concurrency, repetition))
            return self._record(cpu, concurrency, repetition, cleanup=False)

        records, complete, selected = bench.run_sweep(config, run_one)
        self.assertFalse(complete)
        self.assertIsNone(selected)
        self.assertEqual(len(records), 1)
        self.assertEqual(calls, [(2, 8, 1)])


class B2CpuBenchmarkSafetyTest(unittest.TestCase):
    def test_summary_reports_real_payload_cpu_and_dry_run_status(self):
        config = bench.BenchmarkConfig(count=2, dry_run=True)
        summary = bench._summary_md(config, [], False, None)
        self.assertIn("Status: dry-run", summary)
        self.assertIn("4.22 MiB", summary)

        record = bench.RunRecord(
            2, 8, 1, 0,
            [{"files_per_second": 2.0, "retry_attempts": 1, "failures": 0}],
            [], True, valid=True,
            telemetry={
                "upload_cpu": bench.metric_value({"mean": 25.0, "max": 50.0}),
                "outside_cpu": bench.metric_value({"mean": 10.0, "max": 80.0}),
                "max_memory_bytes": bench.metric_value(128 * 1024 * 1024),
            },
        )
        summary = bench._summary_md(bench.BenchmarkConfig(count=2), [record], True, 8)
        self.assertIn("25.0 / 50.0", summary)
        self.assertIn("10.0 / 80.0", summary)
        self.assertIn("128.0", summary)

    def test_dry_run_is_offline_and_creates_one_explicit_archive(self):
        with tempfile.TemporaryDirectory() as temp:
            with mock.patch.object(bench, "parse_env_file", side_effect=AssertionError("env read")), \
                    mock.patch.object(bench.subprocess, "run", side_effect=AssertionError("docker invoked")):
                archive = bench.dry_run(
                    bench.BenchmarkConfig(dry_run=True, output_dir=Path(temp), count=1, repetitions=1)
                )
            self.assertTrue(archive.name.endswith(".tar.gz"))
            with tarfile.open(archive, "r:gz") as handle:
                names = sorted(handle.getnames())
            self.assertEqual(names, ["summary.json", "summary.md"])

    def test_docker_stats_ansi_frame_is_parsed_and_telemetry_is_not_zero(self):
        frame = (
            "\x1b[H\x1b[2J"
            '{"Name":"b2cpu-owned","CPUPerc":"100.00%","MemUsage":"12.5MiB / 3GiB",'
            '"NetIO":"1.2MB / 4.0MB"}\x1b[K'
        )
        sample = bench.parse_docker_stats_line(frame)
        self.assertEqual(sample["name"], "b2cpu-owned")
        self.assertTrue(sample["cpu_percent"]["available"])
        self.assertEqual(sample["cpu_percent"]["value"], 100.0)
        self.assertEqual(sample["memory_bytes"]["value"], 12.5 * 1024 * 1024)
        self.assertEqual(sample["network_tx_bytes"]["value"], 4.0 * 1000 * 1000)
        self.assertFalse(bench.metric_unavailable()["available"])

    def test_env_allowlist_and_docker_argv_never_expose_credentials_or_jvm_options(self):
        with tempfile.TemporaryDirectory() as temp:
            env_file = Path(temp) / "input.env"
            env_file.write_text(
                "B2_ENDPOINT=https://s3.us-east-005.backblazeb2.com\n"
                "B2_BUCKET=benchmark-bucket\n"
                "B2_ACCESS_KEY_ID=access-sentinel\n"
                "B2_SECRET_ACCESS_KEY=secret-sentinel\n"
                "B2_OBJECT_PREFIX=validator/bench\n"
                "UNRELATED=ignored\n",
                encoding="utf-8",
            )
            credentials = bench.parse_env_file(env_file)
            self.assertNotIn("UNRELATED", credentials)
            with mock.patch.object(bench, "_host_uid_gid", return_value=(1000, 1000)):
                args = bench.build_docker_args(
                    bench.BenchmarkConfig(count=1, repetitions=1),
                    container_name="b2cpu-owned",
                    output_path=Path(temp) / "run.log",
                    cpu_quota=2,
                    concurrency=8,
                    repetition=1,
                    project_dir=Path(temp),
                    maven_cache=Path(temp) / "m2",
                )
            rendered = " ".join(args)
            self.assertNotIn("access-sentinel", rendered)
            self.assertNotIn("secret-sentinel", rendered)
            self.assertIn("-DargLine=", rendered)
            self.assertIn("-Xmx512m", rendered)
            self.assertIn("MaxDirectMemorySize=256m", rendered)
            self.assertIn("maxsize=64m", rendered)
            self.assertNotIn("JAVA_TOOL_OPTIONS", rendered)
            self.assertNotIn("_JAVA_OPTIONS", rendered)

    def test_archive_and_log_redaction_are_explicit_and_recoverable(self):
        with tempfile.TemporaryDirectory() as temp:
            writer = bench.ArtifactWriter(Path(temp))
            log = writer.write_text(
                "runs/output.log",
                bench.redact(
                    "B2_SECRET_ACCESS_KEY=secret-sentinel\nAuthorization: AWS4-HMAC-SHA256 "
                    "Credential=access-sentinel, x-amz-signature=ABCDEF0123456789ABCDEF",
                    ["access-sentinel", "secret-sentinel"],
                ),
            )
            summary = writer.write_json("summary.json", {"status": "incomplete"})
            archive = writer.finalize([log, summary])
            with tarfile.open(archive, "r:gz") as handle:
                names = handle.getnames()
                contents = "\n".join(handle.extractfile(name).read().decode() for name in names)
            self.assertEqual(sorted(names), ["runs/output.log", "summary.json"])
            self.assertNotIn("access-sentinel", contents)
            self.assertNotIn("secret-sentinel", contents)
            self.assertNotIn("ABCDEF0123456789ABCDEF", contents)

    def test_owned_container_stop_does_not_accept_production_name(self):
        with mock.patch.object(bench.subprocess, "run", return_value=mock.Mock(returncode=0)) as run:
            bench._stop_owned_container("validator-api", {})
            run.assert_not_called()
            bench._stop_owned_container("b2cpu-owned", {})
            run.assert_called_once()
            self.assertEqual(run.call_args.args[0][-1], "b2cpu-owned")

    def test_owned_stop_falls_back_to_exact_name_kill_and_reports_confirmation(self):
        stop_failed = mock.Mock(returncode=1)
        kill_succeeded = mock.Mock(returncode=0)
        with mock.patch.object(bench.subprocess, "run", side_effect=[stop_failed, kill_succeeded]) as run:
            self.assertTrue(bench._stop_owned_container("b2cpu-owned", {}))
        self.assertEqual(run.call_count, 2)
        self.assertEqual(run.call_args_list[0].args[0], ["docker", "stop", "--time", "10", "b2cpu-owned"])
        self.assertEqual(run.call_args_list[1].args[0], ["docker", "kill", "b2cpu-owned"])

        with mock.patch.object(bench.subprocess, "run", side_effect=OSError("docker unavailable")) as run:
            self.assertFalse(bench._stop_owned_container("b2cpu-owned", {}))
        self.assertEqual(run.call_count, 2)

    def test_reap_waits_after_kill(self):
        class StubbornProcess:
            def __init__(self):
                self.wait_calls = 0
                self.killed = False

            def poll(self):
                return None if not self.killed else -9

            def terminate(self):
                pass

            def wait(self, timeout=None):
                self.wait_calls += 1
                if self.wait_calls == 1:
                    raise bench.subprocess.TimeoutExpired("docker", timeout)
                return -9

            def kill(self):
                self.killed = True

        process = StubbornProcess()
        bench._reap(process, timeout=0)
        self.assertTrue(process.killed)
        self.assertEqual(process.wait_calls, 2)

    def test_startup_interrupt_stops_only_owned_container_and_returns_partial_state(self):
        class StartedProcess:
            returncode = -2
            stdout = io.StringIO("")

            def poll(self):
                return None

            def terminate(self):
                pass

            def wait(self, timeout=None):
                return self.returncode

            def kill(self):
                pass

        process = StartedProcess()
        with mock.patch.object(bench.subprocess, "Popen", return_value=process), \
                mock.patch.object(bench, "_stop_owned_container", return_value=True) as stop, \
                mock.patch.object(bench.threading.Thread, "start", side_effect=KeyboardInterrupt):
            outcome = bench._owned_process(["docker", "run", "--name", "b2cpu-owned"], {}, "b2cpu-owned", 10, 1, False)
        self.assertTrue(outcome.interrupted)
        self.assertTrue(outcome.container_stop_confirmed)
        self.assertFalse(outcome.cleanup_unknown)
        stop.assert_called_once_with("b2cpu-owned", {})

    def test_failed_metric_is_not_conflated_with_unavailable_telemetry(self):
        parsed = {
            "metrics": [{"transport": "sync", "count": 2, "successes": 1, "failures": 1}],
            "phases": [],
            "cleanup_proofs": [],
        }
        valid, reason, _ = bench.validate_run(returncode=0, parsed=parsed, expected_count=2)
        self.assertFalse(valid)
        self.assertIn("failures", reason)
        unavailable = bench.metric_unavailable("no Docker sample")
        self.assertFalse(unavailable["available"])
        self.assertIsNone(unavailable["value"])

    def test_execution_rejects_timeout_production_only_stats_and_empty_jfr(self):
        stats = [{"name": "production", "timestamp_ms": 1005, "cpu_percent": {"available": True, "value": 400.0}, "memory_bytes": {"available": True, "value": 10.0}}]
        owned_stats = [{**stats[0], "name": "b2cpu-owned"}]
        host = [{"timestamp_ms": 1005, "cpu": {"available": True, "value": {}}, "memory": {"available": True, "value": {}}}]
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "run.log"
            jfr = output.with_suffix(".jfr")
            for timed_out, jfr_bytes in ((True, b"valid"), (False, b"")):
                jfr.write_bytes(jfr_bytes)
                outcome = bench.ProcessOutcome(0, complete_sync_log(), stats if timed_out else owned_stats, host, [], timed_out=timed_out)
                with mock.patch.object(bench, "_owned_process", return_value=outcome), \
                        mock.patch.object(bench, "_owner_name", return_value="b2cpu-owned"), \
                        mock.patch.object(bench, "_host_uid_gid", return_value=(1000, 1000)):
                    record = bench.execute_one_run(
                        bench.BenchmarkConfig(count=1),
                        credentials={}, output_path=output, cpu_quota=2, concurrency=8, repetition=1,
                        project_dir=Path(temp), maven_cache=Path(temp) / "m2",
                    )
                self.assertFalse(record.ok)
                if timed_out:
                    self.assertIn("deadline", record.error)
                else:
                    self.assertIn("JFR", record.error)

    def test_env_nul_and_incomplete_archive_failure_are_safe(self):
        with tempfile.TemporaryDirectory() as temp:
            env_file = Path(temp) / "bad.env"
            env_file.write_bytes(b"B2_ENDPOINT=https://example\nB2_BUCKET=b\nB2_ACCESS_KEY_ID=a\nB2_SECRET_ACCESS_KEY=s\nB2_OBJECT_PREFIX=x\x00y\n")
            with self.assertRaises(bench.BenchmarkError):
                bench.parse_env_file(env_file)

            credentials = {
                "B2_ENDPOINT": "https://s3.us-east-005.backblazeb2.com",
                "B2_BUCKET": "benchmark-bucket",
                "B2_ACCESS_KEY_ID": "access-sentinel",
                "B2_SECRET_ACCESS_KEY": "secret-sentinel",
                "B2_OBJECT_PREFIX": "validator/bench",
            }
            good_env = Path(temp) / "good.env"
            good_env.write_text("\n".join(f"{key}={value}" for key, value in credentials.items()), encoding="utf-8")
            failed_prep = bench.ProcessOutcome(1, "Maven output", [], [], [])
            with mock.patch.object(bench.platform, "system", return_value="Linux"), \
                    mock.patch.object(bench, "parse_env_file", return_value=credentials), \
                    mock.patch.object(bench, "_check_image"), \
                    mock.patch.object(bench, "_host_uid_gid", return_value=(1000, 1000)), \
                    mock.patch.object(bench, "_owned_process", return_value=failed_prep):
                with self.assertRaises(bench.IncompleteBenchmark):
                    bench.run_live(bench.BenchmarkConfig(env_file=good_env, output_dir=Path(temp), baseline_seconds=0, count=1, repetitions=1, concurrency=(8,)))
            archives = list(Path(temp).glob("*.tar.gz"))
            self.assertEqual(len(archives), 1)
            with tarfile.open(archives[0], "r:gz") as handle:
                summary = json.loads(handle.extractfile("summary.json").read())
            self.assertIn("Maven build preparation failed", summary["error"])
            self.assertNotIn("secret-sentinel", json.dumps(summary))

    def test_successful_fixture_flows_through_execution_and_requires_jfr(self):
        log = "\n".join(
            [
                "B2_BENCH_PHASE epoch_ms=1000 phase=setup state=start transport=sync repetition=1",
                "B2_BENCH_PHASE epoch_ms=1001 phase=setup state=end transport=sync repetition=1",
                "B2_BENCH_PHASE epoch_ms=1002 phase=warmup state=start transport=sync repetition=1",
                "B2_BENCH_PHASE epoch_ms=1003 phase=warmup state=end transport=sync repetition=1",
                "B2_BENCH_PHASE epoch_ms=1004 phase=upload state=start transport=sync repetition=1",
                "B2_BENCH_PHASE epoch_ms=1005 phase=upload state=end transport=sync repetition=1",
                "B2 benchmark sync: count=2, bytes=2949120, elapsedSeconds=1.0, filesPerSecond=2.0, successes=2, failures=0",
                "B2_BENCH_PHASE epoch_ms=1006 phase=verify state=start transport=sync repetition=1",
                "B2_BENCH_PHASE epoch_ms=1007 phase=verify state=end transport=sync repetition=1",
                "B2_BENCH_PHASE epoch_ms=1008 phase=cleanup state=start transport=sync repetition=1",
                "B2 benchmark cleanup confirmed zero versions and delete markers: prefix=validator/bench/run",
                "B2_BENCH_PHASE epoch_ms=1009 phase=cleanup state=end transport=sync repetition=1",
            ]
        )
        stats = [{
            "name": "b2cpu-owned",
            "timestamp_ms": 1005,
            "cpu_percent": {"available": True, "value": 25.0},
            "memory_bytes": {"available": True, "value": 1024.0},
            "network_rx_bytes": {"available": True, "value": 10.0},
            "network_tx_bytes": {"available": True, "value": 20.0},
        }]
        host = [{"timestamp_ms": 1005, "cpu": {"available": True, "value": {}}, "memory": {"available": True, "value": {}}}]
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "run.log"
            def fake_process(*_args, **_kwargs):
                output.with_suffix(".jfr").write_bytes(b"fake-jfr")
                return bench.ProcessOutcome(0, log, stats, host, [])

            with mock.patch.object(bench, "_owned_process", side_effect=fake_process), \
                    mock.patch.object(bench, "_owner_name", return_value="b2cpu-owned"), \
                    mock.patch.object(bench, "_host_uid_gid", return_value=(1000, 1000)):
                record = bench.execute_one_run(
                    bench.BenchmarkConfig(count=2, repetitions=1),
                    credentials={"B2_ACCESS_KEY_ID": "access-sentinel", "B2_SECRET_ACCESS_KEY": "secret-sentinel"},
                    output_path=output,
                    cpu_quota=2,
                    concurrency=8,
                    repetition=1,
                    project_dir=Path(temp),
                    maven_cache=Path(temp) / "m2",
                )
            self.assertTrue(record.ok)
            self.assertEqual(record.error, None)
            self.assertEqual(record.telemetry["upload_cpu"]["value"]["max"], 25.0)
            self.assertIn("B2 benchmark cleanup confirmed", output.read_text(encoding="utf-8"))

            for scenario in ("collector error", "missing host CPU", "missing RAM"):
                bad_stats = json.loads(json.dumps(stats))
                bad_host = json.loads(json.dumps(host))
                error = "collector failed: secret-sentinel" if scenario == "collector error" else None
                if scenario == "missing host CPU":
                    bad_host[0]["cpu"] = bench.metric_unavailable()
                if scenario == "missing RAM":
                    bad_stats[0]["memory_bytes"] = bench.metric_unavailable()
                outcome = bench.ProcessOutcome(0, log, bad_stats, bad_host, [], error=error)
                with self.subTest(scenario=scenario), \
                        mock.patch.object(bench, "_owned_process", return_value=outcome), \
                        mock.patch.object(bench, "_owner_name", return_value="b2cpu-owned"), \
                        mock.patch.object(bench, "_host_uid_gid", return_value=(1000, 1000)):
                    rejected = bench.execute_one_run(
                        bench.BenchmarkConfig(count=2),
                        credentials={"B2_SECRET_ACCESS_KEY": "secret-sentinel"},
                        output_path=output, cpu_quota=2, concurrency=8, repetition=1,
                        project_dir=Path(temp), maven_cache=Path(temp) / "m2",
                    )
                    self.assertFalse(rejected.ok)
                    self.assertNotIn("secret-sentinel", rejected.error)

    def test_successful_fake_live_flow_archives_baseline_runs_and_jfr(self):
        credentials = {
            "B2_ENDPOINT": "https://s3.us-east-005.backblazeb2.com",
            "B2_BUCKET": "benchmark-bucket",
            "B2_ACCESS_KEY_ID": "access-sentinel",
            "B2_SECRET_ACCESS_KEY": "secret-sentinel",
            "B2_OBJECT_PREFIX": "validator/bench",
        }
        metric = {"transport": "sync", "count": 1, "successes": 1, "failures": 0, "elapsed_seconds": 1.0}
        stats = [{"timestamp_ms": 1005, "cpu_percent": {"available": True, "value": 20.0}, "memory_bytes": {"available": True, "value": 10.0}}]
        host = [{"timestamp_ms": 1005, "cpu": {"available": True, "value": {}}, "memory": {"available": True, "value": {}}}]

        def fake_execute(config, *, credentials, output_path, cpu_quota, concurrency, repetition, project_dir, maven_cache):
            output_path.write_text("B2_SECRET_ACCESS_KEY=[REDACTED]\n", encoding="utf-8")
            output_path.with_suffix(".jfr").write_bytes(b"fake-jfr")
            return bench.RunRecord(cpu_quota, concurrency, repetition, 0, [metric], [], True, stats, host, [], {}, valid=True, jfr_path=str(output_path.with_suffix(".jfr")))

        def fake_process(*_args, **kwargs):
            if len(_args) == 5:
                return bench.ProcessOutcome(0, "baseline", stats, host, [])
            return bench.ProcessOutcome(0, "build", [], [], [])

        with tempfile.TemporaryDirectory() as temp:
            env_file = Path(temp) / "benchmark.env"
            env_file.write_text("\n".join(f"{key}={value}" for key, value in credentials.items()), encoding="utf-8")
            with mock.patch.object(bench.platform, "system", return_value="Linux"), \
                    mock.patch.object(bench, "parse_env_file", return_value=credentials), \
                    mock.patch.object(bench, "_check_image"), \
                    mock.patch.object(bench, "_host_uid_gid", return_value=(1000, 1000)), \
                    mock.patch.object(bench, "_owned_process", side_effect=fake_process), \
                    mock.patch.object(bench, "execute_one_run", side_effect=fake_execute):
                archive = bench.run_live(bench.BenchmarkConfig(env_file=env_file, output_dir=Path(temp), count=1, repetitions=1, baseline_seconds=1, concurrency=(8,)))
            with tarfile.open(archive, "r:gz") as handle:
                names = handle.getnames()
                contents = "\n".join(handle.extractfile(name).read().decode(errors="replace") for name in names if name.endswith((".log", ".json", ".md")))
            self.assertIn("baseline.log", names)
            self.assertIn("baseline.json", names)
            self.assertIn("build-preparation.log", names)
            self.assertIn("metrics/runs.json", names)
            self.assertTrue(any(name.endswith(".jfr") for name in names))
            self.assertNotIn("secret-sentinel", contents)


if __name__ == "__main__":
    unittest.main()
