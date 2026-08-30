package io.neebu.apps.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * MediaParser extracts video and audio metadata from media files by shelling out to the
 * mediainfo CLI (--Output=JSON) rather than binding to MediaInfoLib natively. This trades a
 * per-file process-start cost for avoiding native-library/platform bundling entirely - the CLI
 * is expected to already be installed and on PATH (or pointed at via the mediainfo.path
 * property), the same binary and invocation on Windows, Linux, and Synology DSM alike.
 */
public class MediaParser implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaParser.class);

    private static final String FORMAT = "Format";
    private static final String LANGUAGE = "Language";
    private static final String CODEC_ID = "CodecID";
    private static final String TYPE_GENERAL = "General";
    private static final String TYPE_VIDEO = "Video";
    private static final String TYPE_AUDIO = "Audio";

    private static final Set<String> ALLOWED_LANGUAGES = Set.of("en", "hi", "gu", "te", "ta", "ko", "ja", "zh", "mr","en-us","hi-in");

    private static final Gson GSON = new Gson();
    private static final int PROCESS_TIMEOUT_SECONDS = 30;

    private final Map<String, List<JsonObject>> tracksByType;

    /**
     * Constructs a MediaParser for the given media file.
     * @param filePath Path to the media file
     * @param mediaInfoExecutable Path/command used to invoke the mediainfo CLI (e.g. "mediainfo",
     *                            or an absolute path if it isn't on this process's PATH)
     * @throws IOException If mediainfo can't be run, times out, or the file can't be opened
     */
    public MediaParser(Path filePath, String mediaInfoExecutable) throws IOException {
        LOGGER.debug("Running '{}' against file: {}", mediaInfoExecutable, filePath);
        JsonObject media = runMediaInfo(mediaInfoExecutable, filePath);

        if (media == null || !media.has("track") || !media.get("track").isJsonArray()) {
            throw new IOException("Could not open media file: " + filePath);
        }

        this.tracksByType = new HashMap<>();
        for (JsonElement element : media.getAsJsonArray("track")) {
            JsonObject track = element.getAsJsonObject();
            String type = track.has("@type") ? track.get("@type").getAsString() : "";
            tracksByType.computeIfAbsent(type, t -> new ArrayList<>()).add(track);
        }
        LOGGER.info("Media file loaded successfully: {}", filePath);
    }

    /**
     * Runs the mediainfo CLI against one file and returns its "media" JSON object, or null if
     * mediainfo could not open the file (mirrors mediainfo's own behavior: it exits 0 even for an
     * unreadable/nonexistent file, reporting {"media":null} instead of a nonzero exit code).
     */
    private static JsonObject runMediaInfo(String executable, Path filePath) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(executable, "--Output=JSON", filePath.toString())
                    .redirectErrorStream(false)
                    .start();
        } catch (IOException e) {
            throw new IOException("Could not run mediainfo ('" + executable + "') - check the 'mediainfo.path' " +
                    "setting or that it's installed and on the system PATH: " + e.getMessage(), e);
        }

        // Drain stderr on a separate thread while reading stdout, so a chatty child process on
        // either stream can't deadlock this by filling its pipe buffer while we block on the other.
        StringBuilder stderrCapture = new StringBuilder();
        Thread stderrReader = new Thread(() -> {
            try {
                stderrCapture.append(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // best-effort diagnostic only
            }
        }, "mediainfo-stderr-reader");
        stderrReader.start();

        String stdout;
        boolean finished;
        try {
            stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stderrReader.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running mediainfo on " + filePath, e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("mediainfo timed out after " + PROCESS_TIMEOUT_SECONDS + "s parsing " + filePath);
        }

        String stderr = stderrCapture.toString().trim();
        if (process.exitValue() != 0) {
            throw new IOException("mediainfo exited with code " + process.exitValue() + " for " + filePath
                    + (StringUtils.isNotBlank(stderr) ? ": " + stderr : ""));
        }
        if (StringUtils.isBlank(stdout)) {
            throw new IOException("mediainfo produced no output for " + filePath
                    + (StringUtils.isNotBlank(stderr) ? ": " + stderr : ""));
        }

        JsonObject root;
        try {
            root = GSON.fromJson(stdout, JsonObject.class);
        } catch (JsonSyntaxException e) {
            throw new IOException("Could not parse mediainfo JSON output for " + filePath + ": " + e.getMessage(), e);
        }
        if (root == null || !root.has("media") || root.get("media").isJsonNull()) {
            return null;
        }
        return root.getAsJsonObject("media");
    }

    /**
     * Returns the video codec for the media file.
     * @return Video codec string
     */
    public String getVideoCodec() {
        // Note: the old MediaInfoLib binding also tried a "CodecID/Hint" lookup here, which
        // normalizes legacy AVI-era FourCC codes (e.g. "XVID") to a friendlier name. The CLI's
        // JSON output doesn't expose that parameter-modifier form, so this relies on Format plus
        // the CodecID special-cases below - which already cover the cases that mattered in practice.
        String videoCodec = safeGet(TYPE_VIDEO, 0, FORMAT);

        String codecId = safeGet(TYPE_GENERAL, 0, CODEC_ID);
        if ("XVID".equalsIgnoreCase(codecId)) return "XVID";
        if ("AVC".equalsIgnoreCase(videoCodec)) return "H264";

        if (videoCodec != null && videoCodec.toLowerCase().contains("mpeg")) {
            String versionStr = safeGet(TYPE_VIDEO, 0, "Format_Version");
            try {
                int version = Integer.parseInt(versionStr.replaceAll("\\D", ""));
                return "MPEG" + version;
            } catch (NumberFormatException e) {
                LOGGER.debug("Could not parse MPEG version: {}", e.getMessage());
            }
        }
        LOGGER.debug("Detected video codec: {}", videoCodec);
        return videoCodec;
    }

    /**
     * Returns the video resolution (e.g., 1080p, 2160p).
     * @return Resolution string
     */
    public String getVideoFormat() {
        try {
            int width = Integer.parseInt(safeGet(TYPE_VIDEO, 0, "Width"));
            int height = Integer.parseInt(safeGet(TYPE_VIDEO, 0, "Height"));
            return (width > 0 && height > 0) ? CollectionUtils.detectResolution(width, height) : "";
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid resolution data: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Returns the HDR format if present (e.g., DV, HDR, HLG).
     * @return HDR format string
     */
    public String getHdrFormat() {
        List<String> hdrFields = List.of("HDR_Format", "HDR_Format_String", "HDR_Format_Compatibility");
        String rawHdrData = hdrFields.stream()
                .map(field -> safeGet(TYPE_VIDEO, 0, field))
                .filter(StringUtils::isNotBlank)
                .reduce((a, b) -> a + " / " + b)
                .orElse("");

        String hdrFormat = CollectionUtils.detectHdrFormat(rawHdrData);
        if (hdrFormat != null && !hdrFormat.isBlank()) return hdrFormat;

        String transfer = safeGet(TYPE_VIDEO, 0, "transfer_characteristics");
        hdrFormat = CollectionUtils.detectHdrFormat(transfer);
        if (hdrFormat != null && !hdrFormat.isBlank()) return hdrFormat;

        String color = safeGet(TYPE_VIDEO, 0, "colour_primaries");
        if ((color != null && color.contains("2100")) ||
                (transfer != null && (transfer.contains("2100") || "PQ".equalsIgnoreCase(transfer) || "HLG".equalsIgnoreCase(transfer)))) {
            return "HDR";
        }
        return "";
    }

    /**
     * Returns the audio codec for the media file.
     * @return Audio codec string
     */
    public String getAudioCodec() {
        String[] audioInfo = parseAudioStream();
        return (audioInfo != null) ? audioInfo[0] : null;
    }

    /**
     * Returns the audio channel configuration (e.g., 5.1, 7.1).
     * @return Audio channels string
     */
    public String getAudioChannels() {
        String[] audioInfo = parseAudioStream();
        return (audioInfo != null && !"MP3".equalsIgnoreCase(audioInfo[0])) ? audioInfo[1] : null;
    }

    private String[] parseAudioStream() {
        int audioCount = tracksByType.getOrDefault(TYPE_AUDIO, List.of()).size();

        for (int i = 0; i < audioCount; i++) {
            String titleUpper = safeGet(TYPE_AUDIO, i, "Title").toUpperCase(Locale.ROOT);
            if (titleUpper.contains("COMMENT") || titleUpper.contains("COMPATIBILITY")) continue;

            String language = safeGet(TYPE_AUDIO, i, LANGUAGE);
            LOGGER.info("language data: {}", language);
            if (!language.isBlank() && !ALLOWED_LANGUAGES.contains(language.toLowerCase(Locale.ROOT))) continue;

            String formatProfile = safeGet(TYPE_AUDIO, i, "Format_Profile");
            String formatCommercial = CollectionUtils.coalesce(
                    safeGet(TYPE_AUDIO, i, "Format_Commercial"),
                    safeGet(TYPE_AUDIO, i, "Format_Commercial_IfAny"),
                    safeGet(TYPE_AUDIO, i, FORMAT)
            );

            LOGGER.info("formatCommercial data: {}", formatCommercial);
            String features = safeGet(TYPE_AUDIO, i, "Format_AdditionalFeatures");
            String channels = CollectionUtils.getChannels(safeGet(TYPE_AUDIO, i, "Channels"));

            return new String[]{determineAudioCodec(formatCommercial, formatProfile, features), channels};
        }
        return null;
    }

    private String determineAudioCodec(String formatCommercial, String formatProfile, String features) {
        if (formatCommercial == null) return "Unknown";

        Map<String, String> codecMap = Map.ofEntries(
                Map.entry("Dolby Digital", "DD"),
                Map.entry("DTS-HD Master Audio", "DTS-HD.MA"),
                Map.entry("DTS-HD High Resolution Audio", "DTS-HR"),
                Map.entry("Dolby Digital Plus", "DD+"),
                Map.entry("Dolby Digital Plus with Dolby Atmos", "DD+.Atmos"),
                Map.entry("Dolby TrueHD with Dolby Atmos", "TrueHD.Atmos"),
                Map.entry("Dolby TrueHD", "TrueHD"),
                Map.entry("DTS-HD MA + IMAX Enhanced", "IMAX.Enhanced.DTS-HD.MA")
        );

        if (formatCommercial.contains("DTS-HD") && "XLL X".equals(features)) return "DTS-X";
        if (formatCommercial.contains("DTS-") && features != null && features.contains("ES")) return "DTS-ES";
        if ("MPEG Audio".equals(formatCommercial) && formatProfile != null && formatProfile.contains("Layer 3")) return "MP3";
        if (formatCommercial.contains("AAC")) return "AAC";

        return codecMap.getOrDefault(formatCommercial, formatCommercial);
    }

    private String safeGet(String type, int streamNumber, String key) {
        try {
            List<JsonObject> tracks = tracksByType.get(type);
            if (tracks == null || streamNumber >= tracks.size()) {
                return "";
            }
            JsonElement value = tracks.get(streamNumber).get(key);
            return (value != null && !value.isJsonNull()) ? value.getAsString() : "";
        } catch (Exception e) {
            LOGGER.debug("Error getting stream data for key '{}': {}", key, e.getMessage());
            return "";
        }
    }

    /**
     * No native handle to release - the mediainfo process has already exited by the time the
     * constructor returns. Kept as a no-op so call sites can still use try-with-resources.
     */
    @Override
    public void close() {
        // no-op
    }
}
