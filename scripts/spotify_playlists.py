"""Daily snapshot of the Spotify playlists behind the Persian chart.

Reads each playlist with spotapi (no login) and writes data/spotify_playlists.json:
one entry per playlist with its name and its tracks in playlist order. Playlists that fail
keep their previous snapshot, so one bad day does not empty the chart.

Usage: python scripts/spotify_playlists.py [output_path]
"""
import json
import sys
import datetime
from pathlib import Path

from spotapi import PublicPlaylist

PLAYLISTS = [
    "https://open.spotify.com/playlist/2YZfv7FxCf9zdkshysPkJ2",
    "https://open.spotify.com/playlist/5AY1n65pPMeTO6e7Bmo4zp",
    "https://open.spotify.com/playlist/3cUIzE0uqhCyuHMZTmNmK2",
]

DEFAULT_OUT = Path(__file__).resolve().parent.parent / "data" / "spotify_playlists.json"


def track_from_item(item: dict) -> dict | None:
    data = (item.get("itemV2") or {}).get("data") or {}
    if not data.get("name"):
        return None
    artists = [a.get("profile", {}).get("name", "") for a in (data.get("artists") or {}).get("items", [])]
    sources = (((data.get("albumOfTrack") or {}).get("coverArt") or {}).get("sources")) or []
    return {
        "uri": data.get("uri"),
        "title": data["name"],
        "artists": [a for a in artists if a],
        "durationMs": (data.get("trackDuration") or {}).get("totalMilliseconds"),
        "artwork": sources[0]["url"] if sources else None,
    }


def read_playlist(url: str) -> dict:
    pl = PublicPlaylist(url)
    head = pl.get_playlist_info(limit=1)["data"]["playlistV2"]
    tracks: list[dict] = []
    for page in pl.paginate_playlist():
        for item in page.get("items", []):
            track = track_from_item(item)
            if track:
                tracks.append(track)
    return {"id": pl.playlist_id, "name": head.get("name", ""), "tracks": tracks}


def main() -> int:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT
    previous = {}
    if out.exists():
        previous = {p["id"]: p for p in json.loads(out.read_text()).get("playlists", [])}

    playlists = []
    failed = 0
    for url in PLAYLISTS:
        pid = url.rsplit("/", 1)[-1]
        try:
            entry = read_playlist(url)
            print(f"{pid}: {entry['name']!r}, {len(entry['tracks'])} tracks")
            playlists.append(entry)
        except Exception as e:  # keep yesterday's snapshot for this playlist
            failed += 1
            print(f"{pid}: failed ({type(e).__name__}: {e})", file=sys.stderr)
            if pid in previous:
                playlists.append(previous[pid])

    if not playlists:
        print("No playlist could be read; leaving the snapshot unchanged.", file=sys.stderr)
        return 1

    snapshot = {
        "updated": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "playlists": playlists,
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n")
    print(f"Wrote {out} ({failed} playlist(s) failed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
