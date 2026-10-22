<script setup lang="ts">
import { ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { MessageCircle, Truck, PackageCheck } from "lucide-vue-next";
import { api } from "../api";
import { money, date, labels, type Entity } from "../format";
const props = defineProps<{ order: Entity; admin?: boolean }>();
const emit = defineEmits(["updated", "ask"]);
const busy = ref(false);
async function advance() {
  busy.value = true;
  try {
    emit(
      "updated",
      await api(`/admin/orders/${props.order.id}/advance`, "POST", {
        status: props.order.status === "PAID" ? "SHIPPED" : "DELIVERED",
        version: props.order.version,
      }),
    );
    ElMessage.success("模拟配送进度已更新");
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
async function cancel() {
  try {
    await ElMessageBox.confirm(
      "取消未发货订单后，模拟余额与库存会一并返还。",
      "确认取消订单",
      { confirmButtonText: "确认取消", cancelButtonText: "保留订单" },
    );
    busy.value = true;
    emit("updated", await api(`/orders/${props.order.id}/cancel`, "POST"));
    ElMessage.success("订单已取消");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
async function refund(existing?: Entity) {
  try {
    let draft = existing;
    if (!draft) {
      const prompt = await ElMessageBox.prompt(
        "请说明申请原因。提交后仍需确认并等待管理员审核。",
        "申请售后",
        {
          inputType: "textarea",
          inputValidator: (value: string) =>
            !!value?.trim() || "请填写申请原因",
          inputPlaceholder: "商品或使用方面遇到什么问题？",
          confirmButtonText: "核对申请",
        },
      );
      draft = await api("/refunds", "POST", {
        orderId: props.order.id,
        reason: prompt.value,
      });
    }
    if (draft!.status !== "AWAITING_CONFIRMATION")
      return ElMessage.info("该订单已有售后申请，请查看处理状态");
    await ElMessageBox.confirm(
      `核对订单 ${props.order.id}，申请金额 ¥${money(draft!.amount)}。${props.order.fundingSource === "LEGACY_DEMO" ? "这是历史演示订单，处理后不会增加钱包余额。" : "审核通过后，模拟余额才会原路退回。"}`,
      "确认提交售后",
      { confirmButtonText: "确认提交", cancelButtonText: "暂不提交" },
    );
    busy.value = true;
    await api(`/refunds/${draft!.id}/confirm`, "POST", { confirmed: true });
    emit("updated", await api(`/orders/${props.order.id}`));
    ElMessage.success("已提交，等待管理员审核");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error((e as Error).message);
    try {
      emit("updated", await api(`/orders/${props.order.id}`));
    } catch {
      /* Keep the visible order when the network is unavailable. */
    }
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="order-detail">
    <div class="order-detail-heading">
      <span class="status-pill" :class="order.status.toLowerCase()">{{
        labels[order.status] || order.status
      }}</span
      ><span class="muted">{{ order.id }}</span>
    </div>
    <div v-if="order.fundingSource === 'LEGACY_DEMO'" class="notice">
      历史演示订单：不扣初始钱包余额，售后处理也不会向钱包增加余额。
    </div>
    <div class="order-line" v-for="item in order.items" :key="item.product_id">
      <img :src="item.image" :alt="item.product_name" />
      <div>
        <h3>{{ item.product_name }}</h3>
        <p class="muted">¥{{ money(item.unit_price) }} × {{ item.quantity }}</p>
      </div>
      <strong>¥{{ money(Number(item.unit_price) * item.quantity) }}</strong>
    </div>
    <dl class="detail-grid">
      <div>
        <dt>下单时间</dt>
        <dd>{{ date(order.createdAt) }}</dd>
      </div>
      <div>
        <dt>订单金额</dt>
        <dd>¥{{ money(order.amount) }}</dd>
      </div>
      <div>
        <dt>收货人</dt>
        <dd>{{ order.recipient }} · {{ order.phone || "历史演示信息" }}</dd>
      </div>
      <div>
        <dt>收货地址</dt>
        <dd>{{ order.address }}</dd>
      </div>
      <div>
        <dt>模拟运单</dt>
        <dd>{{ order.trackingNumber || "发货后生成" }}</dd>
      </div>
    </dl>
    <h4 class="subheading">配送与服务进度 <small>模拟流程</small></h4>
    <div class="timeline">
      <div
        v-for="(event, index) in order.events"
        :key="index"
        class="timeline-item"
      >
        <span class="timeline-dot"></span>
        <div>
          <b>{{ event.description }}</b
          ><small>{{ date(event.created_at) }}</small>
        </div>
      </div>
    </div>
    <div
      v-if="order.refunds?.length"
      class="refund-status"
      v-for="r in order.refunds"
      :key="r.id"
    >
      <b>售后申请 · {{ labels[r.status] || r.status }}</b>
      <p>{{ r.reason }}</p>
      <p v-if="r.review_note" class="muted">审核说明：{{ r.review_note }}</p>
      <button
        v-if="!admin && r.status === 'AWAITING_CONFIRMATION'"
        class="secondary"
        @click="refund(r)"
      >
        继续确认申请
      </button>
    </div>
    <div class="dialog-actions">
      <button
        v-if="!admin"
        class="secondary"
        @click="emit('ask', `请查询订单 ${order.id} 的状态和物流。`)"
      >
        <MessageCircle :size="16" />咨询此订单</button
      ><button
        v-if="admin && ['PAID', 'SHIPPED'].includes(order.status)"
        class="primary"
        :disabled="busy"
        @click="advance"
      >
        <Truck v-if="order.status === 'PAID'" :size="16" /><PackageCheck
          v-else
          :size="16"
        />{{ order.status === "PAID" ? "模拟发货" : "模拟签收" }}</button
      ><template v-if="!admin"
        ><button
          v-if="order.status === 'PAID'"
          class="secondary"
          :disabled="busy"
          @click="cancel"
        >
          取消订单</button
        ><button
          v-if="order.status === 'DELIVERED' && !order.refunds?.length"
          class="primary"
          :disabled="busy"
          @click="refund()"
        >
          申请售后
        </button></template
      >
    </div>
  </div>
</template>
