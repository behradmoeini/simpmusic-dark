# SimpMusic MCP server

Play YouTube Music from a chat client. Ask Claude (or any MCP client) "play Googoosh" and it plays
on your computer. No SimpMusic app needed: the server uses SimpMusic's own YouTube Music client for
search, radio and streams, and plays the audio with mpv.

It runs on your own computer only. YouTube ties every stream link to the IP address that asked for
it, so a server hosted somewhere else could not play anything for you.

## What you need

- **Java 21 or newer**: `java -version` should say 21+. (macOS: `brew install openjdk@21`;
  Windows: `winget install Microsoft.OpenJDK.21`; Linux: `sudo apt install openjdk-21-jre`.)
- **mpv**: macOS `brew install mpv`; Windows `winget install mpv` (or `scoop install mpv`);
  Linux `sudo apt install mpv`. If it lives somewhere unusual, set `SIMPMUSIC_MPV` to its path.

## Install

1. Download `simpmusic-mcp.zip` from the
   [latest release](https://github.com/behradmoeini/simpmusic-dark/releases/tag/latest) and unzip
   it anywhere, e.g. `~/simpmusic-mcp`.
2. Add it to your client. The program is `bin/simpmusic-mcp` (`bin\simpmusic-mcp.bat` on Windows).

**Claude Code**

```sh
claude mcp add simpmusic -- ~/simpmusic-mcp/bin/simpmusic-mcp
```

**Claude Desktop** (Settings → Developer → Edit config, then restart Claude):

```json
{
  "mcpServers": {
    "simpmusic": {
      "command": "/Users/you/simpmusic-mcp/bin/simpmusic-mcp"
    }
  }
}
```

On Windows use the full path to `simpmusic-mcp.bat`, with doubled backslashes in JSON.

Other MCP clients (Cursor, VS Code, LM Studio…) take the same command in their MCP settings.

## Tools

| Tool | What it does |
| --- | --- |
| `play` | Plays a song by name or id and queues a radio of similar songs after it |
| `search_music` | Searches songs, videos, albums or playlists and returns their ids |
| `play_playlist` | Plays an album or playlist, optionally shuffled |
| `add_to_queue` | Adds a song to the end, or right after the current one |
| `pause`, `resume`, `next`, `previous`, `stop` | Playback controls |
| `now_playing` | Current song, position and what comes next |
| `show_queue` | The queue from the current song on |
| `seek`, `set_volume` | Position and volume |

Music keeps playing while the chat client is open; closing it (which ends the server) stops mpv.

## Notes

- Nothing is signed in: it plays as an anonymous YouTube Music listener.
- Pulling streams out of YouTube is against YouTube's terms of service. This is for personal use.
- Logs go to the client's MCP server log (stderr), never to the protocol stream.

## Building

From the repository root: `./gradlew :mcpServer:installDist` builds `mcpServer/build/install/simpmusic-mcp/`,
`:mcpServer:distZip` the zip. CI builds the zip and attaches it to the `latest` release.
