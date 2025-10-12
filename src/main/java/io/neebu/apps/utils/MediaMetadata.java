package io.neebu.apps.utils;

import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.neebu.apps.core.entities.Constants.*;

@Getter
@ToString
public class MediaMetadata {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaMetadata.class);

    private static final Pattern EPISODE_PATTERN = Pattern.compile("^(.+?)[. _-]+(?:[Ss](\\d{1,2})[Ee](\\d{1,4})|(\\d{1,2})x(\\d{1,2})).*\\.[^.]+$");
    private static final Pattern OPTIONAL_TAG_PATTERN = Pattern.compile("\\[(.*?)]");
    private static final Pattern OTR_PATTERN = Pattern.compile(".*?(_\\d{2}\\.\\d{2}\\.\\d{2}[_ ]+\\d{2}-\\d{2}_).*?");

    private final String title;
    private final String season;
    private final String episode;
    private final String year;

    public MediaMetadata(String filename) {
        String[] result = {"", "", "", ""}; // title, season, episode, year

        if (StringUtils.isBlank(filename)) {
            LOGGER.warn("Filename is null or empty");
            this.title = this.season = this.episode = this.year = "";
            return;
        }

        LOGGER.trace("Parsing filename: {}", filename);
        filename = filename.replaceAll("\\{tmdb-\\d+}", "");

        boolean episodeMatchFound = false;

        // Step 1: Extract season and episode
        Matcher matcher = EPISODE_PATTERN.matcher(filename);
        if (matcher.find()) {
            result[0] = cleanToken(matcher.group(1));
            result[1] = matcher.group(2) != null ? matcher.group(2) : matcher.group(4); // season
            result[2] = matcher.group(3) != null ? matcher.group(3) : matcher.group(5); // episode
            episodeMatchFound = true;
        }


        // Step 2: Remove file extension and clean tags
        String fname = filename.replaceFirst("\\.\\w{2,4}$", "");
        fname = fname.replaceFirst("(?i)(" + DELIMITER + ")\\d{3,4}x\\d{3,4}(" + DELIMITER + "|$)", "$1");

        for (String word : CLEANWORDS) {
            fname = fname.replaceFirst("(?i)(" + DELIMITER + ")" + word, "$1");
        }

        // Step 3: Extract and clean optionals
        List<String> optionals = new ArrayList<>();
        Matcher optionalTagMatcher = OPTIONAL_TAG_PATTERN.matcher(fname);
        while (optionalTagMatcher.find()) {
            optionals.addAll(Arrays.asList(StringUtils.split(optionalTagMatcher.group(1), DELIMITER)));
            fname = fname.replace(optionalTagMatcher.group(), "");
        }

        Matcher otrMatcher = OTR_PATTERN.matcher(fname);
        if (otrMatcher.matches() && otrMatcher.start(1) > 10) {
            fname = fname.substring(0, otrMatcher.start(1));
        }

        String[] tokens = StringUtils.split(fname, DELIMITER);
        if (tokens.length == 0 && !optionals.isEmpty()) {
            tokens = optionals.toArray(new String[0]);
        }

        // Step 4: Extract year
        int yearPosition = -1;
        int firstStopword = tokens.length;
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);

        for (int i = tokens.length - 1; i > 0; i--) {
            if (tokens[i].matches("\\d{4}")) {
                int parsed = Integer.parseInt(tokens[i]);
                if (parsed >= 1900 && parsed <= currentYear + 5) {
                    result[3] = tokens[i];
                    yearPosition = i;
                    tokens[i] = "";
                    break;
                }
            }
        }

        if (result[3].isEmpty()) {
            for (String o : optionals) {
                if (o.matches("\\d{4}")) {
                    int parsed = Integer.parseInt(o);
                    if (parsed >= 1900 && parsed <= currentYear + 5) {
                        result[3] = o;
                        break;
                    }
                }
            }
        }

        // Step 5: Remove HARD and SOFT stopwords
        for (int i = 0; i < tokens.length; i++) {
            if (isIn(tokens[i], HARD_STOPWORDS)) {
                tokens[i] = "";
                if (i < firstStopword && i >= 2) firstStopword = i;
            }
        }

        int stopScanStart = (yearPosition >= 0) ? yearPosition : 0;
        for (int i = stopScanStart; i < tokens.length; i++) {
            if (isIn(tokens[i], SOFT_STOPWORDS)) {
                tokens[i] = "";
                if (i < firstStopword && i >= 2) firstStopword = i;
            }
        }

        // Step 6: Build clean title
        StringBuilder cleanTitle = new StringBuilder();
        int titleEnd = (yearPosition >= 0) ? Math.min(firstStopword, yearPosition) : firstStopword;

        for (int i = 0; i < titleEnd; i++) {
            if (!tokens[i].isEmpty()) {
                String word = tokens[i];
                cleanTitle.append(isRomanNumeral(word) ? word.toUpperCase(Locale.ROOT) : word).append(" ");
            }
        }

        if (!episodeMatchFound && cleanTitle.length() > 0) {
            result[0] = cleanTitle.toString().strip();
        } else if (!episodeMatchFound && result[0].isEmpty()) {
            result[0] = fname;
        }

        LOGGER.debug("Parsed: title='{}', season='{}', episode='{}', year='{}'",
                result[0], result[1], result[2], result[3]);

        this.title = result[0];
        this.season = result[1];
        this.episode = result[2];
        this.year = result[3];
    }

    private boolean isIn(String token, String[] list) {
        for (String word : list) {
            if (word.equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    private String cleanToken(String input) {
        return input.replace('.', ' ').replace('_', ' ').trim();
    }

    private boolean isRomanNumeral(String word) {
        switch (word.toUpperCase(Locale.ROOT)) {
            case "I": case "II": case "III": case "IV": case "V":
            case "VI": case "VII": case "VIII": case "IX": case "X":
                return true;
            default:
                return false;
        }
    }
}
