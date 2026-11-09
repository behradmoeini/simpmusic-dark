"""Live check: can this environment read a public Spotify user's playlists the way the app does?

Steps, each printed with its result:
1. TOTP secret from the community secret list (same source the app uses).
2. Spotify server time.
3. Web-player access token from open.spotify.com/api/token.
4. The user's playlists from api.spotify.com/v1.
5. The tracks of the first playlist.
Standard library only, so it runs on a bare CI runner.
"""
import base64, hashlib, hmac, json, struct, sys, time, urllib.error, urllib.parse, urllib.request

USER = "31xtmpfhpw4qo2fvz36tjq3oktzm"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")


def get(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:  # network or DNS
        return None, str(e).encode()


def report(step, status, body=b""):
    snippet = body[:200].decode("utf-8", "replace").replace("\n", " ")
    print(f"[{step}] HTTP {status} {snippet}")


def totp_candidates(secret_bytes, version):
    """Two readings of the secret, because the transform is documented only in community code."""
    transformed = [b ^ ((i % 33) + 9) for i, b in enumerate(secret_bytes)]
    return {
        "utf8-of-joined-digits": "".join(str(b) for b in transformed).encode(),
        "raw-transformed-bytes": bytes(transformed),
    }


def totp(key, t):
    counter = struct.pack(">Q", int(t) // 30)
    digest = hmac.new(key, counter, hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000
    return f"{code:06d}"


def main():
    # 1. Secret list.
    status, body = get("https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json")
    report("1 secret list", status, body)
    if status != 200:
        return 1
    secrets = json.loads(body)
    version = max(secrets, key=lambda v: int(v))
    raw = secrets[version]
    print(f"[1] latest version {version}, {len(raw)} bytes")

    # 2. Server time.
    status, body = get("https://open.spotify.com/api/server-time", {"Origin": "https://open.spotify.com", "Referer": "https://open.spotify.com/"})
    report("2 server time", status, body)
    if status != 200:
        return 1
    server_time = json.loads(body)["serverTime"]

    # 3. Access token, trying each reading of the secret.
    token = None
    for name, key in totp_candidates(raw, version).items():
        otp = totp(key, server_time)
        q = urllib.parse.urlencode({"reason": "init", "productType": "web-player",
                                    "totp": otp, "totpServer": otp, "totpVer": version})
        status, body = get(f"https://open.spotify.com/api/token?{q}",
                           {"Origin": "https://open.spotify.com", "Referer": "https://open.spotify.com/"})
        report(f"3 token ({name})", status, body)
        if status == 200 and b"accessToken" in body:
            token = json.loads(body)["accessToken"]
            break
    if not token:
        print("[3] no token from either reading of the secret")
        return 1

    auth = {"Authorization": f"Bearer {token}"}

    # 4. The user's playlists.
    status, body = get(f"https://api.spotify.com/v1/users/{USER}/playlists?limit=50", auth)
    report("4 user playlists", status, body)
    if status != 200:
        return 1
    items = json.loads(body).get("items", [])
    print(f"[4] {len(items)} playlists")
    for p in items[:20]:
        print(f"    - {p.get('name')} ({p.get('id')}) tracks={p.get('tracks', {}).get('total')}")
    if not items:
        return 1

    # 5. Tracks of the first playlist, on both endpoint names.
    pid = items[0]["id"]
    for path in ("items", "tracks"):
        status, body = get(f"https://api.spotify.com/v1/playlists/{pid}/{path}?limit=5", auth)
        report(f"5 playlist {path}", status, body)
        if status == 200:
            data = json.loads(body).get("items", [])
            for entry in data:
                track = entry.get("track") or entry.get("item") or {}
                artists = ", ".join(a.get("name", "") for a in track.get("artists", []))
                print(f"    - {track.get('name')} — {artists}")
            return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
