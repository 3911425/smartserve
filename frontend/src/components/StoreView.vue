<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import {
  ArrowRight,
  ArrowUpRight,
  Search,
  MessageCircle,
  ShoppingBag,
  ShieldCheck,
  Truck,
  Headphones,
} from "lucide-vue-next";
import { ElMessage } from "element-plus";
import { api } from "../api";
import { labels, money, type Entity } from "../format";
const props = defineProps<{
  user: Entity;
  refreshKey: number;
  balance: number;
}>();
const emit = defineEmits(["ask", "purchased"]);
const products = ref<Entity[]>([]),
  search = ref(""),
  category = ref("全部商品"),
  sort = ref("featured"),
  selected = ref<Entity | null>(null),
  checkout = ref(false),
  busy = ref(false),
  loading = ref(false);
const form = reactive({
  quantity: 1,
  recipient: "",
  phone: "",
  address: "",
  requestKey: "",
});
const categories = ["全部商品", "家庭安防", "氛围照明", "生活智控"];
const filtered = computed(() =>
  products.value
    .filter(
      (p) =>
        (category.value === "全部商品" || p.category === category.value) &&
        (p.name + p.description).includes(search.value),
    )
    .sort((a, b) =>
      sort.value === "low"
        ? a.price - b.price
        : sort.value === "high"
          ? b.price - a.price
          : 0,
    ),
);
async function load() {
  loading.value = true;
  try {
    products.value = await api<Entity[]>("/products");
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
watch(() => props.refreshKey, load);
function buy(p: Entity) {
  selected.value = p;
  Object.assign(form, {
    quantity: 1,
    recipient: props.user.displayName,
    phone: props.user.phone || "",
    address: props.user.shippingAddress || "",
    requestKey: crypto.randomUUID(),
  });
  checkout.value = true;
}
async function pay() {
  if (!selected.value || busy.value) return;
  busy.value = true;
  try {
    const order = await api("/purchases", "POST", {
      productId: selected.value.id,
      ...form,
    });
    checkout.value = false;
    selected.value = null;
    await load();
    ElMessage.success(`购买成功，本次模拟配送状态：${labels[order.status]}`);
    emit("purchased", order);
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="store-page page-enter">
    <div class="page-eyebrow">
      <span><i class="live-dot"></i> 用科技，照顾生活的细节</span
      ><span class="muted">精选设备 · 全流程客户服务</span>
    </div>
    <div class="hero">
      <img
        class="hero-image"
        :src="'/files/images/smart-home-hero.png'"
        alt="客厅中的智能家居设备"
      />
      <div class="hero-shade"></div>
      <div class="hero-copy">
        <span class="eyebrow">A LITTLE SMARTER. A LOT MORE HOME.</span>
        <h1>家的每个细节，<br />都值得<span>聪明一点。</span></h1>
        <p>选得明白，用得安心。<br />从选购到使用，让客服助手陪你解决每个问题。</p>
        <button
          class="primary"
          @click="
            emit('ask', '我想给家里搭配一套智能家居，请先了解我的需求再推荐。')
          "
        >
          <MessageCircle :size="17" />让客服助手帮我选<ArrowUpRight :size="17" />
        </button>
      </div>
      <span class="hero-caption">THOUGHTFUL THINGS, FOR EVERYDAY LIVING.</span>
    </div>
    <div class="benefits">
      <div>
        <ShieldCheck :size="24" /><span
          ><b>明白选，安心用</b><small>规格与使用说明有据可查</small></span
        >
      </div>
      <div>
        <Truck :size="24" /><span
          ><b>订单进度随时查看</b
          ><small>模拟购买、配送与售后完整体验</small></span
        >
      </div>
      <div>
        <Headphones :size="24" /><span
          ><b>问题有人回应</b><small>智能咨询与人工服务持续跟进</small></span
        >
      </div>
    </div>
    <div class="section-heading">
      <div>
        <span class="eyebrow">CURATED FOR YOUR HOME</span>
        <h2>精选好物 <small>让理想的家，触手可及</small></h2>
      </div>
      <span class="muted">{{ products.length }} 件日常好物</span>
    </div>
    <div class="catalog-toolbar">
      <div class="category-tabs">
        <button
          v-for="c in categories"
          :key="c"
          :class="{ selected: category === c }"
          @click="category = c"
        >
          {{ c }}
        </button>
      </div>
      <div class="catalog-controls">
        <label class="searchbox"
          ><Search :size="17" /><input
            v-model="search"
            placeholder="寻找适合你的设备"
            aria-label="搜索商品" /></label
        ><select v-model="sort" aria-label="商品排序">
          <option value="featured">精选排序</option>
          <option value="low">价格从低到高</option>
          <option value="high">价格从高到低</option>
        </select>
      </div>
    </div>
    <div v-if="loading" class="empty">正在准备商品…</div>
    <div v-else class="product-grid">
      <article v-for="p in filtered" :key="p.id" class="product-card">
        <div
          class="product-image"
          @click="
            selected = p;
            checkout = false;
          "
        >
          <span class="product-tag">{{ p.type }}</span
          ><img :src="p.image" :alt="p.name" loading="lazy" />
        </div>
        <div class="product-info">
          <span class="eyebrow">{{ p.category }}</span>
          <h3>{{ p.name }}</h3>
          <p>{{ p.description }}</p>
          <div class="feature-tags">
            <span v-for="f in p.features" :key="f">{{ f }}</span>
          </div>
          <div class="product-price">
            <b><small>¥</small>{{ money(p.price) }}</b
            ><span>可售 {{ p.stock }} 件</span>
          </div>
          <div class="product-actions">
            <button
              class="secondary"
              @click="
                emit('ask', `请介绍${p.name}的功能、联网要求和使用限制。`)
              "
            >
              <MessageCircle :size="16" />咨询客服</button
            ><button
              class="primary"
              :disabled="user.role === 'ADMIN' || p.stock < 1"
              @click="buy(p)"
            >
              <ShoppingBag :size="16" />立即购买
            </button>
          </div>
        </div>
      </article>
    </div>
    <div v-if="!loading && !filtered.length" class="empty">
      没有找到匹配的商品，试试其他关键词。
    </div>
    <el-dialog
      :model-value="!!selected && !checkout"
      :title="selected?.name"
      width="min(660px,92vw)"
      @update:model-value="
        (value: boolean) => {
          if (!value) selected = null;
        }
      "
      ><template v-if="selected"
        ><div class="product-detail">
          <img :src="selected.image" :alt="selected.name" />
          <div>
            <p>{{ selected.description }}</p>
            <ul>
              <li v-for="f in selected.features" :key="f">{{ f }}</li>
            </ul>
            <p>保修：{{ selected.warranty }}</p>
            <strong class="price">¥{{ money(selected.price) }}</strong>
          </div>
        </div>
        <div class="dialog-actions">
          <button
            class="secondary"
            @click="
              emit(
                'ask',
                `我想了解${selected.name}，请介绍适用场景和使用限制。`,
              );
              selected = null;
            "
          >
            咨询客服</button
          ><button
            class="primary"
            :disabled="user.role === 'ADMIN' || selected.stock < 1"
            @click="buy(selected)"
          >
            立即购买<ArrowRight :size="16" />
          </button></div></template
    ></el-dialog>
    <el-dialog
      v-model="checkout"
      title="核对购买信息"
      width="min(560px,92vw)"
      :close-on-click-modal="!busy"
      ><form v-if="selected" class="form-stack" @submit.prevent="pay">
        <div class="checkout-summary">
          <img :src="selected.image" :alt="selected.name" />
          <div>
            <b>{{ selected.name }}</b>
            <p>¥{{ money(selected.price) }} / 件</p>
          </div>
        </div>
        <p class="checkout-note">本项目使用模拟余额。支付成功后一次性随机生成待发货、运输中或已送达场景，方便体验不同阶段的订单与售后服务。</p>
        <label
          >购买数量<el-input-number
            v-model="form.quantity"
            :min="1"
            :max="Math.min(20, selected.stock)"
        /></label>
        <div class="form-row">
          <label
            >收件人<input
              v-model="form.recipient"
              maxlength="60"
              required /></label
          ><label
            >联系电话<input v-model="form.phone" maxlength="30" required
          /></label>
        </div>
        <label
          >收货地址<textarea
            v-model="form.address"
            maxlength="300"
            rows="2"
            required
            placeholder="填写本次订单的收货地址"
          ></textarea>
        </label>
        <div class="checkout-total">
          <span>模拟余额 ¥{{ money(balance) }}</span
          ><b>合计 ¥{{ money(Number(selected.price) * form.quantity) }}</b>
        </div>
        <p class="muted">
          使用模拟余额付款，不涉及真实资金或真实发货。订单成功后，收货信息将保存为本次订单快照。
        </p>
        <button class="primary" :disabled="busy">
          {{ busy ? "正在创建订单…" : "确认并使用模拟余额支付" }}
        </button>
      </form></el-dialog
    >
  </section>
</template>
