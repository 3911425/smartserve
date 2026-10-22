<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Activity, ArrowRight, BookOpen, RefreshCw } from "lucide-vue-next";
import { ElMessage } from "element-plus";
import { api } from "../api";
import { date, labels, type Entity } from "../format";
const runs = ref<Entity[]>([]),
  selected = ref<Entity | null>(null),
  events = ref<Entity[]>([]),
  calls = ref<Entity[]>([]),
  documents = ref<Entity[]>([]),
  knowledge = ref<Entity>({}),
  knowledgeOpen = ref(false),
  indexing = ref(false);
const completed = computed(() =>
  runs.value.filter((r) => r.status === "COMPLETED"),
);
const tokens = computed(() =>
  runs.value.reduce((sum, r) => sum + Number(r.tokens || 0), 0),
);
const attempts = computed(() =>
  runs.value.reduce((sum, r) => sum + Number(r.modelCalls || 0), 0),
);
const latency = computed(() => {
  const values = completed.value
    .filter((r) => r.completedAt)
    .map(
      (r) =>
        new Date(r.completedAt).getTime() - new Date(r.createdAt).getTime(),
    );
  return values.length
    ? (values.reduce((a, b) => a + b, 0) / values.length / 1000).toFixed(1) +
        "s"
    : "—";
});
async function load() {
  try {
    runs.value = await api<Entity[]>("/runs");
    knowledge.value = (await api("/status")).knowledge;
    if (selected.value) {
      const fresh = runs.value.find((r) => r.id === selected.value?.id);
      if (fresh) await select(fresh);
    }
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
async function select(run: Entity) {
  selected.value = run;
  [events.value, calls.value] = await Promise.all([
    api<Entity[]>(`/runs/${run.id}/events`),
    api<Entity[]>(`/runs/${run.id}/model-calls`),
  ]);
}
async function openKnowledge() {
  documents.value = await api<Entity[]>("/knowledge");
  knowledgeOpen.value = true;
}
async function reindex() {
  indexing.value = true;
  try {
    knowledge.value = await api("/knowledge/reindex", "POST");
    documents.value = await api<Entity[]>("/knowledge");
    ElMessage.success("知识索引已更新，旧查询缓存自动失效");
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    indexing.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section class="page-enter">
    <div class="section-heading">
      <div>
        <span class="eyebrow">OBSERVE. VERIFY. IMPROVE.</span>
        <h1>AgentOps 运行观测</h1>
        <p class="muted">
          查看可观察的执行事件与逐次模型用量，不展示隐藏推理。
        </p>
      </div>
      <div class="button-row">
        <button class="secondary" @click="openKnowledge">
          <BookOpen :size="16" />知识管理</button
        ><button class="secondary" @click="load">
          <RefreshCw :size="16" />刷新
        </button>
      </div>
    </div>
    <div class="metrics">
      <div>
        <span>当前运行记录</span><b>{{ runs.length }}</b
        ><small>最近 100 条可访问任务</small>
      </div>
      <div>
        <span>已完成任务</span
        ><b
          >{{ completed.length }} <small>/ {{ runs.length }}</small></b
        ><small>流程完成不等于质量评测通过</small>
      </div>
      <div>
        <span>平均端到端时间</span><b>{{ latency }}</b
        ><small>包含排队与所有处理步骤</small>
      </div>
      <div>
        <span>模型请求尝试 / 已知 Token</span
        ><b
          >{{ attempts }} <small>/ {{ tokens.toLocaleString() }}</small></b
        ><small>失败请求的未知用量不记作零成本</small>
      </div>
    </div>
    <div class="ops-layout">
      <aside class="panel run-list">
        <h3>运行记录</h3>
        <button
          v-for="run in runs"
          :key="run.id"
          :class="{ active: run.id === selected?.id }"
          @click="select(run)"
        >
          <Activity :size="17" /><span
            ><b>{{ run.question }}</b
            ><small
              >{{ date(run.createdAt) }} · {{ labels[run.status] }}</small
            ></span
          ><i class="run-dot" :class="run.status.toLowerCase()"></i>
        </button>
        <div v-if="!runs.length" class="empty">
          <Activity :size="30" />
          <p>发送一条客服消息，便会生成真实执行记录。</p>
        </div>
      </aside>
      <div class="panel run-detail">
        <template v-if="selected"
          ><div class="panel-heading">
            <div>
              <h3>执行时间线</h3>
              <small>{{ selected.id }}</small>
            </div>
            <span class="status-pill">{{ labels[selected.status] }}</span>
          </div>
          <div class="flow-strip">
            <span>Supervisor</span><ArrowRight :size="14" /><span>{{
              (selected.routes || [])
                .map((r: string) => labels[r])
                .join(" + ") || "等待路由"
            }}</span
            ><ArrowRight :size="14" /><span>Guardrail</span>
          </div>
          <p class="trace-id">
            Trace ID <code>{{ selected.traceId }}</code>
          </p>
          <div class="timeline">
            <div
              v-for="event in events"
              :key="event.eventId"
              class="timeline-item"
            >
              <span class="timeline-dot" :class="event.status"></span>
              <div>
                <div class="timeline-title">
                  <b>{{ event.agent }}</b
                  ><small>{{
                    event.durationMs != null
                      ? event.durationMs + " ms"
                      : date(event.at)
                  }}</small>
                </div>
                <p>{{ event.label }}</p>
                <details v-if="event.input">
                  <summary>{{ event.tool }} · 工具输入</summary>
                  <pre>{{ JSON.stringify(event.input, null, 2) }}</pre>
                </details>
                <details v-if="event.candidates?.length">
                  <summary>候选证据与过滤结果</summary>
                  <div
                    v-for="candidate in event.candidates"
                    :key="candidate.id"
                    class="retrieval-result"
                  >
                    <b>{{ candidate.heading }}</b
                    ><small
                      >{{ candidate.productCode || "通用" }} · 综合
                      {{ candidate.score }} · 语义
                      {{ candidate.semanticScore }} ·
                      {{
                        candidate.accepted ? "通过门槛" : "未通过门槛"
                      }}</small
                    >
                    <p>{{ candidate.content }}</p>
                  </div>
                </details>
              </div>
            </div>
          </div>
          <h3 class="subheading">逐次模型请求</h3>
          <div v-if="!calls.length" class="notice">
            这次任务没有发送模型请求，可能使用了订单快捷路径或离线模式。
          </div>
          <div v-else class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>节点 / 用途</th>
                  <th>尝试</th>
                  <th>输入 / 输出 Token</th>
                  <th>耗时</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="call in calls" :key="call.id">
                  <td>
                    {{ call.node }}<small>{{ call.purpose }}</small>
                  </td>
                  <td>{{ call.attempt }}</td>
                  <td>
                    {{ call.input_tokens ?? "未知" }} /
                    {{ call.output_tokens ?? "未知" }}
                  </td>
                  <td>{{ call.duration_ms }} ms</td>
                  <td>{{ labels[call.status] || call.status }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="notice">
            这里是运行观测。RAG
            与任务质量需要固定题集、明确评分和回归报告，不能用“任务完成”代替正确率。
          </div></template
        >
        <div v-else class="empty">
          <Activity :size="35" />
          <h3>选择一次运行</h3>
          <p>检查路由、工具、证据、降级与模型请求。</p>
        </div>
      </div>
    </div>
    <el-drawer
      v-model="knowledgeOpen"
      title="知识与索引管理"
      size="min(780px,95vw)"
      ><div class="knowledge-summary">
        <div>
          <span class="eyebrow">CURRENT KNOWLEDGE</span>
          <h2>
            {{ knowledge.documents }} 份文档 · {{ knowledge.chunks }} 个片段
          </h2>
          <p>{{ knowledge.embeddingModel }}</p>
          <p>{{ knowledge.vectorStore }} · {{ knowledge.revision }}</p>
        </div>
        <button class="primary" :disabled="indexing" @click="reindex">
          {{ indexing ? "正在构建索引…" : "重新索引" }}
        </button>
      </div>
      <p class="notice">
        只对项目维护的知识文件重新建立索引。用户反馈不会自动写入知识库。
      </p>
      <details
        v-for="document in documents"
        :key="document.name"
        class="knowledge-document"
      >
        <summary>
          {{ document.name }} <small>{{ document.chunks }} 个片段</small>
        </summary>
        <pre>{{ document.content }}</pre>
      </details></el-drawer
    >
  </section>
</template>
