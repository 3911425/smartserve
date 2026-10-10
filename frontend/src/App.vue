<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { marked } from "marked";
import DOMPurify from "dompurify";
import {
  ShoppingBag,
  Package,
  MessageCircle,
  Activity,
  Headphones,
  Search,
  ArrowRight,
  ArrowUpRight,
  Sparkles,
  ShieldCheck,
  Truck,
  Heart,
  ChevronDown,
  Send,
  Plus,
  BookOpen,
  Check,
  Clock,
  RefreshCw,
  X,
  Leaf,
  Layers,
  Zap,
  UserRound,
  FileText,
  AlertCircle,
  CheckCircle2,
  SlidersHorizontal,
  ChevronRight,
  ExternalLink,
} from "lucide-vue-next";
import { api, setToken, streamRun, type Entity } from "./api";

const tab = ref("store"),
  role = ref(sessionStorage.getItem("smartserve-role") || "customer"),
  status = ref<Entity>({}),
  ready = ref(false),
  error = ref("");
const products = ref<Entity[]>([]),
  orders = ref<Entity[]>([]),
  tickets = ref<Entity[]>([]),
  runs = ref<Entity[]>([]),
  documents = ref<Entity[]>([]),
  conversations = ref<Entity[]>([]);
const category = ref("全部商品"),
  search = ref(""),
  sort = ref("featured"),
  orderFilter = ref("ALL"),
  ticketFilter = ref("ALL");
const favorites = ref<string[]>(
  JSON.parse(localStorage.getItem("smartserve-favorites") || "[]"),
);
const selectedProduct = ref<Entity | null>(null),
  productOpen = ref(false),
  knowledgeOpen = ref(false),
  documentIndex = ref(0);
const conversationId = ref(""),
  messages = ref<Entity[]>([]),
  question = ref(""),
  sending = ref(false),
  progress = ref<Entity[]>([]),
  activeRun = ref(""),
  chatBody = ref<HTMLElement>();
const selectedRun = ref<Entity | null>(null),
  runEvents = ref<Entity[]>([]),
  ticketOpen = ref(false),
  ticketDraftKey = ref(""),
  ticketBusy = ref(false);
const ticketForm = ref({
  title: "",
  details: "",
  orderId: "",
  contact: "",
  priority: "NORMAL",
  confirmed: false,
});
const selectedTicket = ref<Entity | null>(null),
  ticketDetailOpen = ref(false),
  ticketMessages = ref<Entity[]>([]),
  resolution = ref(""),
  internalNote = ref(""),
  processing = ref(false);
const myTicketsOpen = ref(false),
  statusOpen = ref(false);
