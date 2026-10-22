<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  ArrowLeft,
  ArrowRight,
  Check,
  Headphones,
  Search,
  Users,
  RefreshCw,
} from "lucide-vue-next";
import { api } from "../api";
import { date, labels, money, type Entity } from "../format";
import OrderDetail from "./OrderDetail.vue";
const tab = ref("work"),
  tickets = ref<Entity[]>([]),
  refunds = ref<Entity[]>([]),
  customers = ref<Entity[]>([]),
  feedback = ref<Entity[]>([]),
  query = ref(""),
  page = ref(1),
  selectedUser = ref<Entity | null>(null),
  orders = ref<Entity[]>([]),
  orderPage = ref(1),
  selectedOrder = ref<Entity | null>(null),
  selectedTicket = ref<Entity | null>(null),
  ticketMessages = ref<Entity[]>([]),
  selectedRefund = ref<Entity | null>(null),
  busy = ref(false);
const ticketForm = reactive({ resolution: "", note: "" });
const review = reactive({ approve: true, returnedToStock: false, note: "" });
async function load() {
  try {
    [tickets.value, refunds.value, feedback.value] = await Promise.all([
      api<Entity[]>("/tickets"),
      api<Entity[]>("/refunds"),
      api<Entity[]>("/admin/feedback"),
    ]);
    await loadCustomers();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
async function loadCustomers() {
  const result = await api(
    `/admin/customers?q=${encodeURIComponent(query.value)}&page=${page.value}`,
  );
  customers.value = result.items;
}
async function customerOrders(customer: Entity) {
  selectedUser.value = customer;
  const result = await api(
    `/admin/orders?userId=${encodeURIComponent(customer.id)}&page=${orderPage.value}`,
  );
  orders.value = result.items;
}
async function viewOrder(id: string) {
  selectedOrder.value = await api(`/orders/${id}`);
}
async function viewTicket(ticket: Entity) {
  selectedTicket.value = ticket;
  Object.assign(ticketForm, { resolution: ticket.resolution || "", note: "" });
  ticketMessages.value = await api<Entity[]>(
    `/conversations/${ticket.conversationId}/messages`,
  );
}
async function updateTicket(status: string) {
  if (!selectedTicket.value) return;
  busy.value = true;
  try {
    selectedTicket.value = await api(
      `/tickets/${selectedTicket.value.id}`,
      "PUT",
      { ...ticketForm, status, version: selectedTicket.value.version || 0 },
    );
    ticketForm.note = "";
    await load();
    ElMessage.success(
      status === "RESOLVED" ? "处理结论已回写客户会话" : "服务单已更新",
    );
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
async function viewRefund(refund: Entity) {
  selectedRefund.value = await api(`/refunds/${refund.id}`);
  Object.assign(review, { approve: true, returnedToStock: false, note: "" });
}
async function reviewRefund() {
  if (!selectedRefund.value) return;
  busy.value = true;
  try {
    await api(`/admin/refunds/${selectedRefund.value.id}/review`, "POST", {
      ...review,
      version: selectedRefund.value.version,
    });
    selectedRefund.value = null;
    await load();
    ElMessage.success("审核结果已保存");
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
async function reset(customer: Entity) {
  try {
    const result = await ElMessageBox.prompt(
      `为客户 ${customer.username} 设置临时密码。重置后该客户所有登录会话失效。`,
      "重置客户密码",
      {
        inputType: "password",
        inputValidator: (value: string) =>
          (!!value && value.length >= 8) || "至少8个字符",
      },
    );
    await api(`/admin/customers/${customer.id}/reset-password`, "POST", {
      password: result.value,
    });
    ElMessage.success("已重置，请通过可信渠道告知客户");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error((e as Error).message);
  }
}
async function reviewFeedback(item: Entity) {
  try {
    const result = await ElMessageBox.prompt(
      "记录问题归因和后续处理，不会自动发布知识或修改模型。",
      "复核答案反馈",
      {
        inputType: "textarea",
        inputValidator: (value: string) => !!value?.trim() || "请填写复核结论",
      },
    );
    await api(`/admin/feedback/${item.run_id}/review`, "POST", {
      note: result.value,
    });
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error((e as Error).message);
  }
}
function feedbackQuestion(item: Entity) {
  try {
    return JSON.parse(item.run_payload).question;
  } catch {
    return item.run_id;
  }
}
onMounted(load);
</script>
<template>
  <section class="page-enter">
    <div class="section-heading">
      <div>
        <span class="eyebrow">CARE THAT GOES A LITTLE FURTHER</span>
        <h1>服务运营工作台</h1>
        <p class="muted">把问题接住，让每一笔交易和每一次回应有始有终。</p>
      </div>
      <button class="secondary" @click="load">
        <RefreshCw :size="16" />刷新工作台
      </button>
    </div>
    <div class="metrics admin-metrics">
      <div>
        <span>待处理人工服务</span
        ><b>{{ tickets.filter((t) => t.status === "PENDING").length }}</b
        ><small>客户已确认提交</small>
      </div>
      <div>
        <span>待审核退款</span
        ><b>{{ refunds.filter((r) => r.status === "PENDING_REVIEW").length }}</b
        ><small>审核通过才处理模拟余额</small>
      </div>
      <div>
        <span>待复核答案反馈</span
        ><b>{{ feedback.filter((f) => f.review_status === "NEW").length }}</b
        ><small>归因后纳入知识或评测改进</small>
      </div>
    </div>
    <div class="category-tabs admin-tabs">
      <button :class="{ selected: tab === 'work' }" @click="tab = 'work'">
        <Headphones :size="16" />服务待办</button
      ><button
        :class="{ selected: tab === 'customers' }"
        @click="tab = 'customers'"
      >
        <Users :size="16" />客户与订单</button
      ><button
        :class="{ selected: tab === 'feedback' }"
        @click="tab = 'feedback'"
      >
        <Check :size="16" />答案反馈
      </button>
    </div>
    <template v-if="tab === 'work'"
      ><div class="panel">
        <div class="panel-heading">
          <h3>人工服务单</h3>
          <span class="muted">{{ tickets.length }} 条</span>
        </div>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>问题与编号</th>
                <th>客户</th>
                <th>状态</th>
                <th>时间</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="ticket in tickets" :key="ticket.id">
                <td>
                  <b>{{ ticket.title }}</b
                  ><small>{{ ticket.id }}</small>
                </td>
                <td>{{ ticket.userId }}</td>
                <td>
                  <span class="status-pill">{{ labels[ticket.status] }}</span>
                </td>
                <td>{{ date(ticket.createdAt) }}</td>
                <td>
                  <button class="text-button" @click="viewTicket(ticket)">
                    查看与处理<ArrowRight :size="14" />
                  </button>
                </td>
              </tr>
              <tr v-if="!tickets.length">
                <td colspan="5" class="table-empty">暂无人工服务单</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="panel">
        <div class="panel-heading">
          <h3>退款申请</h3>
          <span class="muted">客户确认后进入审核</span>
        </div>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>订单 / 申请原因</th>
                <th>金额</th>
                <th>状态</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="refund in refunds" :key="refund.id">
                <td>
                  <b>{{ refund.orderId }}</b
                  ><small>{{ refund.reason }}</small>
                </td>
                <td>
                  ¥{{ money(refund.amount)
                  }}<small>{{
                    refund.fundingSource === "LEGACY_DEMO"
                      ? "历史演示，不入钱包"
                      : "模拟余额原路退回"
                  }}</small>
                </td>
                <td>
                  <span class="status-pill">{{ labels[refund.status] }}</span>
                </td>
                <td>
                  <button class="text-button" @click="viewRefund(refund)">
                    {{
                      refund.status === "PENDING_REVIEW"
                        ? "审核申请"
                        : "查看申请"
                    }}<ArrowRight :size="14" />
                  </button>
                </td>
              </tr>
              <tr v-if="!refunds.length">
                <td colspan="4" class="table-empty">暂无退款申请</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div></template
    >
    <div v-else-if="tab === 'customers'" class="customer-layout">
      <aside class="panel customer-list">
        <div class="panel-heading"><h3>客户目录</h3></div>
        <form
          class="searchbox"
          @submit.prevent="
            page = 1;
            loadCustomers();
          "
        >
          <Search :size="17" /><input
            v-model="query"
            placeholder="搜索账号或昵称"
            aria-label="搜索客户"
          /><button type="submit" aria-label="执行客户搜索">
            <ArrowRight :size="16" />
          </button>
        </form>
        <button
          v-for="customer in customers"
          :key="customer.id"
          class="customer-row"
          :class="{ active: selectedUser?.id === customer.id }"
          @click="
            orderPage = 1;
            customerOrders(customer);
          "
        >
          <span class="avatar">{{
            String(customer.display_name).slice(0, 1)
          }}</span
          ><span
            ><b>{{ customer.display_name }}</b
            ><small
              >{{ customer.username }} · {{ customer.order_count }} 笔订单{{
                customer.enabled ? "" : " · 历史归档"
              }}</small
            ></span
          ><ArrowRight :size="14" />
        </button>
        <div class="pagination">
          <button
            class="text-button"
            :disabled="page === 1"
            @click="
              page--;
              loadCustomers();
            "
          >
            上一页</button
          ><span>{{ page }}</span
          ><button
            class="text-button"
            :disabled="customers.length < 20"
            @click="
              page++;
              loadCustomers();
            "
          >
            下一页
          </button>
        </div>
      </aside>
      <div class="panel">
        <template v-if="selectedUser"
          ><div class="panel-heading">
            <div>
              <h3>{{ selectedUser.display_name }}的订单</h3>
              <small>{{ selectedUser.username }}</small>
            </div>
            <button class="secondary" @click="reset(selectedUser)">
              重置密码
            </button>
          </div>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>订单 / 商品</th>
                  <th>金额</th>
                  <th>状态</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="order in orders" :key="order.id">
                  <td>
                    <b>{{ order.productName }}</b
                    ><small>{{ order.id }}</small>
                  </td>
                  <td>¥{{ money(order.amount) }}</td>
                  <td>{{ labels[order.status] }}</td>
                  <td>
                    <button class="text-button" @click="viewOrder(order.id)">
                      查看详情<ArrowRight :size="14" />
                    </button>
                  </td>
                </tr>
                <tr v-if="!orders.length">
                  <td colspan="4" class="table-empty">该客户还没有订单</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="pagination">
            <button
              class="secondary"
              :disabled="orderPage === 1"
              @click="
                orderPage--;
                customerOrders(selectedUser);
              "
            >
              上一页</button
            ><span>第 {{ orderPage }} 页</span
            ><button
              class="secondary"
              :disabled="orders.length < 20"
              @click="
                orderPage++;
                customerOrders(selectedUser);
              "
            >
              下一页
            </button>
          </div></template
        >
        <div v-else class="empty">
          <Users :size="35" />
          <h3>先选择一位客户</h3>
          <p>按客户查看订单与服务背景，避免把所有订单混在一起。</p>
        </div>
      </div>
    </div>
    <div v-else class="panel">
      <div class="panel-heading">
        <h3>答案反馈与失败案例</h3>
        <span class="muted">人工复核，不自动入库</span>
      </div>
      <div class="feedback-row" v-for="item in feedback" :key="item.run_id">
        <div>
          <span class="status-pill">{{
            item.helpful ? "有帮助" : "未解决"
          }}</span
          ><b>{{ feedbackQuestion(item) }}</b>
          <p v-if="item.comment">{{ item.comment }}</p>
          <small
            >{{ date(item.created_at) }} ·
            {{ item.review_status === "NEW" ? "待复核" : "已复核" }}
            {{ item.review_note }}</small
          >
        </div>
        <button class="secondary" @click="reviewFeedback(item)">
          记录复核
        </button>
      </div>
      <div v-if="!feedback.length" class="empty">暂无答案反馈</div>
    </div>
    <el-dialog
      :model-value="!!selectedOrder"
      title="客户订单详情"
      width="min(750px,94vw)"
      @update:model-value="
        (value: boolean) => {
          if (!value) selectedOrder = null;
        }
      "
      ><OrderDetail
        v-if="selectedOrder"
        :order="selectedOrder"
        admin
        @updated="
          (order) => {
            selectedOrder = order;
            if (selectedUser) customerOrders(selectedUser);
          }
        "
    /></el-dialog>
    <el-dialog
      :model-value="!!selectedTicket"
      title="人工服务单"
      width="min(820px,94vw)"
      @update:model-value="
        (value: boolean) => {
          if (!value) selectedTicket = null;
        }
      "
      ><template v-if="selectedTicket"
        ><div class="notice">
          {{ selectedTicket.title }} · {{ labels[selectedTicket.status]
          }}<br />客户联系方式：{{ selectedTicket.contact }}
        </div>
        <div class="ticket-context">
          <div v-for="message in ticketMessages" :key="message.id">
            <b>{{
              message.role === "user"
                ? "客户"
                : message.role === "human"
                  ? "人工专员"
                  : "智服"
            }}</b>
            <p>{{ message.content }}</p>
          </div>
        </div>
        <div class="internal-notes" v-if="selectedTicket.notes?.length">
          <h4>内部处理记录（仅管理员）</h4>
          <p v-for="(note, index) in selectedTicket.notes" :key="index">
            {{ date(note.at) }} · {{ note.content }}
          </p>
        </div>
        <form class="form-stack" @submit.prevent="updateTicket('RESOLVED')">
          <label
            >内部备注<textarea
              v-model="ticketForm.note"
              rows="2"
              maxlength="2000"
              :disabled="selectedTicket.status === 'RESOLVED'"
            ></textarea></label
          ><label
            >给客户的处理结论<textarea
              v-model="ticketForm.resolution"
              rows="3"
              maxlength="2000"
              :disabled="selectedTicket.status === 'RESOLVED'"
              placeholder="完成时必填，结论会回写原会话"
            ></textarea>
          </label>
          <div class="dialog-actions">
            <button
              v-if="selectedTicket.status === 'PENDING'"
              class="primary"
              type="button"
              :disabled="busy"
              @click="updateTicket('PROCESSING')"
            >
              认领并开始处理</button
            ><template v-if="selectedTicket.status === 'PROCESSING'"
              ><button
                class="secondary"
                type="button"
                :disabled="busy"
                @click="updateTicket('PROCESSING')"
              >
                保存处理记录</button
              ><button
                class="primary"
                :disabled="busy || !ticketForm.resolution.trim()"
              >
                完成并回复客户
              </button></template
            >
          </div>
        </form></template
      ></el-dialog
    >
    <el-dialog
      :model-value="!!selectedRefund"
      title="核验售后申请"
      width="min(560px,92vw)"
      @update:model-value="
        (value: boolean) => {
          if (!value) selectedRefund = null;
        }
      "
      ><template v-if="selectedRefund"
        ><dl class="detail-grid">
          <div>
            <dt>订单</dt>
            <dd>{{ selectedRefund.orderId }}</dd>
          </div>
          <div>
            <dt>申请金额</dt>
            <dd>¥{{ money(selectedRefund.amount) }}</dd>
          </div>
          <div>
            <dt>原因</dt>
            <dd>{{ selectedRefund.reason }}</dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd>{{ labels[selectedRefund.status] }}</dd>
          </div>
        </dl>
        <p v-if="selectedRefund.fundingSource === 'LEGACY_DEMO'" class="notice">
          历史演示订单未从钱包支付；审核通过只结束演示售后，不增加钱包余额。
        </p>
        <form
          v-if="selectedRefund.status === 'PENDING_REVIEW'"
          class="form-stack"
          @submit.prevent="reviewRefund"
        >
          <label
            >审核决定<select v-model="review.approve">
              <option :value="true">审核通过</option>
              <option :value="false">审核不通过</option>
            </select></label
          ><label
            v-if="
              review.approve && selectedRefund.fundingSource !== 'LEGACY_DEMO'
            "
            class="checkbox-label"
            ><input
              type="checkbox"
              v-model="review.returnedToStock"
            />已确认商品退回且可重新销售，恢复可售库存</label
          ><label
            >审核说明<textarea
              v-model="review.note"
              rows="3"
              maxlength="500"
              required
              placeholder="记录核验依据与处理结论"
            ></textarea></label
          ><button class="primary" :disabled="busy">确认审核结果</button>
        </form>
        <p v-else class="muted">
          {{ selectedRefund.reviewNote || "当前状态不能执行审核。" }}
        </p></template
      ></el-dialog
    >
  </section>
</template>
