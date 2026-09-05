import sys
import types

import pytest

from audiobook_worker.runtime_probe import RuntimeProbe


class _FakeCuda:
    def __init__(self, available):
        self.available = available
        self.device_name_calls = []
        self.device_properties_calls = []

    def is_available(self):
        return self.available

    def get_device_name(self, index):
        self.device_name_calls.append(index)
        return "Test GPU"

    def get_device_properties(self, index):
        self.device_properties_calls.append(index)
        return types.SimpleNamespace(total_memory=16 * 1024**3)


def _fake_torch(cuda):
    torch = types.ModuleType("torch")
    torch.__version__ = "2.7.0"
    torch.version = types.SimpleNamespace(cuda="12.4")
    torch.cuda = cuda
    return torch


def test_detect_reads_cuda_name_memory_and_versions(monkeypatch):
    cuda = _FakeCuda(True)
    monkeypatch.setitem(sys.modules, "torch", _fake_torch(cuda))

    probe = RuntimeProbe.detect()

    assert probe.cuda_available is True
    assert probe.gpu_name == "Test GPU"
    assert probe.gpu_memory_bytes == 16 * 1024**3
    assert probe.cuda_version == "12.4"
    assert probe.torch_version == "2.7.0"
    assert cuda.device_name_calls == [0]
    assert cuda.device_properties_calls == [0]


def test_detect_reports_no_cuda_without_querying_device(monkeypatch):
    cuda = _FakeCuda(False)
    monkeypatch.setitem(sys.modules, "torch", _fake_torch(cuda))

    probe = RuntimeProbe.detect()

    assert probe.cuda_available is False
    assert probe.gpu_name is None
    assert probe.gpu_memory_bytes == 0
    assert probe.cuda_version is None
    assert probe.torch_version == "2.7.0"
    assert cuda.device_name_calls == []
    assert cuda.device_properties_calls == []


def test_detect_is_safe_when_torch_is_not_installed(monkeypatch):
    monkeypatch.setitem(sys.modules, "torch", None)

    probe = RuntimeProbe.detect()

    assert probe.cuda_available is False
    assert probe.gpu_name is None
    assert probe.gpu_memory_bytes == 0
    assert probe.cuda_version is None
    assert probe.torch_version is None


class _FailingCuda:
    def __init__(self, failure_phase):
        self.failure_phase = failure_phase

    def is_available(self):
        if self.failure_phase == "availability":
            raise RuntimeError("CUDA driver initialization failed")
        return True

    def get_device_name(self, index):
        if self.failure_phase == "name":
            raise RuntimeError("device name query failed")
        return "Test GPU"

    def get_device_properties(self, index):
        if self.failure_phase == "properties":
            raise RuntimeError("device properties query failed")
        return types.SimpleNamespace(total_memory=16 * 1024**3)


@pytest.mark.parametrize("failure_phase", ["availability", "name", "properties"])
def test_detect_maps_cuda_runtime_errors_to_unavailable(monkeypatch, failure_phase):
    monkeypatch.setitem(sys.modules, "torch", _fake_torch(_FailingCuda(failure_phase)))

    probe = RuntimeProbe.detect()

    assert probe.cuda_available is False
    assert probe.gpu_name is None
    assert probe.gpu_memory_bytes == 0
    assert probe.cuda_version is None
    assert probe.torch_version == "2.7.0"
