<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { ElMessage } from "element-plus";
import {
  Activity,
  ChevronDown,
  Headphones,
  Leaf,
  LogOut,
  MessageCircle,
  Package,
  ShoppingBag,
  UserRound,
  Wallet,
} from "lucide-vue-next";
import { api, ApiError, setCsrf, BUSINESS_RULES_VERSION } from "./api";
import { date, labels, money, type Entity } from "./format";
import AuthView from "./components/AuthView.vue";
import ProfileDialog from "./components/ProfileDialog.vue";
import StoreView from "./components/StoreView.vue";
import OrdersView from "./components/OrdersView.vue";
import ChatWorkspace from "./components/ChatWorkspace.vue";
import AdminWorkspace from "./components/AdminWorkspace.vue";
import OpsView from "./components/OpsView.vue";
const user = ref<Entity | null>(null),
  booting = ref(true),
  connectionError = ref(""),
  incompatible = ref(false),
  tab = ref("store"),
  refreshKey = ref(0),
  requestText = ref(""),
  requestNonce = ref(0),
  wallet = ref<Entity>({ balance: 0, ledger: [] }),
  walletOpen = ref(false),
  profileOpen = ref(false);
const tabs = computed(() =>
  user.value?.role === "ADMIN"
    ? [
        { id: "admin", label: "服务工作台", icon: Headphones },
        { id: "ops", label: "运行观测", icon: Activity },
        { id: "store", label: "商品目录", icon: ShoppingBag },
        { id: "chat", label: "产品咨询", icon: MessageCircle },
      ]
    : [
        { id: "store", label: "精选好物", icon: ShoppingBag },
        { id: "orders", label: "我的订单", icon: Package },
        { id: "chat", label: "智能客服", icon: MessageCircle },
      ],
);
async function initialize() {
  booting.value = true;
  connectionError.value = "";
  incompatible.value = false;
  try {
    const status = await api("/status");
    if (status.businessRulesVersion !== BUSINESS_RULES_VERSION) {
      incompatible.value = true;
      throw new Error("前后端业务版本不一致，请更新并重启整套应用，再刷新页面。Docker 方式需要重新构建应用镜像。");
    }
    try {
      const result = await api("/auth/me");
      setCsrf(result.csrfToken);
      await authenticated(result.user);
    } catch (e) {
      if (!(e instanceof ApiError) || e.status !== 401) throw e;
    }
  } catch (e) {
    connectionError.value = (e as Error).message;
  } finally {
    booting.value = false;
  }
}
async function authenticated(value: Entity) {
  user.value = value;
  tab.value = value.role === "ADMIN" ? "admin" : "store";
  await refreshWallet();
  window.scrollTo({ top: 0 });
}
async function refreshWallet() {
  if (user.value?.role === "CUSTOMER") wallet.value = await api("/wallet");
}
async function changed() {
  refreshKey.value++;
  await refreshWallet().catch(() => {});
}
function ask(text: string) {
  requestText.value = text;
  requestNonce.value++;
  tab.value = "chat";
}
function clearSession() {
  user.value = null;
  setCsrf("");
  profileOpen.value = false;
  walletOpen.value = false;
  wallet.value = { balance: 0, ledger: [] };
}
async function logout() {
  try {
    await api("/auth/logout", "POST");
    clearSession();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
function menu(command: string) {
  if (command === "profile") profileOpen.value = true;
  else if (command === "wallet") {
    refreshWallet();
    walletOpen.value = true;
  } else if (command === "logout") logout();
}
onMounted(() => {
  initialize();
  window.addEventListener("smartserve:unauthorized", clearSession);
});
onUnmounted(() =>
  window.removeEventListener("smartserve:unauthorized", clearSession),
);
</script>
<template>
  <div v-if="booting" class="loading-page">
    <Leaf :size="36" />
    <p>正在准备你的服务空间…</p>
  </div>
  <template v-else-if="!user"
    ><div v-if="connectionError" class="connection-banner" role="alert">
      服务连接暂时不可用：{{ connectionError }}
      <button @click="initialize">重新连接</button>
    </div>
    <AuthView v-if="!incompatible" @authenticated="authenticated"
  /></template>
  <div v-else class="application" :key="user.id">
    <header class="header">
      <a
        class="brand"
        href="#"
        @click.prevent="tab = user.role === 'ADMIN' ? 'admin' : 'store'"
        ><span class="brand-mark"><Leaf :size="24" /></span
        ><span
          >ServicePilot <em>服务助手</em
          ><small>AGENT-ASSISTED SERVICE</small></span
        ></a
      >
      <nav class="navigation" aria-label="主导航">
        <button
          v-for="item in tabs"
          :key="item.id"
          :class="{ active: tab === item.id }"
          @click="tab = item.id"
        >
          <component :is="item.icon" :size="17" /><span>{{ item.label }}</span>
        </button>
      </nav>
      <div class="header-tools">
        <button
          v-if="user.role === 'CUSTOMER'"
          class="wallet-button"
          @click="
            refreshWallet();
            walletOpen = true;
          "
        >
          <Wallet :size="17" /><span>¥{{ money(wallet.balance) }}</span></button
        ><el-dropdown trigger="click" @command="menu"
          ><button class="profile" aria-label="账号菜单">
            <span class="avatar">{{
              String(user.displayName).slice(0, 1)
            }}</span
            ><span
              >{{ user.displayName
              }}<small>{{
                user.role === "ADMIN" ? "服务管理员" : "我的账号"
              }}</small></span
            ><ChevronDown :size="14" /></button
          ><template #dropdown
            ><el-dropdown-menu
              ><el-dropdown-item command="profile"
                ><UserRound :size="15" />个人资料与密码</el-dropdown-item
              ><el-dropdown-item
                v-if="user.role === 'CUSTOMER'"
                command="wallet"
                ><Wallet :size="15" />模拟钱包</el-dropdown-item
              ><el-dropdown-item command="logout" divided
                ><LogOut :size="15" />退出登录</el-dropdown-item
              ></el-dropdown-menu
            ></template
          ></el-dropdown
        >
      </div>
    </header>
    <main class="main">
      <StoreView
        v-if="tab === 'store'"
        :user="user"
        :refresh-key="refreshKey"
        :balance="Number(wallet.balance)"
        @ask="ask"
        @purchased="
          changed();
          tab = 'orders';
        "
      /><OrdersView
        v-if="tab === 'orders' && user.role === 'CUSTOMER'"
        :refresh-key="refreshKey"
        @ask="ask"
        @changed="changed"
        @store="tab = 'store'"
      /><ChatWorkspace
        v-show="tab === 'chat'"
        :user="user"
        :request-text="requestText"
        :request-nonce="requestNonce"
        :visible="tab === 'chat'"
        @changed="changed"
      /><AdminWorkspace
        v-if="tab === 'admin' && user.role === 'ADMIN'"
      /><OpsView v-if="tab === 'ops' && user.role === 'ADMIN'" />
    </main>
    <footer class="footer">
      <span><Leaf :size="14" /> ServicePilot · 面向电商售后的 Agent 协同服务</span
      ><span>模拟交易与配送体验，不涉及真实资金或发货</span>
    </footer>
    <ProfileDialog
      v-model="profileOpen"
      :user="user"
      @updated="(value) => (user = value)"
      @password-changed="clearSession"
    /><el-drawer
      v-model="walletOpen"
      title="我的模拟钱包"
      size="min(560px,94vw)"
      ><div class="wallet-card">
        <span>当前可用模拟余额</span>
        <h1><small>¥</small>{{ money(wallet.balance) }}</h1>
        <p>新客户一次性获得 ¥10,000，用于体验购买、取消与售后。</p>
      </div>
      <h3 class="subheading">余额变动</h3>
      <div class="ledger-row" v-for="entry in wallet.ledger" :key="entry.id">
        <span
          ><b>{{ labels[entry.kind] || entry.kind }}</b
          ><small
            >{{ date(entry.created_at)
            }}<template v-if="entry.order_id">
              · {{ entry.order_id }}</template
            ></small
          ></span
        ><span :class="Number(entry.amount) > 0 ? 'positive' : 'negative'"
          ><b
            >{{ Number(entry.amount) > 0 ? "+" : ""
            }}{{ money(entry.amount) }}</b
          ><small>余额 ¥{{ money(entry.balance_after) }}</small></span
        >
      </div>
      <p class="notice">
        余额只用于本项目体验。历史演示订单不从钱包扣款，也不会通过退款增加余额。
      </p></el-drawer
    >
  </div>
</template>
