package io.neebu.apps.core;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

@Getter
public class AppProperties {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppProperties.class);

    private List<String> moviePaths;
    private List<String> tvShowPaths;
    private boolean parseMovies = false;
    private boolean parseTv = false;
    private boolean renameMovies = false;
    private boolean renameTv = false;
    private String tmdbApiKey;
    private String tmdbApiUri;
    private String databaseUrl;
    private String databaseUser;
    private String databasePass;
    // Path/command used to invoke the mediainfo CLI. Defaults to relying on the system PATH -
    // set mediainfo.path to an absolute path if it isn't on PATH for the process running this app.
    private String mediaInfoPath = "mediainfo";

    public AppProperties(){
        try (InputStream input = openConfigStream()) {
            Properties prop = new Properties();
            if (input == null) {
                System.out.println("Sorry, unable to find application.properties");
                return;
            }
            prop.load(input);
            this.parseMovies = prop.getProperty("library.movies.enabled").equals("true");
            this.parseTv = prop.getProperty("library.tv.enabled").equals("true");
            this.renameMovies = prop.getProperty("library.movies.rename").equals("true");
            this.renameTv = prop.getProperty("library.tv.rename").equals("true");
            this.moviePaths = Arrays.stream(prop.getProperty("library.movies.paths").split(",")).distinct().toList();
            this.tvShowPaths = Arrays.stream(prop.getProperty("library.tv.paths").split(",")).distinct().toList();
            this.tmdbApiKey = prop.getProperty("tmdb.api.key");
            this.tmdbApiUri = prop.getProperty("tmdb.api.uri");
            this.databaseUrl = prop.getProperty("database.url");
            this.databaseUser = prop.getProperty("database.user");
            this.databasePass = prop.getProperty("database.pass");
            String mediaInfoPathProperty = prop.getProperty("mediainfo.path");
            if (mediaInfoPathProperty != null && !mediaInfoPathProperty.isBlank()) {
                Path candidate = Path.of(mediaInfoPathProperty.trim());
                if (Files.isRegularFile(candidate)) {
                    this.mediaInfoPath = candidate.toString();
                } else {
                    LOGGER.warn("Configured mediainfo.path '{}' does not exist - falling back to resolving 'mediainfo' via PATH", mediaInfoPathProperty);
                }
            }

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Prefers an application.properties sitting next to the running jar (so config can be
     * changed per-machine without repackaging), falling back to the one bundled on the
     * classpath - which is also what a run from an IDE/exploded classes directory always uses,
     * since there's no meaningful "next to the jar" location in that case.
     */
    private static InputStream openConfigStream() throws IOException {
        Path externalPath = jarDirectory().map(dir -> dir.resolve("application.properties")).orElse(null);
        if (externalPath != null && Files.isRegularFile(externalPath)) {
            LOGGER.info("Loading configuration from {}", externalPath);
            return Files.newInputStream(externalPath);
        }
        LOGGER.info("No application.properties found next to the jar - using the one bundled in the jar");
        return AppProperties.class.getClassLoader().getResourceAsStream("application.properties");
    }

    /**
     * @return the directory containing the running jar, or empty if not running from a jar
     *         (e.g. launched from an IDE/exploded classes directory).
     */
    private static Optional<Path> jarDirectory() {
        try {
            CodeSource codeSource = AppProperties.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                return Optional.empty();
            }
            Path location = Path.of(codeSource.getLocation().toURI());
            return Files.isRegularFile(location) ? Optional.of(location.getParent()) : Optional.empty();
        } catch (URISyntaxException | RuntimeException e) {
            LOGGER.debug("Could not resolve jar location: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
