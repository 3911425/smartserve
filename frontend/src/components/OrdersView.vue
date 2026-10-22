<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { Package, ArrowRight, RefreshCw } from "lucide-vue-next";
import { ElMessage } from "element-plus";
import { api } from "../api";
import { money, date, labels, type Entity } from "../format";
import OrderDetail from "./OrderDetail.vue";
const props = defineProps<{ refreshKey: number }>();
const emit = defineEmits(["ask", "changed", "store"]);
const orders = ref<Entity[]>([]),
  status = ref(""),
  selected = ref<Entity | null>(null),
  loading = ref(false),
  page = ref(1);
async function load() {
  loading.value = true;
  try {
    const result = await api(
      `/orders/page?status=${status.value}&page=${page.value}`,
    );
    orders.value = result.items;
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
watch(() => props.refreshKey, load);
watch(status, () => {
  page.value = 1;
  load();
});
async function detail(id: string) {
  try {
    selected.value = await api(`/orders/${id}`);
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
function updated(order: Entity) {
  selected.value = order;
  load();
  emit("changed");
}
</script>
<template>
  <section class="page-enter">
    <div class="section-heading">
      <div>
        <span class="eyebrow">YOUR ORDERS, ALL IN ONE PLACE</span>
        <h1>我的订单</h1>
        <p class="muted">从下单到安心使用，每一步都能找到。</p>
      </div>
      <button class="secondary" @click="load">
        <RefreshCw :size="15" />刷新订单
      </button>
    </div>
    <div class="category-tabs order-tabs">
      <button
        v-for="s in [
          '',
          'PAID',
          'SHIPPED',
          'DELIVERED',
          'REFUND_PENDING',
          'REFUNDED',
          'CANCELLED',
        ]"
        :key="s"
        :class="{ selected: status === s }"
        @click="status = s"
      >
        {{ s ? labels[s] : "全部订单" }}
      </button>
    </div>
    <div v-if="loading" class="empty">正在读取订单…</div>
    <div v-else-if="!orders.length" class="empty empty-card">
      <Package :size="40" />
      <h3>这里还没有订单</h3>
      <p>选一件适合自己的好物，或让智服先帮你了解。</p>
      <button class="primary" @click="emit('store')">
        去看看好物<ArrowRight :size="16" />
      </button>
    </div>
    <div v-else class="orders-grid">
      <article class="order-card" v-for="order in orders" :key="order.id">
        <div class="order-card-top">
          <span>{{ date(order.createdAt) }}</span
          ><span class="status-pill" :class="order.status.toLowerCase()">{{
            labels[order.status]
          }}</span>
        </div>
        <div class="order-card-product">
          <img :src="order.image" :alt="order.productName" />
          <div>
            <h3>{{ order.productName }}</h3>
            <p class="muted">{{ order.id }}</p>
            <small>数量 {{ order.quantity }}</small>
          </div>
          <strong>¥{{ money(order.amount) }}</strong>
        </div>
        <div class="order-card-bottom">
          <span>{{
            order.fundingSource === "LEGACY_DEMO"
              ? "历史演示订单"
              : "模拟余额支付"
          }}</span
          ><button class="text-button" @click="detail(order.id)">
            查看详情<ArrowRight :size="15" />
          </button>
        </div>
      </article>
    </div>
    <div class="pagination">
      <button
        class="secondary"
        :disabled="page === 1"
        @click="
          page--;
          load();
        "
      >
        上一页</button
      ><span>第 {{ page }} 页</span
      ><button
        class="secondary"
        :disabled="orders.length < 20"
        @click="
          page++;
          load();
        "
      >
        下一页
      </button>
    </div>
    <el-dialog
      :model-value="!!selected"
      title="订单详情"
      width="min(750px,94vw)"
      @update:model-value="
        (value: boolean) => {
          if (!value) selected = null;
        }
      "
      ><OrderDetail
        v-if="selected"
        :order="selected"
        @updated="updated"
        @ask="
          (text) => {
            selected = null;
            emit('ask', text);
          }
        "
    /></el-dialog>
  </section>
</template>
