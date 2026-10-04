# MOOD-BASED-PLAYLIST

AuraTune is a Java-based mood playlist application. Every mood playlist displays the exact curated Tamil film songs defined in `MoodMapper`. Spotify credentials power the separate search tool without replacing the curated mood playlists.

## Spotify setup

Create a local `.env` file from `.env.example` and add your Spotify Developer credentials. The server loads this ignored file automatically. Keep the client secret server-side and do not put it in `web/app.js` or `index.html`.

```powershell
Copy-Item .env.example .env
# Edit .env and replace both placeholder values.
javac -d out src/AuraTuneServer.java
java -cp out AuraTuneServer
```

Process environment variables named `SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET` override values in `.env`.

The API keys are read only by the Java server. Do not place them in `web/app.js`, `web/index.html`, or any browser code.

Add `http://localhost:8080` as an allowed redirect URI in the Spotify Developer Dashboard if you later add user login. The current integration uses Spotify's client-credentials flow, so it does not require a redirect or a Spotify user account.

The `GET /api/mood?mood=Focused` endpoint returns the curated Tamil playlist for the selected mood. The `GET /api/search?q=search-term&type=track` endpoint uses the server-side Spotify credentials for live Spotify search results.

The Spotify search tool is available in the web interface and through `GET /api/search?q=search-term&type=track`. Supported types are `track`, `artist`, `album`, `playlist`, and `episode` (podcast episodes). Results are normalized by the Java server before being sent to the browser.

Use **Add to playlist** on a mood track, Spotify track result, favorite, or saved song to create a named playlist or add the song to an existing one. User playlists are stored in the browser's local storage on that device.

## Deploy from GitHub with Render

1. Push this project, including `Dockerfile` and `render.yaml`, to GitHub.
2. In Render, choose **New + → Blueprint**, connect the GitHub repository, and select the branch to deploy. Render reads `render.yaml`, builds the Docker image, and deploys the web service. Future pushes to the selected branch are deployed automatically.
3. When prompted, set `SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET` to the values from your Spotify Developer Dashboard. You can also add or update them under the service's **Environment** settings in Render. Keep the secret out of GitHub and browser code.
4. Open the `auratune` service URL shown in Render after the deploy is healthy.

The Java server binds to the `PORT` value supplied by Render and continues to use ports 8080–8082 when run locally. The Render Blueprint uses the free web-service plan, which may spin down after inactivity.
