<script setup lang="ts">
import { computed, ref } from "vue";
import {
  ArrowRight,
  Eye,
  EyeOff,
  Leaf,
  ShieldCheck,
  MessageCircle,
  PackageCheck,
} from "lucide-vue-next";
import { api, setCsrf } from "../api";
import type { Entity } from "../format";
const emit = defineEmits<{ authenticated: [user: Entity] }>();
const registering = ref(false),
  busy = ref(false),
  visible = ref(false),
  error = ref("");
const username = ref(""),
  password = ref(""),
  displayName = ref(""),
  confirmation = ref("");
const title = computed(() =>
  registering.value ? "开启你的智服空间" : "欢迎回来",
);
async function submit() {
  if (busy.value) return;
  error.value = "";
  if (registering.value && password.value !== confirmation.value) {
    error.value = "两次输入的密码不一致";
    return;
  }
  busy.value = true;
  try {
    if (registering.value)
      await api("/auth/register", "POST", {
        username: username.value,
        password: password.value,
        displayName: displayName.value,
      });
    const result = await api("/auth/login", "POST", {
      username: username.value,
      password: password.value,
    });
    setCsrf(result.csrfToken);
    emit("authenticated", result.user);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="auth-page">
    <section class="auth-story">
      <a href="#" class="brand"
        ><span class="brand-mark"><Leaf :size="24" /></span
        ><span
          >SmartServe <em>智服</em
          ><small>SMART HOME, THOUGHTFUL SERVICE</small></span
        ></a
      >
      <div class="auth-story-copy">
        <span class="eyebrow">为生活里的小问题，找到好答案</span>
        <h1>聪明一点的家，<br />贴心一点的服务。</h1>
        <p>
          从选购到日常使用，从一笔订单到一次安心的回应。<br />你的智能家居生活，在这里连起来。
        </p>
      </div>
      <div class="auth-visual">
        <img
          :src="'/files/images/smart-home-hero.png'"
          alt="温暖的智能家居与设备组合"
        />
        <div class="auth-visual-note">
          <span class="live-dot"></span>产品、订单与服务，都在你的空间里
        </div>
      </div>
      <div class="auth-values">
        <span><ShieldCheck :size="16" /> 资料有据可查</span
        ><span><MessageCircle :size="16" /> 智能与人工协作</span
        ><span><PackageCheck :size="16" /> 服务进度可跟进</span>
      </div>
    </section>
    <section class="auth-form-panel">
      <div class="auth-form-wrap">
        <div class="auth-topline">
          WELCOME TO SMARTSERVE <span>02 / 生活与服务</span>
        </div>
        <h2>{{ title }}</h2>
        <p class="muted auth-subtitle">
          {{
            registering
              ? "创建客户账号，开始探索更轻松的智能生活。"
              : "登录后，继续你的设备选购与服务会话。"
          }}
        </p>
        <div class="auth-tabs">
          <button
            :class="{ active: !registering }"
            @click="
              registering = false;
              error = '';
            "
          >
            账号登录</button
          ><button
            :class="{ active: registering }"
            @click="
              registering = true;
              error = '';
            "
          >
            创建账号
          </button>
        </div>
        <form @submit.prevent="submit" class="form-stack">
          <label v-if="registering"
            >怎么称呼你<input
              v-model="displayName"
              autocomplete="nickname"
              placeholder="输入昵称"
              maxlength="60"
              required
          /></label>
          <label
            >账号<input
              v-model="username"
              autocomplete="username"
              placeholder="3～40 位字母、数字、下划线或短横线"
              minlength="3"
              maxlength="40"
              pattern="[A-Za-z0-9][A-Za-z0-9_\-]{2,39}"
              required
          /></label>
          <label
            >密码
            <div class="password-input">
              <input
                v-model="password"
                :type="visible ? 'text' : 'password'"
                :autocomplete="
                  registering ? 'new-password' : 'current-password'
                "
                placeholder="至少 8 个字符"
                minlength="8"
                maxlength="72"
                required
              /><button
                type="button"
                :aria-label="visible ? '隐藏密码' : '显示密码'"
                @click="visible = !visible"
              >
                <EyeOff v-if="visible" :size="18" /><Eye v-else :size="18" />
              </button></div
          ></label>
          <label v-if="registering"
            >确认密码<input
              v-model="confirmation"
              :type="visible ? 'text' : 'password'"
              autocomplete="new-password"
              placeholder="再次输入密码"
              minlength="8"
              maxlength="72"
              required
          /></label>
          <div v-if="error" class="inline-error" role="alert">{{ error }}</div>
          <button class="primary auth-submit" :disabled="busy" type="submit">
            {{
              busy
                ? "正在连接你的空间…"
                : registering
                  ? "创建账号并进入"
                  : "进入我的空间"
            }}<ArrowRight :size="18" />
          </button>
        </form>
        <p v-if="registering" class="auth-tip">
          新客户获得 ¥10,000 模拟体验余额，用于体验购买与售后，不涉及真实资金。
        </p>
        <p v-else class="auth-tip">
          忘记密码？请联系服务管理员重置。管理员使用同一入口登录，权限由服务端确认。
        </p>
        <div class="auth-footer">
          <span class="tiny-leaf"><Leaf :size="15" /></span
          >安心的家，从一次贴心的回应开始。
        </div>
      </div>
    </section>
  </main>
</template>
