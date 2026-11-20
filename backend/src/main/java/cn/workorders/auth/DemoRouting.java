package cn.workorders.auth;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/** One immutable database choice per request, shared with Spring's transaction manager. */
final class DemoRouting extends AbstractRoutingDataSource {
  private static final ThreadLocal<Boolean> DEMO = new ThreadLocal<>();

  DemoRouting(DataSource primary, DataSource demo) {
    setTargetDataSources(Map.of(false, primary, true, demo));
    setDefaultTargetDataSource(primary);
    setLenientFallback(false);
  }

  static void select(boolean demo) {
    DEMO.set(demo);
  }

  static void clear() {
    DEMO.remove();
  }

  @Override
  protected Object determineCurrentLookupKey() {
    return Boolean.TRUE.equals(DEMO.get());
  }
}
