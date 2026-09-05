from pathlib import Path


ROOT = Path(__file__).parents[2]


def test_smoke_script_uses_fixed_sample_and_does_not_publish_debug_ports():
    script = (ROOT / "scripts" / "p0-smoke.sh").read_text(encoding="utf-8")
    assert "examples/p0-sample.txt" in script
    assert "examples/p0-preset.json" in script
    assert "P0_PROXY_DIR" in script
    assert "subscription.base64" in script
    compose = (ROOT / "infra" / "p0" / "docker-compose.yml").read_text(
        encoding="utf-8"
    )
    assert "127.0.0.1:6080" in compose
    assert "127.0.0.1:9222" in compose


def test_compose_routes_browser_traffic_through_private_proxy_service():
    compose = (ROOT / "infra" / "p0" / "docker-compose.yml").read_text(
        encoding="utf-8"
    )
    browser_runtime = (ROOT / "infra" / "browser" / "supervisord.conf").read_text(
        encoding="utf-8"
    )

    assert "  proxy:" in compose
    assert "127.0.0.1:7890:7890" in compose
    assert "condition: service_healthy" in compose
    assert "http://proxy:7890" in browser_runtime
    assert "--proxy-bypass-list=<-loopback>" in browser_runtime


def test_public_proxy_listener_is_separate_and_authenticated():
    compose = (ROOT / "infra" / "p0" / "docker-compose.yml").read_text(
        encoding="utf-8"
    )
    proxy_config = (ROOT / "infra" / "proxy" / "config.yaml.example").read_text(
        encoding="utf-8"
    )

    assert '"0.0.0.0:7891:7891"' in compose
    assert "name: public-mixed" in proxy_config
    assert "listen: 0.0.0.0" in proxy_config
    assert "port: 7891" in proxy_config
    assert "users:" in proxy_config
    assert "password: change-this-password" in proxy_config
