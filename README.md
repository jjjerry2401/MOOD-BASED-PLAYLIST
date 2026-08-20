# MOOD-BASED-PLAYLIST

AuraTune is a Java-based mood playlist application. When Spotify credentials are configured, the server requests live tracks from Spotify; otherwise it uses the built-in curated playlists.

## Spotify setup

Create a local `.env` file from `.env.example` and add your Spotify Developer credentials. The server loads this ignored file automatically. Keep the client secret server-side and do not put it in `web/app.js` or `index.html`.

```powershell
Copy-Item .env.example .env
# Edit .env and replace both placeholder values.
javac -d out src/AuraTuneServer.java
java -cp out AuraTuneServer
```

Process environment variables named `SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET` override values in `.env`.

Add `http://localhost:8080` as an allowed redirect URI in the Spotify Developer Dashboard if you later add user login. The current integration uses Spotify's client-credentials flow, so it does not require a redirect or a Spotify user account.

The `GET /api/mood?mood=Focused` endpoint obtains and caches an app access token, searches Spotify for mood-related tracks, and falls back to the local playlist when Spotify is unavailable.

The Spotify search tool is available in the web interface and through `GET /api/search?q=search-term&type=track`. Supported types are `track`, `artist`, `album`, `playlist`, and `episode` (podcast episodes). Results are normalized by the Java server before being sent to the browser.
