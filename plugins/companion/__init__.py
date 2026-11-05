"""companion: give Hermes a phone as its body.

The phone app streams microphone audio to this plugin. The plugin opens an
OpenAI Realtime voice session with the user's ChatGPT (Codex) sign-in through
Hermes's credentialed_provider_request, relays audio both ways, and hands real
work to Hermes through its API server.
"""

from __future__ import annotations


def _setup(subparser):
    subs = subparser.add_subparsers(dest="companion_command")
    serve = subs.add_parser("serve", help="Run the phone link server")
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8796)
    subs.add_parser("selftest", help="Open one voice session and save a spoken reply (no phone needed)")
    subs.add_parser("pair", help="Print the pairing link to open on the phone")
    subparser.set_defaults(func=_handle)


def _handle(args):
    try:
        from . import companion_server as cs
    except ImportError:  # flat-module load
        import companion_server as cs

    sub = getattr(args, "companion_command", None)
    if sub == "serve":
        return cs.cli_serve(args.host, args.port)
    if sub == "selftest":
        return cs.cli_selftest()
    if sub == "pair":
        return cs.cli_pair()
    print("Usage: hermes companion <serve|selftest|pair>")
    return 2


def register(ctx):
    ctx.register_cli_command(
        name="companion",
        help="Phone companion: a realtime voice link between a phone and Hermes",
        setup_fn=_setup,
        handler_fn=_handle,
    )
