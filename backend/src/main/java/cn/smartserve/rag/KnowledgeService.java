package cn.smartserve.rag;

import cn.smartserve.business.BusinessService;
import cn.smartserve.infra.JsonStore;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeService implements ApplicationRunner {

  private final JsonStore db;
  private final StringRedisTemplate redis;

  @Value("${smartserve.data-path}")
  String dataPath;

  @Value("${smartserve.embeddings-enabled}")
  boolean embeddings;

  @Value("${smartserve.async-mode}")
  String mode;

  private EmbeddingModel model;
  private volatile List<Chunk> chunks = List.of();
  private volatile String revision = "";

  @Value("${smartserve.pgvector-enabled}")
  boolean pgvector;

  private boolean postgres;

  public record Chunk(
    String id,
    String source,
    String heading,
    String content,
    float[] vector
  ) {}

  public KnowledgeService(JsonStore db, StringRedisTemplate redis) {
    this.db = db;
    this.redis = redis;
  }

  @Override
  public void run(ApplicationArguments a) throws Exception {
    try (var conn = db.jdbc.getDataSource().getConnection()) {
      postgres = conn
        .getMetaData()
        .getDatabaseProductName()
        .contains("PostgreSQL");
    }
    if (postgres && pgvector) {
      db.jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
      db.jdbc.execute(
        "ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS embedding vector(512)"
      );
      db.jdbc.execute(
        "CREATE INDEX IF NOT EXISTS knowledge_vector_hnsw ON knowledge_chunk USING hnsw (embedding vector_cosine_ops)"
      );
    }
    if (embeddings) model = new BgeSmallZhEmbeddingModel();
    reindex();
  }

  public synchronized Map<String, Object> reindex() throws Exception {
    var indexed = new ArrayList<Chunk>();
    Path root = Path.of(dataPath, "knowledge");
    if (!Files.isDirectory(root)) throw new IllegalStateException(
      "知识库目录不存在：" + root.toAbsolutePath()
    );
    try (var paths = Files.list(root)) {
      for (Path p : paths
        .filter(x -> x.getFileName().toString().endsWith(".md"))
        .sorted()
        .toList()) {
        String source = p.getFileName().toString();
        String content = Files.readString(p);
        String[] sections = content.split("(?m)^## ");
        for (int i = 1; i < sections.length; i++) {
          String[] parts = sections[i].split("\\R", 2);
          String heading = parts[0].strip();
          String text = parts.length > 1 ? parts[1].strip() : "";
          if (text.isBlank()) continue;
          // Section chunks preserve source headings; bound model input to avoid silently truncating evidence.
          for (int start = 0; start < text.length(); start += 450) {
            String body = text.substring(
              start,
              Math.min(start + 500, text.length())
            );
            String id = BusinessService.hash(
              source + "|" + heading + "|" + start
            ).substring(0, 32);
            String hash = BusinessService.hash(heading + body);
            var old = db.jdbc.queryForList(
              "SELECT content_hash,vector_text FROM knowledge_chunk WHERE id=?",
              id
            );
            float[] vector;
            if (
              !old.isEmpty() &&
              hash.equals(old.get(0).get("content_hash")) &&
              (!embeddings || !"[]".equals(old.get(0).get("vector_text")))
            ) vector = db.mapper.readValue(
              (String) old.get(0).get("vector_text"),
              float[].class
            );
            else {
              vector = embed(heading + "\n" + body);
              db.jdbc.update("DELETE FROM knowledge_chunk WHERE id=?", id);
              db.jdbc.update(
                "INSERT INTO knowledge_chunk(id,source,heading,content,content_hash,vector_text) VALUES(?,?,?,?,?,?)",
                id,
                source,
                heading,
                body,
                hash,
                db.json(vector)
              );
            }
            if (postgres && pgvector && vector.length > 0) db.jdbc.update(
              "UPDATE knowledge_chunk SET embedding=CAST(? AS vector) WHERE id=?",
              db.json(vector),
              id
            );
            indexed.add(new Chunk(id, source, heading, body, vector));
          }
        }
      }
    }
    var current = indexed.stream().map(Chunk::id).collect(Collectors.toSet());
    for (String stale : db.jdbc.queryForList(
      "SELECT id FROM knowledge_chunk",
      String.class
    ))
      if (!current.contains(stale)) db.jdbc.update(
        "DELETE FROM knowledge_chunk WHERE id=?",
        stale
      );
    chunks = List.copyOf(indexed);
    revision = BusinessService.hash(
      indexed
        .stream()
        .map(c -> c.id() + c.content())
        .collect(Collectors.joining())
    );
    return stats();
  }

  private float[] embed(String text) {
    return model == null ? new float[0] : model.embed(text).content().vector();
  }

  public Map<String, Object> stats() {
    return Map.of(
      "documents",
      chunks.stream().map(Chunk::source).distinct().count(),
      "chunks",
      chunks.size(),
      "embeddingModel",
      embeddings ? "bge-small-zh (ONNX, 512维)" : "词法检索（测试模式）",
      "vectorStore",
      postgres
        ? (pgvector
            ? "PostgreSQL / pgvector"
            : "PostgreSQL / JVM余弦（本机配置）")
        : "H2 / JVM余弦（本地演示）",
      "revision",
      revision.substring(0, Math.min(12, revision.length())),
      "reranker",
      "语义与词法加权重排"
    );
  }

  public List<Map<String, Object>> documents() {
    return chunks
      .stream()
      .collect(
        Collectors.groupingBy(Chunk::source, TreeMap::new, Collectors.toList())
      )
      .entrySet()
      .stream()
      .map(e ->
        Map.<String, Object>of(
          "name",
          e.getKey(),
          "chunks",
          e.getValue().size(),
          "content",
          e
            .getValue()
            .stream()
            .map(c -> "## " + c.heading() + "\n" + c.content())
            .collect(Collectors.joining("\n\n"))
        )
      )
      .toList();
  }

  public List<Map<String, Object>> search(String query, int limit) {
    return search(query, limit, "hybrid");
  }

  public List<Map<String, Object>> search(
    String query,
    int limit,
    String strategy
  ) {
    String cacheKey =
      "smartserve:rag:" +
      revision +
      ":" +
      strategy +
      ":" +
      BusinessService.hash(query) +
      ":" +
      limit;
    if (mode.equals("rabbit")) {
      try {
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) return db.mapper.readValue(
          cached,
          new com.fasterxml.jackson.core.type.TypeReference<
            List<Map<String, Object>>
          >() {}
        );
      } catch (Exception ignored) {}
    }
    float[] q = embed(query);
    Map<String, Double> dense = new HashMap<>();
    if (postgres && pgvector && q.length > 0) {
      db.jdbc.query(
        "SELECT id,1-(embedding <=> CAST(? AS vector)) AS score FROM knowledge_chunk WHERE embedding IS NOT NULL ORDER BY embedding <=> CAST(? AS vector) LIMIT 20",
        rs -> {
          dense.put(rs.getString("id"), rs.getDouble("score"));
        },
        db.json(q),
        db.json(q)
      );
    } else for (Chunk c : chunks) dense.put(c.id(), cosine(q, c.vector()));
    Map<String, Double> sparse = new HashMap<>();
    Set<String> qt = tokens(query);
    double avg = chunks
      .stream()
      .mapToInt(c -> tokens(c.heading() + c.content()).size())
      .average()
      .orElse(1);
    for (Chunk c : chunks) {
      var dt = tokens(c.heading() + c.content());
      double score = 0;
      for (String term : qt) {
        if (!dt.contains(term)) continue;
        long df = chunks
          .stream()
          .filter(x -> tokens(x.heading() + x.content()).contains(term))
          .count();
        double idf = Math.log(1 + (chunks.size() - df + 0.5) / (df + 0.5));
        score += (idf * 2.2) / (1 + 1.2 * (.25 + (.75 * dt.size()) / avg));
      }
      sparse.put(c.id(), score);
    }
    var denseOrder = rank(dense);
    var sparseOrder = rank(sparse);
    double maxLex = sparse
      .values()
      .stream()
      .mapToDouble(Double::doubleValue)
      .max()
      .orElse(1);
    if (maxLex == 0) maxLex = 1;
    final double lexMax = maxLex;
    var candidates = chunks
      .stream()
      .filter(
        c -> denseOrder.indexOf(c.id()) < 20 || sparseOrder.indexOf(c.id()) < 20
      )
      .map(c -> {
        double semantic = dense.getOrDefault(c.id(), 0.0),
          lex = sparse.get(c.id()) / lexMax;
        double rrf =
          1.0 / (61 + denseOrder.indexOf(c.id())) +
          1.0 / (61 + sparseOrder.indexOf(c.id()));
        double score = strategy.equals("vector")
          ? semantic
          : strategy.equals("bm25")
            ? lex
            : .72 * semantic + .23 * lex + .05 * rrf * 30;
        var m = new LinkedHashMap<String, Object>();
        m.put("id", c.id());
        m.put("source", c.source());
        m.put("heading", c.heading());
        m.put("content", c.content());
        m.put("score", Math.round(score * 10000) / 10000.0);
        m.put("semanticScore", Math.round(semantic * 10000) / 10000.0);
        m.put("lexicalScore", Math.round(lex * 10000) / 10000.0);
        m.put(
          "accepted",
          domainQuery(query) &&
            ((semantic >= .63 && lex > .08) || (!embeddings && lex > .35))
        );
        return m;
      })
      .sorted(
        Comparator.comparingDouble((Map<String, Object> m) ->
          ((Number) m.get("score")).doubleValue()
        ).reversed()
      )
      .limit(Math.max(1, Math.min(limit, 8)))
      .toList();
    List<Map<String, Object>> result = new ArrayList<>(candidates);
    for (int i = 0; i < result.size(); i++) result
      .get(i)
      .put("citation", "S" + (i + 1));
    if (mode.equals("rabbit")) {
      try {
        redis
          .opsForValue()
          .set(cacheKey, db.json(result), Duration.ofMinutes(15));
      } catch (Exception ignored) {}
    }
    return result;
  }

  private static boolean domainQuery(String query) {
    return java.util.regex.Pattern.compile(
      "家居|设备|门锁|指纹|开门|摄像|录像|灯泡|插座|网关|温湿|温度计|供电|电池|配网|wifi|wi-fi|保修|售后|退款|退货|退钱|能退|换货|物流|订单|快递|联网|断网|隐私|安装|云存储|智能|5ghz|zigbee|matter|homekit|\\b(s1|c2|l1|p1|h1|t1)\\b",
      java.util.regex.Pattern.CASE_INSENSITIVE
    )
      .matcher(query)
      .find();
  }

  private List<String> rank(Map<String, Double> scores) {
    return scores
      .entrySet()
      .stream()
      .sorted(
        Map.Entry.<String, Double>comparingByValue()
          .reversed()
          .thenComparing(Map.Entry::getKey)
      )
      .map(Map.Entry::getKey)
      .toList();
  }

  static Set<String> tokens(String text) {
    String normalized = text
      .toLowerCase(Locale.ROOT)
      .replace("wifi", "wi-fi")
      .replace("联网", "网络")
      .replace("退钱", "退款")
      .replace("没电", "供电");
    var result = new HashSet<String>();
    String s = normalized.replaceAll("[^\\p{L}\\p{N}]", "");
    for (int i = 0; i < s.length() - 1; i++) result.add(s.substring(i, i + 2));
    for (String term : normalized.split("[^a-z0-9]+"))
      if (term.length() > 1) result.add(term);
    return result;
  }

  static double cosine(float[] a, float[] b) {
    if (a.length == 0 || a.length != b.length) return 0;
    double dot = 0,
      aa = 0,
      bb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      aa += a[i] * a[i];
      bb += b[i] * b[i];
    }
    return aa * bb == 0 ? 0 : dot / Math.sqrt(aa * bb);
  }
}
