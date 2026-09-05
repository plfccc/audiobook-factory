from __future__ import annotations

import asyncio
import hashlib
import json
from pathlib import Path

import httpx
import pytest

from audiobook_worker.runtime_probe import RuntimeProbe
from audiobook_worker.server_client import (
    ControlPlaneClient,
    ControlPlaneError,
)


def run_async(awaitable):
    return asyncio.run(awaitable)


class RecordingTransport:
    def __init__(self, responses):
        self.requests = []
        self.responses = iter(responses)

    async def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        response = next(self.responses)
        return response


class OneChunkStream(httpx.AsyncByteStream):
    def __init__(self, content: bytes):
        self.content = content

    async def __aiter__(self):
        yield self.content


def make_client(transport):
    return ControlPlaneClient(
        "https://factory.example",
        "worker-token",
        transport=httpx.MockTransport(transport),
    )


def test_claim_job_sends_bearer_token():
    transport = RecordingTransport(
        [
            httpx.Response(
                200,
                json={
                    "jobId": "job-1",
                    "text": "测试",
                    "chapterIndex": 1,
                    "segmentIndex": 1,
                },
            )
        ]
    )
    client = make_client(transport)

    job = run_async(client.claim_job())

    request = transport.requests[0]
    assert request.method == "POST"
    assert str(request.url) == "https://factory.example/api/v1/workers/claim"
    assert request.headers["Authorization"] == "Bearer worker-token"
    assert job.job_id == "job-1"
    assert job.text == "测试"


def test_control_plane_requires_https_for_non_local_hosts():
    with pytest.raises(ValueError, match="HTTPS"):
        ControlPlaneClient("http://factory.example", "worker-token")

    assert ControlPlaneClient("http://localhost:8080", "worker-token")
    assert ControlPlaneClient("http://127.0.0.1:8080", "worker-token")


def test_control_plane_rejects_token_in_base_url():
    with pytest.raises(ValueError, match="URL"):
        ControlPlaneClient(
            "https://factory.example/api?access_token=worker-token",
            "worker-token",
        )


@pytest.mark.parametrize("job_id", ["", ".", "..", "../job-1", "nested/job", "任务-1"])
def test_job_id_must_be_a_safe_ascii_path_component(job_id):
    client = ControlPlaneClient("https://factory.example", "worker-token")

    with pytest.raises(ValueError, match="job_id"):
        run_async(client.heartbeat(job_id, {"phase": "generating"}))


def test_claim_job_returns_none_for_no_work():
    transport = RecordingTransport([httpx.Response(204)])
    client = make_client(transport)

    assert run_async(client.claim_job()) is None


def test_register_serializes_runtime_and_returns_registration():
    transport = RecordingTransport(
        [
            httpx.Response(
                200,
                json={
                    "workerId": "worker-1",
                    "workerToken": "short-lived-token",
                    "leaseSeconds": 300,
                },
            )
        ]
    )
    client = make_client(transport)
    runtime = RuntimeProbe(
        True,
        "Test GPU",
        16 * 1024**3,
        "12.4",
        "2.7.0",
        "3.11",
    )

    registration = run_async(
        client.register("colab-1", runtime, {"engineId": "qwen3-tts"})
    )

    payload = json.loads(transport.requests[0].content)
    assert payload["workerName"] == "colab-1"
    assert payload["runtime"]["cudaAvailable"] is True
    assert payload["runtime"]["gpuMemoryBytes"] == 16 * 1024**3
    assert payload["capabilities"]["engineId"] == "qwen3-tts"
    assert registration.worker_id == "worker-1"
    assert registration.worker_token == "short-lived-token"


def test_heartbeat_posts_progress_without_logging_token():
    transport = RecordingTransport([httpx.Response(204)])
    client = make_client(transport)

    run_async(client.heartbeat("job-1", {"phase": "generating", "percent": 25}))

    request = transport.requests[0]
    assert str(request.url) == (
        "https://factory.example/api/v1/workers/jobs/job-1/heartbeat"
    )
    assert json.loads(request.content) == {
        "phase": "generating",
        "percent": 25,
    }
    assert "worker-token" not in repr(client)


