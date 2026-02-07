package io.openduck.conf;

import java.io.InputStream;
import java.util.Properties;

public class Config {

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream is = Config.class.getClassLoader().getResourceAsStream("openduck.properties")) {
            if (is == null) {
                throw new RuntimeException("app.properties not found on classpath");
            }
            PROPS.load(is);
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
