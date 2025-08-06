package io.neebu.apps.core;

import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

@Getter
public class AppProperties {

    private List<String> moviePaths;
    private List<String> tvShowPaths;
    private String tmdbApiKey;
    private String tmdbApiUri;
    private String databaseUrl;
    private String databaseUser;
    private String databasePass;

    public AppProperties(){
        try (InputStream input = AppProperties.class.getClassLoader().getResourceAsStream("application.properties")) {
            Properties prop = new Properties();
            if (input == null) {
                System.out.println("Sorry, unable to find application.properties");
                return;
            }
            // Load a properties file from class path, inside static method
            prop.load(input);
            this.moviePaths = Arrays.stream(prop.getProperty("library.movies.paths").split(",")).distinct().toList();
            this.tvShowPaths = Arrays.stream(prop.getProperty("library.tv.paths").split(",")).distinct().toList();
            this.tmdbApiKey = prop.getProperty("tmdb.api.key");
            this.tmdbApiUri = prop.getProperty("tmdb.api.uri");
            this.databaseUrl = prop.getProperty("database.url");
            this.databaseUser = prop.getProperty("database.user");
            this.databasePass = prop.getProperty("database.pass");

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}
