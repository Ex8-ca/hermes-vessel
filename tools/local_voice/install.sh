#!/usr/bin/env bash
# Hermes Vessel - Local voice: install everything on one Linux box with an NVIDIA GPU (16 GB or more).
#
#   tools/local_voice/install.sh [--dir DIR] [--listen ADDR] [--quant auto|nf4|none] [--no-services]
#
# Installs, under DIR (default ~/vessel-local-voice):
#   - the duplex voice worker (MiniCPM-o 4.5, pinned revision; 4-bit on cards under 24 GB)   port 8797
#   - speech-to-text (faster-whisper "turbo", int8)                                          port 8798
#   - the hand-off model (MiniCPM5-2B fine-tune, GGUF, CPU) in llama.cpp's llama-server       port 8799
# and three systemd user services (vessel-local-voice, -stt, -router) sharing one random token.
# Nothing here runs at call time except those three servers; all downloads are pinned and happen now.
#
# Needs: Linux x86-64, an NVIDIA driver for CUDA 12.8 (nvidia-smi), uv (https://docs.astral.sh/uv/), curl,
# ~30 GB of disk, and 16 GB of GPU memory with nothing else on the card.
set -euo pipefail

DIR="$HOME/vessel-local-voice"
LISTEN="127.0.0.1"
QUANT="auto"
SERVICES=1
while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --listen) LISTEN="$2"; shift 2 ;;
    --quant) QUANT="$2"; shift 2 ;;
    --no-services) SERVICES=0; shift ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "unknown option: $1 (see --help)"; exit 2 ;;
  esac
done

# Pinned sources (bump deliberately, after testing).
VOICE_REPO="openbmb/MiniCPM-o-4_5"
VOICE_REV="eb4141de24550a3a1a4082f718be58f46b761728"
HANDOFF_REPO="burtlelife/vessel-handoff-MiniCPM5-2B"
HANDOFF_REV="__HANDOFF_REV__"
HANDOFF_FILE="MiniCPM5-2B-vessel-handoff-Q6_K.gguf"
LLAMA_TAG="b12177"
LLAMA_TGZ="llama-${LLAMA_TAG}-bin-ubuntu-x64.tar.gz"
LLAMA_SHA256="5d936162de06187654d1353fe94d65390904f1c67cd1a887023dbc9b0b207284"
HERE="$(cd "$(dirname "$0")" && pwd)"