let aborter: AbortController | undefined;
const tabs = computed(() => [
  { id: "store", label: "发现好物", icon: ShoppingBag },
  { id: "orders", label: "我的订单", icon: Package },
  { id: "chat", label: "智服客服", icon: MessageCircle },
  { id: "ops", label: "AgentOps", icon: Activity },
  ...(role.value === "admin"
    ? [{ id: "tickets", label: "人工处理中心", icon: Headphones }]
    : []),
]);
const categories = ["全部商品", "家庭安防", "氛围照明", "生活智控"];
const filteredProducts = computed(() => {
  const items = products.value.filter(
    (p) =>
      (category.value === "全部商品" || p.category === category.value) &&
      (p.name + p.description + p.features.join("")).includes(search.value),
  );
  return sort.value === "priceLow"
    ? [...items].sort((a, b) => a.price - b.price)
    : sort.value === "priceHigh"
      ? [...items].sort((a, b) => b.price - a.price)
      : items;
});
const filteredOrders = computed(() =>
  orders.value.filter(
    (o) => orderFilter.value === "ALL" || o.status === orderFilter.value,
  ),
);
const filteredTickets = computed(() =>
  tickets.value.filter(
    (t) => ticketFilter.value === "ALL" || t.status === ticketFilter.value,
  ),
);
const completedRuns = computed(() =>
  runs.value.filter((r) => r.status === "COMPLETED"),
);
const averageLatency = computed(() => {
  const vals = completedRuns.value
    .filter((r) => r.completedAt)
    .map(
      (r) =>
        new Date(r.completedAt).getTime() - new Date(r.createdAt).getTime(),
    );
  return vals.length
    ? (vals.reduce((a, b) => a + b, 0) / vals.length / 1000).toFixed(1) + "s"
    : "—";
});
const tokenTotal = computed(() =>
  runs.value.reduce((sum, r) => sum + Number(r.tokens || 0), 0),
);
const statusLabels: Record<string, string> = {
  DELIVERED: "已签收",
  SHIPPED: "运输中",
  REFUND_PENDING: "退款待处理",
  PENDING: "待处理",
  PROCESSING: "处理中",
  RESOLVED: "已完成",
  QUEUED: "排队中",
  RUNNING: "执行中",
  COMPLETED: "已完成",
  FAILED: "未完成",
  AWAITING_CONFIRMATION: "待你确认",
  APPROVED: "申请已确认",
};
const routeLabels: Record<string, string> = {
  PRODUCT: "产品专家",
  ORDER: "订单专家",
  AFTER_SALES: "售后专家",
  ESCALATE: "人工协作",
};
function markdown(text: string) {
  return DOMPurify.sanitize(marked.parse(text, { async: false }) as string);
}
function money(value: unknown) {
  return Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
}
function date(value: string) {
  return value
    ? new Date(value).toLocaleString("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      })
    : "—";
}
function toggleFavorite(id: string) {
  favorites.value = favorites.value.includes(id)
    ? favorites.value.filter((x) => x !== id)
    : [...favorites.value, id];
  localStorage.setItem("smartserve-favorites", JSON.stringify(favorites.value));
}
async function loadBusiness() {
  const values = await Promise.all([
    api<Entity[]>("/products"),
    api<Entity[]>("/orders"),
    api<Entity[]>("/tickets"),
    api<Entity[]>("/runs"),
    api<Entity[]>("/conversations"),
  ]);
  [
    products.value,
    orders.value,
    tickets.value,
    runs.value,
    conversations.value,
  ] = values;
}
async function changeRole(value: string) {
  if (sending.value) return ElMessage.info("请等待当前任务完成后切换角色");
  try {
    const session = await api("/demo/session", "POST", { role: value });
    setToken(session.token);
    role.value = session.role;
    sessionStorage.setItem("smartserve-role", role.value);
    conversationId.value = "";
    messages.value = [];
    progress.value = [];
    selectedRun.value = null;
    tab.value = "store";
    await loadBusiness();
    ElMessage.success(
      value === "admin" ? "已进入管理员演示模式" : "已进入客户演示模式",
    );
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
async function initialize() {
  error.value = "";
  try {
    status.value = await api("/status");
    if (status.value.demoAccess) {
      const session = await api("/demo/session", "POST", { role: role.value });
      setToken(session.token);
    }
    await loadBusiness();
    documents.value = await api<Entity[]>("/knowledge");
    const saved = sessionStorage.getItem(
      "smartserve-conversation-" + role.value,
    );
    if (saved && conversations.value.some((c) => c.id === saved)) {
      conversationId.value = saved;
      messages.value = await api<Entity[]>(`/conversations/${saved}/messages`);
    }
    ready.value = true;
  } catch (e) {
    error.value = (e as Error).message;
  }
}
async function newConversation() {
  if (sending.value) return;
  const c = await api("/conversations", "POST");
  conversationId.value = c.id;
  messages.value = [];
  progress.value = [];
  sessionStorage.setItem("smartserve-conversation-" + role.value, c.id);
  conversations.value.push(c);
}
async function loadConversation(id: string) {
  if (sending.value) return;
  conversationId.value = id;
  messages.value = await api<Entity[]>(`/conversations/${id}/messages`);
  sessionStorage.setItem("smartserve-conversation-" + role.value, id);
  scrollChat();
}
async function scrollChat() {
  await nextTick();
  chatBody.value?.scrollTo({
    top: chatBody.value.scrollHeight,
    behavior: "smooth",
  });
}
async function ask(text: string) {
  tab.value = "chat";
  question.value = text;
  await nextTick();
  await send();
}
async function send() {
  const text = question.value.trim();
  if (!text || sending.value) return;
  if (text.length > 2000) return ElMessage.warning("消息请控制在2000字以内");
  sending.value = true;
  question.value = "";
  progress.value = [];
  aborter = new AbortController();
  try {
    if (!conversationId.value) {
      const c = await api("/conversations", "POST");
      conversationId.value = c.id;
      conversations.value.push(c);
      sessionStorage.setItem("smartserve-conversation-" + role.value, c.id);
    }
    const run = await api("/runs", "POST", {
      conversationId: conversationId.value,
      question: text,
      requestKey: crypto.randomUUID(),
    });
    activeRun.value = run.id;
    messages.value.push({
      id: run.id + "user",
      role: "user",
      content: text,
      createdAt: new Date().toISOString(),
    });
    await scrollChat();
    await streamRun(
      run.id,
      (event) => {
        progress.value.push(event);
        scrollChat();
      },
      aborter.signal,
    );
    const final = await api(`/runs/${run.id}`);
    if (final.status === "FAILED") ElMessage.warning(final.error);
    messages.value = await api<Entity[]>(
      `/conversations/${conversationId.value}/messages`,
    );
    runs.value = await api<Entity[]>("/runs");
    orders.value = await api<Entity[]>("/orders");
  } catch (e) {
    ElMessage.error((e as Error).message);
    question.value = text;
  } finally {
    sending.value = false;
    scrollChat();
  }
}
async function refreshMessages() {
  if (conversationId.value && !sending.value) {
    messages.value = await api<Entity[]>(
      `/conversations/${conversationId.value}/messages`,
    );
    tickets.value = await api<Entity[]>("/tickets");
    scrollChat();
  }
}
function viewProduct(p: Entity) {
  selectedProduct.value = p;
  productOpen.value = true;
}
function consultProduct(p: Entity) {
  productOpen.value = false;
  ask(`请介绍${p.name}的主要功能、联网要求和保修。`);
}
function openTicket(message?: Entity) {
  ticketDraftKey.value = message?.ticketDraftKey || crypto.randomUUID();
  ticketForm.value = {
    title: "智能家居服务咨询",
    details:
      messages.value.filter((m) => m.role === "user").at(-1)?.content || "",
    orderId: message?.orderCard?.id || "",
    contact: "",
    priority: "NORMAL",
    confirmed: false,
  };
  ticketOpen.value = true;
}
async function submitTicket() {
  if (!ticketForm.value.confirmed)
    return ElMessage.warning("请核对并确认表单信息");
  if (
    !ticketForm.value.title.trim() ||
    !ticketForm.value.details.trim() ||
    !ticketForm.value.contact.trim()
  )
    return ElMessage.warning("请填写问题标题、详情和联系方式");
  ticketBusy.value = true;
  try {
    if (!conversationId.value) await newConversation();
    const t = await api("/tickets", "POST", {
      ...ticketForm.value,
      conversationId: conversationId.value,
      requestKey: ticketDraftKey.value,
      confirmed: true,
    });
    ticketOpen.value = false;
    ElMessage.success("服务升级单已提交：" + t.id);
    await refreshMessages();
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    ticketBusy.value = false;
  }
}
async function confirmRefund(refund: Entity) {
  try {
    await ElMessageBox.confirm(
      `确认订单 ${refund.orderId} 的 ¥${money(refund.amount)} 退款申请？这是模拟资金处理，后续仍需退货质检。`,
      "核对退款申请",
      {
        confirmButtonText: "确认申请",
        cancelButtonText: "再考虑一下",
        type: "info",
      },
    );
    const result = await api(`/refunds/${refund.id}/confirm`, "POST", {
      confirmed: true,
    });
    refund.status = result.status;
    orders.value = await api<Entity[]>("/orders");
    ElMessage.success("申请已确认，进入模拟退款待处理");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error((e as Error).message);
  }
}
async function showRun(run: Entity) {
  selectedRun.value = run;
  runEvents.value = await api<Entity[]>(`/runs/${run.id}/events`);
}
async function refreshOps() {
  runs.value = await api<Entity[]>("/runs");
  if (selectedRun.value) {
    selectedRun.value =
      runs.value.find((r) => r.id === selectedRun.value?.id) ||
      selectedRun.value;
    runEvents.value = await api<Entity[]>(
      `/runs/${selectedRun.value.id}/events`,
    );
  }
}
async function viewTicket(ticket: Entity) {
  selectedTicket.value = ticket;
  resolution.value = ticket.resolution || "";
  internalNote.value = "";
  ticketMessages.value = await api<Entity[]>(
    `/conversations/${ticket.conversationId}/messages`,
  );
  ticketDetailOpen.value = true;
}
async function updateTicket(state: string) {
  if (!selectedTicket.value) return;
  processing.value = true;
  try {
    selectedTicket.value = await api(
      `/tickets/${selectedTicket.value.id}`,
      "PUT",
      {
        status: state,
        resolution: resolution.value,
        note: internalNote.value,
        version: selectedTicket.value.version || 0,
      },
    );
    tickets.value = await api<Entity[]>("/tickets");
    internalNote.value = "";
    ElMessage.success(
      state === "RESOLVED" ? "处理结果已回写用户会话" : "已认领服务升级单",
    );
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    processing.value = false;
  }
}
async function reindex() {
  try {
    status.value.knowledge = await api("/knowledge/reindex", "POST");
    documents.value = await api<Entity[]>("/knowledge");
    ElMessage.success("知识库索引已更新");
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
function focusRefresh() {
  if (ready.value && tab.value === "chat") refreshMessages().catch(() => {});
}
onMounted(() => {
  initialize();
  window.addEventListener("focus", focusRefresh);
});
onUnmounted(() => {
  aborter?.abort();
  window.removeEventListener("focus", focusRefresh);
});
</script>

<template>
  <div class="application">
    <header class="header">
      <a class="brand" href="#" @click.prevent="tab = 'store'"
        ><span class="brand-mark"><Leaf :size="24" /></span
        ><span
          >SmartServe<span class="brand-cn">智服</span
          ><small>SMART HOME, THOUGHTFUL SERVICE</small></span
        ></a
      >
      <nav class="navigation" aria-label="主导航">
        <button
          v-for="item in tabs"
          :key="item.id"
          :class="{ active: tab === item.id }"
          @click="tab = item.id"
        >
          <component :is="item.icon" :size="17" /><span>{{ item.label }}</span
          ><i v-if="item.id === 'chat'" class="new-dot"></i
          ><span
            v-if="
              item.id === 'tickets' &&
              tickets.filter((t) => t.status === 'PENDING').length
            "
            class="count"
            >{{ tickets.filter((t) => t.status === "PENDING").length }}</span
          >
        </button>
      </nav>
      <div class="header-tools">
        <button
          class="icon-button knowledge-button"
          title="知识库"
          aria-label="打开知识库"
          @click="knowledgeOpen = true"
        >
          <BookOpen :size="20" />
        </button>
        <div class="header-divider"></div>
        <el-dropdown
          @command="changeRole"
          trigger="click"
          :disabled="!status.demoAccess || sending"
          ><button class="profile">
            <span class="avatar"><UserRound :size="17" /></span
            ><span
              >{{ role === "admin" ? "管理员" : "体验用户"
              }}<small>{{
                role === "admin" ? "服务运营" : "客户模式"
              }}</small></span
            ><ChevronDown :size="14" /></button
          ><template #dropdown
            ><el-dropdown-menu
              ><el-dropdown-item command="customer"
                >体验用户 · 客户模式</el-dropdown-item
              ><el-dropdown-item command="admin"
                >管理员 · 服务运营</el-dropdown-item
              ></el-dropdown-menu
            ></template
          ></el-dropdown
        >
      </div>
    </header>

    <main class="main">
      <div v-if="error" class="connection-error">
        <AlertCircle :size="28" />
        <h2>服务暂时没有连接</h2>
        <p>{{ error }}</p>
        <p>启动后端后，点击重新连接即可继续。</p>
        <button class="primary" @click="initialize">重新连接</button>
      </div>
      <div v-else-if="!ready" class="loading-page">
        <span class="loading-leaf"><Leaf :size="32" /></span>
        <p>正在准备你的智能家居服务空间…</p>
      </div>

      <template v-else>
        <section v-if="tab === 'store'" class="store-page page-enter">
          <div class="page-eyebrow">
            <span><span class="tiny-dot"></span> 用科技，照顾生活的细节</span
            ><button @click="statusOpen = true">
              <span class="online-dot"></span> 智服服务已就绪
              <ChevronRight :size="13" />
            </button>
          </div>
          <div class="hero">
            <img
              :src="'/files/images/smart-home-hero.png'"
              alt="智能门锁、摄像头与全屋智能设备的产品组合"
              class="hero-image"
            />
            <div class="hero-shade"></div>
            <div class="hero-copy">
              <span class="hero-label"
                ><Sparkles :size="13" /> A LITTLE SMARTER. A LOT MORE
                HOME.</span
              >
              <h1>家的每个细节，<br />都值得<span>聪明一点。</span></h1>
              <p>
                精选智能家居，让生活轻松一点。<br />从选购到使用，智服陪你解决每个问题。
              </p>
              <button
                class="primary hero-cta"
                @click="ask('我想给家里搭配一套智能家居，有什么推荐？')"
              >
                <MessageCircle :size="17" /> 让智服帮我选
                <ArrowUpRight :size="16" />
              </button>
            </div>
            <div class="hero-caption">
              <span class="online-dot"></span> 好产品，也有好服务
              <span>01 / 06</span>
            </div>
          </div>
          <div class="benefits">
            <div>
              <span class="benefit-icon"><ShieldCheck :size="23" /></span
              ><span
                ><b>安心选，放心用</b
                ><small>产品信息有据可查 · 售后政策透明</small></span
              >
            </div>
            <div>
              <span class="benefit-icon"><Sparkles :size="23" /></span
              ><span
                ><b>你的智能家居顾问</b
                ><small>产品、订单、售后，专业 Agent 为你解答</small></span
              >
            </div>
            <div>
              <span class="benefit-icon"><Headphones :size="23" /></span
              ><span
                ><b>每个问题，都有回应</b
                ><small>AI 与人工协作，服务结果持续跟进</small></span
              >
            </div>
          </div>
          <div class="section-heading">
            <div>
              <span class="overline">CURATED FOR YOUR HOME</span>
              <h2>精选好物 <span>让理想的家，触手可及</span></h2>
            </div>
            <button class="text-button" @click="knowledgeOpen = true">
              了解设备与服务 <ArrowUpRight :size="15" />
            </button>
          </div>
          <div class="catalog-toolbar">
            <div class="category-tabs">
              <button
                v-for="c in categories"
                :key="c"
                :class="{ selected: category === c }"
                @click="category = c"
              >
                {{ c
                }}<span v-if="c === '全部商品'">{{ products.length }}</span>
              </button>
            </div>
            <div class="catalog-controls">
              <label class="searchbox"
                ><Search :size="17" /><input
                  v-model="search"
                  placeholder="寻找适合你的设备"
                  aria-label="搜索商品" /><button
                  v-if="search"
                  @click="search = ''"
                  aria-label="清除搜索"
                >
                  <X :size="13" /></button></label
              ><select v-model="sort" aria-label="商品排序">
                <option value="featured">精选排序</option>
                <option value="priceLow">价格从低到高</option>
                <option value="priceHigh">价格从高到低</option>
              </select>
            </div>
          </div>
          <div class="product-grid">
            <article
              v-for="(p, index) in filteredProducts"
              :key="p.id"
              class="product-card"
            >
              <div
                class="product-visual"
                @click="viewProduct(p)"
                @keydown.enter="viewProduct(p)"
                tabindex="0"
                role="button"
                :aria-label="'查看' + p.name"
              >
                <span class="product-badge">{{
                  p.category === "家庭安防"
                    ? "守护家的安心"
                    : p.category === "氛围照明"
                      ? "点亮日常"
                      : "让生活更轻松"
                }}</span
                ><button
                  class="favorite"
                  :class="{ saved: favorites.includes(p.id) }"
                  @click.stop="toggleFavorite(p.id)"
                  :aria-label="
                    favorites.includes(p.id) ? '取消收藏' : '收藏商品'
                  "
                >
                  <Heart
                    :size="18"
                    :fill="favorites.includes(p.id) ? 'currentColor' : 'none'"
                  /></button
                ><img
                  :src="p.image"
                  :alt="p.name"
                  :loading="index < 3 ? 'eager' : 'lazy'"
                /><span class="visual-link"><ArrowUpRight :size="20" /></span>
              </div>
              <div class="product-content">
                <div class="product-type">
                  {{ p.category
                  }}<span><ShieldCheck :size="12" /> {{ p.warranty }}质保</span>
                </div>
                <button class="product-name" @click="viewProduct(p)">
                  {{ p.name }}
                </button>
                <p>{{ p.description }}</p>
                <div class="feature-tags">
                  <span v-for="f in p.features" :key="f">{{ f }}</span>
                </div>
                <div class="product-bottom">
                  <div class="price">
                    <span>¥</span>{{ money(p.price) }}<small>演示价格</small>
                  </div>
                  <button class="consult-button" @click="consultProduct(p)">
                    <MessageCircle :size="15" /> 咨询智服
                    <ArrowRight :size="14" />
                  </button>
                </div>
              </div>
            </article>
          </div>
          <div v-if="!filteredProducts.length" class="empty">
            <Search :size="30" />
            <h3>没有找到匹配的商品</h3>
            <p>试试设备名称，或换一个分类。</p>
            <button
              class="secondary"
              @click="
                search = '';
                category = '全部商品';
              "
            >
              查看全部商品
            </button>
          </div>
          <div class="service-banner">
            <div class="service-symbol">
              <MessageCircle :size="27" /><Sparkles :size="14" />
            </div>
            <div>
              <h3>好生活，从一个小问题开始。</h3>
              <p>不知道如何选、不会配网，或想查询订单？随时问问智服。</p>
            </div>
            <button class="secondary" @click="tab = 'chat'">
              和智服聊聊 <ArrowUpRight :size="16" />
            </button>
          </div>
        </section>

        <section v-if="tab === 'orders'" class="page-enter">
          <div class="section-heading page-heading">
            <div>
              <span class="overline">EVERY ORDER, TAKEN CARE OF</span>
              <h1>{{ role === "admin" ? "订单工作台" : "我的订单" }}</h1>
              <p>
                每一份期待，都有清晰的进展。{{
                  role === "admin" ? "当前可查看全部样例订单。" : ""
                }}
              </p>
            </div>
            <button class="secondary" @click="loadBusiness">
              <RefreshCw :size="16" /> 刷新订单
            </button>
          </div>
          <div class="status-filters">
            <button
              v-for="f in [
                ['ALL', '全部订单'],
                ['SHIPPED', '运输中'],
                ['DELIVERED', '已签收'],
                ['REFUND_PENDING', '售后中'],
              ]"
              :key="f[0]"
              :class="{ selected: orderFilter === f[0] }"
              @click="orderFilter = f[0]"
            >
              {{ f[1] }}</button
            ><span>{{ filteredOrders.length }} 笔订单</span>
          </div>
          <div class="order-list">
            <article v-for="o in filteredOrders" :key="o.id" class="order-card">
              <div class="order-head">
                <span
                  >{{ o.createdAt }} <b>订单号 {{ o.id }}</b></span
                ><span class="status-pill" :class="o.status.toLowerCase()">{{
                  statusLabels[o.status]
                }}</span>
              </div>
              <div class="order-content">
                <img :src="o.image" :alt="o.productName" />
                <div class="order-product">
                  <h3>{{ o.productName }}</h3>
                  <p>标准款 · 数量 {{ o.quantity }}</p>
                  <small v-if="role === 'admin'">客户 {{ o.userId }}</small
                  ><span><Truck :size="14" /> {{ o.logistics }}</span>
                </div>
                <div class="order-amount">
                  <small>实付金额</small><b>¥{{ money(o.amount) }}</b>
                </div>
                <div class="order-actions">
                  <button
                    class="secondary"
                    @click="ask(`请查询订单 ${o.id} 的物流和当前状态。`)"
                  >
                    查询物流</button
                  ><button
                    class="text-button"
                    @click="
                      ask(`我想申请订单 ${o.id} 的退货退款，请核验售后条件。`)
                    "
                  >
                    申请售后 <ChevronRight :size="14" />
                  </button>
                </div>
              </div>
            </article>
          </div>
          <div v-if="!filteredOrders.length" class="empty">
            <Package :size="32" />
            <h3>这个状态下暂时没有订单</h3>
            <p>可以切换到「全部订单」查看。</p>
          </div>
          <div class="subtle-note">
            <ShieldCheck :size="15" />
            订单为样例数据，售后资金处理为模拟；所有查询仍由服务端校验归属。
          </div>
        </section>

        <section v-if="tab === 'chat'" class="chat-page page-enter">
          <div class="chat-heading">
            <div>
              <span class="overline">YOUR HOME, OUR CARE</span>
              <h1>有问题，问智服。</h1>
              <p>懂产品、查订单、办售后，也知道何时请人工帮忙。</p>
            </div>
            <div class="chat-tools">
              <el-select
                :model-value="conversationId"
                placeholder="历史会话"
                @change="loadConversation"
                style="width: 180px"
                :disabled="sending"
                ><el-option
                  v-for="c in conversations"
                  :key="c.id"
                  :value="c.id"
                  :label="
                    date(c.createdAt) + ' · ' + c.id.slice(-4)
                  " /></el-select
              ><button
                class="secondary"
                @click="newConversation"
                :disabled="sending"
              >
                <Plus :size="16" /> 新会话
              </button>
            </div>
          </div>
          <div class="chat-shell">
            <div class="chat-shell-head">
              <span class="agent-avatar"><Sparkles :size="21" /></span
              ><span
                ><b>智服 · 智能家居服务助手</b
                ><small
                  ><span class="online-dot"></span> {{ status.modelMode }} ·
                  资料与业务事实可追溯</small
                ></span
              ><button class="text-button" @click="refreshMessages">
                <RefreshCw :size="14" /> 同步回复
              </button>
            </div>
            <div ref="chatBody" class="chat-body" aria-live="polite">
              <div v-if="!messages.length" class="chat-welcome">
                <span class="welcome-symbol"><Leaf :size="32" /></span>
                <h2>你好，很高兴照顾你的家。</h2>
                <p>描述你的问题，我会找到合适的专家与可靠的依据。</p>
                <div class="quick-prompts">
                  <button
                    v-for="p in [
                      '门锁没电了怎么应急开门？',
                      '摄像头录像一定要付费吗？',
                      '温湿度计需要搭配网关吗？',
                      '帮我查询订单 SS20261010002',
                    ]"
                    :key="p"
                    @click="ask(p)"
                  >
                    <MessageCircle :size="15" />{{ p
                    }}<ArrowUpRight :size="14" />
                  </button>
                </div>
              </div>
              <article
                v-for="m in messages"
                :key="m.id"
                class="chat-message"
                :class="m.role"
              >
                <span v-if="m.role !== 'user'" class="message-avatar"
                  ><Headphones v-if="m.role === 'human'" :size="17" /><Sparkles
                    v-else
                    :size="17"
                /></span>
                <div class="message-body">
                  <div v-if="m.role !== 'user'" class="message-author">
                    {{ m.role === "human" ? "人工服务专员" : "智服助手"
                    }}<span>{{ date(m.createdAt) }}</span>
                  </div>
                  <div
                    class="message-content prose"
                    v-html="markdown(m.content)"
                  ></div>
                  <div v-if="m.routes?.length" class="message-routing">
                    <span v-for="r in m.routes" :key="r">{{
                      routeLabels[r] || r
                    }}</span
                    ><span v-if="m.validation"
                      ><ShieldCheck :size="12" />{{ m.validation }}</span
                    >
                  </div>
                  <details v-if="m.sources?.length" class="sources">
                    <summary>
                      <BookOpen :size="14" /> 参考了
                      {{ m.sources.length }} 条知识依据
                      <ChevronDown :size="13" />
                    </summary>
                    <div v-for="s in m.sources" :key="s.id" class="source">
                      <b>[{{ s.citation }}] {{ s.source }} · {{ s.heading }}</b>
                      <p>{{ s.content }}</p>
                    </div>
                  </details>
                  <div v-if="m.orderCard?.id" class="chat-order">
                    <Package :size="22" />
                    <div>
                      <b>{{ m.orderCard.productName }}</b
                      ><small
                        >{{ m.orderCard.id }} ·
                        {{ statusLabels[m.orderCard.status] }}</small
                      >
                    </div>
                    <span>¥{{ money(m.orderCard.amount) }}</span>
                  </div>
                  <div v-if="m.refundCard?.id" class="refund-card">
                    <div>
                      <ShieldCheck :size="22" /><span
                        ><b>退款申请 · ¥{{ money(m.refundCard.amount) }}</b
                        ><small
                          >{{ m.refundCard.orderId }} ·
                          {{ statusLabels[m.refundCard.status] }}</small
                        ></span
                      >
                    </div>
                    <p>仅模拟资金处理。用户确认后仍需退货与质检。</p>
                    <button
                      v-if="m.refundCard.status === 'AWAITING_CONFIRMATION'"
                      class="primary"
                      @click="confirmRefund(m.refundCard)"
                    >
                      核对并确认申请 <Check :size="15" /></button
                    ><span v-else class="green"
                      ><CheckCircle2 :size="15" /> 申请已确认</span
                    >
                  </div>
                  <button
                    v-if="m.needsEscalation"
                    class="handoff-button"
                    @click="openTicket(m)"
                  >
                    <Headphones :size="16" /> 填写服务升级单
                    <ArrowRight :size="14" />
                  </button>
                </div>
              </article>
              <div v-if="sending" class="stream-progress">
                <span class="message-avatar"><Sparkles :size="17" /></span>
                <div>
                  <div class="thinking-dots">
                    <i></i><i></i><i></i
                    ><span>{{
                      progress.at(-1)?.label || "正在连接合适的服务专家"
                    }}</span>
                  </div>
                  <div class="mini-timeline">
                    <span
                      v-for="e in progress.filter((e) =>
                        [
                          'routing',
                          'tool',
                          'retrieval',
                          'validation',
                          'checkpoint',
                        ].includes(e.type),
                      )"
                      :key="e.eventId"
                      ><Check :size="12" />{{ e.agent }} · {{ e.label }}</span
                    >
                  </div>
                </div>
              </div>
            </div>
            <div class="chat-composer">
              <form @submit.prevent="send">
                <textarea
                  v-model="question"
                  rows="2"
                  placeholder="告诉我设备型号、订单号，或你遇到的问题…"
                  aria-label="输入客服问题"
                  :disabled="sending"
                  @keydown.enter.exact.prevent="send"
                  maxlength="2000"
                ></textarea>
                <div>
                  <span>Enter 发送 · Shift + Enter 换行</span
                  ><button
                    class="send-button"
                    type="submit"
                    :disabled="sending || !question.trim()"
                    aria-label="发送消息"
                  >
                    <Send :size="18" />
                  </button>
                </div>
              </form>
              <div class="composer-footer">
                <span
                  ><ShieldCheck :size="12" />
                  回答基于已审核资料；不确定时请人工核验</span
                ><button @click="myTicketsOpen = true">
                  我的服务升级单 <ChevronRight :size="12" />
                </button>
              </div>
            </div>
          </div>
        </section>

        <section v-if="tab === 'ops'" class="ops-page page-enter">
          <div class="section-heading page-heading">
            <div>
              <span class="overline">OBSERVE. UNDERSTAND. IMPROVE.</span>
              <h1>AgentOps 运行观测</h1>
              <p>每一次路由、检索与工具调用，都有可追溯的执行记录。</p>
            </div>
            <button class="secondary" @click="refreshOps">
              <RefreshCw :size="16" /> 刷新运行
            </button>
          </div>
          <div class="metrics">
            <div>
              <span>会话任务</span><b>{{ runs.length }}</b
              ><small>当前可访问运行记录</small>
            </div>
            <div>
              <span>完成任务</span
              ><b
                >{{ completedRuns.length }}<small>/ {{ runs.length }}</small></b
              ><small>实际终态统计</small>
            </div>
            <div>
              <span>平均响应时间</span><b>{{ averageLatency }}</b
              ><small>已完成任务的端到端耗时</small>
            </div>
            <div>
              <span>模型 Token</span><b>{{ tokenTotal.toLocaleString() }}</b
              ><small>API 用量；离线模式为 0</small>
            </div>
          </div>
          <div class="ops-content">
            <div class="run-list-panel">
              <div class="panel-heading">
                <h3>运行记录</h3>
                <span>{{ runs.length }} runs</span>
              </div>
              <button
                v-for="r in runs"
                :key="r.id"
                class="run-row"
                :class="{ selected: selectedRun?.id === r.id }"
                @click="showRun(r)"
              >
                <span class="run-icon"><Layers :size="18" /></span
                ><span
                  ><b>{{ r.question }}</b
                  ><small
                    >{{ date(r.createdAt) }} ·
                    {{
                      (r.routes || [])
                        .map((x: string) => routeLabels[x])
                        .join(" / ") || "等待路由"
                    }}</small
                  ></span
                ><i :class="r.status.toLowerCase()"></i>
              </button>
              <div v-if="!runs.length" class="empty compact">
                <Activity :size="30" />
                <h3>第一条运行记录，等你开启</h3>
                <p>发送一条客服消息，即可查看真实执行链路。</p>
                <button
                  class="secondary"
                  @click="ask('门锁没电了怎么应急开门？')"
                >
                  体验一条链路 <ArrowRight :size="14" />
                </button>
              </div>
            </div>
            <div class="run-detail-panel">
              <template v-if="selectedRun"
                ><div class="panel-heading">
                  <div>
                    <h3>执行时间线</h3>
                    <small>{{ selectedRun.id }}</small>
                  </div>
                  <span class="status-pill">{{
                    statusLabels[selectedRun.status]
                  }}</span>
                </div>
                <div class="flow-strip">
                  <span>Supervisor</span><ChevronRight :size="14" /><span>{{
                    (selectedRun.routes || [])
                      .map((x: string) => routeLabels[x])
                      .join(" + ") || "Worker"
                  }}</span
                  ><ChevronRight :size="14" /><span>Guardrail</span>
                </div>
                <div class="trace-id">
                  Trace ID <code>{{ selectedRun.traceId }}</code>
                </div>
                <div class="timeline">
                  <div
                    v-for="e in runEvents"
                    :key="e.eventId"
                    class="timeline-item"
                  >
                    <span class="timeline-dot"
                      ><Check v-if="e.status === 'ok'" :size="12" /><AlertCircle
                        v-else
                        :size="12"
                    /></span>
                    <div>
                      <div class="timeline-title">
                        <b>{{ e.agent }}</b
                        ><small>{{
                          e.durationMs != null
                            ? e.durationMs + " ms"
                            : date(e.at)
                        }}</small>
                      </div>
                      <p>{{ e.label }}</p>
                      <span v-if="e.tool" class="tool-chip">{{
                        e.transport
                      }}</span>
                      <details v-if="e.candidates?.length">
                        <summary>
                          查看检索证据 ({{ e.candidates.length }})
                        </summary>
                        <div
                          v-for="c in e.candidates"
                          :key="c.id"
                          class="retrieval-result"
                        >
                          <b>{{ c.heading }}</b
                          ><span
                            >综合 {{ c.score }} · 语义 {{ c.semanticScore }} ·
                            {{ c.accepted ? "通过门槛" : "未通过" }}</span
                          >
                          <p>{{ c.content }}</p>
                        </div>
                      </details>
                      <details v-if="e.input">
                        <summary>工具输入摘要</summary>
                        <pre>{{ JSON.stringify(e.input, null, 2) }}</pre>
                      </details>
                    </div>
                  </div>
                </div>
                <div class="subtle-note">
                  <ShieldCheck :size="14" />
                  展示执行与审计摘要；不展示模型隐藏推理。
                </div></template
              >
              <div v-else class="empty">
                <Layers :size="34" />
                <h3>选择一次运行</h3>
                <p>查看它如何找到答案，以及哪些工具参与了服务。</p>
              </div>
            </div>
          </div>
        </section>

        <section
          v-if="tab === 'tickets' && role === 'admin'"
          class="page-enter"
        >
          <div class="section-heading page-heading">
            <div>
              <span class="overline">HUMAN CARE, WHEN IT MATTERS</span>
              <h1>人工处理中心</h1>
              <p>接过 AI 的接力棒，让每个服务请求得到清晰的结果。</p>
            </div>
            <button class="secondary" @click="loadBusiness">
              <RefreshCw :size="16" /> 刷新服务单
            </button>
          </div>
          <div class="ticket-overview">
            <div>
              <Clock :size="20" /><span
                ><b>{{
                  tickets.filter((t) => t.status === "PENDING").length
                }}</b
                >待处理</span
              >
            </div>
            <div>
              <Headphones :size="20" /><span
                ><b>{{
                  tickets.filter((t) => t.status === "PROCESSING").length
                }}</b
                >处理中</span
              >
            </div>
            <div>
              <CheckCircle2 :size="20" /><span
                ><b>{{
                  tickets.filter((t) => t.status === "RESOLVED").length
                }}</b
                >已完成</span
              >
            </div>
          </div>
          <div class="status-filters">
            <button
              v-for="f in [
                ['ALL', '全部服务单'],
                ['PENDING', '待处理'],
                ['PROCESSING', '处理中'],
                ['RESOLVED', '已完成'],
              ]"
              :key="f[0]"
              :class="{ selected: ticketFilter === f[0] }"
              @click="ticketFilter = f[0]"
            >
              {{ f[1] }}
            </button>
          </div>
          <div class="ticket-table">
            <div class="ticket-table-head">
              <span>问题与服务编号</span><span>客户 / 优先级</span
              ><span>提交时间</span><span>状态</span><span>操作</span>
            </div>
            <div
              v-for="t in filteredTickets"
              :key="t.id"
              class="ticket-table-row"
            >
              <span
                ><b>{{ t.title }}</b
                ><small>{{ t.id }}</small></span
              ><span
                >{{ t.userId
                }}<small :class="{ urgent: t.priority === 'HIGH' }">{{
                  t.priority === "HIGH" ? "高优先级" : "普通优先级"
                }}</small></span
              ><span>{{ date(t.createdAt) }}</span
              ><span
                ><i class="status-pill" :class="t.status.toLowerCase()">{{
                  statusLabels[t.status]
                }}</i></span
              ><button class="text-button" @click="viewTicket(t)">
                查看处理 <ArrowRight :size="15" />
              </button>
            </div>
            <div v-if="!filteredTickets.length" class="empty">
              <Headphones :size="32" />
              <h3>当前没有待查看的服务升级单</h3>
              <p>客户在客服页确认并提交后，服务单会出现在这里。</p>
            </div>
          </div>
        </section>
      </template>
    </main>

    <footer class="footer">
      <span
        ><Leaf :size="14" /> SmartServe 智服
        <i>让科技有温度，让服务有依据。</i></span
      >
      <div>
        <button @click="knowledgeOpen = true">产品与服务资料</button
        ><span>·</span><button @click="statusOpen = true">项目运行状态</button
        ><span class="demo-label">业务演示环境</span>
      </div>
    </footer>

    <el-dialog
      v-model="productOpen"
      width="720px"
      class="product-dialog"
      :title="selectedProduct?.name"
      destroy-on-close
      ><div v-if="selectedProduct" class="product-detail">
        <img :src="selectedProduct.image" :alt="selectedProduct.name" />
        <div>
          <span class="overline">{{ selectedProduct.category }}</span>
          <h2>{{ selectedProduct.name }}</h2>
          <p>{{ selectedProduct.description }}</p>
          <div class="price">
            <span>¥</span>{{ money(selectedProduct.price) }}
          </div>
          <ul>
            <li v-for="f in selectedProduct.features" :key="f">
              <Check :size="14" />{{ f }}
            </li>
            <li>
              <ShieldCheck :size="14" />{{ selectedProduct.warranty }}质保
            </li>
          </ul>
          <p class="muted">
            联网、安装和兼容性等详细规格，请向智服咨询并查看引用资料。
          </p>
          <button class="primary" @click="consultProduct(selectedProduct)">
            咨询这款产品 <MessageCircle :size="16" />
          </button>
        </div></div
    ></el-dialog>
    <el-dialog
      v-model="knowledgeOpen"
      width="940px"
      title="知识库 · 产品与服务资料"
      class="knowledge-dialog"
      ><div class="knowledge-layout">
        <aside>
          <button
            v-for="(d, i) in documents"
            :key="d.name"
            :class="{ selected: documentIndex === i }"
            @click="documentIndex = i"
          >
            <FileText :size="17" /><span
              >{{ d.name }}<small>{{ d.chunks }} 个检索片段</small></span
            ></button
          ><button v-if="role === 'admin'" class="text-button" @click="reindex">
            <RefreshCw :size="14" />重新索引
          </button>
        </aside>
        <div
          v-if="documents[documentIndex]"
          class="prose knowledge-content"
          v-html="markdown(documents[documentIndex].content)"
        ></div>
      </div>
      <div class="subtle-note">
        虚构品牌样例资料 · 版本 1.0 · 更新文档后由管理员重新索引
      </div></el-dialog
    >
    <el-dialog
      v-model="ticketOpen"
      width="600px"
      title="提交服务升级单"
      :close-on-click-modal="false"
      ><div class="dialog-intro">
        <Headphones :size="22" />
        <p>
          让人工专员继续协助你。核对信息并确认提交后，才会建立服务单；结果将回复到原会话。
        </p>
      </div>
      <el-form label-position="top"
        ><el-form-item label="问题标题" required
          ><el-input
            v-model="ticketForm.title"
            maxlength="100"
            placeholder="简要描述需要协助的问题" /></el-form-item
        ><el-form-item label="问题详情" required
          ><el-input
            v-model="ticketForm.details"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
        /></el-form-item>
        <div class="form-two">
          <el-form-item label="关联订单（可选）"
            ><el-select
              v-model="ticketForm.orderId"
              clearable
              placeholder="选择相关订单"
              ><el-option
                v-for="o in orders"
                :key="o.id"
                :label="o.id + ' · ' + o.productName"
                :value="o.id" /></el-select></el-form-item
          ><el-form-item label="优先级"
            ><el-select v-model="ticketForm.priority"
              ><el-option label="普通服务咨询" value="NORMAL" /><el-option
                label="设备安全 / 重要服务中断"
                value="HIGH" /></el-select
          ></el-form-item>
        </div>
        <el-form-item label="联系方式" required
          ><el-input
            v-model="ticketForm.contact"
            placeholder="邮箱或手机号；请勿填写密码、验证码"
            maxlength="100" /></el-form-item
        ><el-checkbox v-model="ticketForm.confirmed"
          >我已核对信息，同意将问题移交人工专员处理</el-checkbox
        ></el-form
      ><template #footer
        ><button class="secondary" @click="ticketOpen = false">暂不提交</button
        ><button
          class="primary"
          @click="submitTicket"
          :disabled="ticketBusy || !ticketForm.confirmed"
        >
          {{ ticketBusy ? "正在提交…" : "确认并提交" }}
          <ArrowRight :size="15" /></button></template
    ></el-dialog>
    <el-dialog v-model="myTicketsOpen" width="680px" title="我的服务升级单"
      ><div v-if="!tickets.length" class="empty">
        <Headphones :size="30" />
        <p>你还没有提交服务升级单。</p>
      </div>
      <article v-for="t in tickets" :key="t.id" class="my-ticket">
        <div>
          <b>{{ t.title }}</b
          ><span class="status-pill">{{ statusLabels[t.status] }}</span>
        </div>
        <small>{{ t.id }} · {{ date(t.createdAt) }}</small>
        <p>{{ t.details }}</p>
        <div v-if="t.resolution" class="resolution">
          <CheckCircle2 :size="16" />{{ t.resolution }}
        </div>
      </article></el-dialog
    >
    <el-dialog
      v-model="ticketDetailOpen"
      width="860px"
      :title="selectedTicket?.title"
      class="ticket-detail-dialog"
      ><template v-if="selectedTicket"
        ><div class="ticket-info">
          <span>{{ selectedTicket.id }}</span
          ><span class="status-pill">{{
            statusLabels[selectedTicket.status]
          }}</span>
        </div>
        <div class="ticket-info-grid">
          <div><small>客户</small>{{ selectedTicket.userId }}</div>
          <div>
            <small>关联订单</small>{{ selectedTicket.orderId || "未关联" }}
          </div>
          <div><small>联系方式</small>{{ selectedTicket.contact }}</div>
        </div>
        <h4>用户问题与移交摘要</h4>
        <p class="ticket-text">{{ selectedTicket.details }}</p>
        <details class="transcript">
          <summary>展开原会话（{{ ticketMessages.length }} 条消息）</summary>
          <div v-for="m in ticketMessages" :key="m.id">
            <b>{{
              m.role === "user" ? "客户" : m.role === "human" ? "人工" : "智服"
            }}</b>
            <p>{{ m.content }}</p>
          </div>
        </details>
        <div v-if="selectedTicket.notes?.length" class="internal-notes">
          <h4>内部处理记录</h4>
          <p v-for="(n, i) in selectedTicket.notes" :key="i">
            {{ date(n.at) }} · {{ n.content }}
          </p>
        </div>
        <el-form
          v-if="selectedTicket.status !== 'RESOLVED'"
          label-position="top"
          ><el-form-item label="给用户的处理结果（完成时必填）"
            ><el-input
              v-model="resolution"
              type="textarea"
              :rows="3"
              placeholder="填写经过核验的处理结果，不承诺尚未完成的操作"
              maxlength="2000" /></el-form-item
          ><el-form-item label="内部备注（普通用户不可见）"
            ><el-input
              v-model="internalNote"
              type="textarea"
              :rows="2"
              maxlength="2000" /></el-form-item
        ></el-form>
        <div v-else class="resolution">
          {{ selectedTicket.resolution }}
        </div></template
      ><template #footer
        ><button class="secondary" @click="ticketDetailOpen = false">
          关闭</button
        ><button
          v-if="selectedTicket?.status === 'PENDING'"
          class="primary"
          @click="updateTicket('PROCESSING')"
          :disabled="processing"
        >
          认领并开始处理 <Headphones :size="15" /></button
        ><button
          v-if="selectedTicket?.status === 'PROCESSING'"
          class="secondary"
          @click="updateTicket('PROCESSING')"
          :disabled="processing"
        >
          保存备注</button
        ><button
          v-if="selectedTicket?.status === 'PROCESSING'"
          class="primary"
          @click="updateTicket('RESOLVED')"
          :disabled="processing || !resolution.trim()"
        >
          完成并回复用户 <Check :size="15" /></button></template
    ></el-dialog>
    <el-dialog v-model="statusOpen" width="540px" title="项目运行状态"
      ><div class="system-status">
        <div>
          <span>模型模式</span><b>{{ status.modelMode }}</b>
        </div>
        <div>
          <span>知识库文档 / 片段</span
          ><b
            >{{ status.knowledge?.documents }} /
            {{ status.knowledge?.chunks }}</b
          >
        </div>
        <div>
          <span>向量模型</span><b>{{ status.knowledge?.embeddingModel }}</b>
        </div>
        <div>
          <span>检索存储</span><b>{{ status.knowledge?.vectorStore }}</b>
        </div>
        <div>
          <span>重排策略</span><b>{{ status.knowledge?.reranker }}</b>
        </div>
        <div>
          <span>索引版本</span><code>{{ status.knowledge?.revision }}</code>
        </div>
        <div><span>资金处理</span><b>模拟 · 无真实转账</b></div>
        <div>
          <span>身份模式</span
          ><b>{{ status.demoAccess ? "演示角色切换" : "预配置访问凭证" }}</b>
        </div>
      </div></el-dialog
    >
  </div>
</template>
