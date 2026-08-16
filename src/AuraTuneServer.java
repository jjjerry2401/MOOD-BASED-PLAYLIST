import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AuraTuneServer {

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

            Playlist playlist = MoodMapper.mapMoodToPlaylist(mood);
            String response = playlist.toApiResponse();

            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
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
    private String clientId;
    private String clientSecret;

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
        String seed = "";
        switch (mood.toLowerCase(Locale.ROOT)) {
            case "calm": seed = "ambient"; break;
            case "energetic": seed = "pop"; break;
            case "focused": seed = "instrumental"; break;
            case "melancholy": seed = "indie"; break;
            case "stressed": seed = "chill"; break;
            case "joyful": seed = "happy"; break;
            default: seed = "pop"; break;
        }

        return "https://api.spotify.com/v1/recommendations?limit=5&seed_genres=" + seed;
    }
}