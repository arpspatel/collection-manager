package io.neebu.apps.utils;

import com.amilesend.mediainfo.MediaInfo;
import com.amilesend.mediainfo.lib.MediaInfoAccessor;
import com.amilesend.mediainfo.lib.MediaInfoLibrary;
import com.amilesend.mediainfo.type.StreamType;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class MediaParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaParser.class);

    private static final String FORMAT = "Format";
    private static final String LANGUAGE = "Language";
    private static final String CODEC_ID = "CodecID";

    private static final Set<String> ALLOWED_LANGUAGES = Set.of("en", "hi", "gu", "te", "ta", "ko", "ja", "zh", "mr");

    private final MediaInfo mediaInfo;

    public MediaParser(Path filePath) throws IOException {
        LOGGER.debug("Initializing MediaParser for file: {}", filePath);
        MediaInfoLibrary library = MediaInfoLibrary.newInstance();
        MediaInfoAccessor accessor = new MediaInfoAccessor(library);
        this.mediaInfo = new MediaInfo(accessor).open(filePath.toFile());

        if (this.mediaInfo == null) {
            throw new IOException("Could not open media file: " + filePath);
        }
        LOGGER.info("Media file loaded successfully: {}", filePath);
    }

    public String getVideoCodec() {
        String codecHint = safeGet(StreamType.Video, 0, "CodecID/Hint");
        String videoCodec = StringUtils.isBlank(codecHint) ? safeGet(StreamType.Video, 0, FORMAT) : codecHint;

        if (StringUtils.containsIgnoreCase(videoCodec, "microsoft")) {
            videoCodec = safeGet(StreamType.Video, 0, FORMAT);
        }

        String codecId = safeGet(StreamType.General, 0, CODEC_ID);
        if ("XVID".equalsIgnoreCase(codecId)) return "XVID";
        if ("AVC".equalsIgnoreCase(videoCodec)) return "H264";

        if (StringUtils.containsIgnoreCase(videoCodec, "mpeg")) {
            String versionStr = safeGet(StreamType.Video, 0, "Format_Version");
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

    public String getVideoFormat() {
        try {
            int width = Integer.parseInt(safeGet(StreamType.Video, 0, "Width"));
            int height = Integer.parseInt(safeGet(StreamType.Video, 0, "Height"));
            return (width > 0 && height > 0) ? CollectionUtils.detectResolution(width, height) : "";
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid resolution data: {}", e.getMessage());
            return "";
        }
    }

    public String getHdrFormat() {
        List<String> hdrFields = List.of("HDR_Format", "HDR_Format_String", "HDR_Format_Compatibility");
        String rawHdrData = hdrFields.stream()
                .map(field -> safeGet(StreamType.Video, 0, field))
                .filter(StringUtils::isNotBlank)
                .reduce((a, b) -> a + " / " + b)
                .orElse("");

        String hdrFormat = CollectionUtils.detectHdrFormat(rawHdrData);
        if (StringUtils.isNotBlank(hdrFormat)) return hdrFormat;

        String transfer = safeGet(StreamType.Video, 0, "transfer_characteristics");
        hdrFormat = CollectionUtils.detectHdrFormat(transfer);
        if (StringUtils.isNotBlank(hdrFormat)) return hdrFormat;

        String color = safeGet(StreamType.Video, 0, "colour_primaries");
        if ((StringUtils.contains(color, "2100")) ||
                (StringUtils.contains(transfer, "2100") || "PQ".equalsIgnoreCase(transfer) || "HLG".equalsIgnoreCase(transfer))) {
            return "HDR";
        }
        return "";
    }

    public String getAudioCodec() {
        String[] audioInfo = parseAudioStream();
        return (audioInfo != null) ? audioInfo[0] : null;
    }

    public String getAudioChannels() {
        String[] audioInfo = parseAudioStream();
        return (audioInfo != null && !"MP3".equalsIgnoreCase(audioInfo[0])) ? audioInfo[1] : null;
    }

    private String[] parseAudioStream() {
        int audioCount = mediaInfo.getStreamCount(StreamType.Audio);

        for (int i = 0; i < audioCount; i++) {
            String titleUpper = safeGet(StreamType.Audio, i, "Title").toUpperCase(Locale.ROOT);
            if (titleUpper.contains("COMMENT") || titleUpper.contains("COMPATIBILITY")) continue;

            String language = safeGet(StreamType.Audio, i, LANGUAGE);
            if (!language.isBlank() && !ALLOWED_LANGUAGES.contains(language.toLowerCase(Locale.ROOT))) continue;

            String formatProfile = safeGet(StreamType.Audio, i, "Format_Profile");
            String formatCommercial = CollectionUtils.coalesce(
                    safeGet(StreamType.Audio, i, "Format_Commercial"),
                    safeGet(StreamType.Audio, i, "Format_Commercial_IfAny"),
                    safeGet(StreamType.Audio, i, FORMAT)
            );
            String features = safeGet(StreamType.Audio, i, "Format_AdditionalFeatures");
            String channels = CollectionUtils.getChannels(safeGet(StreamType.Audio, i, "Channels"));

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

    private String safeGet(StreamType type, int streamNumber, String key) {
        try {
            String val = mediaInfo.get(type, streamNumber, key);
            return val != null ? val : "";
        } catch (Exception e) {
            LOGGER.debug("Error getting stream data for key '{}': {}", key, e.getMessage());
            return "";
        }
    }
}
