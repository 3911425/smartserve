package cn.smartserve;

import java.nio.file.*;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SmartServeApplication {

  public static void main(String[] args) throws Exception {
    // SQL TIMESTAMP fields and calendar-day service windows use one business zone
    // in Windows, hybrid and Linux container runtimes.
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    // Process environment wins. Never log secrets; .env remains outside the artifact.
    for (Path path : List.of(Path.of("../.env"), Path.of(".env"))) {
      if (!Files.isRegularFile(path)) continue;
      for (String line : Files.readAllLines(path)) {
        String text = line.strip();
        if (
          text.isEmpty() || text.startsWith("#") || !text.contains("=")
        ) continue;
        int split = text.indexOf('=');
        String key = text.substring(0, split).strip();
        String value = text.substring(split + 1).strip();
        if (value.startsWith("\"") && value.endsWith("\"")) value =
          value.substring(1, value.length() - 1);
        if (
          "SPRING_PROFILES_ACTIVE".equals(key) &&
          System.getenv(key) == null &&
          System.getProperty("spring.profiles.active") == null
        ) {
          System.setProperty("spring.profiles.active", value);
        }
        if (
          System.getenv(key) == null && System.getProperty(key) == null
        ) System.setProperty(key, value);
      }
    }
    SpringApplication.run(SmartServeApplication.class, args);
  }
}
