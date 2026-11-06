"""vessel: give Hermes a phone as its body.

The phone app streams microphone audio to this plugin. The plugin opens an
OpenAI Realtime voice session with the user's ChatGPT (Codex) sign-in through
Hermes's credentialed_provider_request, relays audio both ways, and hands real
work to Hermes through its API server.
"""

from __future__ import annotations


def _setup(subparser):
    subs = subparser.add_subparsers(dest="vessel_command")
    serve = subs.add_parser("serve", help="Run the phone link server")
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8796)
    st = subs.add_parser("selftest", help="Open one voice session and save a spoken reply (no phone needed)")
    st.add_argument("--voice", help="Voice to use for this test (default: the configured voice)")
    st.add_argument("--say", help="Have the voice say this exact line (handy for comparing voices)")
    st.add_argument("--out", help="Where to save the WAV (default: the plugin data dir)")
    subs.add_parser("pair", help="Print the pairing link to open on the phone")
    lo = subs.add_parser("local", help="Connect (or check, or turn off) the private Local voice on your own GPU")
    lo.add_argument("--url", help="the duplex worker, e.g. ws://gpu-box:8797/ws (printed by install.sh)")
    lo.add_argument("--token-file", help="file holding the worker's token (from install.sh); asked for if omitted")
    lo.add_argument("--stt", help="speech-to-text URL (default: same host, port 8798)")
    lo.add_argument("--router", help="hand-off model URL (default: same host, port 8799)")
    lo.add_argument("--off", action="store_true", help="remove the Local voice from this server")
    subparser.set_defaults(func=_handle)


def _handle(args):
    try:
        from . import vessel_server as cs
    except ImportError:  # flat-module load
        import vessel_server as cs

    sub = getattr(args, "vessel_command", None)
    if sub == "serve":
        return cs.cli_serve(args.host, args.port)
    if sub == "selftest":
        return cs.cli_selftest(voice=getattr(args, "voice", None), say=getattr(args, "say", None), out=getattr(args, "out", None))
    if sub == "pair":
        return cs.cli_pair()
    if sub == "local":
        return cs.cli_local(url=args.url, token_file=args.token_file, stt=args.stt, router=args.router, off=args.off)
    print("Usage: hermes vessel <serve|selftest|pair|local>")
    return 2


def register(ctx):
    ctx.register_cli_command(
        name="vessel",
        help="Phone vessel: a realtime voice link between a phone and Hermes",
        setup_fn=_setup,
        handler_fn=_handle,
    )
