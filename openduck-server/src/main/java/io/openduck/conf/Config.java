package io.openduck.conf;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class Config {

    private static final Properties PROPS = new Properties();

    static {
        try {
            String confDir = System.getProperty("conf.dir");
            if (confDir == null) {
                throw new RuntimeException("conf.dir system property not set");
            }

            Path file = Paths.get(confDir, "openduck.properties");

            try (InputStream is = Files.newInputStream(file)) {
                PROPS.load(is);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to load configuration", e);
        }
    }

    public static String get(String key) {
        return PROPS.getProperty(key);
    }

    public static int getInt(String key) {
        return Integer.parseInt(get(key));
    }
}
