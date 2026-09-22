"""csl_capture 客户端 × §9 线协议联调测试（模拟手机侧）。

覆盖：握手收帧 COMPLETE 落盘、BAD_TOKEN、GAP→INCOMPLETE、
COMPLETE 但序号断续（客户端揭穿）、帧长度声明不符（断开）、
空闲心跳、--duration 主动停止。
"""
import json
import subprocess
import sys
from pathlib import Path

from mock_phone import MockPhone, i420_frame

SCRIPT = Path(__file__).resolve().parent.parent / "csl_capture.py"
TOKEN = "test1234"
W, H = 4, 4
PTS_STEP = 33_333  # 模拟 30fps


def run_client(phone: MockPhone, out: Path, token: str = TOKEN, duration: float | None = None):
    cmd = [sys.executable, str(SCRIPT), "--port", str(phone.port), "--token", token, "--out", str(out)]
    if duration is not None:
        cmd += ["--duration", str(duration)]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=30)


def only_segment(out: Path) -> Path:
    dirs = [d for d in out.iterdir() if d.is_dir()]
    assert len(dirs) == 1
    return dirs[0]


def read_summary(out: Path) -> dict:
    return json.loads((only_segment(out) / "summary.json").read_text(encoding="utf-8"))


def types_of(phone: MockPhone) -> list[str]:
    return [h["type"] for h in phone.received]


# ------------------------------------------------------------------- 测试


def test_complete_capture(tmp_path):
    frames = [(i, PTS_STEP * (i + 1), i + 1) for i in range(3)]  # (idx, ptsUs, fill)
    script = [("auth_ok",), ("config", W, H, 30)]
    script += [("frame", i, pts, W, H, i420_frame(W, H, fill)) for i, pts, fill in frames]
    script += [("end", "COMPLETE", None, 2, PTS_STEP, PTS_STEP * 3)]
    phone = MockPhone(TOKEN)
    phone.start(script)
    r = run_client(phone, tmp_path)
    phone.wait()
    assert r.returncode == 0, r.stderr

    # 握手序：AUTH_REQ → (ACK) CONFIG_ACK
    assert types_of(phone)[0] == "AUTH_REQ"
    assert phone.received[0]["token"] == TOKEN
    assert "CONFIG_ACK" in types_of(phone)

    summary = read_summary(tmp_path)
    assert summary["status"] == "COMPLETE"
    assert summary["frames"] == 3
    assert summary["bytes"] == 3 * len(i420_frame(W, H, 1))
    assert summary["lastFrameIndex"] == 2
    assert summary["firstPtsUs"] == PTS_STEP
    assert summary["lastPtsUs"] == PTS_STEP * 3
    assert summary["gaps"] == []
    assert summary["missing"] == []
    assert summary["problems"] == []

    seg = only_segment(tmp_path)
    # frames.i420：按序追加，内容逐帧可复现
    assert (seg / "frames.i420").read_bytes() == b"".join(
        i420_frame(W, H, fill) for _, _, fill in frames
    )
    # meta.jsonl：逐帧索引（offset/len 可重切单帧）
    meta = [json.loads(line) for line in (seg / "meta.jsonl").read_text().splitlines()]
    assert [m["frameIndex"] for m in meta] == [0, 1, 2]
    assert [m["offset"] for m in meta] == [0, 24, 48]
    assert all(m["len"] == 24 and m["width"] == W and m["height"] == H for m in meta)
    assert [m["ptsUs"] for m in meta] == [PTS_STEP * (i + 1) for i in range(3)]


def test_bad_token(tmp_path):
    phone = MockPhone(TOKEN)
    phone.start([("auth_bad",)])
    r = run_client(phone, tmp_path, token="wrongtoken")
    phone.wait()
    assert r.returncode == 2
    assert "BAD_TOKEN" in r.stderr
    assert phone.received[0]["token"] == "wrongtoken"


