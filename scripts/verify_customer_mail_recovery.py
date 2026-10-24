"""실제 출력 스케줄러를 SMTP 전/후에 종료하고 별도 JVM 복원·중복·다른 스케줄러의 진행을 검증한다."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent
ARTIFACTS = ROOT.parent / ".local"
DOCKER = shutil.which("docker") or "/usr/local/bin/docker"
JDK = Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"))
name = "cex-mail-recovery-" + uuid4().hex[:12]
report = {"container": name, "checks": [], "bots_disabled": True, "worker_jvm_runs": 0, "exit": None}
worker = None
sink = None
sink_port = None


def sql(statement):
    return subprocess.check_output([DOCKER, "exec", name, "psql", "-U", "root", "-d", "cex_test", "-v", "ON_ERROR_STOP=1", "-At", "-c", statement], text=True).strip()


def wait_for(predicate, description, timeout=45):
    deadline = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() > deadline:
            raise RuntimeError(description)
        time.sleep(0.1)


def start(phase, classpath, address, first=False):
    report["worker_jvm_runs"] += 1
    env = {k: v for k, v in os.environ.items() if not k.startswith(("CDD_", "EDD_", "DIDIT_", "CUSTOMER_MAIL_", "SPRING_MAIL_"))}
    env["CEX_MAIL_PROBE_PHASE"] = phase
    args = [str(JDK / "bin/java"), "-cp", classpath, "dustin.cex.domains.kyc.CustomerMailRecoveryProcessProbe",
        "--server.address=127.0.0.1", "--server.port=0", f"--spring.datasource.url=jdbc:postgresql://{address}/cex_test",
        "--spring.datasource.username=root", "--spring.datasource.password=1234", "--spring.datasource.driver-class-name=org.postgresql.Driver",
        "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect", "--spring.jpa.hibernate.ddl-auto=" + ("create" if first else "validate"),
        "--bot.bot-count=0", "--order.dispatch.enabled=false", "--kyc.reconciliation.enabled=false", "--engine.http.url=http://127.0.0.1:1",
        "--spring.kafka.bootstrap-servers=127.0.0.1:1", "--spring.kafka.listener.auto-startup=false",
        "--jwt.secret=isolated-test-jwt-secret-at-least-32-bytes", "--engine.service.token=isolated-engine-service-token-32-bytes",
        "--cdd.encryption-key=AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "--cdd.fingerprint-key=AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "--didit.encryption-key=",
        "--customer-mail.dispatch-enabled=true", "--customer-mail.from=no-reply@exchange.test", "--customer-mail.public-base=https://exchange.example.test",
        "--spring.mail.host=127.0.0.1", f"--spring.mail.port={sink_port}", "--spring.mail.protocol=" + ("smtp" if phase == "invalid-tls" else "smtps"),
        "--spring.mail.properties.mail.smtps.ssl.trust=127.0.0.1", "--spring.mail.properties.mail.smtps.ssl.checkserveridentity=false"]
    log = ARTIFACTS / ("mail-recovery-" + phase + ".log")
    with log.open("w") as output:
        return subprocess.Popen(args, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT), log


try:
    ARTIFACTS.mkdir(exist_ok=True)
    subprocess.run([DOCKER, "run", "--detach", "--rm", "--name", name, "--publish", "127.0.0.1::5432", "--tmpfs", "/var/lib/postgresql/data", "-e", "POSTGRES_USER=root", "-e", "POSTGRES_PASSWORD=1234", "-e", "POSTGRES_DB=cex_test", "postgres:16"], check=True, stdout=subprocess.DEVNULL)
    wait_for(lambda: subprocess.run([DOCKER, "exec", name, "pg_isready", "-U", "root", "-d", "cex_test"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0, "PostgreSQL 준비 시간 초과")
    address = subprocess.check_output([DOCKER, "port", name, "5432/tcp"], text=True).strip()
    with tempfile.TemporaryDirectory(prefix="cex-mail-process-") as directory:
        temporary = Path(directory)
        init = temporary / "probe.gradle"
        classpath_file = temporary / "classpath"
        init.write_text("allprojects { tasks.register('writeMailProbeClasspath') { doLast { file(System.getenv('CEX_MAIL_CLASSPATH')).text = sourceSets.test.runtimeClasspath.asPath } } }\n")
        env = os.environ.copy()
        env.update(JAVA_HOME=str(JDK), CEX_MAIL_CLASSPATH=str(classpath_file))
        with (ARTIFACTS / "mail-recovery-build.log").open("w") as log:
            subprocess.run(["./gradlew", "testClasses", "-I", str(init), "writeMailProbeClasspath", "--console=plain"], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        classpath = classpath_file.read_text()
        with (ARTIFACTS / "mail-recovery-smtp.log").open("w") as log:
            sink = subprocess.Popen([str(JDK / "bin/java"), "-cp", classpath, "dustin.cex.domains.kyc.CustomerMailSmtpSink", str(temporary)], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        wait_for(lambda: (temporary / "port").exists() and (temporary / "messages.json").exists(), "로컬 SMTPS 준비 시간 초과")
        sink_port = int((temporary / "port").read_text())

        def messages():
            return json.loads((temporary / "messages.json").read_text())

        for index, phase in enumerate(["pause-before", "pause-after"]):
            worker, log = start(phase, classpath, address, first=index == 0)
            marker = "MAIL_BEFORE_SMTP:" if index == 0 else "MAIL_AFTER_SMTP:"
            wait_for(lambda: marker in log.read_text() and "MAIL_QUEUED:" in log.read_text(), "메일 출력 정지 지점 진입 실패")
            # 메일이 정지해도 일반 @Scheduled 작업을 실행할 기본 스케줄러는 별도로 진행해야 한다.
            wait_for(lambda: "MAIL_DEFAULT_SCHEDULER_ALIVE:" in log.read_text(), "메일 정지 중 기본 스케줄러가 진행하지 못했습니다", timeout=5)
            heartbeat = next(line for line in log.read_text().splitlines() if line.startswith("MAIL_DEFAULT_SCHEDULER_ALIVE:"))
            assert "customer-mail-" not in heartbeat, "메일 워커를 기본 스케줄러로 재사용했습니다"
            job = next(line.split(":", 1)[1] for line in log.read_text().splitlines() if line.startswith("MAIL_QUEUED:"))
            assert sql(f"SELECT status||':'||attempts FROM customer_mail_delivery WHERE job_id='{job}'") == "SENDING:1"
            expected_before_kill = 0 if index == 0 else 2
            wait_for(lambda: len(messages()) >= expected_before_kill, "SMTP 수신 기록 준비 실패")
            assert len(messages()) == expected_before_kill
            worker.kill()
            code = worker.wait(timeout=10)
            assert code < 0
            assert sql(f"SELECT count(*) FROM customer_mail_attempts WHERE job_id='{job}' AND phase='RESULT'") == "0"
            report["checks"].append({"test": phase + "_kill_preserves_lease_and_unconfirmed_result", "passed": True, "killed_exit": code, "default_scheduler_alive": True})
            # 운영 임대만 만료시킨다. 작업/시도/감사/금융 원문은 수정하지 않는다.
            sql(f"UPDATE customer_mail_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE job_id='{job}'")
            worker, resumed = start("resume", classpath, address)
            assert worker.wait(timeout=45) == 0, "별도 JVM 메일 복원 실패"
            assert "MAIL_RECOVERY_CONFIRMED" in resumed.read_text()
            expected_after = 1 if index == 0 else 3
            wait_for(lambda: len(messages()) == expected_after, "메일 복원 수신 기록 불일치")
            assert sql(f"SELECT status||':'||attempts FROM customer_mail_delivery WHERE job_id='{job}'") == "SMTP_ACCEPTED:2"
            assert sql(f"SELECT count(*) FROM customer_mail_attempts WHERE job_id='{job}' AND phase='CLAIMED'") == "2"
            assert sql(f"SELECT count(*) FROM customer_mail_attempts WHERE job_id='{job}' AND phase='RESULT'") == "1"
            expected_id = "<" + job + "@exchange.test>"
            assert messages().count(expected_id) == (1 if index == 0 else 2)
            report["checks"].append({"test": phase + "_fresh_jvm_recovers_original_message_id", "passed": True, "smtp_copies": 1 if index == 0 else 2})
        worker, log = start("verify", classpath, address)
        assert worker.wait(timeout=45) == 0
        assert len(messages()) == 3
        assert sql("SELECT count(*) FROM customer_mail_jobs") == "2"
        report["checks"].append({"test": "confirmed_jobs_are_not_resent_by_later_jvm", "passed": True})
        worker, log = start("invalid-tls", classpath, address)
        assert worker.wait(timeout=45) != 0
        assert len(messages()) == 3
        assert sql("SELECT sum(attempts) FROM customer_mail_delivery") == "4"
        report["checks"].append({"test": "unsafe_tls_prevents_scheduler_start_and_claim", "passed": True})
        report["exit"] = 0
finally:
    for process in [worker, sink]:
        if process is not None and process.poll() is None:
            process.kill()
            process.wait(timeout=10)
    report["container_removed"] = subprocess.run([DOCKER, "rm", "--force", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if sink_port:
        try:
            with socket.create_connection(("127.0.0.1", sink_port), timeout=1):
                report["smtp_closed"] = False
        except OSError:
            report["smtp_closed"] = True
    report["owned_processes_stopped"] = all(p is None or p.poll() is not None for p in [worker, sink])
    (ARTIFACTS / "mail-recovery-process.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps(report, ensure_ascii=False, indent=2))