say() { printf '\n== %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

[ "$(uname -s)-$(uname -m)" = "Linux-x86_64" ] || die "Linux x86-64 only"
command -v nvidia-smi >/dev/null || die "no nvidia-smi: install the NVIDIA driver first"
command -v uv >/dev/null || die "uv is required: see https://docs.astral.sh/uv/getting-started/installation/"
command -v curl >/dev/null || die "curl is required"
GPU_MB=$(nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits | head -1 | tr -d ' ')
[ "${GPU_MB:-0}" -ge 15000 ] || die "the GPU has ${GPU_MB} MB; the local voice needs about 16 GB"
if [ "$QUANT" = "auto" ]; then
  if [ "$GPU_MB" -ge 23000 ]; then QUANT="none"; else QUANT="nf4"; fi
fi
MAX_GIB=0
[ "$QUANT" = "nf4" ] && MAX_GIB=$(( GPU_MB / 1024 - 3 ))  # leave ~3 GB for speech-to-text
say "GPU: ${GPU_MB} MB -> voice model precision: $QUANT${MAX_GIB:+ (capped at ${MAX_GIB} GiB)}"

mkdir -p "$DIR"
cd "$DIR"

say "Python environment ($DIR/.venv)"
[ -d .venv ] || env -u PYTHONPATH uv venv -q --python 3.10 .venv
PY="$DIR/.venv/bin/python"
env -u PYTHONPATH uv pip install -q --python "$PY" "torch==2.8.0" "torchaudio==2.8.0" \
  --index-url https://download.pytorch.org/whl/cu128
env -u PYTHONPATH uv pip install -q --python "$PY" "transformers==4.51.0" "accelerate>=1.0,<2" \
  "minicpmo-utils[all]>=1.0.5,<=1.0.6" "bitsandbytes>=0.46,<1" librosa soundfile "aiohttp>=3.9,<4" \
  "setuptools<81" "faster-whisper==1.2.1" "huggingface_hub>=0.34,<2"
env -u PYTHONPATH "$PY" -c "import torch; assert torch.cuda.is_available(), 'CUDA not available to torch'; print('torch', torch.__version__, torch.cuda.get_device_name(0))"

say "Voice model ($VOICE_REPO @ ${VOICE_REV:0:10}, ~19 GB)"
env -u PYTHONPATH "$DIR/.venv/bin/hf" download "$VOICE_REPO" --revision "$VOICE_REV" --local-dir "$DIR/MiniCPM-o-4_5" \
  --exclude "*.mp4" >/dev/null

say "Hand-off model ($HANDOFF_REPO @ ${HANDOFF_REV:0:10})"
env -u PYTHONPATH "$DIR/.venv/bin/hf" download "$HANDOFF_REPO" "$HANDOFF_FILE" --revision "$HANDOFF_REV" \
  --local-dir "$DIR/handoff" >/dev/null

say "llama.cpp $LLAMA_TAG (CPU build, checksum-verified)"
if [ ! -x "$DIR/llama.cpp/llama-server" ]; then
  curl -fsSL -o "$DIR/$LLAMA_TGZ" "https://github.com/ggml-org/llama.cpp/releases/download/$LLAMA_TAG/$LLAMA_TGZ"
  echo "$LLAMA_SHA256  $DIR/$LLAMA_TGZ" | sha256sum -c --quiet - || die "llama.cpp download failed its checksum"
  mkdir -p "$DIR/llama.cpp.tmp"
  tar -xzf "$DIR/$LLAMA_TGZ" -C "$DIR/llama.cpp.tmp"
  SERVER=$(find "$DIR/llama.cpp.tmp" -name llama-server -type f | head -1)
  [ -n "$SERVER" ] || die "llama-server not found in $LLAMA_TGZ"
  rm -rf "$DIR/llama.cpp" && mv "$(dirname "$SERVER")" "$DIR/llama.cpp"
  rm -rf "$DIR/llama.cpp.tmp" "$DIR/$LLAMA_TGZ"
fi

say "Code and token"
cp "$HERE/duplex_worker.py" "$HERE/stt_server.py" "$DIR/"
if [ ! -s "$DIR/worker_token" ]; then
  (umask 077; env -u PYTHONPATH "$PY" -c "import secrets; print(secrets.token_urlsafe(24))" > "$DIR/worker_token")
fi
chmod 600 "$DIR/worker_token"
TOKEN="$(cat "$DIR/worker_token")"

# Half the CPU threads for the hand-off model, so the voice worker keeps enough to stay real time.
THREADS=$(( $(nproc) / 2 )); [ "$THREADS" -ge 2 ] || THREADS=2
VOICE_ARGS="--model $DIR/MiniCPM-o-4_5 --quant $QUANT --host $LISTEN --port 8797 --token-file $DIR/worker_token"
[ "$MAX_GIB" -gt 0 ] && VOICE_ARGS="$VOICE_ARGS --max-gpu-gib $MAX_GIB"

if [ "$SERVICES" = 1 ]; then
  say "systemd user services"
  UNITS="$HOME/.config/systemd/user"
  mkdir -p "$UNITS"
  cat > "$UNITS/vessel-local-voice.service" <<EOF
[Unit]
Description=Vessel local voice: MiniCPM-o 4.5 duplex worker
After=network-online.target

[Service]
WorkingDirectory=$DIR
Environment=PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True
UnsetEnvironment=PYTHONPATH PYTHONHOME
ExecStart=$PY $DIR/duplex_worker.py $VOICE_ARGS
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
EOF
  cat > "$UNITS/vessel-local-stt.service" <<EOF
[Unit]
Description=Vessel local voice: speech-to-text (faster-whisper)

[Service]
WorkingDirectory=$DIR
UnsetEnvironment=PYTHONPATH PYTHONHOME
ExecStart=/bin/sh -c 'export LD_LIBRARY_PATH="\$(echo $DIR/.venv/lib/python3.10/site-packages/nvidia/*/lib | tr " " ":")"; exec $PY $DIR/stt_server.py --host $LISTEN --port 8798 --token-file $DIR/worker_token'
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
EOF
  cat > "$UNITS/vessel-local-router.service" <<EOF
[Unit]
Description=Vessel local voice: hand-off model (CPU)

[Service]
# CPU only: the GPU belongs to the voice model and speech-to-text.
Environment=CUDA_VISIBLE_DEVICES=
ExecStart=$DIR/llama.cpp/llama-server -m $DIR/handoff/$HANDOFF_FILE -a handoff --host $LISTEN --port 8799 -ngl 0 -t $THREADS -c 4096 --jinja --min-p 0.0 -np 1 --api-key-file $DIR/worker_token
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
EOF
  systemctl --user daemon-reload
  systemctl --user enable -q --now vessel-local-stt.service vessel-local-router.service vessel-local-voice.service
  loginctl enable-linger "$USER" 2>/dev/null || echo "(run 'sudo loginctl enable-linger $USER' to keep them running after logout)"
  say "Waiting for the voice model to load (up to 3 minutes)"
  for _ in $(seq 1 90); do curl -sf -m 2 "http://$LISTEN:8797/health" >/dev/null && break; sleep 2; done
  for port in 8797 8798 8799; do
    if curl -sf -m 3 -H "Authorization: Bearer $TOKEN" "http://$LISTEN:$port/health" >/dev/null; then
      echo "  ok   port $port"
    else
      echo "  FAIL port $port  (journalctl --user -u vessel-local-voice / -stt / -router)"
    fi
  done
fi

HOSTNAME_HINT="$LISTEN"
[ "$LISTEN" = "0.0.0.0" ] && HOSTNAME_HINT="$(hostname)"
say "Done. On your Hermes machine (copy $DIR/worker_token there first if it's another machine):"
echo "  hermes vessel local --url ws://$HOSTNAME_HINT:8797/ws --token-file $DIR/worker_token"
echo "Then restart the Vessel server and pick 'Local' in the app's voice list."
[ "$LISTEN" = "127.0.0.1" ] || echo "Keep ports 8797-8799 on a private network (e.g. your tailnet); every request needs the token."