def test_download_asset_streams_to_target():
    transport = RecordingTransport(
        [httpx.Response(200, content=b"reference audio")]
    )
    client = make_client(transport)
    target = Path("target.wav")

    result = run_async(client.download_asset("asset-1", target))

    assert result == target
    assert target.read_bytes() == b"reference audio"
    target.unlink()


def test_download_asset_reports_protocol_error_without_reading_secret(tmp_path):
    transport = RecordingTransport(
        [
            httpx.Response(
                401,
                json={"code": "AUTH_REQUIRED", "message": "worker-token rejected"},
            )
        ]
    )
    client = make_client(transport)

    with pytest.raises(ControlPlaneError) as caught:
        run_async(client.download_asset("asset-1", tmp_path / "target.wav"))

    assert caught.value.code == "AUTH_REQUIRED"
    assert "worker-token" not in str(caught.value)


def test_download_asset_consumes_unread_stream_error_before_mapping(tmp_path):
    transport = RecordingTransport(
        [
            httpx.Response(
                401,
                stream=OneChunkStream(
                    b'{"code":"AUTH_REQUIRED","message":"sign in required"}'
                ),
            )
        ]
    )
    client = make_client(transport)

    with pytest.raises(ControlPlaneError) as caught:
        run_async(client.download_asset("asset-1", tmp_path / "target.wav"))

    assert caught.value.code == "AUTH_REQUIRED"


def test_upload_result_sends_audio_and_metadata(tmp_path):
    transport = RecordingTransport([httpx.Response(204)])
    client = make_client(transport)
    audio = tmp_path / "result.wav"
    audio.write_bytes(b"wav")

    run_async(
        client.upload_result(
            "job-1",
            audio,
            {"sha256": hashlib.sha256(b"wav").hexdigest(), "sizeBytes": 3},
        )
    )

    request = transport.requests[0]
    assert str(request.url) == (
        "https://factory.example/api/v1/workers/jobs/job-1/result"
    )
    assert b"result.wav" in request.content
    assert hashlib.sha256(b"wav").hexdigest().encode() in request.content
    assert b"wav" in request.content


def test_upload_result_rejects_invalid_declared_sha_before_request(tmp_path):
    transport = RecordingTransport([httpx.Response(204)])
    client = make_client(transport)
    audio = tmp_path / "result.wav"
    audio.write_bytes(b"wav")

    with pytest.raises(ValueError, match="sha256"):
        run_async(client.upload_result("job-1", audio, {"sha256": "abc"}))

    assert transport.requests == []


def test_error_response_maps_legacy_worker_auth_code_to_waiting_state():
    transport = RecordingTransport(
        [httpx.Response(401, json={"code": "WORKER_UNAUTHORIZED", "message": "expired"})]
    )
    client = make_client(transport)

    with pytest.raises(ControlPlaneError) as caught:
        run_async(client.claim_job())

    assert caught.value.code == "AUTH_REQUIRED"


def test_report_failure_sends_only_a_safe_bounded_summary():
    transport = RecordingTransport([httpx.Response(204)])
    client = make_client(transport)

    run_async(
        client.report_failure(
            "job-1",
            "PERMANENT_FAILED",
            "worker-token=worker-token clone_prompt=secret\nTraceback at com.example.Secret",
        )
    )

    payload = json.loads(transport.requests[0].content)
    assert len(payload["message"]) <= 240
    assert "worker-token" not in payload["message"]
    assert "clone_prompt" not in payload["message"]
    assert "secret" not in payload["message"]
    assert "Traceback" not in payload["message"]


def test_report_failure_posts_code_and_message():
    transport = RecordingTransport([httpx.Response(204)])
    client = make_client(transport)

    run_async(client.report_failure("job-1", "PERMANENT_FAILED", "bad audio"))

    assert json.loads(transport.requests[0].content) == {
        "code": "PERMANENT_FAILED",
        "message": "bad audio",
    }


def test_error_response_exposes_protocol_code_without_secret():
    transport = RecordingTransport(
        [
            httpx.Response(
                401,
                json={"code": "AUTH_REQUIRED", "message": "sign in required"},
            )
        ]
    )
    client = make_client(transport)

    with pytest.raises(ControlPlaneError) as caught:
        run_async(client.claim_job())

    assert caught.value.code == "AUTH_REQUIRED"
    assert "worker-token" not in str(caught.value)
