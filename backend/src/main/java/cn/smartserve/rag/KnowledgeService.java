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
  private static final String MODEL_ID = "bge-small-zh:lc4j-0.36.2:512";
  private static final String RETRIEVAL_VERSION =
    "hybrid-v2.1-sku-boundary-business-domain";

  @Value("${smartserve.pgvector-enabled}")
  boolean pgvector;

  private boolean postgres;

  public record Chunk(
    String id,
    String source,
    String heading,
    String content,
    float[] vector,
    String productCode,
    String documentVersion
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
        var versionMatcher = java.util.regex.Pattern.compile(
          "(?m)^版本[：:]\\s*(\\S+)"
        ).matcher(content);
        String documentVersion = versionMatcher.find()
          ? versionMatcher.group(1)
          : "2.0";
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
              "SELECT content_hash,vector_text,model_id FROM knowledge_chunk WHERE id=?",
              id
            );
            float[] vector;
            if (
              !old.isEmpty() &&
              hash.equals(old.get(0).get("content_hash")) &&
              MODEL_ID.equals(old.get(0).get("model_id")) &&
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
            String productCode = inferProduct(heading);
            db.jdbc.update(
              "UPDATE knowledge_chunk SET model_id=?,product_code=?,document_version=?,category=? WHERE id=?",
              MODEL_ID,
              productCode,
              documentVersion,
              source.contains("政策") || source.contains("交易")
                ? "policy"
                : "manual",
              id
            );
            if (postgres && pgvector && vector.length > 0) db.jdbc.update(
              "UPDATE knowledge_chunk SET embedding=CAST(? AS vector) WHERE id=?",
              db.json(vector),
              id
            );
            indexed.add(
              new Chunk(
                id,
                source,
                heading,
                body,
                vector,
                productCode,
                documentVersion
              )
            );
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
      MODEL_ID +
        RETRIEVAL_VERSION +
        indexed
          .stream()
          .map(
            c -> c.id() + c.content() + c.productCode() + c.documentVersion()
          )
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
      "语义/BM25/RRF加权融合（非神经重排）"
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
    String productScope = inferProduct(query);
    List<Chunk> chunks = this.chunks.stream()
      .filter(
        c ->
          productScope.equals("all") ||
          c.productCode().equals("all") ||
          c.productCode().equals(productScope)
      )
      .toList();
    Map<String, Double> dense = new HashMap<>();
    if (postgres && pgvector && q.length > 0) {
      db.jdbc.query(
        "SELECT id,1-(embedding <=> CAST(? AS vector)) AS score FROM knowledge_chunk WHERE embedding IS NOT NULL AND (?='all' OR product_code='all' OR product_code=?) ORDER BY embedding <=> CAST(? AS vector) LIMIT 20",
        rs -> {
          dense.put(rs.getString("id"), rs.getDouble("score"));
        },
        db.json(q),
        productScope,
        productScope,
        db.json(q)
      );
    } else for (Chunk c : chunks) dense.put(c.id(), cosine(q, c.vector()));
    Map<String, Double> sparse = new HashMap<>();
    Set<String> qt = tokens(query);
    double avg = chunks
      .stream()
      .mapToInt(c -> terms(c.heading() + c.content()).size())
      .average()
      .orElse(1);
    for (Chunk c : chunks) {
      var dt = terms(c.heading() + c.content());
      double score = 0;
      for (String term : qt) {
        if (!dt.contains(term)) continue;
        long df = chunks
          .stream()
          .filter(x -> tokens(x.heading() + x.content()).contains(term))
          .count();
        double idf = Math.log(1 + (chunks.size() - df + 0.5) / (df + 0.5));
        int tf = Collections.frequency(dt, term);
        score +=
          (idf * tf * 2.2) / (tf + 1.2 * (.25 + (.75 * dt.size()) / avg));
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
        c ->
          (denseOrder.indexOf(c.id()) >= 0 &&
            denseOrder.indexOf(c.id()) < 20) ||
          (sparse.getOrDefault(c.id(), 0.0) > 0 &&
            sparseOrder.indexOf(c.id()) >= 0 &&
            sparseOrder.indexOf(c.id()) < 20)
      )
      .map(c -> {
        double semantic = dense.getOrDefault(c.id(), 0.0),
          lex = sparse.get(c.id()) / lexMax;
        double rrf =
          reciprocalRank(denseOrder, c.id()) +
          reciprocalRank(sparseOrder, c.id());
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
        m.put("productCode", c.productCode());
        m.put("documentVersion", c.documentVersion());
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
      "家居|设备|门锁|指纹|开门|摄像|录像|灯泡|插座|网关|温湿|温度计|供电|电池|配网|wifi|wi-fi|保修|售后|退款|退货|退钱|能退|换货|物流|订单|快递|联网|断网|隐私|安装|云存储|智能|5ghz|zigbee|matter|homekit|钱包|赠送|库存|购买|注册|登录|密码|管理员|工单|服务单|(?<![a-z0-9])(s1|c2|l1|p1|h1|t1)(?![a-z0-9])",
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
    return new HashSet<>(terms(text));
  }

  static List<String> terms(String text) {
    String normalized = text
      .toLowerCase(Locale.ROOT)
      .replace("wifi", "wi-fi")
      .replace("联网", "网络")
      .replace("退钱", "退款")
      .replace("没电", "供电");
    var result = new ArrayList<String>();
    String s = normalized.replaceAll("[^\\p{L}\\p{N}]", "");
    for (int i = 0; i < s.length() - 1; i++) result.add(s.substring(i, i + 2));
    for (String term : normalized.split("[^a-z0-9]+"))
      if (term.length() > 1) result.add(term);
    return result;
  }

  static double reciprocalRank(List<String> order, String id) {
    int rank = order.indexOf(id);
    return rank < 0 ? 0 : 1.0 / (61 + rank);
  }

  static String inferProduct(String text) {
    var matches = new ArrayList<String>();
    String[] ids = {
      "lock-s1",
      "camera-c2",
      "bulb-l1",
      "plug-p1",
      "hub-h1",
      "sensor-t1",
    };
    String[] patterns = {
      "(?i)(?<![a-z0-9])S1(?![a-z0-9])|智安|门锁",
      "(?i)(?<![a-z0-9])C2(?![a-z0-9])|守望|摄像",
      "(?i)(?<![a-z0-9])L1(?![a-z0-9])|晨光|灯泡",
      "(?i)(?<![a-z0-9])P1(?![a-z0-9])|轻控|插座",
      "(?i)(?<![a-z0-9])H1(?![a-z0-9])|联家|网关",
      "(?i)(?<![a-z0-9])T1(?![a-z0-9])|知温|温湿度",
    };
    for (int i = 0; i < ids.length; i++) if (
      java.util.regex.Pattern.compile(patterns[i]).matcher(text).find()
    ) matches.add(ids[i]);
    return matches.size() == 1 ? matches.get(0) : "all";
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
