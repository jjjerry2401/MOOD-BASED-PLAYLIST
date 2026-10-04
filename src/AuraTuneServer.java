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
        HttpServer server = null;
        int boundPort;
        String configuredPort = System.getenv("PORT");

        if (configuredPort != null && !configuredPort.isBlank()) {
            try {
                boundPort = Integer.parseInt(configuredPort);
            } catch (NumberFormatException e) {
                throw new IOException("PORT must be a valid port number.", e);
            }
            if (boundPort < 1 || boundPort > 65535) {
                throw new IOException("PORT must be between 1 and 65535.");
            }
            server = HttpServer.create(new InetSocketAddress(boundPort), 0);
        } else {
            int[] ports = {8080, 8081, 8082};
            boundPort = -1;
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
                tracks.add(new Track("Munbe Vaa", "A.R. Rahman", "Sillunu Oru Kadhal", 92, 14, 68, "5:58", "https://open.spotify.com/search/Munbe%20Vaa%20A.R.%20Rahman"));
                tracks.add(new Track("Vaseegara", "Harris Jayaraj", "Minnale", 94, 12, 65, "5:02", "https://open.spotify.com/search/Vaseegara%20Harris%20Jayaraj"));
                tracks.add(new Track("Nenjukkul Peidhidum", "Harris Jayaraj", "Vaaranam Aayiram", 89, 18, 72, "6:09", "https://open.spotify.com/search/Nenjukkul%20Peidhidum%20Harris%20Jayaraj"));
                tracks.add(new Track("Pachai Kiligal", "A.R. Rahman", "Indian", 90, 11, 62, "5:47", "https://open.spotify.com/search/Pachai%20Kiligal%20A.R.%20Rahman"));
                tracks.add(new Track("Malargal Kaettaen", "A.R. Rahman", "O Kadhal Kanmani", 78, 14, 76, "5:54", "https://open.spotify.com/search/Malargal%20Kaettaen%20A.R.%20Rahman"));
                tracks.add(new Track("Melliname", "Harris Jayaraj", "Shahjahan", 86, 17, 73, "5:25", "https://open.spotify.com/search/Melliname%20Harris%20Jayaraj"));
                tracks.add(new Track("Mazhai Kuruvi", "A.R. Rahman", "Chekka Chivantha Vaanam", 82, 15, 68, "5:48", "https://open.spotify.com/search/Mazhai%20Kuruvi%20A.R.%20Rahman"));
                tracks.add(new Track("Omana Penne", "A.R. Rahman", "Vinnaithaandi Varuvaayaa", 91, 16, 70, "5:32", "https://open.spotify.com/search/Omana%20Penne%20A.R.%20Rahman"));
                tracks.add(new Track("Hosanna", "A.R. Rahman", "Vinnaithaandi Varuvaayaa", 88, 22, 74, "5:30", "https://open.spotify.com/search/Hosanna%20A.R.%20Rahman"));
                tracks.add(new Track("Anbil Avan", "Harris Jayaraj", "Vinnaithaandi Varuvaayaa", 93, 13, 69, "4:59", "https://open.spotify.com/search/Anbil%20Avan%20Harris%20Jayaraj"));
                tracks.add(new Track("Azhagiye", "A.R. Rahman", "Kaatru Veliyidai", 87, 19, 71, "5:56", "https://open.spotify.com/search/Azhagiye%20A.R.%20Rahman"));
                tracks.add(new Track("New York Nagaram", "A.R. Rahman", "Sillunu Oru Kadhal", 85, 20, 76, "6:17", "https://open.spotify.com/search/New%20York%20Nagaram%20A.R.%20Rahman"));
                tracks.add(new Track("Ennodu Nee Irundhaal", "A.R. Rahman", "I", 89, 17, 73, "5:51", "https://open.spotify.com/search/Ennodu%20Nee%20Irundhaal%20A.R.%20Rahman"));
                tracks.add(new Track("Kadhaippoma", "Leon James", "Oh My Kadavule", 91, 15, 67, "4:42", "https://open.spotify.com/search/Kadhaippoma%20Leon%20James"));
                tracks.add(new Track("Thalli Pogathey", "A.R. Rahman", "Achcham Yenbadhu Madamaiyada", 86, 21, 78, "4:58", "https://open.spotify.com/search/Thalli%20Pogathey%20A.R.%20Rahman"));
                tracks.add(new Track("Yaar Azhaippadhu", "Govind Vasantha", "Maara", 95, 10, 64, "4:56", "https://open.spotify.com/search/Yaar%20Azhaippadhu%20Govind%20Vasantha"));
                tracks.add(new Track("Kaathalae Kaathalae", "Govind Vasantha", "96", 94, 12, 66, "5:12", "https://open.spotify.com/search/Kaathalae%20Kaathalae%20Govind%20Vasantha"));
                tracks.add(new Track("Nallai Allai", "A.R. Rahman", "Kaatru Veliyidai", 88, 16, 70, "3:59", "https://open.spotify.com/search/Nallai%20Allai%20A.R.%20Rahman"));
                tracks.add(new Track("Vaan Varuvaan", "Dhibu Ninan Thomas", "Kaatru Veliyidai", 56, 26, 33, "4:38", "https://open.spotify.com/search/Vaan%20Varuvaan%20Dhibu%20Ninan%20Thomas"));
                tracks.add(new Track("Kaatre En Vaasal", "A.R. Rahman", "Rhythm", 76, 31, 54, "5:58", "https://open.spotify.com/search/Kaatre%20En%20Vaasal%20A.R.%20Rahman"));
                break;
            case "Energetic":
                tracks.add(new Track("Arabic Kuthu", "Anirudh Ravichander", "Beast", 94, 91, 88, "4:41", "https://open.spotify.com/search/Arabic%20Kuthu%20Anirudh%20Ravichander"));
                tracks.add(new Track("Vaathi Coming", "Anirudh Ravichander", "Master", 96, 93, 91, "3:49", "https://open.spotify.com/search/Vaathi%20Coming%20Anirudh%20Ravichander"));
                tracks.add(new Track("Aaluma Doluma", "Anirudh Ravichander", "Vedalam", 95, 94, 87, "4:19", "https://open.spotify.com/search/Aaluma%20Doluma%20Anirudh%20Ravichander"));
                tracks.add(new Track("Rowdy Baby", "Yuvan Shankar Raja", "Maari 2", 97, 92, 94, "4:20", "https://open.spotify.com/search/Rowdy%20Baby%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("Hukum", "Anirudh Ravichander", "Jailer", 98, 97, 93, "3:27", "https://open.spotify.com/search/Hukum%20Anirudh%20Ravichander"));
                tracks.add(new Track("Danga Maari Oodhari", "Harris Jayaraj", "Anegan", 92, 89, 86, "5:42", "https://open.spotify.com/search/Danga%20Maari%20Oodhari%20Harris%20Jayaraj"));
                tracks.add(new Track("Don'u Don'u Don'u", "Anirudh Ravichander", "Maari", 93, 90, 89, "3:17", "https://open.spotify.com/search/Donu%20Donu%20Donu%20Anirudh%20Ravichander"));
                tracks.add(new Track("Why This Kolaveri Di", "Anirudh Ravichander", "3", 91, 87, 90, "4:03", "https://open.spotify.com/search/Why%20This%20Kolaveri%20Di%20Anirudh%20Ravichander"));
                tracks.add(new Track("Selfie Pulla", "Anirudh Ravichander", "Kaththi", 94, 91, 92, "4:51", "https://open.spotify.com/search/Selfie%20Pulla%20Anirudh%20Ravichander"));
                tracks.add(new Track("Sodakku", "Anthony Daasan", "Thaanaa Serndha Koottam", 95, 93, 88, "3:58", "https://open.spotify.com/search/Sodakku%20Anthony%20Daasan"));
                tracks.add(new Track("Jalabulajangu", "Anirudh Ravichander", "Don", 92, 94, 90, "4:16", "https://open.spotify.com/search/Jalabulajangu%20Anirudh%20Ravichander"));
                tracks.add(new Track("Chill Bro", "Anirudh Ravichander", "Pattas", 89, 88, 93, "3:53", "https://open.spotify.com/search/Chill%20Bro%20Anirudh%20Ravichander"));
                tracks.add(new Track("Rakita Rakita Rakita", "Dhanush", "Jagame Thandhiram", 96, 95, 89, "4:06", "https://open.spotify.com/search/Rakita%20Rakita%20Rakita%20Dhanush"));
                tracks.add(new Track("Kutti Story", "Anirudh Ravichander", "Master", 88, 86, 94, "5:01", "https://open.spotify.com/search/Kutti%20Story%20Anirudh%20Ravichander"));
                tracks.add(new Track("Petta Paraak", "Anirudh Ravichander", "Petta", 97, 96, 92, "3:56", "https://open.spotify.com/search/Petta%20Paraak%20Anirudh%20Ravichander"));
                tracks.add(new Track("Vaathi Raid", "Anirudh Ravichander", "Master", 97, 95, 90, "3:48", "https://open.spotify.com/search/Vaathi%20Raid%20Anirudh%20Ravichander"));
                tracks.add(new Track("Jolly O Gymkhana", "Anirudh Ravichander", "Beast", 95, 94, 96, "3:33", "https://open.spotify.com/search/Jolly%20O%20Gymkhana%20Anirudh%20Ravichander"));
                tracks.add(new Track("Badass", "Anirudh Ravichander", "Leo", 99, 98, 91, "3:49", "https://open.spotify.com/search/Badass%20Anirudh%20Ravichander"));
                tracks.add(new Track("Dharala Prabhu Title Track", "Anirudh Ravichander", "Dharala Prabhu", 93, 90, 91, "3:48", "https://open.spotify.com/search/Dharala%20Prabhu%20Title%20Track%20Anirudh%20Ravichander"));
                tracks.add(new Track("Porkanda Singam", "Anirudh Ravichander", "Vikram", 94, 92, 87, "3:47", "https://open.spotify.com/search/Porkanda%20Singam%20Anirudh%20Ravichander"));
                break;
            case "Focused":
                tracks.add(new Track("Vaan Varuvaan", "Dhibu Ninan Thomas", "Kaatru Veliyidai", 56, 26, 33, "4:38", "https://open.spotify.com/search/Vaan%20Varuvaan%20Dhibu%20Ninan%20Thomas"));
                tracks.add(new Track("Maruvaarthai", "Darbuka Siva", "Enai Noki Paayum Thota", 59, 29, 35, "5:56", "https://open.spotify.com/search/Maruvaarthai%20Darbuka%20Siva"));
                tracks.add(new Track("Innum Konjam Naeram", "A.R. Rahman", "Maryan", 78, 32, 54, "5:14", "https://open.spotify.com/search/Innum%20Konjam%20Naeram%20A.R.%20Rahman"));
                tracks.add(new Track("Theera Ulaa", "A.R. Rahman", "O Kadhal Kanmani", 84, 35, 58, "4:46", "https://open.spotify.com/search/Theera%20Ulaa%20A.R.%20Rahman"));
                tracks.add(new Track("Kadhaippoma", "Leon James", "Oh My Kadavule", 91, 36, 67, "4:42", "https://open.spotify.com/search/Kadhaippoma%20Leon%20James"));
                tracks.add(new Track("Kaatre En Vaasal", "A.R. Rahman", "Rhythm", 76, 31, 54, "5:58", "https://open.spotify.com/search/Kaatre%20En%20Vaasal%20A.R.%20Rahman"));
                tracks.add(new Track("Pookkal Pookkum", "G.V. Prakash Kumar", "Madrasapattinam", 49, 21, 27, "6:36", "https://open.spotify.com/search/Pookkal%20Pookkum%20G.V.%20Prakash%20Kumar"));
                tracks.add(new Track("Mannipaaya", "A.R. Rahman", "Vinnaithaandi Varuvaayaa", 70, 30, 45, "6:56", "https://open.spotify.com/search/Mannipaaya%20A.R.%20Rahman"));
                tracks.add(new Track("Nenjame", "Harris Jayaraj", "Doctor", 82, 28, 48, "4:45", "https://open.spotify.com/search/Nenjame%20Harris%20Jayaraj"));
                tracks.add(new Track("Aaruyire", "A.R. Rahman", "Guru", 80, 29, 52, "6:06", "https://open.spotify.com/search/Aaruyire%20A.R.%20Rahman"));
                tracks.add(new Track("Vizhigalil Oru Vaanavil", "Yuvan Shankar Raja", "Deiva Thirumagal", 50, 23, 31, "5:24", "https://open.spotify.com/search/Vizhigalil%20Oru%20Vaanavil%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("En Kadhal Solla", "Yuvan Shankar Raja", "Paiyaa", 58, 31, 39, "4:56", "https://open.spotify.com/search/En%20Kadhal%20Solla%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("Naan Nee", "Shakthisree Gopalan", "Madras", 57, 30, 38, "4:54", "https://open.spotify.com/search/Naan%20Nee%20Shakthisree%20Gopalan"));
                tracks.add(new Track("Yennai Maatrum Kadhale", "A.R. Rahman", "Naanum Rowdy Dhaan", 58, 27, 32, "4:34", "https://open.spotify.com/search/Yennai%20Maatrum%20Kadhale%20A.R.%20Rahman"));
                tracks.add(new Track("Unakkenna Venum Sollu", "Harris Jayaraj", "Yennai Arindhaal", 53, 22, 30, "5:08", "https://open.spotify.com/search/Unakkenna%20Venum%20Sollu%20Harris%20Jayaraj"));
                tracks.add(new Track("Kangal Irandal", "James Vasanthan", "Subramaniapuram", 64, 34, 46, "5:24", "https://open.spotify.com/search/Kangal%20Irandal%20James%20Vasanthan"));
                tracks.add(new Track("Oru Kal Oru Kannadi", "Harris Jayaraj", "Siva Manasula Sakthi", 61, 34, 42, "5:58", "https://open.spotify.com/search/Oru%20Kal%20Oru%20Kannadi%20Harris%20Jayaraj"));
                tracks.add(new Track("Idhazhin Oram", "Anirudh Ravichander", "3", 67, 41, 48, "3:57", "https://open.spotify.com/search/Idhazhin%20Oram%20Anirudh%20Ravichander"));
                tracks.add(new Track("Thalli Pogathey", "A.R. Rahman", "Achcham Yenbadhu Madamaiyada", 63, 36, 41, "4:58", "https://open.spotify.com/search/Thalli%20Pogathey%20A.R.%20Rahman"));
                tracks.add(new Track("Oru Naalil", "Yuvan Shankar Raja", "Pudhupettai", 58, 25, 29, "4:39", "https://open.spotify.com/search/Oru%20Naalil%20Yuvan%20Shankar%20Raja"));
                break;
            case "Melancholy":
                tracks.add(new Track("Po Nee Po", "Anirudh Ravichander", "3", 55, 28, 31, "4:15", "https://open.spotify.com/search/Po%20Nee%20Po%20Anirudh%20Ravichander"));
                tracks.add(new Track("Kanave Unai", "Yuvan Shankar Raja", "Azhagai Irukkirai Bayamai Irukkirathu", 52, 25, 34, "5:04", "https://open.spotify.com/search/Kanave%20Unai%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("Naan Nee", "Shakthisree Gopalan", "Madras", 57, 30, 38, "4:54", "https://open.spotify.com/search/Naan%20Nee%20Shakthisree%20Gopalan"));
                tracks.add(new Track("Usure Pogudhey", "A.R. Rahman", "Raavanan", 47, 20, 26, "6:06", "https://open.spotify.com/search/Usure%20Pogudhey%20A.R.%20Rahman"));
                tracks.add(new Track("Pogadhe", "Yuvan Shankar Raja", "Deepavali", 51, 24, 29, "4:46", "https://open.spotify.com/search/Pogadhe%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("En Kadhal Solla", "Yuvan Shankar Raja", "Paiyaa", 58, 31, 39, "4:56", "https://open.spotify.com/search/En%20Kadhal%20Solla%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("Oru Kal Oru Kannadi", "Harris Jayaraj", "Siva Manasula Sakthi", 61, 34, 42, "5:58", "https://open.spotify.com/search/Oru%20Kal%20Oru%20Kannadi%20Harris%20Jayaraj"));
                tracks.add(new Track("Yaar Indha Saalai Oram", "G.V. Prakash Kumar", "Thalaivaa", 54, 27, 36, "5:21", "https://open.spotify.com/search/Yaar%20Indha%20Saalai%20Oram%20G.V.%20Prakash%20Kumar"));
                tracks.add(new Track("Azhagiye", "A.R. Rahman", "Kaatru Veliyidai", 64, 38, 45, "5:56", "https://open.spotify.com/search/Azhagiye%20A.R.%20Rahman"));
                tracks.add(new Track("Maruvaarthai", "Darbuka Siva", "Enai Noki Paayum Thota", 59, 29, 35, "5:56", "https://open.spotify.com/search/Maruvaarthai%20Darbuka%20Siva"));
                tracks.add(new Track("Thalli Pogathey", "A.R. Rahman", "Achcham Yenbadhu Madamaiyada", 63, 36, 41, "4:58", "https://open.spotify.com/search/Thalli%20Pogathey%20A.R.%20Rahman"));
                tracks.add(new Track("Idhazhin Oram", "Anirudh Ravichander", "3", 67, 41, 48, "3:57", "https://open.spotify.com/search/Idhazhin%20Oram%20Anirudh%20Ravichander"));
                tracks.add(new Track("Unakkenna Venum Sollu", "Harris Jayaraj", "Yennai Arindhaal", 53, 22, 30, "5:08", "https://open.spotify.com/search/Unakkenna%20Venum%20Sollu%20Harris%20Jayaraj"));
                tracks.add(new Track("Pookkal Pookkum", "G.V. Prakash Kumar", "Madrasapattinam", 49, 21, 27, "6:36", "https://open.spotify.com/search/Pookkal%20Pookkum%20G.V.%20Prakash%20Kumar"));
                tracks.add(new Track("Oru Deivam Thantha Poove", "A.R. Rahman", "Kannathil Muthamittal", 45, 18, 24, "6:53", "https://open.spotify.com/search/Oru%20Deivam%20Thantha%20Poove%20A.R.%20Rahman"));
                tracks.add(new Track("Ennodu Nee Irundhaal", "A.R. Rahman", "I", 62, 33, 37, "5:51", "https://open.spotify.com/search/Ennodu%20Nee%20Irundhaal%20A.R.%20Rahman"));
                tracks.add(new Track("Yennai Maatrum Kadhale", "A.R. Rahman", "Naanum Rowdy Dhaan", 58, 27, 32, "4:34", "https://open.spotify.com/search/Yennai%20Maatrum%20Kadhale%20A.R.%20Rahman"));
                tracks.add(new Track("Vaan Varuvaan", "Dhibu Ninan Thomas", "Kaatru Veliyidai", 56, 26, 33, "4:38", "https://open.spotify.com/search/Vaan%20Varuvaan%20Dhibu%20Ninan%20Thomas"));
                tracks.add(new Track("Usure Pogudhey", "A.R. Rahman", "Raavanan", 47, 20, 26, "6:06", "https://open.spotify.com/search/Usure%20Pogudhey%20A.R.%20Rahman"));
                tracks.add(new Track("Vizhigalil Oru Vaanavil", "Yuvan Shankar Raja", "Deiva Thirumagal", 50, 23, 31, "5:24", "https://open.spotify.com/search/Vizhigalil%20Oru%20Vaanavil%20Yuvan%20Shankar%20Raja"));
                break;
            case "Stressed":
                tracks.add(new Track("Oru Deivam Thantha Poove", "A.R. Rahman", "Kannathil Muthamittal", 45, 18, 24, "6:53", "https://open.spotify.com/search/Oru%20Deivam%20Thantha%20Poove%20A.R.%20Rahman"));
                tracks.add(new Track("Aararo", "D. Imman", "Siruthai", 44, 17, 22, "5:05", "https://open.spotify.com/search/Aararo%20D.%20Imman"));
                tracks.add(new Track("Yaar Azhaippadhu", "Govind Vasantha", "Maara", 95, 10, 64, "4:56", "https://open.spotify.com/search/Yaar%20Azhaippadhu%20Govind%20Vasantha"));
                tracks.add(new Track("Vaan Varuvaan", "Dhibu Ninan Thomas", "Kaatru Veliyidai", 56, 26, 33, "4:38", "https://open.spotify.com/search/Vaan%20Varuvaan%20Dhibu%20Ninan%20Thomas"));
                tracks.add(new Track("Mazhai Kuruvi", "A.R. Rahman", "Chekka Chivantha Vaanam", 82, 15, 68, "5:48", "https://open.spotify.com/search/Mazhai%20Kuruvi%20A.R.%20Rahman"));
                tracks.add(new Track("Pachai Kiligal", "A.R. Rahman", "Indian", 90, 11, 62, "5:47", "https://open.spotify.com/search/Pachai%20Kiligal%20A.R.%20Rahman"));
                tracks.add(new Track("Munbe Vaa", "A.R. Rahman", "Sillunu Oru Kadhal", 92, 14, 68, "5:58", "https://open.spotify.com/search/Munbe%20Vaa%20A.R.%20Rahman"));
                tracks.add(new Track("Melliname", "Harris Jayaraj", "Shahjahan", 86, 17, 73, "5:25", "https://open.spotify.com/search/Melliname%20Harris%20Jayaraj"));
                tracks.add(new Track("Vaseegara", "Harris Jayaraj", "Minnale", 94, 12, 65, "5:02", "https://open.spotify.com/search/Vaseegara%20Harris%20Jayaraj"));
                tracks.add(new Track("Kaatre En Vaasal", "A.R. Rahman", "Rhythm", 76, 31, 54, "5:58", "https://open.spotify.com/search/Kaatre%20En%20Vaasal%20A.R.%20Rahman"));
                tracks.add(new Track("Mannipaaya", "A.R. Rahman", "Vinnaithaandi Varuvaayaa", 70, 30, 45, "6:56", "https://open.spotify.com/search/Mannipaaya%20A.R.%20Rahman"));
                tracks.add(new Track("Pookkal Pookkum", "G.V. Prakash Kumar", "Madrasapattinam", 49, 21, 27, "6:36", "https://open.spotify.com/search/Pookkal%20Pookkum%20G.V.%20Prakash%20Kumar"));
                tracks.add(new Track("Nenjame", "Harris Jayaraj", "Doctor", 82, 28, 48, "4:45", "https://open.spotify.com/search/Nenjame%20Harris%20Jayaraj"));
                tracks.add(new Track("Aaruyire", "A.R. Rahman", "Guru", 80, 29, 52, "6:06", "https://open.spotify.com/search/Aaruyire%20A.R.%20Rahman"));
                tracks.add(new Track("Vizhigalil Oru Vaanavil", "Yuvan Shankar Raja", "Deiva Thirumagal", 50, 23, 31, "5:24", "https://open.spotify.com/search/Vizhigalil%20Oru%20Vaanavil%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("Yaar Indha Saalai Oram", "G.V. Prakash Kumar", "Thalaivaa", 54, 27, 36, "5:21", "https://open.spotify.com/search/Yaar%20Indha%20Saalai%20Oram%20G.V.%20Prakash%20Kumar"));
                tracks.add(new Track("Naan Nee", "Shakthisree Gopalan", "Madras", 57, 30, 38, "4:54", "https://open.spotify.com/search/Naan%20Nee%20Shakthisree%20Gopalan"));
                tracks.add(new Track("Idhazhin Oram", "Anirudh Ravichander", "3", 67, 41, 48, "3:57", "https://open.spotify.com/search/Idhazhin%20Oram%20Anirudh%20Ravichander"));
                tracks.add(new Track("Melliname", "Harris Jayaraj", "Shahjahan", 86, 17, 73, "5:25", "https://open.spotify.com/search/Melliname%20Harris%20Jayaraj"));
                tracks.add(new Track("Nenjukkul Peidhidum", "Harris Jayaraj", "Vaaranam Aayiram", 89, 18, 72, "6:09", "https://open.spotify.com/search/Nenjukkul%20Peidhidum%20Harris%20Jayaraj"));
                break;
            case "Joyful":
                tracks.add(new Track("Jimikki Ponnu", "Anirudh Ravichander", "Varisu", 108, 88, 95, "3:44", "https://open.spotify.com/search/Jimikki%20Ponnu%20Anirudh%20Ravichander"));
                tracks.add(new Track("Ranjithame", "Thaman S", "Varisu", 110, 89, 96, "4:17", "https://open.spotify.com/search/Ranjithame%20Thaman%20S"));
                tracks.add(new Track("Megham Karukatha", "Dhanush", "Thiruchitrambalam", 112, 82, 94, "4:50", "https://open.spotify.com/search/Megham%20Karukatha%20Dhanush"));
                tracks.add(new Track("Private Party", "Anirudh Ravichander", "Don", 116, 90, 93, "3:34", "https://open.spotify.com/search/Private%20Party%20Anirudh%20Ravichander"));
                tracks.add(new Track("Jolly O Gymkhana", "Anirudh Ravichander", "Beast", 95, 94, 96, "3:33", "https://open.spotify.com/search/Jolly%20O%20Gymkhana%20Anirudh%20Ravichander"));
                tracks.add(new Track("Arabic Kuthu", "Anirudh Ravichander", "Beast", 94, 91, 88, "4:41", "https://open.spotify.com/search/Arabic%20Kuthu%20Anirudh%20Ravichander"));
                tracks.add(new Track("Vaathi Coming", "Anirudh Ravichander", "Master", 96, 93, 91, "3:49", "https://open.spotify.com/search/Vaathi%20Coming%20Anirudh%20Ravichander"));
                tracks.add(new Track("Jimikki Ponnu", "Anirudh Ravichander", "Varisu", 108, 88, 95, "3:44", "https://open.spotify.com/search/Jimikki%20Ponnu%20Anirudh%20Ravichander"));
                tracks.add(new Track("Megham Karukatha", "Dhanush", "Thiruchitrambalam", 112, 82, 94, "4:50", "https://open.spotify.com/search/Megham%20Karukatha%20Dhanush"));
                tracks.add(new Track("Private Party", "Anirudh Ravichander", "Don", 116, 90, 93, "3:34", "https://open.spotify.com/search/Private%20Party%20Anirudh%20Ravichander"));
                tracks.add(new Track("Danga Maari Oodhari", "Harris Jayaraj", "Anegan", 92, 89, 86, "5:42", "https://open.spotify.com/search/Danga%20Maari%20Oodhari%20Harris%20Jayaraj"));
                tracks.add(new Track("Rowdy Baby", "Yuvan Shankar Raja", "Maari 2", 97, 92, 94, "4:20", "https://open.spotify.com/search/Rowdy%20Baby%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("Sodakku", "Anthony Daasan", "Thaanaa Serndha Koottam", 95, 93, 88, "3:58", "https://open.spotify.com/search/Sodakku%20Anthony%20Daasan"));
                tracks.add(new Track("Jalabulajangu", "Anirudh Ravichander", "Don", 92, 94, 90, "4:16", "https://open.spotify.com/search/Jalabulajangu%20Anirudh%20Ravichander"));
                tracks.add(new Track("Chill Bro", "Anirudh Ravichander", "Pattas", 89, 88, 93, "3:53", "https://open.spotify.com/search/Chill%20Bro%20Anirudh%20Ravichander"));
                tracks.add(new Track("Rakita Rakita Rakita", "Dhanush", "Jagame Thandhiram", 96, 95, 89, "4:06", "https://open.spotify.com/search/Rakita%20Rakita%20Rakita%20Dhanush"));
                tracks.add(new Track("Kutti Story", "Anirudh Ravichander", "Master", 88, 86, 94, "5:01", "https://open.spotify.com/search/Kutti%20Story%20Anirudh%20Ravichander"));
                tracks.add(new Track("Vaathi Raid", "Anirudh Ravichander", "Master", 97, 95, 90, "3:48", "https://open.spotify.com/search/Vaathi%20Raid%20Anirudh%20Ravichander"));
                tracks.add(new Track("Badass", "Anirudh Ravichander", "Leo", 99, 98, 91, "3:49", "https://open.spotify.com/search/Badass%20Anirudh%20Ravichander"));
                break;
            default:
                tracks.add(new Track("Munbe Vaa", "A.R. Rahman", "Sillunu Oru Kadhal", 92, 14, 68, "5:58", "https://open.spotify.com/search/Munbe%20Vaa%20A.R.%20Rahman"));
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
            case "calm": seed = "Tamil calm melody A.R. Rahman Harris Jayaraj"; break;
            case "energetic": seed = "Tamil kuthu energetic Anirudh Ravichander"; break;
            case "focused": seed = "Tamil soft melody focus A.R. Rahman"; break;
            case "melancholy": seed = "Tamil sad melody Yuvan Shankar Raja"; break;
            case "stressed": seed = "Tamil relaxing melody A.R. Rahman"; break;
            case "joyful": seed = "Tamil happy dance Anirudh Ravichander"; break;
            default: seed = "Tamil songs"; break;
        }

        return SEARCH_URL + "?q=" + URLEncoder.encode(seed, StandardCharsets.UTF_8)
            + "&type=track&market=IN&limit=20";
    }

    public Playlist createMoodPlaylist(UserMood mood) {
        return MoodMapper.mapMoodToPlaylist(mood);
    }

    public String search(String query, String type) throws IOException, InterruptedException, SpotifyApiException {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new SpotifyApiException(503, "Spotify credentials are not configured on the server.");
        }

        String searchUri = SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&type=" + type + "&market=IN&limit=10";
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
            if (tracks.size() == 20) {
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