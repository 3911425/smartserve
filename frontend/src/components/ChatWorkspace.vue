<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import {
  ArrowUpRight,
  Check,
  Headphones,
  Leaf,
  MessageCircle,
  Plus,
  RefreshCw,
  Send,
  Square,
  ThumbsDown,
  ThumbsUp,
} from "lucide-vue-next";
import { marked } from "marked";
import DOMPurify from "dompurify";
import { api, streamRun } from "../api";
import { date, labels, money, type Entity } from "../format";
import OrderDetail from "./OrderDetail.vue";
const props = defineProps<{
  user: Entity;
  requestText: string;
  requestNonce: number;
  visible: boolean;
}>();
const emit = defineEmits(["changed"]);
const conversations = ref<Entity[]>([]),
  conversation = ref(""),
  messages = ref<Entity[]>([]),
  question = ref(""),
  sending = ref(false),
  activeRun = ref(""),
  progress = ref<Entity[]>([]),
  body = ref<HTMLElement | null>(null),
  selectedOrder = ref<Entity | null>(null);
const ticketOpen = ref(false),
  ticketsOpen = ref(false),
  tickets = ref<Entity[]>([]),
  ticketBusy = ref(false),
  feedback = ref<Record<string, boolean>>({});
const ticket = reactive({
  title: "智能家居服务咨询",
  details: "",
  orderId: "",
  contact: "",
  priority: "NORMAL",
  confirmed: false,
  requestKey: "",
});
let aborter: AbortController | undefined;
let pending: { text: string; key: string } | null = null;
const markdown = (text: string) =>
  DOMPurify.sanitize(marked.parse(text || "", { async: false }) as string);
