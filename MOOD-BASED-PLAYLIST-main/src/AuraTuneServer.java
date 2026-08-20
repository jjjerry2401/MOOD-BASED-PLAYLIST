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
        java.nio.file.Path classpathRoot;
        try {
            classpathRoot = java.nio.file.Paths.get(
                    AuraTuneServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Unable to locate the application files", e);
        }

        java.nio.file.Path projectRoot = classpathRoot.getFileName().toString().equals("build")
                ? classpathRoot.getParent()
                : java.nio.file.Paths.get("").toAbsolutePath();
        java.nio.file.Path file = projectRoot.resolve("web").resolve(fileName);
        if (!java.nio.file.Files.exists(file)) {
            throw new IOException("Missing static file: " + fileName);
        }
        return java.nio.file.Files.readAllBytes(file);
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
                tracks.add(new Track("Munbe Vaa", "A.R. Rahman", "Sillunu Oru Kadhal", 92, 14, 68, "5:58", "https://open.spotify.com/search/Munbe%20Vaa%20AR%20Rahman"));
                tracks.add(new Track("Vaseegara", "Harris Jayaraj", "Minnale", 94, 12, 65, "5:02", "https://open.spotify.com/search/Vaseegara%20Harris%20Jayaraj"));
                tracks.add(new Track("Nenjukkul Peidhidum", "Harris Jayaraj", "Vaaranam Aayiram", 89, 18, 72, "6:09", "https://open.spotify.com/search/Nenjukkul%20Peidhidum%20Harris%20Jayaraj"));
                tracks.add(new Track("Omana Penne", "A.R. Rahman", "Vinnaithaandi Varuvaayaa", 91, 16, 70, "5:32", "https://open.spotify.com/search/Omana%20Penne%20AR%20Rahman"));
                tracks.add(new Track("Hosanna", "A.R. Rahman", "Vinnaithaandi Varuvaayaa", 88, 22, 74, "5:30", "https://open.spotify.com/search/Hosanna%20AR%20Rahman"));
                tracks.add(new Track("Pachai Kiligal", "A.R. Rahman", "Indian", 90, 11, 62, "5:47", "https://open.spotify.com/search/Pachai%20Kiligal%20AR%20Rahman"));
                tracks.add(new Track("Anbil Avan", "Harris Jayaraj", "Vinnaithaandi Varuvaayaa", 93, 13, 69, "4:59", "https://open.spotify.com/search/Anbil%20Avan%20Harris%20Jayaraj"));
                tracks.add(new Track("Azhagiye", "A.R. Rahman", "Kaatru Veliyidai", 87, 19, 71, "5:56", "https://open.spotify.com/search/Azhagiye%20AR%20Rahman"));
                tracks.add(new Track("New York Nagaram", "A.R. Rahman", "Sillunu Oru Kadhal", 85, 20, 76, "6:17", "https://open.spotify.com/search/New%20York%20Nagaram%20AR%20Rahman"));
                tracks.add(new Track("Ennodu Nee Irundhaal", "A.R. Rahman", "I", 89, 17, 73, "5:51", "https://open.spotify.com/search/Ennodu%20Nee%20Irundhaal%20AR%20Rahman"));
                tracks.add(new Track("Kadhaippoma", "Leon James", "Oh My Kadavule", 91, 15, 67, "4:42", "https://open.spotify.com/search/Kadhaippoma%20Leon%20James"));
                tracks.add(new Track("Thalli Pogathey", "A.R. Rahman", "Achcham Yenbadhu Madamaiyada", 86, 21, 78, "4:58", "https://open.spotify.com/search/Thalli%20Pogathey%20AR%20Rahman"));
                tracks.add(new Track("Yaar Azhaippadhu", "Govind Vasantha", "Maara", 95, 10, 64, "4:56", "https://open.spotify.com/search/Yaar%20Azhaippadhu%20Govind%20Vasantha"));
                tracks.add(new Track("Kaathalae Kaathalae", "Govind Vasantha", "96", 94, 12, 66, "5:12", "https://open.spotify.com/search/Kaathalae%20Kaathalae%20Govind%20Vasantha"));
                tracks.add(new Track("Nallai Allai", "A.R. Rahman", "Kaatru Veliyidai", 88, 16, 70, "3:59", "https://open.spotify.com/search/Nallai%20Allai%20AR%20Rahman"));
                break;
            case "Energetic":
                tracks.add(new Track("Smooth Criminal", "Michael Jackson", "Bad", 124, 93, 88, "4:17", "https://open.spotify.com/track/2bCQHF9gdG5BNDVuEIEnNk"));
                tracks.add(new Track("Arabic Kuthu", "Anirudh Ravichander", "Beast", 94, 91, 88, "4:41", "https://open.spotify.com/search/Arabic%20Kuthu%20Anirudh%20Ravichander"));
                tracks.add(new Track("Vaathi Coming", "Anirudh Ravichander", "Master", 96, 93, 91, "3:49", "https://open.spotify.com/search/Vaathi%20Coming%20Anirudh%20Ravichander"));
                tracks.add(new Track("Aaluma Doluma", "Anirudh Ravichander", "Vedalam", 95, 94, 87, "4:19", "https://open.spotify.com/search/Aaluma%20Doluma%20Anirudh%20Ravichander"));
                tracks.add(new Track("Danga Maari Oodhari", "Harris Jayaraj", "Anegan", 92, 89, 86, "5:42", "https://open.spotify.com/search/Danga%20Maari%20Oodhari%20Harris%20Jayaraj"));
                tracks.add(new Track("Rowdy Baby", "Yuvan Shankar Raja", "Maari 2", 97, 92, 94, "4:20", "https://open.spotify.com/search/Rowdy%20Baby%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("Don'u Don'u Don'u", "Anirudh Ravichander", "Maari", 93, 90, 89, "3:17", "https://open.spotify.com/search/Donu%20Donu%20Donu%20Anirudh%20Ravichander"));
                tracks.add(new Track("Why This Kolaveri Di", "Anirudh Ravichander", "3", 91, 87, 90, "4:03", "https://open.spotify.com/search/Why%20This%20Kolaveri%20Di%20Anirudh%20Ravichander"));
                tracks.add(new Track("Selfie Pulla", "Anirudh Ravichander", "Kaththi", 94, 91, 92, "4:51", "https://open.spotify.com/search/Selfie%20Pulla%20Anirudh%20Ravichander"));
                tracks.add(new Track("Sodakku", "Anthony Daasan", "Thaanaa Serndha Koottam", 95, 93, 88, "3:58", "https://open.spotify.com/search/Sodakku%20Anthony%20Daasan"));
                tracks.add(new Track("Jalabulajangu", "Anirudh Ravichander", "Don", 92, 94, 90, "4:16", "https://open.spotify.com/search/Jalabulajangu%20Anirudh%20Ravichander"));
                tracks.add(new Track("Chill Bro", "Anirudh Ravichander", "Pattas", 89, 88, 93, "3:53", "https://open.spotify.com/search/Chill%20Bro%20Anirudh%20Ravichander"));
                tracks.add(new Track("Dharala Prabhu Title Track", "Anirudh Ravichander", "Dharala Prabhu", 93, 90, 91, "3:48", "https://open.spotify.com/search/Dharala%20Prabhu%20Title%20Track%20Anirudh"));
                tracks.add(new Track("Rakita Rakita Rakita", "Dhanush", "Jagame Thandhiram", 96, 95, 89, "4:06", "https://open.spotify.com/search/Rakita%20Rakita%20Rakita%20Dhanush"));
                tracks.add(new Track("Kutti Story", "Anirudh Ravichander", "Master", 88, 86, 94, "5:01", "https://open.spotify.com/search/Kutti%20Story%20Anirudh%20Ravichander"));
                tracks.add(new Track("Petta Paraak", "Anirudh Ravichander", "Petta", 97, 96, 92, "3:56", "https://open.spotify.com/search/Petta%20Paraak%20Anirudh%20Ravichander"));
                tracks.add(new Track("Hukum", "Anirudh Ravichander", "Jailer", 98, 97, 93, "3:27", "https://open.spotify.com/search/Hukum%20Anirudh%20Ravichander"));
                tracks.add(new Track("Vaathi Raid", "Anirudh Ravichander", "Master", 97, 95, 90, "3:48", "https://open.spotify.com/search/Vaathi%20Raid%20Anirudh%20Ravichander"));
                tracks.add(new Track("Jolly O Gymkhana", "Anirudh Ravichander", "Beast", 95, 94, 96, "3:33", "https://open.spotify.com/search/Jolly%20O%20Gymkhana%20Anirudh%20Ravichander"));
                tracks.add(new Track("Porkanda Singam", "Anirudh Ravichander", "Vikram", 94, 92, 87, "3:47", "https://open.spotify.com/search/Porkanda%20Singam%20Anirudh%20Ravichander"));
                tracks.add(new Track("Badass", "Anirudh Ravichander", "Leo", 99, 98, 91, "3:49", "https://open.spotify.com/search/Badass%20Anirudh%20Ravichander"));
                break;
            case "Focused":
                tracks.add(new Track("Aalaporan Thamizhan", "A.R. Rahman", "Mersal", 97, 95, 94, "5:48", "https://open.spotify.com/search/Aalaporan%20Thamizhan%20AR%20Rahman"));
                tracks.add(new Track("Surviva", "Anirudh Ravichander", "Vivegam", 96, 97, 88, "3:42", "https://open.spotify.com/search/Surviva%20Anirudh%20Ravichander"));
                tracks.add(new Track("Verithanam", "A.R. Rahman", "Bigil", 98, 96, 95, "4:06", "https://open.spotify.com/search/Verithanam%20AR%20Rahman"));
                tracks.add(new Track("Mersal Arasan", "A.R. Rahman", "Mersal", 97, 94, 93, "4:16", "https://open.spotify.com/search/Mersal%20Arasan%20AR%20Rahman"));
                tracks.add(new Track("Petta Theme", "Anirudh Ravichander", "Petta", 99, 98, 91, "1:33", "https://open.spotify.com/search/Petta%20Theme%20Anirudh%20Ravichander"));
                tracks.add(new Track("Vikram Title Track", "Anirudh Ravichander", "Vikram", 98, 99, 89, "3:38", "https://open.spotify.com/search/Vikram%20Title%20Track%20Anirudh%20Ravichander"));
                tracks.add(new Track("Rolex Theme", "Anirudh Ravichander", "Vikram", 100, 99, 90, "2:27", "https://open.spotify.com/search/Rolex%20Theme%20Anirudh%20Ravichander"));
                tracks.add(new Track("Leo Das Entry", "Anirudh Ravichander", "Leo", 98, 97, 87, "2:18", "https://open.spotify.com/search/Leo%20Das%20Entry%20Anirudh%20Ravichander"));
                tracks.add(new Track("Neruppu Kumar", "Anirudh Ravichander", "Petta", 97, 96, 92, "2:45", "https://open.spotify.com/search/Neruppu%20Kumar%20Anirudh%20Ravichander"));
                tracks.add(new Track("Surviva", "Anirudh Ravichander", "Vivegam", 96, 97, 88, "3:42", "https://open.spotify.com/search/Surviva%20Anirudh%20Ravichander"));
                tracks.add(new Track("Aathi", "Anirudh Ravichander", "Kaththi", 94, 93, 91, "4:24", "https://open.spotify.com/search/Aathi%20Anirudh%20Ravichander"));
                tracks.add(new Track("Bad Eyes", "Anirudh Ravichander", "Vikram", 95, 96, 86, "2:14", "https://open.spotify.com/search/Bad%20Eyes%20Anirudh%20Ravichander"));
                tracks.add(new Track("Bloody Sweet", "Anirudh Ravichander", "Leo", 99, 98, 89, "3:35", "https://open.spotify.com/search/Bloody%20Sweet%20Anirudh%20Ravichander"));
                tracks.add(new Track("Naa Ready", "Anirudh Ravichander", "Leo", 98, 97, 95, "4:08", "https://open.spotify.com/search/Naa%20Ready%20Anirudh%20Ravichander"));
                tracks.add(new Track("Once Upon a Time", "Anirudh Ravichander", "Vikram", 96, 95, 84, "2:23", "https://open.spotify.com/search/Once%20Upon%20a%20Time%20Anirudh%20Ravichander"));
                tracks.add(new Track("Badass", "Anirudh Ravichander", "Leo", 99, 98, 91, "3:49", "https://open.spotify.com/search/Badass%20Anirudh%20Ravichander"));
                tracks.add(new Track("Hukum", "Anirudh Ravichander", "Jailer", 98, 97, 93, "3:27", "https://open.spotify.com/search/Hukum%20Anirudh%20Ravichander"));
                tracks.add(new Track("Kaavaalaa", "Anirudh Ravichander", "Jailer", 96, 94, 97, "3:10", "https://open.spotify.com/search/Kaavaalaa%20Anirudh%20Ravichander"));
                tracks.add(new Track("Dippam Dappam", "Anirudh Ravichander", "Kaathuvaakula Rendu Kaadhal", 94, 92, 96, "3:29", "https://open.spotify.com/search/Dippam%20Dappam%20Anirudh%20Ravichander"));
                tracks.add(new Track("Jalabulajangu", "Anirudh Ravichander", "Don", 95, 94, 93, "4:16", "https://open.spotify.com/search/Jalabulajangu%20Anirudh%20Ravichander"));
                break;
            case "Melancholy":
                tracks.add(new Track("Po Nee Po", "Anirudh Ravichander", "3", 55, 28, 31, "4:15", "https://open.spotify.com/search/Po%20Nee%20Po%20Anirudh%20Ravichander"));
                tracks.add(new Track("Kanave Unai", "Yuvan Shankar Raja", "Azhagai Irukkirai Bayamai Irukkirathu", 52, 25, 34, "5:04", "https://open.spotify.com/search/Kanave%20Unai%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("En Kadhal Solla", "Yuvan Shankar Raja", "Paiyaa", 58, 31, 39, "4:56", "https://open.spotify.com/search/En%20Kadhal%20Solla%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("Oru Kal Oru Kannadi", "Harris Jayaraj", "Siva Manasula Sakthi", 61, 34, 42, "5:58", "https://open.spotify.com/search/Oru%20Kal%20Oru%20Kannadi%20Harris%20Jayaraj"));
                tracks.add(new Track("Yaar Indha Saalai Oram", "G.V. Prakash Kumar", "Thalaivaa", 54, 27, 36, "5:21", "https://open.spotify.com/search/Yaar%20Indha%20Saalai%20Oram%20GV%20Prakash%20Kumar"));
                tracks.add(new Track("Azhagiye", "A.R. Rahman", "Kaatru Veliyidai", 64, 38, 45, "5:56", "https://open.spotify.com/search/Azhagiye%20AR%20Rahman"));
                tracks.add(new Track("Naan Nee", "Shakthisree Gopalan", "Madras", 57, 30, 38, "4:54", "https://open.spotify.com/search/Naan%20Nee%20Shakthisree%20Gopalan"));
                tracks.add(new Track("Maruvaarthai", "Darbuka Siva", "Enai Noki Paayum Thota", 59, 29, 35, "5:56", "https://open.spotify.com/search/Maruvaarthai%20Darbuka%20Siva"));
                tracks.add(new Track("Thalli Pogathey", "A.R. Rahman", "Achcham Yenbadhu Madamaiyada", 63, 36, 41, "4:58", "https://open.spotify.com/search/Thalli%20Pogathey%20AR%20Rahman"));
                tracks.add(new Track("Vaan Varuvaan", "Dhibu Ninan Thomas", "Kaatru Veliyidai", 56, 26, 33, "4:38", "https://open.spotify.com/search/Vaan%20Varuvaan%20Dhibu%20Ninan%20Thomas"));
                tracks.add(new Track("Pogadhe", "Yuvan Shankar Raja", "Deepavali", 51, 24, 29, "4:46", "https://open.spotify.com/search/Pogadhe%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("Idhazhin Oram", "Anirudh Ravichander", "3", 67, 41, 48, "3:57", "https://open.spotify.com/search/Idhazhin%20Oram%20Anirudh%20Ravichander"));
                tracks.add(new Track("Unakkenna Venum Sollu", "Harris Jayaraj", "Yennai Arindhaal", 53, 22, 30, "5:08", "https://open.spotify.com/search/Unakkenna%20Venum%20Sollu%20Harris%20Jayaraj"));
                tracks.add(new Track("Pookkal Pookkum", "G.V. Prakash Kumar", "Madrasapattinam", 49, 21, 27, "6:36", "https://open.spotify.com/search/Pookkal%20Pookkum%20GV%20Prakash%20Kumar"));
                tracks.add(new Track("Oru Deivam Thantha Poove", "A.R. Rahman", "Kannathil Muthamittal", 45, 18, 24, "6:53", "https://open.spotify.com/search/Oru%20Deivam%20Thantha%20Poove%20AR%20Rahman"));
                tracks.add(new Track("Ennodu Nee Irundhaal", "A.R. Rahman", "I", 62, 33, 37, "5:51", "https://open.spotify.com/search/Ennodu%20Nee%20Irundhaal%20AR%20Rahman"));
                tracks.add(new Track("Unakkenna Venum Sollu", "Harris Jayaraj", "Yennai Arindhaal", 53, 22, 30, "5:08", "https://open.spotify.com/search/Unakkenna%20Venum%20Sollu%20Harris%20Jayaraj"));
                tracks.add(new Track("Yennai Maatrum Kadhale", "A.R. Rahman", "Naanum Rowdy Dhaan", 58, 27, 32, "4:34", "https://open.spotify.com/search/Yennai%20Maatrum%20Kadhale%20AR%20Rahman"));
                tracks.add(new Track("Usure Pogudhey", "A.R. Rahman", "Raavanan", 47, 20, 26, "6:06", "https://open.spotify.com/search/Usure%20Pogudhey%20AR%20Rahman"));
                tracks.add(new Track("Vizhigalil Oru Vaanavil", "Yuvan Shankar Raja", "Deiva Thirumagal", 50, 23, 31, "5:24", "https://open.spotify.com/search/Vizhigalil%20Oru%20Vaanavil%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("Aararo", "D. Imman", "Siruthai", 44, 17, 22, "5:05", "https://open.spotify.com/search/Aararo%20D%20Imman"));
                break;
            case "Stressed":
                tracks.add(new Track("Nallaru Po", "Sai Abhyankkar", "Dude", 52, 25, 23, "3:56","https://open.spotify.com/track/3Nt5m3MOIrNt1sf8v14GPM"));
                tracks.add(new Track("Local Boys", "Anirudh Ravichander", "Ethir Neechal", 87, 76, 74, "4:34","https://open.spotify.com/search/Local%20Boys%20Anirudh%20Ravichander"));
                tracks.add(new Track("Kadhal Yen Kadhal", "Dhanush", "Mayakkam Enna", 68, 43, 31, "6:08","https://open.spotify.com/search/Kadhal%20Yen%20Kadhal%20Dhanush"));
                tracks.add(new Track("Ava Enna", "Karthik", "Vaaranam Aayiram", 55, 27, 24, "5:21","https://open.spotify.com/search/Ava%20Enna%20Karthik"));
                tracks.add(new Track("Venam Machan", "D. Imman", "Oru Kal Oru Kannadi", 63, 39, 42, "4:17","https://open.spotify.com/search/Venam%20Machan%20D%20Imman"));
                tracks.add(new Track("Oru Kal Oru Kannadi", "Harris Jayaraj", "Siva Manasula Sakthi", 61, 34, 42, "5:58","https://open.spotify.com/search/Oru%20Kal%20Oru%20Kannadi%20Harris%20Jayaraj"));
                tracks.add(new Track("Pogadhey", "Yuvan Shankar Raja", "Deepavali", 52, 24, 27, "4:46","https://open.spotify.com/search/Pogadhey%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("Aaruyire", "A.R. Rahman", "Guru", 55, 29, 25, "6:06","https://open.spotify.com/search/Aaruyire%20AR%20Rahman%20Guru"));
                tracks.add(new Track("Enakenna Yaarum Illaye", "Anirudh Ravichander", "Aakko", 58, 32, 29, "4:06","https://open.spotify.com/search/Enakenna%20Yaarum%20Illaye%20Anirudh"));
                tracks.add(new Track("Kaaviya Oviya", "A.R. Rahman", "I", 57, 30, 28, "5:21","https://open.spotify.com/search/Kaaviya%20Oviya%20AR%20Rahman"));
                tracks.add(new Track("Why This Kolaveri Di", "Anirudh Ravichander", "3", 61, 35, 39, "4:03","https://open.spotify.com/search/Why%20This%20Kolaveri%20Di%20Anirudh"));
                tracks.add(new Track("Vaanganna Vanakkanganna", "Anirudh Ravichander", "Thalaivaa", 86, 73, 78, "5:14","https://open.spotify.com/search/Vaanganna%20Vanakkanganna%20Anirudh"));
                tracks.add(new Track("Kannukulla", "Leon James", "Nilavukku Enmel Ennadi Kobam", 59, 33, 35, "4:10","https://open.spotify.com/search/Kannukulla%20Leon%20James"));
                tracks.add(new Track("Ennai Vittu Uyir Ponalum", "A.R. Rahman", "Love Today", 49, 21, 19, "5:06","https://open.spotify.com/search/Ennai%20Vittu%20Uyir%20Ponalum%20Love%20Today"));
                tracks.add(new Track("Voda Voda", "Dhanush", "Mayakkam Enna", 74, 57, 46, "4:51","https://open.spotify.com/search/Voda%20Voda%20Dhanush"));
                tracks.add(new Track("Quit Pannuda", "Anirudh Ravichander", "Master", 82, 69, 61, "4:02","https://open.spotify.com/search/Quit%20Pannuda%20Anirudh"));
                tracks.add(new Track("Open the Tasmac", "Anirudh Ravichander", "Maan Karate", 89, 82, 84, "4:25","https://open.spotify.com/search/Open%20the%20Tasmac%20Anirudh"));
                tracks.add(new Track("Udhungada Sangu", "Anirudh Ravichander", "Velaiilla Pattadhari", 89, 78, 76, "3:41","https://open.spotify.com/search/Udhungada%20Sangu%20Anirudh"));
                tracks.add(new Track("Ennodu Nee Irundhaal", "A.R. Rahman", "I", 60, 31, 27, "5:51","https://open.spotify.com/search/Ennodu%20Nee%20Irundhaal%20AR%20Rahman"));
                tracks.add(new Track("Vaadi Pulla Vaadi", "Hiphop Tamizha", "Meesaya Murukku", 81, 62, 66, "4:07","https://open.spotify.com/search/Vaadi%20Pulla%20Vaadi%20Hiphop%20Tamizha"));
                break;
            case "Joyful":
                tracks.add(new Track("Machii Open the Bottle", "Yuvan Shankar Raja", "Mankatha", 94, 86, 91, "4:45","https://open.spotify.com/search/Machii%20Open%20the%20Bottle%20Yuvan%20Shankar%20Raja"));
                tracks.add(new Track("Seva Kodi", "A.R. Rahman", "Sivaji", 91, 79, 88, "4:30","https://open.spotify.com/search/Seva%20Kodi%20AR%20Rahman"));
                tracks.add(new Track("Singari", "Sai Abhyankkar", "Dude", 91, 78, 94, "3:28","https://open.spotify.com/track/0pxE2MKJnBMR9c0giy7v5J"));
                tracks.add(new Track("Evanda Enakku Custody", "Santhosh Narayanan", "Mahaan", 91, 84, 93, "3:36","https://open.spotify.com/search/Evanda%20Enakku%20Custody%20Mahaan"));
                tracks.add(new Track("Tasaku Tasaku", "Alex Paul", "EPCO 302", 78, 65, 76, "5:06","https://open.spotify.com/search/Tasaku%20Tasaku%20Alex%20Paul"));
                tracks.add(new Track("Vaa Vaathi", "G.V. Prakash Kumar", "Vaathi", 82, 61, 91, "3:45","https://open.spotify.com/search/Vaa%20Vaathi%20GV%20Prakash%20Kumar"));
                tracks.add(new Track("Mallipoo", "A.R. Rahman", "Vendhu Thanindhathu Kaadu", 76, 49, 87, "4:05","https://open.spotify.com/search/Mallipoo%20AR%20Rahman"));
                tracks.add(new Track("Azhagiye", "A.R. Rahman", "Kaatru Veliyidai", 81, 52, 90, "5:56","https://open.spotify.com/search/Azhagiye%20AR%20Rahman"));
                tracks.add(new Track("Enamo Yedho", "Harris Jayaraj", "Ko", 84, 64, 89, "5:25","https://open.spotify.com/search/Enamo%20Yedho%20Harris%20Jayaraj"));
                tracks.add(new Track("Mei Nigara", "A.R. Rahman", "24", 86, 68, 92, "5:16","https://open.spotify.com/search/Mei%20Nigara%20AR%20Rahman"));
                tracks.add(new Track("Kadhal Ara Onnu Vizundhuchu", "Sean Roldan", "Vaayai Moodi Pesavum", 82, 61, 88, "3:45","https://open.spotify.com/track/7IO5iU84tFIZ4kfvhrg4oZ"));
                tracks.add(new Track("Nannare", "A.R. Rahman", "Guru", 74, 43, 86, "5:28","https://open.spotify.com/track/0ILPJhvliKjxlyd5CdZ4I3"));
                tracks.add(new Track("Naaka Mukka", "Vijay Antony", "Kadhalil Vizhunthen", 97, 94, 98, "4:39","https://open.spotify.com/search/Naaka%20Mukka%20Vijay%20Antony"));
                tracks.add(new Track("Kalasaala", "Vijay Antony", "Osthe", 95, 91, 96, "4:11","https://open.spotify.com/search/Kalasaala%20Vijay%20Antony"));
                tracks.add(new Track("Mascara", "Vijay Antony", "Salim", 88, 70, 91, "4:54","https://open.spotify.com/search/Mascara%20Vijay%20Antony"));
                tracks.add(new Track("Rich Rich", "Santhosh Narayanan", "Mahaan", 85, 67, 93, "3:06","https://open.spotify.com/search/Rich%20Rich%20Santhosh%20Narayanan"));
                tracks.add(new Track("Yenga Area", "Anirudh Ravichander", "Maan Karate", 91, 82, 94, "4:27","https://open.spotify.com/search/Yenga%20Area%20Anirudh%20Ravichander"));
                tracks.add(new Track("Rakita Rakita", "Dhanush", "Jagame Thandhiram", 96, 95, 94, "4:06","https://open.spotify.com/search/Rakita%20Rakita%20Dhanush"));
                tracks.add(new Track("Kannima", "Santhosh Narayanan", "Suriya 43", 78, 52, 88, "4:12","https://open.spotify.com/search/Kannima%20Santhosh%20Narayanan"));
                tracks.add(new Track("Pala Pala", "Hariharan", "Ayan", 89, 73, 94, "4:43","https://open.spotify.com/search/Pala%20Pala%20Hariharan%20Ayan"));
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