def test_gap_incomplete(tmp_path):
    script = [
        ("auth_ok",), ("config", W, H, 30),
        ("frame", 0, PTS_STEP, W, H, i420_frame(W, H, 1)),
        ("frame", 1, PTS_STEP * 2, W, H, i420_frame(W, H, 2)),
        ("gap", "OVERLOAD", 1, PTS_STEP * 2, PTS_STEP * 2),
        ("end", "INCOMPLETE", "OVERLOAD", 1, PTS_STEP, PTS_STEP * 2),
    ]
    phone = MockPhone(TOKEN)
    phone.start(script)
    r = run_client(phone, tmp_path)
    phone.wait()
    assert r.returncode == 3, r.stderr
    summary = read_summary(tmp_path)
    assert summary["status"] == "INCOMPLETE"
    assert summary["reason"] == "OVERLOAD"
    assert summary["frames"] == 2
    assert summary["gaps"] == [{
        "reason": "OVERLOAD", "lastFrameIndex": 1,
        "startPtsUs": PTS_STEP * 2, "endPtsUs": PTS_STEP * 2,
    }]
    # 断点后的帧不落盘（不拼连续样本）
    assert (only_segment(tmp_path) / "frames.i420").read_bytes() == (
        i420_frame(W, H, 1) + i420_frame(W, H, 2)
    )


def test_complete_but_discontinuous(tmp_path):
    # 手机声称 COMPLETE 但序号缺 1 → 客户端必须揭穿（§9.3 序号连续性在 END 核对）
    script = [
        ("auth_ok",), ("config", W, H, 30),
        ("frame", 0, PTS_STEP, W, H, i420_frame(W, H, 1)),
        ("frame", 2, PTS_STEP * 3, W, H, i420_frame(W, H, 3)),
        ("end", "COMPLETE", None, 2, PTS_STEP, PTS_STEP * 3),
    ]
    phone = MockPhone(TOKEN)
    phone.start(script)
    r = run_client(phone, tmp_path)
    phone.wait()
    assert r.returncode == 4, r.stderr
    summary = read_summary(tmp_path)
    assert summary["status"] == "VERIFICATION_FAILED"
    assert summary["missing"] == [[0, 2]]
    assert summary["problems"]


def test_bad_frame_length(tmp_path):
    # 声明 payloadLen 与 payload 实际/尺寸计算值不符 → 断开，退出码 4
    script = [
        ("auth_ok",), ("config", W, H, 30),
        ("bad_frame", 0, PTS_STEP, W, H, i420_frame(W, H, 1), 23),
    ]
    phone = MockPhone(TOKEN)
    phone.start(script)
    r = run_client(phone, tmp_path)
    phone.wait()
    assert r.returncode == 4, r.stderr
    summary = read_summary(tmp_path)
    assert summary["status"] == "PROTOCOL_ERROR"
    assert summary["frames"] == 0
    assert "长度不符" in r.stderr or "payloadLen" in r.stderr


def test_heartbeat_when_idle(tmp_path):
    # 空闲 ≥2s：客户端须发 HEARTBEAT（§9.1），手机侧零帧 COMPLETE 收尾
    script = [("auth_ok",), ("config", W, H, 30), ("sleep", 3.0),
              ("end", "COMPLETE", None, -1, -1, -1)]
    phone = MockPhone(TOKEN)
    phone.start(script)
    r = run_client(phone, tmp_path)
    phone.wait()
    assert r.returncode == 0, r.stderr
    heartbeats = [h for h in phone.received if h["type"] == "HEARTBEAT"]
    assert heartbeats, "空闲期间未收到客户端心跳"
    assert all(isinstance(h["t"], int) for h in heartbeats)
    summary = read_summary(tmp_path)
    assert summary["status"] == "COMPLETE"
    assert summary["frames"] == 0
    assert summary["lastFrameIndex"] == -1


def test_duration_stop(tmp_path):
    # --duration 到时客户端主动断开：§9.1 视作断连 → CLIENT_STOP，素材保留
    script = [("auth_ok",), ("config", W, H, 30), ("sleep", 8.0),
              ("end", "COMPLETE", None, -1, -1, -1)]
    phone = MockPhone(TOKEN)
    phone.start(script)
    r = run_client(phone, tmp_path, duration=0.5)
    assert r.returncode == 0, r.stderr
    summary = read_summary(tmp_path)
    assert summary["status"] == "CLIENT_STOP"
    assert summary["frames"] == 0
    assert phone.received[0]["type"] == "AUTH_REQ"
