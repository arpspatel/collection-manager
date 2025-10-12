package io.neebu.apps.utils;

import io.neebu.apps.core.entities.Constants;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;

import static io.neebu.apps.core.entities.Constants.*;

/**
 * Utility class for collection management operations such as file listing, string cleaning,
 * media property detection, and more. All methods are static and stateless.
 */
public class CollectionUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionUtils.class);

    /**
     * Detects the streaming source from a file label based on known OTT platform abbreviations.
     *
     * @param fileLabel The label or name of the file.
     * @return The OTT platform abbreviation if found, otherwise the default value.
     */
    public static String getStreamingSource(String fileLabel) {
        for (String ottAbbreviation : Constants.OTT_PLATFORMS.split(",")) {
            if (fileLabel.toUpperCase().contains("." + ottAbbreviation.toUpperCase() + ".")) {
                return ottAbbreviation;
            }
        }
        return WEB_DL_DEFAULT;
    }

    /**
     * Recursively lists all video files in a directory, filtering by known video extensions.
     *
     * @param directory The root directory to scan.
     * @return List of absolute paths to video files, sorted by filename.
     */
    public static List<Path> listFilesRecursively(Path directory) {
        List<Path> fileNames = new ArrayList<>();

        if (directory == null) {
            LOGGER.warn("Provided directory path is null. Skipping scan.");
            return fileNames;
        }

        if (!Files.exists(directory)) {
            LOGGER.warn("Directory does not exist: {}", directory.toAbsolutePath());
            return fileNames;
        }

        if (!Files.isDirectory(directory)) {
            LOGGER.warn("Path is not a directory: {}", directory.toAbsolutePath());
            return fileNames;
        }

        try (var paths = Files.walk(directory)) {
            fileNames = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return VIDEO_EXTENSIONS.stream().anyMatch(name::endsWith);
                    })
                    .map(Path::toAbsolutePath)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (Exception e) {
            LOGGER.error("Error listing files recursively in {}: {}", directory, e.getMessage(), e);
        }

        if (fileNames.isEmpty()) {
            LOGGER.warn("No video files found in {}", directory.toAbsolutePath());
        }

        return fileNames;
    }

    /**
     * Slightly increases a value by 1% (used for resolution blurring).
     *
     * @param value The value to blur.
     * @return The blurred value.
     */
    public static int blur(int value) {
        return value + (value / 100);
    }

    /**
     * Detects HDR format(s) from a source string.
     *
     * @param source The string to analyze.
     * @return Dot-separated HDR format abbreviations (e.g., "DV.HDR").
     */
    public static String detectHdrFormat(String source) {
        source = source.toLowerCase(Locale.ROOT);
        Set<String> ret = new LinkedHashSet<>();

        if (source.contains("dolby vision")) ret.add("DV");
        if (source.contains("hlg")) ret.add("HLG");
        if (source.contains("2094") || source.contains("hdr10+")) ret.add("HDR");
        source = source.replaceAll("hdr10\\+", "");
        if (source.contains("2086") || source.contains("hdr10")) ret.add("HDR");

        return String.join(".", ret);
    }

    /**
     * Checks if the input string contains any item from the provided list.
     *
     * @param inputStr The string to search in.
     * @param items Array of items to search for.
     * @return True if any item is found, false otherwise.
     */
    public static boolean stringContainsItemFromList(String inputStr, String[] items) {
        return Arrays.stream(items).anyMatch(inputStr::contains);
    }

    /**
     * Converts a channel count string to a human-readable format (e.g., "5" to "4.1").
     *
     * @param channels The channel count as a string.
     * @return The formatted channel string, or "Unknown" if parsing fails.
     */
    public static String getChannels(String channels) {
        try {
            int ch = Integer.parseInt(channels);
            return ch > 2 ? (ch - 1) + ".1" : ch + ".0";
        } catch (NumberFormatException e) {
            LOGGER.warn("Unable to parse channel count '{}': {}", channels, e.getMessage());
            return "Unknown";
        }
    }

    /**
     * Returns the first non-blank value from the provided arguments.
     *
     * @param values Variable number of string values.
     * @return The first non-blank value, or null if all are blank.
     */
    public static String coalesce(String... values) {
        for (String value : values) {
            if (!StringUtils.isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Cleans a string for safe use in filenames or identifiers.
     *
     * Steps:
     * 1. Replace '&' with 'and'.
     * 2. Normalize Unicode and remove accents.
     * 3. Replace all non-alphanumeric, non-dot, non-dash characters with dot.
     * 4. Collapse multiple dots into one.
     * 5. Trim leading/trailing dots.
     *
     * @param input The input string.
     * @return The cleaned string, or null if input is null.
     */
    public static String cleanString(String input) {
        if (input == null) return null;

        // Replace & with "and"
        String replaced = input.replace("&", "and");

        // Step 1: Normalize Unicode and remove accents
        String normalized = Normalizer.normalize(replaced, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        // Step 2: Replace everything except A-Z, a-z, 0-9, dot, dash with dot
        normalized = normalized.replaceAll("[^A-Za-z0-9.-]", ".");

        // Step 3: Collapse multiple dots into one
        normalized = normalized.replaceAll("\\.+", ".");

        // Step 4: Trim leading/trailing dots
        normalized = normalized.replaceAll("^\\.|\\.$", "");


        return normalized;
    }

    /**
     * Removes illegal characters from a string for safe use as a filename.
     *
     * @param name The input string.
     * @return The sanitized string.
     */
    public static String removeIllegalCharacters(String name) {
        name = StringUtils.stripAccents(name)
                .replaceAll("[^\\p{ASCII}]", " ")
                .replace("&", "and")
                .replace(" - ", "-")
                .replace(". ", ".")
                .replaceAll("'", "")
                .replaceAll("[\\\\/:*?\"<>|,!]", " ")
                .trim()
                .replaceAll("\\s+", ".")
                .replaceAll("\\.+$", "");
        return name;
    }

    /**
     * Detects the video resolution (e.g., 720p, 1080p) based on width and height.
     * Uses a set of thresholds and a blur margin.
     *
     * @param width The video width in pixels.
     * @param height The video height in pixels.
     * @return The resolution string (e.g., "1080p").
     */
    public static String detectResolution(int width, int height) {
        int[][] thresholds = {
                {128, 96, 96}, {160, 120, 120}, {176, 144, 144}, {256, 144, 144},
                {320, 240, 240}, {352, 240, 240}, {426, 240, 240}, {480, 272, 288},
                {480, 360, 360}, {640, 360, 360}, {640, 480, 480}, {720, 480, 480},
                {800, 480, 480}, {853, 480, 480}, {776, 592, 576}, {1024, 576, 576},
                {960, 544, 540}, {1280, 720, 720}, {1920, 1080, 1080}, {2560, 1440, 1440},
                {3840, 2160, 2160}, {3840, 1600, 2160}, {4096, 2160, 2160}, {4096, 1716, 2160},
                {3996, 2160, 2160}
        };

        for (int[] t : thresholds) {
            if (width <= blur(t[0]) && height <= blur(t[1])) {
                return t[2] + "p";
            }
        }
        return "4320p";
    }
}