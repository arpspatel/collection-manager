package io.neebu.apps.diagnostics;

import io.neebu.apps.utils.MediaParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone check for the mediainfo CLI integration - runs it against one real media file
 * without touching the database, TMDb, or any configured library paths. Meant to be run
 * directly off the packaged jar on a new platform (e.g. Synology/Ubuntu) to confirm the
 * configured mediainfo executable resolves and parses correctly before trusting a real scan there.
 * <p>
 * Usage: java -cp collection-manager.jar io.neebu.apps.diagnostics.MediaInfoCliCheck /path/to/file.mkv [mediainfo-executable]
 * If the executable argument is omitted, "mediainfo" is used (i.e. resolved via PATH).
 */
public class MediaInfoCliCheck {

    public static void main(String[] args) {
        System.out.println("os.name = " + System.getProperty("os.name"));
        System.out.println("os.arch = " + System.getProperty("os.arch"));
        System.out.println("java.version = " + System.getProperty("java.version"));
        System.out.println("java.vendor = " + System.getProperty("java.vendor"));
        System.out.println();

        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: java -cp collection-manager.jar io.neebu.apps.diagnostics.MediaInfoCliCheck <path-to-media-file> [mediainfo-executable]");
            System.exit(2);
        }

        Path filePath = Path.of(args[0]);
        String mediaInfoExecutable = args.length == 2 ? args[1] : "mediainfo";

        if (!Files.isReadable(filePath)) {
            System.err.println("File not readable: " + filePath.toAbsolutePath());
            System.exit(2);
        }

        System.out.println("mediainfo executable: " + mediaInfoExecutable);
        System.out.println("Testing against: " + filePath.toAbsolutePath());
        System.out.println();

        try (MediaParser parser = new MediaParser(filePath, mediaInfoExecutable)) {
            System.out.println("SUCCESS - mediainfo ran and the file parsed.");
            System.out.println("  Video codec:     " + parser.getVideoCodec());
            System.out.println("  Resolution:      " + parser.getVideoFormat());
            System.out.println("  HDR format:      " + parser.getHdrFormat());
            System.out.println("  Audio codec:     " + parser.getAudioCodec());
            System.out.println("  Audio channels:  " + parser.getAudioChannels());
        } catch (Throwable t) {
            System.out.println("FAILURE - see exception below.");
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
