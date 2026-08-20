import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuraTuneServer {
    private static final Map<String, String> DOT_ENV = loadDotEnv();
    private static final SpotifyApiClient SPOTIFY = new SpotifyApiClient(
            configuredValue("SPOTIFY_CLIENT_ID"),
            configuredValue("SPOTIFY_CLIENT_SECRET"));

    private static String configuredValue(String name) {
        String environmentValue = System.getenv(name);
        return environmentValue != null && !environmentValue.isBlank() ? environmentValue : DOT_ENV.get(name);
    }

    private static Map<String, String> loadDotEnv() {
        Map<String, String> values = new HashMap<>();
        Path envFile = Path.of(".env");
        if (!Files.exists(envFile)) {
            return values;
        }

        try {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException e) {
            System.out.println("Unable to read .env; using process environment variables.");
        }
        return values;
    }

    public static void main(String[] args) throws IOException {
        int[] ports = {8080, 8081, 8082};
        HttpServer server = null;
        int boundPort = -1;

        for (int port : ports) {
            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
                boundPort = port;
                break;
            } catch (IOException e) {
                System.out.println("Port " + port + " unavailable, trying next port...");
            }
        }

        if (server == null) {
            throw new IOException("Unable to bind to ports " + java.util.Arrays.toString(ports));
        }

        server.createContext("/", new StaticFileHandler());
        server.createContext("/api/mood", new MoodHandler());
        server.createContext("/api/search", new SearchHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("AuraTune server running at http://localhost:" + boundPort);
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            if (path == null || path.equals("/")) {
                path = "/index.html";
            }

            String target = path.substring(1);
            if (target.isEmpty()) {
                target = "index.html";
            }

            byte[] content;
            String contentType;

            if (target.equals("index.html")) {
                content = FileLoader.read("index.html");
                contentType = "text/html; charset=UTF-8";
            } else if (target.equals("styles.css")) {
                content = FileLoader.read("styles.css");
                contentType = "text/css; charset=UTF-8";
            } else if (target.equals("app.js")) {
                content = FileLoader.read("app.js");
                contentType = "application/javascript; charset=UTF-8";
            } else {
                sendText(exchange, 404, "Not Found");
                return;
            }

            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }

    static class MoodHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method)) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String moodName = getQueryParam(query, "mood");
            if (moodName == null || moodName.trim().isEmpty()) {
                sendText(exchange, 400, "Please provide a valid mood parameter.");
                return;
            }

            moodName = moodName.trim();

            UserMood mood = UserMood.fromInput(moodName);
            if (mood == null) {
                sendText(exchange, 400, "Unsupported mood. Try Calm, Energetic, Focused, Melancholy, Stressed, or Joyful." );
                return;
            }

            Playlist playlist = SPOTIFY.createMoodPlaylist(mood);
            String response = playlist.toApiResponse();

            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    static class SearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            String query = getQueryParam(exchange.getRequestURI().getQuery(), "q");
            String type = getQueryParam(exchange.getRequestURI().getQuery(), "type");
            if (query == null || query.trim().isEmpty()) {
                sendText(exchange, 400, "Please provide a search query.");
                return;
            }
            if (!SpotifyApiClient.isSearchTypeSupported(type)) {
                sendText(exchange, 400, "Search type must be track, artist, album, playlist, or episode.");
                return;
            }

            try {
                String response = SPOTIFY.search(query.trim(), type);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendText(exchange, 503, "Spotify search was interrupted.");
            } catch (SpotifyApiException e) {
                sendText(exchange, e.statusCode, e.getMessage());
            }
        }
    }

    private static String getQueryParam(String query, String key) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && pair[0].equalsIgnoreCase(key)) {
                return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    static void sendText(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

class FileLoader {
    public static byte[] read(String fileName) throws IOException {
        String relative = "web" + java.io.File.separator + fileName;
        java.io.File file = new java.io.File(relative);
        if (!file.exists()) {
            throw new IOException("Missing static file: " + fileName);
        }
        return java.nio.file.Files.readAllBytes(file.toPath());
    }
}

class UserMood {
    private final String name;
    private final String description;

    public UserMood(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public static UserMood fromInput(String input) {
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "calm":
                return new UserMood("Calm", "Soft and restorative listening");
            case "energized":
            case "energetic":
                return new UserMood("Energetic", "High-drive push listening");
            case "focused":
                return new UserMood("Focused", "Steady concentration playlist");
            case "melancholy":
            case "sad":
                return new UserMood("Melancholy", "Reflective and emotional listening");
            case "stressed":
            case "stressed out":
                return new UserMood("Stressed", "Recovery and release");
            case "joyful":
            case "happy":
                return new UserMood("Joyful", "Bright and uplifting soundscape");
            default:
                return null;
        }
    }
}

class Track {
    private final String title;
    private final String artist;
    private final String album;
    private final int bpm;
    private final int energy;
    private final int valence;
    private final String duration;
    private final String spotifyUrl;

    public Track(String title, String artist, String album, int bpm, int energy, int valence, String duration, String spotifyUrl) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.bpm = bpm;
        this.energy = energy;
        this.valence = valence;
        this.duration = duration;
        this.spotifyUrl = spotifyUrl;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public int getBpm() {
        return bpm;
    }

    public int getEnergy() {
        return energy;
    }

    public int getValence() {
        return valence;
    }

    public String getDuration() {
        return duration;
    }

    public String getSpotifyUrl() {
        return spotifyUrl;
    }
}

class Playlist {
    private final UserMood mood;
    private final List<Track> tracks;
    public Playlist(UserMood mood, List<Track> tracks) {
        this.mood = mood;
        this.tracks = tracks;
    }

    public UserMood getMood() {
        return mood;
    }

    public List<Track> getTracks() {
        return tracks;
    }

    public String toApiResponse() {
        StringBuilder response = new StringBuilder();
        response.append("Mood: ").append(mood.getName()).append("\n");
        response.append("Description: ").append(mood.getDescription()).append("\n");
        response.append("Tracks:\n");

        for (Track track : tracks) {
            response.append("- ").append(track.getTitle())
                    .append(" by ").append(track.getArtist())
                    .append(" | Album: ").append(track.getAlbum())
                    .append(" | BPM: ").append(track.getBpm())
                    .append(" | Energy: ").append(track.getEnergy())
                    .append(" | Positivity: ").append(track.getValence())
                    .append(" | Duration: ").append(track.getDuration())
                    .append(" | Spotify: ").append(track.getSpotifyUrl())
                    .append("\n");
        }

        return response.toString();
    }
}

class MoodMapper {
    public static Playlist mapMoodToPlaylist(UserMood mood) {
        List<Track> tracks = new ArrayList<>();
        String moodName = mood.getName();

        switch (moodName) {
            case "Calm":
                tracks.add(new Track("Melliname", "Harris Jayaraj", "Shahjahan", 86, 17, 73, "5:25", "https://open.spotify.com/search/Melliname%20Harris%20Jayaraj"));
                tracks.add(new Track("Mazhai Kuruvi", "A.R. Rahman", "Chekka Chivantha Vaanam", 82, 15, 68, "5:48", "https://open.spotify.com/search/Mazhai%20Kuruvi%20A.R.%20Rahman"));
                tracks.add(new Track("Malargal Kaettaen", "A.R. Rahman", "O Kadhal Kanmani", 78, 14, 76, "5:54", "https://open.spotify.com/search/Malargal%20Kaettaen%20A.R.%20Rahman"));
                tracks.add(new Track("A Thousand Years", "Christina Perri", "The Twilight Saga: Breaking Dawn", 139, 19, 45, "4:45", "https://open.spotify.com/track/6f1hgqFWaWPhc9CpuiItkR"));
                tracks.add(new Track("Photograph", "Ed Sheeran", "x", 108, 20, 62, "4:18", "https://open.spotify.com/search/Photograph%20Ed%20Sheeran"));
                break;
            case "Energetic":
                tracks.add(new Track("Smooth Criminal", "Michael Jackson", "Bad", 124, 93, 88, "4:17", "https://open.spotify.com/track/2bCQHF9gdG5BNDVuEIEnNk"));
                tracks.add(new Track("Turbo Hearts", "Fireline", "After Dark", 128, 90, 75, "3:37", "https://open.spotify.com/track/turbo-hearts"));
                tracks.add(new Track("Pulse District", "The Urban Frequency", "Run It Back", 118, 89, 86, "3:45", "https://open.spotify.com/track/pulse-district"));
                tracks.add(new Track("Solar Drive", "Neon Engine", "Speed of Sound", 132, 94, 82, "3:28", "https://open.spotify.com/track/solar-drive"));
                tracks.add(new Track("High Voltage", "Rhythm Collider", "Sparked", 126, 91, 85, "3:51", "https://open.spotify.com/track/high-voltage"));
                break;
            case "Focused":
                tracks.add(new Track("Deep Production Line", "Logic Motion", "Clean Room", 108, 62, 54, "4:10", "https://open.spotify.com/track/deep-production-line"));
                tracks.add(new Track("Low Key Systems", "Orbit Studies", "Library Hours", 96, 50, 63, "4:00", "https://open.spotify.com/track/low-key-systems"));
                tracks.add(new Track("Control Flow", "The Quiet Index", "Work Drift", 104, 58, 57, "4:08", "https://open.spotify.com/track/control-flow"));
                tracks.add(new Track("Steady Pulse", "Focus Protocol", "Minimal Modes", 98, 52, 60, "3:52", "https://open.spotify.com/track/steady-pulse"));
                tracks.add(new Track("Coded Silence", "The Study Set", "Precision", 90, 48, 55, "3:46", "https://open.spotify.com/track/coded-silence"));
                break;
            case "Melancholy":
                tracks.add(new Track("Blue Rooms", "Sunday Weather", "Left Home", 88, 33, 24, "4:22", "https://open.spotify.com/track/blue-rooms"));
                tracks.add(new Track("After the Day", "Loam Stories", "Fading Places", 74, 28, 22, "4:06", "https://open.spotify.com/track/after-the-day"));
                tracks.add(new Track("Late Signal", "The Collapsing Scene", "Night Archive", 86, 40, 28, "4:14", "https://open.spotify.com/track/late-signal"));
                tracks.add(new Track("Falling Ink", "Quiet Confession", "Paper Skies", 80, 36, 20, "3:58", "https://open.spotify.com/track/falling-ink"));
                tracks.add(new Track("Lost Pages", "Winter Hollow", "Distant Places", 84, 38, 26, "4:10", "https://open.spotify.com/track/lost-pages"));
                break;
            case "Stressed":
                tracks.add(new Track("Release Frequency", "The Green Rooms", "Breathe In", 104, 46, 72, "4:05", "https://open.spotify.com/track/release-frequency"));
                tracks.add(new Track("Blue Hour Reset", "Mason Daylight", "Circle", 78, 30, 88, "3:52", "https://open.spotify.com/track/blue-hour-reset"));
                tracks.add(new Track("Room to Breathe", "Astra Lane", "Afterwork", 92, 55, 82, "4:15", "https://open.spotify.com/track/room-to-breathe"));
                tracks.add(new Track("Gentle Release", "Calm Circuit", "Soft Relief", 86, 40, 84, "4:02", "https://open.spotify.com/track/gentle-release"));
                tracks.add(new Track("Open Window", "Night Meadow", "Clear Skies", 88, 42, 81, "3:59", "https://open.spotify.com/track/open-window"));
                break;
            case "Joyful":
                tracks.add(new Track("Golden Street", "The Weekend Signal", "Open Skies", 118, 88, 97, "3:55", "https://open.spotify.com/track/golden-street"));
                tracks.add(new Track("Bright Room", "Morning Machine", "Color Release", 116, 84, 95, "4:02", "https://open.spotify.com/track/bright-room"));
                tracks.add(new Track("Sunday Light", "The Skyline Pop Line", "High Sun", 110, 80, 90, "3:49", "https://open.spotify.com/track/sunday-light"));
                tracks.add(new Track("Happy Carousel", "Joy Motion", "Summer Nights", 112, 86, 93, "4:14", "https://open.spotify.com/track/happy-carousel"));
                tracks.add(new Track("Radiant Walk", "Golden Pulse", "Feeling Good", 120, 90, 96, "4:07", "https://open.spotify.com/track/radiant-walk"));
                break;
            default:
                tracks.add(new Track("Open Door", "Aura Tone", "Default Session", 100, 60, 75, "3:30", "https://open.spotify.com/track/open-door"));
        }

        return new Playlist(mood, tracks);
    }
}

class SpotifyApiClient {
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SEARCH_URL = "https://api.spotify.com/v1/search";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\\"access_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern NAME_PATTERN = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern ARTISTS_PATTERN = Pattern.compile("\\\"artists\\\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
    private static final Pattern ALBUM_PATTERN = Pattern.compile("\\\"album\\\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL);
    private static final Pattern DURATION_PATTERN = Pattern.compile("\\\"duration_ms\\\"\\s*:\\s*(\\d+)");
    private static final Pattern SPOTIFY_URL_PATTERN = Pattern.compile("\\\"external_urls\\\"\\s*:\\s*\\{.*?\\\"spotify\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.DOTALL);
    private static final Pattern ARTIST_PATTERN = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");

    private final String clientId;
    private final String clientSecret;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String accessToken;
    private long tokenExpiresAt;

    public static boolean isSearchTypeSupported(String type) {
        return type != null && (type.equals("track") || type.equals("artist") || type.equals("album")
                || type.equals("playlist") || type.equals("episode"));
    }

    public SpotifyApiClient(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public String buildTokenRequestBody() {
        return "grant_type=client_credentials";
    }

    public String buildAuthorizationHeader() {
        return "Basic " + java.util.Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    }

    public String createSearchUri(String mood) {
        String seed;
        switch (mood.toLowerCase(Locale.ROOT)) {
            case "calm": seed = "ambient relaxation"; break;
            case "energetic": seed = "energetic pop"; break;
            case "focused": seed = "instrumental focus"; break;
            case "melancholy": seed = "melancholic indie"; break;
            case "stressed": seed = "chill meditation"; break;
            case "joyful": seed = "happy upbeat"; break;
            default: seed = "pop"; break;
        }

        return SEARCH_URL + "?q=" + URLEncoder.encode(seed, StandardCharsets.UTF_8) + "&type=track&limit=5";
    }

    public Playlist createMoodPlaylist(UserMood mood) {
        Playlist fallback = MoodMapper.mapMoodToPlaylist(mood);
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return fallback;
        }

        try {
            String token = getAccessToken();
            HttpRequest request = HttpRequest.newBuilder(URI.create(createSearchUri(mood.getName())))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return fallback;
            }
            List<Track> tracks = parseTracks(response.body());
            return tracks.isEmpty() ? fallback : new Playlist(mood, tracks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    public String search(String query, String type) throws IOException, InterruptedException, SpotifyApiException {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new SpotifyApiException(503, "Spotify credentials are not configured on the server.");
        }

        String searchUri = SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&type=" + type + "&limit=10";
        HttpRequest request = HttpRequest.newBuilder(URI.create(searchUri))
                .header("Authorization", "Bearer " + getAccessToken())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new SpotifyApiException(response.statusCode(), "Spotify search failed with status " + response.statusCode() + ".");
        }
        return normalizeSearchResults(response.body(), type);
    }

    private String normalizeSearchResults(String json, String type) {
        StringBuilder result = new StringBuilder("{\"type\":\"").append(type).append("\",\"items\":[");
        List<String> objects = extractTrackObjects(json);
        for (int index = 0; index < objects.size() && index < 10; index++) {
            if (index > 0) {
                result.append(',');
            }
            String object = objects.get(index);
            String name = extractTopLevelString(object, "name", "Untitled");
            String artist = extract(ARTIST_PATTERN, extract(ARTISTS_PATTERN, object, ""), "");
            String album = extractTopLevelString(extract(ALBUM_PATTERN, object, ""), "name", "");
            String show = extractTopLevelString(extract(SHOW_PATTERN, object, ""), "name", "");
            String owner = extract(DISPLAY_NAME_PATTERN, extract(OWNER_PATTERN, object, ""), "");
            String url = extractLast(SPOTIFY_URL_PATTERN, object, "#");
            String image = extract(IMAGE_URL_PATTERN, object, "");
            String releaseDate = extract(RELEASE_DATE_PATTERN, object, "");
            String duration = formatDuration(Long.parseLong(extract(DURATION_PATTERN, object, "0")));
            String total = extract(TRACKS_TOTAL_PATTERN, object, "");
            result.append("{\"name\":\"").append(jsonEscape(name))
                    .append("\",\"artist\":\"").append(jsonEscape(artist))
                    .append("\",\"album\":\"").append(jsonEscape(album))
                    .append("\",\"show\":\"").append(jsonEscape(show))
                    .append("\",\"owner\":\"").append(jsonEscape(owner))
                    .append("\",\"url\":\"").append(jsonEscape(url))
                    .append("\",\"image\":\"").append(jsonEscape(image))
                    .append("\",\"releaseDate\":\"").append(jsonEscape(releaseDate))
                    .append("\",\"duration\":\"").append(duration)
                    .append("\",\"total\":\"").append(jsonEscape(total)).append("\"}");
        }
        return result.append("]}").toString();
    }

    private synchronized String getAccessToken() throws IOException, InterruptedException {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return accessToken;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Authorization", buildAuthorizationHeader())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(buildTokenRequestBody()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Spotify token request failed with status " + response.statusCode());
        }

        Matcher matcher = TOKEN_PATTERN.matcher(response.body());
        if (!matcher.find()) {
            throw new IOException("Spotify token response did not contain an access token");
        }
        accessToken = matcher.group(1);
        tokenExpiresAt = System.currentTimeMillis() + 3_300_000L;
        return accessToken;
    }

    private List<Track> parseTracks(String json) {
        List<Track> tracks = new ArrayList<>();
        for (String trackJson : extractTrackObjects(json)) {
            if (tracks.size() == 5) {
                break;
            }
            String title = extract(NAME_PATTERN, trackJson, "Unknown track");
            String artistsJson = extract(ARTISTS_PATTERN, trackJson, "");
            String artist = extract(ARTIST_PATTERN, artistsJson, "Unknown artist");
            String albumJson = extract(ALBUM_PATTERN, trackJson, "");
            String album = extract(NAME_PATTERN, albumJson, "Unknown album");
            long durationMs = Long.parseLong(extract(DURATION_PATTERN, trackJson, "0"));
            String spotifyUrl = extract(SPOTIFY_URL_PATTERN, trackJson, "#");
            tracks.add(new Track(
                    title,
                    artist,
                    album,
                    0,
                    0,
                    0,
                    formatDuration(durationMs),
                    spotifyUrl));
        }
        return tracks;
    }

    private List<String> extractTrackObjects(String json) {
        List<String> objects = new ArrayList<>();
        int itemsStart = json.indexOf("\"items\"");
        int arrayStart = itemsStart < 0 ? -1 : json.indexOf('[', itemsStart);
        if (arrayStart < 0) {
            return objects;
        }

        int depth = 0;
        int objectStart = -1;
        boolean quoted = false;
        for (int index = arrayStart + 1; index < json.length(); index++) {
            char character = json.charAt(index);
            if (character == '"' && (index == 0 || json.charAt(index - 1) != '\\')) {
                quoted = !quoted;
            }
            if (quoted) {
                continue;
            }
            if (character == '{') {
                if (depth == 0) {
                    objectStart = index;
                }
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    objects.add(json.substring(objectStart, index + 1));
                    objectStart = -1;
                }
            } else if (character == ']' && depth == 0) {
                break;
            }
        }
        return objects;
    }

    private String extract(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private String extractLast(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        String value = fallback;
        while (matcher.find()) {
            value = matcher.group(1);
        }
        return value;
    }

    private String extractTopLevelString(String json, String key, String fallback) {
        String keyToken = "\"" + key + "\"";
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (!quoted && depth == 1 && json.startsWith(keyToken, index)) {
                int colon = json.indexOf(':', index + keyToken.length());
                int quoteStart = colon < 0 ? -1 : json.indexOf('"', colon + 1);
                int quoteEnd = quoteStart < 0 ? -1 : json.indexOf('"', quoteStart + 1);
                if (quoteEnd > quoteStart) {
                    return json.substring(quoteStart + 1, quoteEnd);
                }
            }
            if (character == '"' && (index == 0 || json.charAt(index - 1) != '\\')) {
                quoted = !quoted;
            }
            if (quoted) {
                continue;
            }
            if (character == '{' || character == '[') {
                depth++;
            } else if (character == '}' || character == ']') {
                depth--;
            }
        }
        return fallback;
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000;
        return (totalSeconds / 60) + ":" + String.format(Locale.ROOT, "%02d", totalSeconds % 60);
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static final Pattern SHOW_PATTERN = Pattern.compile("\\\"show\\\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL);
    private static final Pattern OWNER_PATTERN = Pattern.compile("\\\"owner\\\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL);
    private static final Pattern DISPLAY_NAME_PATTERN = Pattern.compile("\\\"display_name\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile("\\\"images\\\"\\s*:\\s*\\[.*?\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.DOTALL);
    private static final Pattern RELEASE_DATE_PATTERN = Pattern.compile("\\\"release_date\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern TRACKS_TOTAL_PATTERN = Pattern.compile("\\\"tracks\\\"\\s*:\\s*\\{.*?\\\"total\\\"\\s*:\\s*(\\d+)", Pattern.DOTALL);
}

class SpotifyApiException extends Exception {
    final int statusCode;

    SpotifyApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
}