const storageKey = () => `smartserve-conversation-${props.user.id}`;
async function scroll() {
  await nextTick();
  body.value?.scrollTo({ top: body.value.scrollHeight, behavior: "smooth" });
}
async function load() {
  try {
    conversations.value = (await api<Entity[]>("/conversations")).sort((a, b) =>
      String(b.createdAt).localeCompare(String(a.createdAt)),
    );
    const saved = sessionStorage.getItem(storageKey());
    if (saved && conversations.value.some((c) => c.id === saved))
      await select(saved);
    else if (conversations.value.length)
      await select(conversations.value[0].id);
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
async function create() {
  const c = await api("/conversations", "POST");
  conversations.value.unshift(c);
  conversation.value = c.id;
  messages.value = [];
  progress.value = [];
  sessionStorage.setItem(storageKey(), c.id);
  return c;
}
async function newConversation() {
  try {
    await create();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
async function myTickets() {
  try {
    tickets.value = await api<Entity[]>("/tickets");
    ticketsOpen.value = true;
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
async function select(id: string) {
  if (sending.value) return;
  conversation.value = id;
  sessionStorage.setItem(storageKey(), id);
  messages.value = await api<Entity[]>(`/conversations/${id}/messages`);
  progress.value = [];
  await scroll();
  const runs = await api<Entity[]>("/runs");
  const unfinished = runs.find(
    (r) => r.conversationId === id && ["RUNNING", "QUEUED"].includes(r.status),
  );
  if (unfinished) observe(unfinished.id);
}
async function refresh() {
  if (!conversation.value) return;
  messages.value = await api<Entity[]>(
    `/conversations/${conversation.value}/messages`,
  );
  await scroll();
}
async function observe(id: string) {
  sending.value = true;
  activeRun.value = id;
  aborter = new AbortController();
  try {
    await streamRun(
      id,
      (event) => {
        progress.value.push(event);
        scroll();
      },
      aborter.signal,
    );
    const final = await api(`/runs/${id}`);
    if (final.status === "FAILED")
      ElMessage.warning(final.error || "任务未完成，可以重试或联系人工");
    pending = null;
    await refresh();
    emit("changed");
  } catch (e) {
    if (!aborter.signal.aborted) ElMessage.warning((e as Error).message);
  } finally {
    sending.value = false;
    activeRun.value = "";
    await refresh().catch(() => {});
  }
}
async function send() {
  const text = question.value.trim();
  if (!text || sending.value) return;
  if (text.length > 2000) return ElMessage.warning("消息请控制在2000字以内");
  sending.value = true;
  question.value = "";
  progress.value = [];
  if (!pending || pending.text !== text)
    pending = { text, key: crypto.randomUUID() };
  try {
    if (!conversation.value) await create();
    const run = await api("/runs", "POST", {
      conversationId: conversation.value,
      question: text,
      requestKey: pending.key,
    });
    messages.value = await api<Entity[]>(
      `/conversations/${conversation.value}/messages`,
    );
    await scroll();
    await observe(run.id);
  } catch (e) {
    ElMessage.error((e as Error).message);
    question.value = text;
    sending.value = false;
  }
}
async function cancel() {
  if (!activeRun.value) return;
  try {
    await api(`/runs/${activeRun.value}/cancel`, "POST");
    aborter?.abort();
    pending = null;
    ElMessage.info("任务已取消");
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
async function openOrder(id: string) {
  try {
    selectedOrder.value = await api(`/orders/${id}`);
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
function openTicket(message?: Entity) {
  Object.assign(ticket, {
    title: "智能家居服务咨询",
    details:
      messages.value.filter((m) => m.role === "user").at(-1)?.content || "",
    orderId: message?.orderCard?.id || "",
    contact: props.user.phone || "",
    priority: "NORMAL",
    confirmed: false,
    requestKey: crypto.randomUUID(),
  });
  ticketOpen.value = true;
}
async function submitTicket() {
  if (!ticket.confirmed) return ElMessage.warning("请核对并勾选确认信息");
  ticketBusy.value = true;
  try {
    if (!conversation.value) await create();
    const result = await api("/tickets", "POST", {
      ...ticket,
      conversationId: conversation.value,
    });
    ticketOpen.value = false;
    ElMessage.success("人工服务单已提交：" + result.id);
    await refresh();
    emit("changed");
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    ticketBusy.value = false;
  }
}
async function rate(id: string, helpful: boolean) {
  try {
    await api(`/runs/${id}/feedback`, "POST", { helpful, comment: "" });
    feedback.value[id] = helpful;
    ElMessage.success("谢谢反馈，服务管理员会查看");
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
function stage() {
  const event = progress.value.at(-1);
  if (!event) return "正在准备服务…";
  return (
    (
      {
        queued: "正在等待服务",
        routing: "正在理解你的问题",
        tool: "正在核验资料与业务记录",
        retrieval: "正在查找相关资料",
        model: "正在分析与整理",
        validation: "正在核对回答依据",
        completed: "回答已完成",
        fallback: "正在使用可核验的资料回复",
      } as Record<string, string>
    )[event.type] || "正在处理你的问题"
  );
}
watch(
  () => props.requestNonce,
  async () => {
    if (props.requestText && !sending.value) {
      question.value = props.requestText;
      await send();
    }
  },
);
watch(
  () => props.visible,
  (value) => {
    if (value && !sending.value) refresh().catch(() => {});
  },
);
function onFocus() {
  if (props.visible && !sending.value) refresh().catch(() => {});
}
onMounted(() => {
  load();
  window.addEventListener("focus", onFocus);
});
onUnmounted(() => {
  aborter?.abort();
  window.removeEventListener("focus", onFocus);
});
</script>
<template>
  <section class="chat-workspace page-enter">
    <aside class="conversation-sidebar">
      <div class="sidebar-heading">
        <span class="eyebrow">YOUR SERVICE SPACE</span>
        <h2>智服会话</h2>
        <button class="secondary" :disabled="sending" @click="newConversation">
          <Plus :size="16" />开始新会话
        </button>
      </div>
      <div class="conversation-list">
        <button
          v-for="c in conversations"
          :key="c.id"
          :class="{ active: c.id === conversation }"
          :disabled="sending"
          @click="select(c.id)"
        >
          <MessageCircle :size="17" /><span
            >{{ c.title }}<small>{{ date(c.createdAt) }}</small></span
          >
        </button>
        <p v-if="!conversations.length" class="muted">
          从第一个问题开始，记录会留在你的账号里。
        </p>
      </div>
      <div class="sidebar-note">
        <Leaf :size="21" />
        <p>
          一个会话，一件事。<br /><small
            >产品、订单与人工回复都能接着聊。</small
          >
        </p>
      </div>
    </aside>
    <div class="chat-panel">
      <div class="mobile-conversations">
        <select
          :value="conversation"
          :disabled="sending"
          aria-label="选择客服会话"
          @change="select(($event.target as HTMLSelectElement).value)"
        >
          <option value="" disabled>选择会话</option>
          <option v-for="c in conversations" :value="c.id" :key="c.id">
            {{ c.title }}
          </option>
        </select>
        <button class="secondary" :disabled="sending" @click="newConversation">
          <Plus :size="16" />新会话
        </button>
      </div>
      <div class="chat-heading">
        <div class="service-avatar"><Leaf :size="23" /></div>
        <div>
          <h2>智服 · 你的家居顾问</h2>
          <p><i class="live-dot"></i>选购、使用与售后，在这里继续</p>
        </div>
        <div class="chat-heading-actions">
          <button class="text-button" @click="myTickets">我的服务单</button>
          <button
            class="icon-button"
            title="刷新会话"
            aria-label="刷新会话"
            @click="refresh"
          >
            <RefreshCw :size="18" /></button
          ><button class="secondary" @click="openTicket()">
            <Headphones :size="16" />联系人工
          </button>
        </div>
      </div>
      <div ref="body" class="chat-body">
        <div v-if="!messages.length" class="chat-welcome">
          <span class="welcome-leaf"><Leaf :size="32" /></span
          ><span class="eyebrow">A THOUGHTFUL ANSWER STARTS HERE</span>
          <h1>你好，{{ user.displayName }}。<br />今天有什么想了解的？</h1>
          <p>
            告诉我你的设备、使用场景或订单问题。<br />需要核验的信息，我会先问清楚。
          </p>
          <div class="suggestions">
            <button
              v-for="text in [
                'S1门锁没电了怎么应急开门？',
                '我家只有5GHz Wi-Fi，怎么选择设备？',
                '退款需要哪些条件和确认步骤？',
              ]"
              :key="text"
              @click="
                question = text;
                send();
              "
            >
              {{ text }}<ArrowUpRight :size="17" />
            </button>
          </div>
        </div>
        <article
          v-for="m in messages"
          :key="m.id"
          class="message"
          :class="m.role === 'user' ? 'outgoing' : 'incoming'"
        >
          <span v-if="m.role !== 'user'" class="message-avatar"
            ><Headphones v-if="m.role === 'human'" :size="18" /><Leaf
              v-else
              :size="18"
          /></span>
          <div class="message-main">
            <span class="message-author"
              >{{
                m.role === "user"
                  ? user.displayName
                  : m.role === "human"
                    ? "人工服务专员"
                    : "智服"
              }}
              <small>{{ date(m.createdAt) }}</small></span
            >
            <div class="message-bubble">
              <div v-if="m.role === 'user'" class="plain-message">
                {{ m.content }}
              </div>
              <div v-else class="markdown" v-html="markdown(m.content)"></div>
              <details v-if="m.sources?.length" class="source-list">
                <summary>查看回答依据 · {{ m.sources.length }} 条资料</summary>
                <div v-for="source in m.sources" :key="source.id">
                  <b>[{{ source.citation }}] {{ source.heading }}</b
                  ><small
                    >{{ source.source }} · 版本
                    {{ source.documentVersion || "2.0" }}</small
                  >
                  <p>{{ source.content }}</p>
                </div>
              </details>
              <div v-if="m.orderCard" class="assistant-card">
                <span class="eyebrow">订单核验结果</span>
                <h4>{{ m.orderCard.productName }}</h4>
                <p>{{ m.orderCard.id }} · {{ labels[m.orderCard.status] }}</p>
                <button class="text-button" @click="openOrder(m.orderCard.id)">
                  查看订单详情<ArrowUpRight :size="15" />
                </button>
              </div>
              <div v-if="m.refundProposal" class="assistant-card">
                <span class="eyebrow">售后建议 · 尚未提交申请</span>
                <h4>
                  {{
                    m.refundProposal.eligible
                      ? "符合当前自动申请窗口"
                      : "需要进一步核验"
                  }}
                </h4>
                <p>
                  {{ m.refundProposal.orderId }} · ¥{{
                    money(m.refundProposal.amount)
                  }}
                </p>
                <p class="muted">
                  {{
                    m.refundProposal.fundingSource === "LEGACY_DEMO"
                      ? "历史演示订单不会增加钱包余额。"
                      : "审核通过后，模拟余额才会退回钱包。"
                  }}
                </p>
                <button
                  class="secondary"
                  @click="openOrder(m.refundProposal.orderId)"
                >
                  核对订单与申请
                </button>
              </div>
              <button
                v-if="m.needsEscalation"
                class="secondary"
                @click="openTicket(m)"
              >
                <Headphones :size="15" />提交人工服务单
              </button>
            </div>
            <div
              v-if="m.role === 'assistant' && m.runId"
              class="message-feedback"
            >
              <span>这次回应解决了问题吗？</span
              ><button
                :class="{ selected: feedback[m.runId] === true }"
                aria-label="有帮助"
                @click="rate(m.runId, true)"
              >
                <ThumbsUp :size="14" /></button
              ><button
                :class="{ selected: feedback[m.runId] === false }"
                aria-label="没有解决"
                @click="rate(m.runId, false)"
              >
                <ThumbsDown :size="14" />
              </button>
            </div>
          </div>
        </article>
        <div v-if="sending" class="service-progress">
          <span class="typing"><i></i><i></i><i></i></span
          ><span>{{ stage() }}</span
          ><button class="text-button" @click="cancel">
            <Square :size="12" />取消
          </button>
        </div>
      </div>
      <form class="chat-composer" @submit.prevent="send">
        <textarea
          v-model="question"
          maxlength="2000"
          rows="2"
          placeholder="描述产品、订单或使用问题，Shift + Enter 换行"
          aria-label="发送给智服的问题"
          :disabled="sending"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <div>
          <span>回答基于可核验资料，信息不足时会进一步询问。</span
          ><button
            class="primary"
            :disabled="sending || !question.trim()"
            aria-label="发送消息"
          >
            <Send :size="18" />
          </button>
        </div>
      </form>
    </div>
    <el-drawer
      v-model="ticketsOpen"
      title="我的人工服务单"
      size="min(560px,96vw)"
    >
      <p class="muted">人工服务为异步处理，完成结论也会回写原会话。</p>
      <article v-for="item in tickets" :key="item.id" class="panel">
        <div class="panel-heading">
          <h3>{{ item.title }}</h3>
          <span class="status-pill">{{ labels[item.status] }}</span>
        </div>
        <small>{{ item.id }} · {{ date(item.createdAt) }}</small>
        <p>{{ item.details }}</p>
        <p v-if="item.resolution" class="notice">{{ item.resolution }}</p>
        <button
          class="text-button"
          :disabled="sending"
          @click="
            ticketsOpen = false;
            select(item.conversationId);
          "
        >
          返回原会话<ArrowUpRight :size="15" />
        </button>
      </article>
      <div v-if="!tickets.length" class="empty">你还没有提交人工服务单</div>
    </el-drawer>
    <el-dialog
      v-model="ticketOpen"
      title="把问题交给人工专员"
      width="min(570px,92vw)"
      ><form class="form-stack" @submit.prevent="submitTicket">
        <label
          >问题标题<input
            v-model="ticket.title"
            maxlength="100"
            required /></label
        ><label
          >具体情况<textarea
            v-model="ticket.details"
            rows="4"
            maxlength="2000"
            required
          ></textarea></label
        ><label
          >关联订单号（选填）<input
            v-model="ticket.orderId"
            maxlength="40"
            placeholder="SS 开头的本人订单号" /></label
        ><label
          >联系方式<input
            v-model="ticket.contact"
            maxlength="100"
            required /></label
        ><label
          >优先级<select v-model="ticket.priority">
            <option value="NORMAL">普通</option>
            <option value="HIGH">需要优先关注</option>
          </select></label
        ><label class="checkbox-label"
          ><input
            v-model="ticket.confirmed"
            type="checkbox"
            required
          />我已核对信息，确认提交人工服务单。</label
        >
        <p class="muted">
          专员的处理结果会回复到当前会话。填写表单期间不会自动提交。
        </p>
        <button class="primary" :disabled="ticketBusy">
          {{ ticketBusy ? "正在提交…" : "确认提交" }}
        </button>
      </form></el-dialog
    >
    <el-dialog
      :model-value="!!selectedOrder"
      title="核对订单"
      width="min(750px,94vw)"
      @update:model-value="
        (value: boolean) => {
          if (!value) selectedOrder = null;
        }
      "
      ><OrderDetail
        v-if="selectedOrder"
        :order="selectedOrder"
        @updated="
          (order) => {
            selectedOrder = order;
            emit('changed');
          }
        "
        @ask="
          (text) => {
            selectedOrder = null;
            question = text;
            send();
          }
        "
    /></el-dialog>
  </section>
</template>
