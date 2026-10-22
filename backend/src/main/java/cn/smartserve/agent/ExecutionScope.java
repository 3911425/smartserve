package cn.smartserve.agent;

import cn.smartserve.infra.JsonStore;

/** Lease fencing is independent of the graph state and therefore cannot be restored from an old checkpoint. */
public final class ExecutionScope implements AutoCloseable {

  private static final ThreadLocal<ExecutionScope> CURRENT =
    new ThreadLocal<>();
  private final String runId;
  private final String lease;

  private ExecutionScope(String runId, String lease) {
    this.runId = runId;
    this.lease = lease;
    CURRENT.set(this);
  }

  public static ExecutionScope enter(String runId, String lease) {
    return new ExecutionScope(runId, lease);
  }

  public static void check(JsonStore db) {
    check(db, false);
  }

  public static void check(JsonStore db, boolean lock) {
    var scope = CURRENT.get();
    if (scope == null) return;
    var rows = db.jdbc.queryForList(
      "SELECT status,lease_token,lease_until FROM agent_run WHERE id=?" +
        (lock ? " FOR UPDATE" : ""),
      scope.runId
    );
    if (
      rows.isEmpty() ||
      !"RUNNING".equals(rows.get(0).get("status")) ||
      !scope.lease.equals(rows.get(0).get("lease_token")) ||
      !(rows.get(0).get("lease_until") instanceof java.sql.Timestamp t) ||
      !t.toInstant().isAfter(java.time.Instant.now())
    ) throw new StaleExecutionException();
  }

  @Override
  public void close() {
    CURRENT.remove();
  }

  public static class StaleExecutionException extends RuntimeException {

    public StaleExecutionException() {
      super("任务已取消或执行租约已失效");
    }
  }
}
