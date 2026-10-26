package com.shortvideo.agent;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Optional real-MySQL regression: the server may be in any system zone; JDBC must force UTC. */
@EnabledIfEnvironmentVariable(named = "MYSQL_TIMEZONE_TEST_URL", matches = "jdbc:mysql:.*")
class MySqlTimezoneTest {
  @Test
  void currentTimestampRoundTripsAsRealInstantInForcedUtcSession() throws Exception {
    String url = System.getenv("MYSQL_TIMEZONE_TEST_URL");
    String username = System.getenv().getOrDefault("MYSQL_TIMEZONE_TEST_USERNAME", "root");
    String password = System.getenv().getOrDefault("MYSQL_TIMEZONE_TEST_PASSWORD", "");
    try (var connection = DriverManager.getConnection(url, username, password);
        var statement = connection.createStatement()) {
      try (var zone = statement.executeQuery("SELECT @@session.time_zone")) {
        assertThat(zone.next()).isTrue();
        assertThat(zone.getString(1)).isIn("+00:00", "UTC");
      }
      statement.execute(
          "CREATE TEMPORARY TABLE timezone_regression (created_at TIMESTAMP(6) DEFAULT"
              + " CURRENT_TIMESTAMP(6))");
      Instant before = Instant.now();
      statement.execute("INSERT INTO timezone_regression () VALUES ()");
      try (var result = statement.executeQuery("SELECT created_at FROM timezone_regression")) {
        assertThat(result.next()).isTrue();
        Instant read = result.getTimestamp(1).toInstant();
        assertThat(Duration.between(before, read).abs()).isLessThan(Duration.ofSeconds(5));
      }
    }
  }
}
